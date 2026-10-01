/**
 * 裁剪矩形往返（本期唯一一处「写错也不报错」的数学）。
 *
 * 链路三段：
 *   1) 保存时把取景框从「解码位图坐标」换算回「原图坐标」（EditActivity.saveCropRectIfNeeded，
 *      比例 = 原图 / 位图，两轴各算一次，与 exportByRegion 同一套）；
 *   2) 写盘前按两位小数取整（WallpaperStore.round2）；
 *   3) 下次进编辑页把「原图坐标」换算回「位图坐标」，再按 scale = viewW / rect.width 摆取景框
 *      （EditActivity.applyPendingRestore + CropView.restoreSourceRect）。
 * 走完 1→3 必须回到同一个取景状态，否则用户点铅笔看到的不是上次的构图，而且一声不吭。
 *
 * 用例都由「合法视图状态」正向构造（给定比例 + 平移，反解出可见矩形），
 * 因为随手写一个矩形很容易得到法律上不可能出现的现场（比如可见宽度等于原图宽但高度超了），
 * 那只会让断言在骗后来人。
 *
 * android.jar 里的 Matrix/RectF 在 JVM 上一调用就抛 Stub!，所以这里用纯 double 复刻同一套算式。
 * 改了生产代码那三段换算，记得同步这里（与 BackupRoundtripTest 同一套约定）。
 * 跑法：check/croprect.cmd。输出保持纯 ASCII —— 本机控制台是 GBK。
 */
public class CropRectTest {

    static int pass = 0;
    static int fail = 0;

    /** 与 CropView.MAX_SCALE_FACTOR 一致 */
    static final double MAX_SCALE_FACTOR = 8d;

    static void near(String what, double expect, double actual, double tol) {
        double diff = Math.abs(expect - actual);
        if (diff <= tol) {
            pass++;
        } else {
            fail++;
            System.out.println("FAIL " + what + ": expect " + fmt(expect)
                    + " actual " + fmt(actual) + " diff " + fmt(diff) + " tol " + fmt(tol));
        }
    }

    static void check(String what, boolean ok) {
        if (ok) {
            pass++;
        } else {
            fail++;
            System.out.println("FAIL " + what);
        }
    }

    static String fmt(double v) {
        return String.format("%.4f", v);
    }

    /** WallpaperStore.round2 的等价实现 */
    static double round2(double v) {
        return Math.round(v * 100d) / 100d;
    }

    /**
     * 往返断言：给一个「已经存进 library.json」的原图矩形，走完读回来 → 摆取景框 → 再存一次，
     * 应回到同一个矩形。
     */
    static void roundtrip(String name, int origW, int origH, int bmpW, int bmpH,
                          int viewW, int viewH, double rl, double rt, double rr, double rb) {
        // 现场合法性：矩形必须在原图内，且宽高比等于取景框宽高比（可见区就是取景框，这是恒等的）
        check(name + " rect inside original",
                rl >= -1e-6 && rt >= -1e-6 && rr <= origW + 1e-6 && rb <= origH + 1e-6
                        && rr > rl && rb > rt);
        double aspect = viewW / (double) viewH;
        double rectAspect = (rr - rl) / (rb - rt);
        check(name + " rect aspect matches view (" + fmt(rectAspect) + " vs " + fmt(aspect) + ")",
                Math.abs(rectAspect - aspect) < aspect * 1e-9);

        // 写盘：round2（原图坐标）
        double sl = round2(rl), st = round2(rt), sr = round2(rr), sb = round2(rb);
        near(name + " stored left", rl, sl, 0.01);
        near(name + " stored top", rt, st, 0.01);
        near(name + " stored right", rr, sr, 0.01);
        near(name + " stored bottom", rb, sb, 0.01);

        // 读回来：原图坐标 → 位图坐标（applyPendingRestore 的 kx/ky）
        double kx = bmpW / (double) origW;
        double ky = bmpH / (double) origH;
        double rectL = sl * kx, rectT = st * ky;
        double rectW = (sr - sl) * kx, rectH = (sb - st) * ky;

        // 复原取景框用的比例（restoreSourceRect：只按宽轴算），并确认它没被 min/maxScale 钳走 ——
        // 一旦被钳，摆出来的就不是存进去的那块，用户会觉得「构图自己松了」
        double minScale = Math.max(viewW / (double) bmpW, viewH / (double) bmpH);
        double maxScale = minScale * MAX_SCALE_FACTOR;
        double scale = viewW / rectW;
        double applied = Math.max(minScale, Math.min(maxScale, scale));
        check(name + " scale " + fmt(scale) + " not clamped by [" + fmt(minScale)
                + "," + fmt(maxScale) + "]", Math.abs(applied - scale) < 1e-9);

        // 按比例 applied 摆好后，取景框覆盖的位图区域
        double visL = rectL, visT = rectT;
        double visR = rectL + viewW / applied;
        double visB = rectT + viewH / applied;

        // 再走一次保存的换算（位图 → 原图）
        double backL = visL * origW / (double) bmpW;
        double backT = visT * origH / (double) bmpH;
        double backR = visR * origW / (double) bmpW;
        double backB = visB * origH / (double) bmpH;

        // 0.5 像素的容差很宽（round2 本身只贡献 0.01），但足以抓出「两轴比例写反」
        // 「忘了减 rect.left」「把位图尺寸当原图尺寸」这类真错误
        near(name + " restored left", rl, backL, 0.5);
        near(name + " restored top", rt, backT, 0.5);
        near(name + " restored right", rr, backR, 0.5);
        near(name + " restored bottom", rb, backB, 0.5);
    }

    /**
     * 由一个合法的视图状态反解出「存进 JSON 的矩形」再做往返：
     *
     * @param scaleFactor 相对 fit-cover 的放大倍数（1 = 最小倍数，用户捏到 3 倍就是 3）
     * @param fracX/fracY 平移位置，0..1（0 = 贴原图左/上边缘，1 = 贴右/下边缘）
     */
    static void fromView(String name, int origW, int origH, int bmpW, int bmpH,
                         int viewW, int viewH, double scaleFactor, double fracX, double fracY) {
        double minScale = Math.max(viewW / (double) bmpW, viewH / (double) bmpH);
        double scale = minScale * scaleFactor;
        double drawnW = bmpW * scale, drawnH = bmpH * scale;
        // clampTranslate 允许的平移：图片边缘不得进入取景框内部，所以 tx 在 [min(0,viewW-drawnW), max(0,...)]
        double tx = (viewW - drawnW) * fracX;
        double ty = (viewH - drawnH) * fracY;
        // 取景框 [0,viewW]x[0,viewH] 反解回位图坐标：bmp = (screen - t) / scale
        double bl = (0 - tx) / scale, br = (viewW - tx) / scale;
        double bt = (0 - ty) / scale, bb = (viewH - ty) / scale;
        // 位图坐标 → 原图坐标（保存那一步）
        double rl = bl * origW / (double) bmpW, rr = br * origW / (double) bmpW;
        double rt = bt * origH / (double) bmpH, rb = bb * origH / (double) bmpH;
        roundtrip(name, origW, origH, bmpW, bmpH, viewW, viewH, rl, rt, rr, rb);
    }

    // 真机档案里的档位：屏幕 1264x2800；常见相机原图 4032x3024，被像素预算解成一半
    static final int VIEW_W = 1264, VIEW_H = 2800;
    static final int ORIG_W = 4032, ORIG_H = 3024;
    static final int BMP_W = 2016, BMP_H = 1512;

    public static void main(String[] args) {
        // 1) 刚进页面的 fit-cover 默认态，居中
        fromView("cover-center", ORIG_W, ORIG_H, BMP_W, BMP_H, VIEW_W, VIEW_H, 1, 0.5, 0.5);
        // 2) 同一个默认态但贴左上角（默认态下左右还有可挪余量时）
        fromView("cover-topleft", ORIG_W, ORIG_H, BMP_W, BMP_H, VIEW_W, VIEW_H, 1, 0, 0);
        // 3) 捏到 3 倍、居中 —— 「稍微高一点矮一点」的微调日常就落在这附近
        fromView("zoom-3x-center", ORIG_W, ORIG_H, BMP_W, BMP_H, VIEW_W, VIEW_H, 3, 0.5, 0.42);
        // 4) 捏到上限 8 倍并贴右下边界：clampTranslate 起作用 + round2 误差最大的现场
        fromView("zoom-8x-bottomright", ORIG_W, ORIG_H, BMP_W, BMP_H, VIEW_W, VIEW_H, 8, 1, 1);
        // 5) 原图没被降采样（小图 / kx=ky=1），确认这条退化路径也精确往返
        fromView("no-resample", 1080, 2400, 1080, 2400, VIEW_W, VIEW_H, 1.6, 0.25, 0.75);
        // 6) 超宽原图 6000x4000 被解成 3000x2000：横向余量大，贴左边缘
        fromView("wide-orig", 6000, 4000, 3000, 2000, VIEW_W, VIEW_H, 2, 0, 0.5);
        // 7) 竖长原图（比取景框还窄）：cover 由高度决定，左右反而没余量
        fromView("narrow-orig", 2000, 5000, 1000, 2500, VIEW_W, VIEW_H, 1, 0.5, 0.2);

        System.out.println("== crop rect: " + pass + " passed, " + fail + " failed ==");
        if (fail > 0) {
            System.exit(1);
        }
    }
}
