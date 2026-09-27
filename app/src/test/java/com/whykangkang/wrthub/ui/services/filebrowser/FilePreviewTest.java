package com.whykangkang.wrthub.ui.services.filebrowser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

/** 预览的判定逻辑:降采样倍数、二进制识别、扩展名 */
public class FilePreviewTest {

    @Test
    public void largeImagesGetDownsampledToPowersOfTwo() {
        assertEquals(1, FilePreviewActivity.sampleSize(1024, 768));
        assertEquals(1, FilePreviewActivity.sampleSize(2048, 1536));
        assertEquals(2, FilePreviewActivity.sampleSize(4096, 3072));
        assertEquals(4, FilePreviewActivity.sampleSize(8192, 100));
    }

    @Test
    public void textIsNotMistakenForBinary() {
        assertFalse(FilePreviewActivity.looksBinary(
                "config wifi-iface\n\toption ssid 'home'\n".getBytes(StandardCharsets.UTF_8)));
        assertFalse(FilePreviewActivity.looksBinary("中文内容也行".getBytes(StandardCharsets.UTF_8)));
        assertFalse(FilePreviewActivity.looksBinary(new byte[0]));
    }

    @Test
    public void nulByteMeansBinary() {
        byte[] bytes = new byte[]{'M', 'Z', 0x00, 0x01, 0x02};
        assertTrue(FilePreviewActivity.looksBinary(bytes));
    }

    @Test
    public void mostlyControlBytesMeansBinary() {
        byte[] bytes = new byte[64];
        for (int i = 0; i < bytes.length; i++) bytes[i] = 0x01;
        assertTrue(FilePreviewActivity.looksBinary(bytes));
    }

    @Test
    public void extensionIsLowercased() {
        assertEquals(".png", FilePreviewActivity.extensionOf("Photo.PNG"));
        assertEquals("", FilePreviewActivity.extensionOf("passwd"));
        assertEquals("", FilePreviewActivity.extensionOf(null));
    }
}
