package com.whykangkang.wrthub.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.whykangkang.wrthub.model.FileItem;

import org.junit.Test;

import java.util.List;

/** 原生 FTP 客户端的协议与 LIST 解析(对应 iOS FTPClient.parseFTPListing) */
public class FtpClientTest {

    @Test
    public void parsesPasvReplyIntoPort() {
        int[] addr = FtpClient.parsePasv("227 Entering Passive Mode (192,168,1,1,200,21)");
        assertEquals(200 * 256 + 21, addr[0]);
        assertNull(FtpClient.parsePasv("227 no parens"));
        assertNull(FtpClient.parsePasv(null));
    }

    @Test
    public void parsesUnixListingDirectoriesFirst() {
        String listing = "drwxr-xr-x    2 root  root  4096 Jan 01 12:00 Documents\r\n"
                + "-rw-r--r--    1 root  root  12345 Jan 01 12:00 notes with spaces.txt\r\n"
                + "drwxr-xr-x    2 root  root  4096 Jan 01 12:00 .\r\n"
                + "drwxr-xr-x    2 root  root  4096 Jan 01 12:00 ..\r\n";
        List<FileItem> files = FtpClient.parseListing(listing, "/mnt");
        // . 与 .. 被过滤;目录排在文件前面
        assertEquals(2, files.size());
        assertTrue(files.get(0).isDirectory);
        assertEquals("Documents", files.get(0).name);
        assertEquals("/mnt/Documents", files.get(0).path);
        // 名字里的空格要完整保留
        assertEquals("notes with spaces.txt", files.get(1).name);
        assertEquals(12345, files.get(1).size);
    }

    @Test
    public void normalizesPaths() {
        assertEquals("/", FtpClient.normalize(null));
        assertEquals("/", FtpClient.normalize(""));
        assertEquals("/mnt", FtpClient.normalize("mnt"));
        assertEquals("/mnt", FtpClient.normalize("//mnt"));
    }

    @Test
    public void formatsFileSizes() {
        assertEquals("", new FileItem("d", "/d", true, 0).sizeString());
        assertEquals("512 B", new FileItem("f", "/f", false, 512).sizeString());
        assertEquals("1.0 KB", new FileItem("f", "/f", false, 1024).sizeString());
        assertEquals("TXT", new FileItem("a.txt", "/a.txt", false, 1).extension());
        assertNull(new FileItem("noext", "/noext", false, 1).extension());
    }
}
