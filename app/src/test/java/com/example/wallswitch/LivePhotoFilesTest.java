package com.example.wallswitch;

import com.example.wallswitch.gl.MotionSource;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

import static org.junit.Assert.*;

public class LivePhotoFilesTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test public void detectsMp4ByBytesWithoutExtension() throws Exception {
        File source = folder.newFile("inbox-id");
        Files.write(source.toPath(), mp4("mp42", "isom"));
        assertTrue(LivePhotoFiles.isMp4(source));
    }

    @Test public void rejectsImageContainersAndJpegLive() throws Exception {
        for (String brand : new String[]{"avif", "heic", "mif1"}) {
            File source = folder.newFile(brand);
            Files.write(source.toPath(), mp4(brand, brand));
            assertFalse(LivePhotoFiles.isMp4(source));
        }
        File live = folder.newFile("live.jpg");
        byte[] bytes = new byte[100];
        bytes[0] = (byte) 0xff;
        bytes[1] = (byte) 0xd8;
        System.arraycopy(mp4("mp42", "isom"), 0, bytes, 20, 24);
        Files.write(live.toPath(), bytes);
        assertFalse(LivePhotoFiles.isMp4(live));
    }

    @Test public void recognizesFtypAfterFreeBox() throws Exception {
        File source = folder.newFile();
        ByteBuffer data = ByteBuffer.allocate(32);
        data.putInt(8).put("free".getBytes(StandardCharsets.US_ASCII));
        data.put(mp4("isom", "mp42"));
        Files.write(source.toPath(), data.array());
        assertTrue(LivePhotoFiles.isMp4(source));
    }

    @Test public void rejectsTruncatedAndInvalidBoxes() throws Exception {
        for (byte[] data : new byte[][]{new byte[0], new byte[]{0, 0, 0},
                ByteBuffer.allocate(8).putInt(4).put("ftyp".getBytes(StandardCharsets.US_ASCII)).array(),
                ByteBuffer.allocate(8).putInt(1000).put("ftyp".getBytes(StandardCharsets.US_ASCII)).array()}) {
            File source = folder.newFile();
            Files.write(source.toPath(), data);
            assertFalse(LivePhotoFiles.isMp4(source));
        }
    }

    @Test public void writesVerifiedSixtyByteTrailerAndPreservesMedia() throws Exception {
        File cover = folder.newFile("cover");
        byte[] jpeg = {(byte) 0xff, (byte) 0xd8, 4, 5, (byte) 0xff, (byte) 0xd9};
        Files.write(cover.toPath(), jpeg);
        File video = folder.newFile("video");
        byte[] mp4 = Arrays.copyOf(mp4("mp42", "isom"), 70_000);
        Files.write(video.toPath(), mp4);
        File live = folder.newFile("result");
        LivePhotoFiles.write(cover, video, live, 1200);
        byte[] result = Files.readAllBytes(live.toPath());
        assertEquals(jpeg.length + mp4.length + 60, result.length);
        assertArrayEquals(jpeg, Arrays.copyOfRange(result, 0, jpeg.length));
        assertArrayEquals(mp4, Arrays.copyOfRange(result, jpeg.length, jpeg.length + mp4.length));
        String trailer = new String(result, result.length - 60, 60, StandardCharsets.US_ASCII);
        assertEquals("v3_f0_c", trailer.substring(0, 20).trim());
        assertEquals("1200:1000", trailer.substring(20, 40).trim());
        assertEquals("LIVE_70020", trailer.substring(40).trim());
        assertArrayEquals(new long[]{jpeg.length, mp4.length + 20}, MotionSource.locate(live));
        assertFalse(LivePhotoFiles.isMp4(live));
    }

    @Test public void smallLiveStillHasPlayableRange() throws Exception {
        File cover = folder.newFile("tiny-cover");
        Files.write(cover.toPath(), new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xd9});
        File video = folder.newFile("tiny-video");
        Files.write(video.toPath(), mp4("mp42", "isom"));
        File live = folder.newFile("tiny-live");
        LivePhotoFiles.write(cover, video, live, 0);
        assertTrue(live.length() < 64 * 1024);
        assertArrayEquals(new long[]{4, video.length() + 20}, MotionSource.locate(live));
    }

    private static byte[] mp4(String major, String compatible) {
        return ByteBuffer.allocate(24).putInt(24).put("ftyp".getBytes(StandardCharsets.US_ASCII))
                .put(major.getBytes(StandardCharsets.US_ASCII)).putInt(0)
                .put(compatible.getBytes(StandardCharsets.US_ASCII))
                .put("mp42".getBytes(StandardCharsets.US_ASCII)).array();
    }
}
