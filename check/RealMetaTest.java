import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 用手机上真实拉出来的 library.json / libraries.json 跑 MetaFiles 的两件事：
 * 1) 真文件原样解析（正常路径，不该触发救回）；
 * 2) 在真实内容的多个字节位置人为截断，验证救回算法在真数据上的表现，
 *    并逐条比对「救回来的前 N 条必须和原件前 N 条一字不差」。
 *
 * 算法部分是 MetaFiles.java 里那三个纯函数的副本（跑在 JVM 上，不带 android Context）。
 * 改了 MetaFiles 的救回逻辑，记得把这里同步过来，否则这个夹具会给出假绿。
 *
 * 用法：check\realmeta.bat <拉出来的文件目录>   （目录里放 library.json / libraries.json）
 */
public class RealMetaTest {

    private static final int MAX_NESTING = 32;
    private static final int MAX_NOTICE_CHARS = 256 * 1024;
    private static final int MAX_SALVAGE_TRIES = 8;

    // ===== MetaFiles 算法副本 =====
    static String longestValidPrefix(String text) {
        List<int[]> closes = completedElementCloses(text);
        if (closes == null) {
            return null;
        }
        for (int i = closes.size() - 1, tries = 0; i >= 0 && tries < MAX_SALVAGE_TRIES; i--, tries++) {
            int end = closes.get(i)[0];
            String suffix = closes.get(i)[1] == 0 ? "" : "]";
            String candidate = text.substring(0, end + 1) + suffix;
            if (candidate.length() > MAX_NOTICE_CHARS) {
                continue;
            }
            try {
                new JSONArray(candidate);
                return candidate;
            } catch (JSONException ignored) {
            }
        }
        return null;
    }

    static List<int[]> completedElementCloses(String text) {
        List<int[]> closes = new ArrayList<>();
        char[] stack = new char[MAX_NESTING + 1];
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                int end = skipString(text, i);
                if (end < 0) {
                    break;
                }
                i = end;
                continue;
            }
            if (c == '[' || c == '{') {
                if (depth >= MAX_NESTING) {
                    break;
                }
                stack[depth++] = c;
                continue;
            }
            if (c != ']' && c != '}') {
                continue;
            }
            if (depth == 0 || (c == ']') != (stack[depth - 1] == '[')) {
                return null;
            }
            depth--;
            if (c == '}' && depth == 1) {
                closes.add(new int[]{i, depth});
            } else if (c == ']' && depth == 0) {
                closes.add(new int[]{i, 0});
            }
        }
        return closes;
    }

    static int skipString(String text, int quoteIndex) {
        for (int i = quoteIndex + 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                i++;
                continue;
            }
            if (c == '"') {
                return i;
            }
        }
        return -1;
    }

    static int countTopLevelElements(String text) {
        int count = 0;
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                int end = skipString(text, i);
                if (end < 0) {
                    break;
                }
                if (depth == 1) {
                    count++;
                }
                i = end;
                continue;
            }
            if (c == '[' || c == '{') {
                if (depth == 1) {
                    count++;
                }
                if (depth < MAX_NESTING) {
                    depth++;
                }
                continue;
            }
            if (c == ']' || c == '}') {
                if (depth > 0) {
                    depth--;
                }
            }
        }
        return count;
    }

    // ===== 原子写的 JVM 等价实现（不带 Context，落临时目录） =====
    static void atomicWrite(Path target, String content) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp-" + java.util.UUID.randomUUID());
        try {
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp.toFile());
                 java.io.Writer w = new java.io.OutputStreamWriter(out, StandardCharsets.UTF_8)) {
                w.write(content);
                w.flush();
                out.getFD().sync();
            }
            Files.move(tmp, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.deleteIfExists(target);
            Files.move(tmp, target);
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }

    static int pass = 0, fail = 0;

    static void ok(boolean cond, String what) {
        System.out.println((cond ? "PASS  " : "FAIL  ") + what);
        if (cond) pass++; else fail++;
    }

    /** 真数据上的一轮：原件解析 + 多点人为截断。 */
    static void runOne(String label, Path file) throws IOException {
        System.out.println("\n===== " + label + "  (" + file + ") =====");
        byte[] raw = Files.readAllBytes(file);
        String text = new String(raw, StandardCharsets.UTF_8);
        System.out.println("字节数=" + raw.length + "  顶层条目数=" + countTopLevelElements(text));

        JSONArray intact;
        try {
            intact = new JSONArray(text);
            System.out.println("正常解析（与 load() 同一条路径）：条目数=" + intact.length());
        } catch (JSONException e) {
            ok(false, "真文件本身就解析不过：" + e.getMessage() + " —— 那这台机器的元数据已经是坏的，直接看留档");
            return;
        }

        // 逐条核对：救回来的前 N 条必须与原件前 N 条一字不差
        int[] offsets = {raw.length / 4, raw.length / 2, raw.length * 3 / 4, raw.length - 1, raw.length - 8,
                raw.length - 40, raw.length - 200};
        int rescuedRuns = 0, hopeless = 0;
        for (int off : offsets) {
            if (off <= 0 || off >= text.length()) {
                continue;
            }
            String cut = text.substring(0, off);
            String cand = longestValidPrefix(cut);
            if (cand == null) {
                hopeless++;
                System.out.printf("  截到 %,8d 字节 -> 判为救不出（一条都没闭合，交留档）%n", off);
                continue;
            }
            JSONArray rescued = new JSONArray(cand);
            boolean same = true;
            for (int i = 0; i < rescued.length(); i++) {
                JSONObject a = rescued.getJSONObject(i);
                JSONObject b = intact.getJSONObject(i);
                if (!String.valueOf(a.opt("id")).equals(String.valueOf(b.opt("id")))
                        || !String.valueOf(a.opt("lib_id")).equals(String.valueOf(b.opt("lib_id")))
                        || !String.valueOf(a.opt("title")).equals(String.valueOf(b.opt("title")))) {
                    same = false;
                    break;
                }
            }
            rescuedRuns++;
            int started = countTopLevelElements(cut);
            System.out.printf("  截到 %,8d 字节 -> 救回 %d 条（截断段里已开始 %d 条，最后一条没写完的不算）前缀逐条一致=%s%n",
                    off, rescued.length(), started, same);
            ok(same && (rescued.length() == started || rescued.length() == started - 1),
                    "救回结果与原件前缀一致（offset=" + off + "）");
        }
        System.out.println("  小统计：可救回 " + rescuedRuns + " 处 / 判为救不出 " + hopeless + " 处");

        // 救回内容再原子写回、重读一遍（模拟 readJson -> writeJson -> 下次 load）
        Path sandbox = Files.createTempDirectory("realmeta");
        Path copy = sandbox.resolve(file.getFileName().toString());
        int cutOff = text.length() / 2;
        String cand = longestValidPrefix(text.substring(0, cutOff));
        if (cand != null) {
            atomicWrite(copy, new JSONArray(cand).toString());
            JSONArray again = new JSONArray(new String(Files.readAllBytes(copy), StandardCharsets.UTF_8));
            ok(again.length() == new JSONArray(cand).length(),
                    "救回结果原子写回后可原样重读（条目 " + again.length() + "）");
        }
        Files.deleteIfExists(copy);
        Files.deleteIfExists(sandbox);
    }

    /** 真实规模下的覆写实验：边写边读，比较裸覆写与 tmp+rename 各自读到坏内容的次数。 */
    static void raceExperiment(Path source) throws IOException {
        System.out.println("\n===== 真数据规模的「写一半被打断」实验（源文件 " + source + "）=====");
        System.out.println("  拉出来的真文件 " + Files.size(source)
                + " 字节，太小写不慢，改用同样格式把载荷放大到真库可能到达的规模");

        Path sandbox = Files.createTempDirectory("realmeta-race");
        Path target = sandbox.resolve("library.json");

        String original = jsonOf(60);
        atomicWrite(target, original);
        // 几百张壁纸的 library.json 就是这个量级；再配「每 8KB 歇一次」的节奏，模拟慢速存储上被杀
        String padded = jsonOf(4000);
        System.out.println("  原件=" + original.length() + " 字节（由拉出来的真条目放大到真库规模），"
                + "被打断的那次要写 " + (padded.length() / 1024) + " KB");

        // 失效模型：写一半进程被打断（= ROM force-stop 的真实情形），看盘上剩什么。
        // 节奏没踩准时重来（最多 5 次），每次都先把目标复位成完整原件。
        boolean rawLeftHalf = false, rawBroken = false, rawChanged = false;
        for (int r = 0; r < 5; r++) {
            atomicWrite(target, original);
            rawLeftHalf = writeThenKill(target, padded, false);
            String onDisk = new String(Files.readAllBytes(target), StandardCharsets.UTF_8);
            rawChanged = !original.equals(onDisk);
            rawBroken = !parses(target);
            if (rawLeftHalf && rawBroken && rawChanged) {
                System.out.println("  （第 " + (r + 1) + " 次抽到：目标从 " + original.length() + " 字节变成 "
                        + onDisk.length() + " 字节的半截）");
                break;
            }
        }
        System.out.println("  裸覆写被打断：" + (rawLeftHalf ? "盘上留下半截文件" : "这次没抽到半截")
                + "，目标" + (rawChanged ? "被改得面目全非" : "还是原样")
                + "，之后读它 " + (rawBroken ? "解析不出（全库元数据就是这么没的）" : "还能解析"));
        ok(rawLeftHalf && rawBroken && rawChanged, "裸覆写：打断一次就足以弄坏元数据");

        atomicWrite(target, original);
        boolean atomicLeftHalf = writeThenKill(target, padded, true);
        boolean targetUntouched = original.equals(new String(Files.readAllBytes(target), StandardCharsets.UTF_8));
        System.out.println("  tmp+rename 被打断：" + (atomicLeftHalf ? "写入没走完（正是要的）" : "写完了")
                + "，目标内容" + (targetUntouched ? "还是那份完整原件，一字未动" : "被改坏了"));
        ok(targetUntouched, "tmp+rename：打断只会废掉临时文件，目标永远是「完整旧版」或「完整新版」");

        int strays = 0;
        try (java.util.stream.Stream<Path> st = Files.list(sandbox)) {
            strays = (int) st.filter(p -> p.getFileName().toString().contains(".tmp-")).count();
        }
        System.out.println("  被打断留下的 .tmp 残骸 " + strays + " 个（一次几 KB，永不参与读取，只等下次同名单改写）");
        Files.walk(sandbox).sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
            try { Files.deleteIfExists(p); } catch (IOException ignored) { }
        });
    }

    static boolean parses(Path target) {
        try {
            new JSONArray(new String(Files.readAllBytes(target), StandardCharsets.UTF_8));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 用拉出来的真条目拼一份载荷（重复 id 无所谓，这里只测字节层面的写入行为）。 */
    static String jsonOf(int items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append("{\"id\":\"pad-").append(i).append("\",\"lib_id\":\"real-lib\",\"title\":\"真条目复制 ")
              .append(i).append("\"}");
        }
        return sb.append("]").toString();
    }

    /** 起一个写者、让它写起来，然后打断（模拟 ROM force-stop）；返回「被打断在写入中途」是否成立。 */
    static boolean writeThenKill(Path target, String content, boolean atomic) {
        final boolean[] stoppedMidWrite = {false};
        final boolean[] reachedEnd = {false};
        final boolean[] stop = {false};
        Thread writer = new Thread(() -> {
            try {
                if (atomic) {
                    // 和 MetaFiles.writeJson 同构：临时文件写完 sync，停一下再 rename（打断点落在 rename 之前）
                    Path tmp = target.resolveSibling(target.getFileName() + ".tmp-" + java.util.UUID.randomUUID());
                    try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp.toFile());
                         java.io.Writer w = new java.io.OutputStreamWriter(out, StandardCharsets.UTF_8)) {
                        w.write(content);
                        w.flush();
                        out.getFD().sync();
                    }
                    for (int i = 0; i < 60 && !stop[0]; i++) {
                        Thread.sleep(5);
                    }
                    if (!stop[0]) {
                        moveOver(tmp, target);
                        reachedEnd[0] = true;
                    } else {
                        stoppedMidWrite[0] = true;
                    }
                    return;
                }
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(target.toFile())) {
                    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
                    int sent = 0;
                    while (sent < bytes.length && !stop[0]) {
                        out.write(bytes, sent, Math.min(4096, bytes.length - sent));
                        sent += 4096;
                        Thread.sleep(2);
                    }
                    if (sent < bytes.length) {
                        stoppedMidWrite[0] = true;   // 流在这里被 close，盘上就是半截
                    } else {
                        reachedEnd[0] = true;
                    }
                }
            } catch (InterruptedException e) {
                // interrupt() 让 sleep 抛出：此时写入还没做完，等价于「写一半被杀」
                stoppedMidWrite[0] = true;
            } catch (Exception ignored) {
            }
        });
        writer.setDaemon(true);
        writer.start();
        sleep(atomic ? 120 : 80);
        stop[0] = true;              // 用标志停，不用 Thread.interrupt()（写入调用不响应中断）
        writer.interrupt();
        try {
            writer.join(3000);
        } catch (InterruptedException ignored) {
        }
        return stoppedMidWrite[0] && !reachedEnd[0];
    }

    static void moveOver(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.deleteIfExists(target);
            Files.move(tmp, target);
        }
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || !Files.isDirectory(Paths.get(args[0]))) {
            System.out.println("用法：check\\realmeta.bat <目录>（目录里要有 library.json / libraries.json）");
            System.exit(2);
        }
        Path dir = Paths.get(args[0]);
        File json = dir.toFile();
        System.out.println("org.json 实现：" + JSONArray.class.getProtectionDomain().getCodeSource().getLocation());
        for (String name : new String[]{"library.json", "libraries.json"}) {
            Path p = dir.resolve(name);
            if (Files.exists(p)) {
                runOne(name, p);
            } else {
                System.out.println("\n（跳过 " + name + "：目录里没有这个文件）");
            }
        }
        Path biggest = dir.resolve(Files.exists(dir.resolve("library.json")) ? "library.json" : "libraries.json");
        if (Files.exists(biggest)) {
            raceExperiment(biggest);
        }
        System.out.println("\n== real meta: " + pass + " passed, " + fail + " failed ==");
        if (fail > 0) {
            System.exit(1);
        }
    }
}
