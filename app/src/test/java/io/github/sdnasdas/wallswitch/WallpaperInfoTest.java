package io.github.sdnasdas.wallswitch;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

public class WallpaperInfoTest {

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    private static byte[] ftyp(String brand) {
        byte[] head = new byte[16];
        System.arraycopy("ftyp".getBytes(StandardCharsets.US_ASCII), 0, head, 4, 4);
        System.arraycopy(brand.getBytes(StandardCharsets.US_ASCII), 0, head, 8, 4);
        return head;
    }

    @Test public void recognisesCommonOriginalFormats() {
        assertEquals("JPEG", WallpaperInfo.sniffFormat(bytes(0xFF, 0xD8, 0xFF, 0xE1)));
        assertEquals("PNG", WallpaperInfo.sniffFormat(bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)));
        assertEquals("WebP", WallpaperInfo.sniffFormat(
                "RIFF\0\0\0\0WEBPVP8 ".getBytes(StandardCharsets.US_ASCII)));
        assertEquals("GIF", WallpaperInfo.sniffFormat("GIF89a".getBytes(StandardCharsets.US_ASCII)));
        assertEquals("HEIC", WallpaperInfo.sniffFormat(ftyp("heic")));
        assertEquals("HEIC", WallpaperInfo.sniffFormat(ftyp("mif1")));
        assertEquals("AVIF", WallpaperInfo.sniffFormat(ftyp("avif")));
    }

    @Test public void unknownOrShortHeadersGiveNoFormat() {
        assertNull(WallpaperInfo.sniffFormat(null));
        assertNull(WallpaperInfo.sniffFormat(new byte[0]));
        assertNull(WallpaperInfo.sniffFormat(bytes(0xFF, 0xD8)));
        // MP4 视频不是图片格式，不能被认成 HEIC
        assertNull(WallpaperInfo.sniffFormat(ftyp("isom")));
    }

    @Test public void fileSizesUseReadableUnits() {
        assertEquals("512 B", WallpaperInfo.formatBytes(512));
        assertEquals("320 KB", WallpaperInfo.formatBytes(320 * 1024));
        assertEquals("1.8 MB", WallpaperInfo.formatBytes((long) (1.8 * 1024 * 1024)));
    }

    @Test public void originalsAreSavedWithMatchingExtensionAndMime() {
        assertArrayEquals(new String[]{".jpg", "image/jpeg"}, WallpaperInfo.albumTypeOf("JPEG"));
        assertArrayEquals(new String[]{".png", "image/png"}, WallpaperInfo.albumTypeOf("PNG"));
        assertArrayEquals(new String[]{".heic", "image/heic"}, WallpaperInfo.albumTypeOf("HEIC"));
        assertArrayEquals(new String[]{".webp", "image/webp"}, WallpaperInfo.albumTypeOf("WebP"));
        // 认不出的格式按 JPEG 存（相册按内容解码）
        assertArrayEquals(new String[]{".jpg", "image/jpeg"}, WallpaperInfo.albumTypeOf(null));
    }

    @Test public void liveDurationAndFpsAreRounded() {
        assertEquals("2.7", WallpaperInfo.formatSeconds(2_700_000L));
        assertEquals("3.0", WallpaperInfo.formatSeconds(2_960_000L));
        assertEquals(60, WallpaperInfo.roundFps(59.94f));
        assertEquals(30, WallpaperInfo.roundFps(30f));
    }
}
