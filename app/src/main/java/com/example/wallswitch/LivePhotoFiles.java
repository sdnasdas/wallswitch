package com.example.wallswitch;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

public final class LivePhotoFiles {
    private LivePhotoFiles() { }

    public static boolean isMp4(File file) throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            long limit = Math.min(input.length(), 64 * 1024);
            long offset = 0;
            while (offset + 8 <= limit) {
                input.seek(offset);
                long size = Integer.toUnsignedLong(input.readInt());
                int type = input.readInt();
                int header = 8;
                if (size == 1 && offset + 16 <= limit) {
                    size = input.readLong();
                    header = 16;
                }
                if (size < header || size > limit - offset) {
                    return false;
                }
                if (type == 0x66747970) {
                    if (size < header + 8) {
                        return false;
                    }
                    String major = brand(input);
                    if (imageBrand(major)) {
                        return false;
                    }
                    input.readInt();
                    boolean videoBrand = videoBrand(major);
                    while (input.getFilePointer() + 4 <= offset + size) {
                        videoBrand |= videoBrand(brand(input));
                    }
                    return videoBrand;
                }
                if (type != 0x66726565 && type != 0x77696465 && type != 0x736b6970) {
                    return false;
                }
                offset += size;
            }
            return false;
        }
    }

    private static String brand(RandomAccessFile input) throws IOException {
        byte[] value = new byte[4];
        input.readFully(value);
        return new String(value, StandardCharsets.US_ASCII);
    }

    private static boolean imageBrand(String value) {
        return value.equals("avif") || value.equals("avis") || value.equals("mif1")
                || value.equals("msf1") || value.startsWith("hei") || value.startsWith("hev");
    }

    private static boolean videoBrand(String value) {
        return value.equals("isom") || value.startsWith("iso") || value.equals("mp41")
                || value.equals("mp42") || value.equals("avc1") || value.equals("hvc1")
                || value.equals("M4V ") || value.equals("MSNV") || value.equals("dash");
    }

    public static void write(File jpeg, File mp4, File output, long coverTimeMs) throws IOException {
        if (jpeg.length() == 0 || !isMp4(mp4) || coverTimeMs < 0) {
            throw new IOException("Live 封面或视频无效");
        }
        try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(output))) {
            Files.copy(jpeg.toPath(), out);
            Files.copy(mp4.toPath(), out);
            out.write(field("v3_f0_c"));
            out.write(field(coverTimeMs + ":1000"));
            // 尾部解析器只扣末 40 字节，所以长度还要包含版本字段的 20 字节。
            out.write(field("LIVE_" + (mp4.length() + 20)));
        }
    }

    private static byte[] field(String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        if (bytes.length > 20) {
            throw new IOException("Live 尾字段过长");
        }
        byte[] padded = new byte[20];
        Arrays.fill(padded, (byte) ' ');
        System.arraycopy(bytes, 0, padded, 0, bytes.length);
        return padded;
    }
}
