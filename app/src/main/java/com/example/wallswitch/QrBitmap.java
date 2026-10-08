package com.example.wallswitch;

import android.graphics.Bitmap;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.EnumMap;
import java.util.Map;

/**
 * 地址字符串 → 二维码 Bitmap。铺像素那套算式在 {@link LanPair#qrPixels}（有夹具断言），
 * 这里只负责 zxing 编码与 android 的 Bitmap，出问题时两条链能分开看。
 *
 * <p>纠错档取 M（约 15% 恢复能力）：屏幕对屏幕扫没有污损，H 会让点阵更密、更难对上焦。
 * 失败一律返回 null，由界面退回到「显示可复制的地址」那条路 —— 码画不出来不能让功能死。
 */
public final class QrBitmap {

    /** 每模块像素数：URL 约 50 字节 + M 级 ≈ 25~33 模块，取 8 → 约 300px 见方，220dp 框里正好。 */
    private static final int PX_PER_MODULE = 8;
    /** 标准留白是 4 个模块，扫码方自己也会补，这里给 3 就够（屏幕上不缺那几像素）。 */
    private static final int QUIET_MODULES = 3;

    private QrBitmap() {
    }

    public static Bitmap encode(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, QUIET_MODULES);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints);
            int w = m.getWidth();
            int h = m.getHeight();
            boolean[] rows = new boolean[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    rows[y * w + x] = m.get(x, y);
                }
            }
            int[] px = LanPair.qrPixels(rows, w, h, PX_PER_MODULE, QUIET_MODULES);
            int widthPx = LanPair.qrSidePixels(w, PX_PER_MODULE, QUIET_MODULES);
            int heightPx = LanPair.qrSidePixels(h, PX_PER_MODULE, QUIET_MODULES);
            return Bitmap.createBitmap(px, widthPx, heightPx, Bitmap.Config.ARGB_8888);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }
}
