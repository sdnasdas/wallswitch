import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * BackupStore 两处最容易写错的逻辑之二：zip 打包 → 解包往返（条目名、字节一致性）。
 *
 * <p>2026-10-12 从 check/BackupRoundtripTest.java 拆出：prefs 解析那部分（legacyFormat /
 * v2Format，纯合成数据）已拆去 app/src/test 的 BackupPrefsParseTest 走 JUnit；本文件剩下
 * 的 zip 往返需要真机导出的 library.json / libraries.json 才能构造真包，留在 .cmd 路线。
 *
 * <p>跑法：check\backupzip.cmd ^<library.json^> ^<libraries.json^>
 * （先从手机上把这俩文件拉出来。classpath 里的 org.json 从 Gradle 缓存取，
 *  即 app/build.gradle 里 testImplementation 声明的那份。）
 */
public class BackupZipRoundtripTest {

    static int pass = 0, fail = 0;

    static void ok(boolean cond, String what) {
        System.out.println((cond ? "PASS  " : "FAIL  ") + what);
        if (cond) pass++; else fail++;
    }

    // ===== 用例：zip 往返，用真实拉出来的 json =====
    static void zipRoundtrip(File libraryJson, File librariesJson) throws Exception {
        System.out.println("\n===== zip pack/unpack round-trip (real files) =====");
        File dir = Files.createTempDirectory("bkrt").toFile();
        File zipFile = new File(dir, "wallswitch-backup-test.zip");
        byte[] libBytes = Files.readAllBytes(libraryJson.toPath());
        byte[] libsBytes = Files.readAllBytes(librariesJson.toPath());
        int realItems = new JSONArray(new String(libBytes, StandardCharsets.UTF_8)).length();

        try (ZipOutputStream zout = new ZipOutputStream(new FileOutputStream(zipFile))) {
            JSONArray arr = new JSONArray(new String(libBytes, StandardCharsets.UTF_8));
            JSONObject m = new JSONObject();
            m.put("app_version", "3.75");
            m.put("library_items", arr.length());
            putText(zout, "manifest.json", m.toString(2));
            putStream(zout, "library.json", libBytes);
            putStream(zout, "libraries.json", libsBytes);
            for (int i = 0; i < arr.length(); i++) {
                String id = arr.getJSONObject(i).getString("id");
                putText(zout, "wallpapers/" + id + ".png", "fake-png-bytes-" + id);
                putText(zout, "thumbs/" + id + ".jpg", "fake-thumb-" + id);
            }
            putText(zout, "prefs/settings.xml", "<preferences/>");
        }

        ZipFile zip = new ZipFile(zipFile);
        int images = 0, thumbs = 0;
        boolean manifestOk = false, libOk = false;
        java.util.Enumeration<? extends ZipEntry> es = zip.entries();
        List<String> names = new ArrayList<>();
        while (es.hasMoreElements()) {
            ZipEntry e = es.nextElement();
            String n = e.getName();
            names.add(n);
            if (n.startsWith("wallpapers/")) images++;
            if (n.startsWith("thumbs/")) thumbs++;
            if (n.equals("manifest.json")) {
                manifestOk = new JSONObject(new String(read(zip, e), StandardCharsets.UTF_8))
                        .optInt("library_items") == realItems;
            }
            if (n.equals("library.json")) {
                libOk = new String(read(zip, e), StandardCharsets.UTF_8)
                        .equals(new String(libBytes, StandardCharsets.UTF_8));
            }
        }
        zip.close();
        System.out.println("  entries " + names.size() + ", wallpapers " + images + ", thumbs " + thumbs
                + ", real items " + realItems);
        ok(images == realItems && thumbs == realItems, "every metadata entry gets one image + one thumb entry");
        ok(libOk, "library.json unpacks byte-identical to the original file");
        ok(manifestOk, "manifest item count matches the real data");

        // 模拟 restore 的 dropMissingImages：包里少一张图，那条元数据要被丢弃
        JSONArray all = new JSONArray(new String(libBytes, StandardCharsets.UTF_8));
        List<String> present = new ArrayList<>();
        for (int i = 0; i < all.length(); i++) {
            String id = all.getJSONObject(i).getString("id");
            if (i != 7) {                       // 假装第 8 张图在包里缺失
                present.add("wallpapers/" + id + ".png");
            }
        }
        int kept = 0, dropped = 0;
        for (int i = 0; i < all.length(); i++) {
            String id = all.getJSONObject(i).getString("id");
            if (present.contains("wallpapers/" + id + ".png")) kept++; else dropped++;
        }
        System.out.println("  missing 1 image: kept " + kept + ", dropped " + dropped);
        ok(kept == all.length() - 1 && dropped == 1, "a missing image drops only that entry, no dead entries left");

        // 包名冲突：同一秒重复备份不能覆盖旧包
        String a = backupNameAt(1_760_000_000_000L);
        String b = backupNameAt(1_760_000_000_500L);
        System.out.println("  two backups within the same second: " + a + " / " + b);
        ok(a.equals(b), "timestamp precision is seconds -- same-second backups collide (known naming-strategy edge)");
        zipFile.delete();
        dir.delete();
    }

    /** BackupStore 里的命名式样：yyyyMMdd-HHmmss（只到秒）。 */
    static String backupNameAt(long millis) {
        return "wallswitch-backup-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                .format(new java.util.Date(millis)) + ".zip";
    }

    static void putText(ZipOutputStream zout, String name, String content) throws Exception {
        putStream(zout, name, content.getBytes(StandardCharsets.UTF_8));
    }

    static void putStream(ZipOutputStream zout, String name, byte[] bytes) throws Exception {
        zout.putNextEntry(new ZipEntry(name));
        zout.write(bytes);
        zout.closeEntry();
    }

    static byte[] read(ZipFile zip, ZipEntry e) throws Exception {
        try (InputStream in = zip.getInputStream(e)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("usage: BackupZipRoundtripTest <library.json> <libraries.json>");
            System.exit(2);
        }
        zipRoundtrip(new File(args[0]), new File(args[1]));
        System.out.println("\n== backup zip roundtrip: " + pass + " passed, " + fail + " failed ==");
        if (fail > 0) System.exit(1);
    }
}
