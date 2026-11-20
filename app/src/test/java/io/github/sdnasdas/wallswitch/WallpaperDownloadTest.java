package io.github.sdnasdas.wallswitch;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;

import static org.junit.Assert.*;

public class WallpaperDownloadTest {

    @Test
    public void acceptsOnlyHttpsWithoutCredentials() {
        assertTrue(WallpaperDownload.isHttps("https://img.haowallpaper.com/a.png"));
        assertTrue(WallpaperDownload.isHttps("HTTPS://haowallpaper.com/mobileView"));
        assertFalse(WallpaperDownload.isHttps("http://haowallpaper.com/a.png"));
        assertFalse(WallpaperDownload.isHttps("file:///sdcard/a.png"));
        assertFalse(WallpaperDownload.isHttps("javascript:alert(1)"));
        assertFalse(WallpaperDownload.isHttps("blob:https://haowallpaper.com/test"));
        assertFalse(WallpaperDownload.isHttps("https://user:pass@example.com/a.png"));
        assertFalse(WallpaperDownload.isHttps(null));
    }

    @Test
    public void supportsPageGeneratedImagesButNotOtherData() {
        assertTrue(WallpaperDownload.isPageImage("blob:https://haowallpaper.com/test"));
        assertTrue(WallpaperDownload.isPageImage("data:image/png;base64,aGVsbG8="));
        assertTrue(WallpaperDownload.isPageImage("data:image/jpeg;base64,aGVsbG8="));
        assertFalse(WallpaperDownload.isPageImage("data:text/html,<script></script>"));
        assertFalse(WallpaperDownload.isPageImage("file:///sdcard/a.png"));
        assertFalse(WallpaperDownload.isPageImage("https://haowallpaper.com/a.png"));
        assertFalse(WallpaperDownload.isPageImage(null));
    }

    @Test
    public void cookieOriginIncludesPortAndScheme() throws Exception {
        URL page = new URL("https://haowallpaper.com/a");
        assertTrue(WallpaperDownload.sameOrigin(page, new URL("https://haowallpaper.com:443/b")));
        assertTrue(WallpaperDownload.sameOrigin(page, new URL("https://HAOWALLPAPER.com/c")));
        assertFalse(WallpaperDownload.sameOrigin(page, new URL("https://img.haowallpaper.com/a")));
        assertFalse(WallpaperDownload.sameOrigin(page, new URL("https://haowallpaper.com:8443/a")));
        assertFalse(WallpaperDownload.sameOrigin(page, new URL("http://haowallpaper.com/a")));
        assertFalse(WallpaperDownload.sameOrigin(page, new URL("https://haowallpaper.com.evil.test/a")));
    }

    @Test
    public void crossOriginReferrerDoesNotLeakPathOrQuery() throws Exception {
        String page = "https://haowallpaper.com/mobileViewLook/123?private=value#fragment";
        assertEquals("https://haowallpaper.com/", WallpaperDownload.referrer(page,
                new URL("https://img.haowallpaper.com/image.png")));
        assertEquals("https://haowallpaper.com/mobileViewLook/123?private=value",
                WallpaperDownload.referrer(page, new URL("https://haowallpaper.com/image.png")));
        assertNull(WallpaperDownload.referrer("file:///tmp/page.html", new URL("https://example.com/a")));
        assertEquals("https://haowallpaper.com:8443/", WallpaperDownload.referrer(
                "https://haowallpaper.com:8443/a?q=value", new URL("https://img.haowallpaper.com/a")));
    }

    @Test
    public void cancellingBeforeConnectRemovesPartialDownload() throws Exception {
        File partial = File.createTempFile("wallpaper-test-", ".download");
        Files.write(partial.toPath(), new byte[]{1, 2, 3});
        WallpaperDownload download = new WallpaperDownload();
        download.cancel();
        try {
            download.download("https://haowallpaper.com/test.png", null, null, null,
                    partial, (bytes, total) -> fail("取消后不应传进度"));
            fail("应当取消");
        } catch (IOException expected) {
            assertFalse(partial.exists());
        } finally {
            Files.deleteIfExists(partial.toPath());
        }
    }
}
