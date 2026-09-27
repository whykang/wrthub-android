package com.whykangkang.wrthub.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * 搜索结果解析。
 * 报文取自真机实测:GET /api/search/etc/?query=passwd →
 * [{"dir":false,"path":"passwd"},{"dir":false,"path":"samba/smbpasswd"}]
 * —— path 是**相对搜索起点**的,不拼成绝对路径后续操作全会找错文件。
 */
public class FileBrowserSearchTest {

    @Test
    public void relativePathsBecomeAbsolute() {
        String body = "[{\"dir\":false,\"path\":\"passwd\"},"
                + "{\"dir\":false,\"path\":\"samba/smbpasswd\"},"
                + "{\"dir\":true,\"path\":\"config\"}]";
        List<FileBrowserApi.Entry> items = FileBrowserApi.parseSearch(body, "/etc");

        assertEquals(3, items.size());
        assertEquals("/etc/passwd", items.get(0).path);
        assertEquals("passwd", items.get(0).name);
        assertFalse(items.get(0).isDir);

        assertEquals("/etc/samba/smbpasswd", items.get(1).path);
        assertEquals("smbpasswd", items.get(1).name);

        assertEquals("/etc/config", items.get(2).path);
        assertTrue(items.get(2).isDir);
    }

    @Test
    public void searchFromRootDoesNotDoubleTheSlash() {
        List<FileBrowserApi.Entry> items = FileBrowserApi.parseSearch(
                "[{\"dir\":false,\"path\":\"etc/passwd\"}]", "/");
        assertEquals("/etc/passwd", items.get(0).path);
    }

    @Test
    public void toleratesLeadingSlashInResult() {
        List<FileBrowserApi.Entry> items = FileBrowserApi.parseSearch(
                "[{\"dir\":false,\"path\":\"/a/b.txt\"}]", "/tmp");
        assertEquals("/tmp/a/b.txt", items.get(0).path);
    }

    /**
     * 搜索接口只回 {dir, path},没有体积和时间。
     * 界面必须知道这一点,否则会把缺省的 0 当真实体积显示成「0 B · 」。
     */
    @Test
    public void searchResultsAreMarkedAsLackingMetadata() {
        List<FileBrowserApi.Entry> items = FileBrowserApi.parseSearch(
                "[{\"dir\":false,\"path\":\"passwd\"}]", "/etc");
        assertTrue(items.get(0).fromSearch);
        assertEquals(0, items.get(0).size);
        assertEquals("", items.get(0).modified);
    }

    @Test
    public void directoryListingsAreNotMarkedAsSearchResults() {
        String listing = "{\"items\":[{\"path\":\"/tmp/b.txt\",\"name\":\"b.txt\","
                + "\"size\":17,\"modified\":\"2026-09-20T05:31:12.1Z\",\"isDir\":false}]}";
        List<FileBrowserApi.Entry> items = FileBrowserApi.parseListing(listing);
        assertFalse(items.get(0).fromSearch);
        assertEquals(17, items.get(0).size);
    }

    @Test
    public void malformedSearchYieldsEmptyList() {
        assertTrue(FileBrowserApi.parseSearch("not json", "/").isEmpty());
        assertTrue(FileBrowserApi.parseSearch("{}", "/").isEmpty());
        assertTrue(FileBrowserApi.parseSearch("[]", "/").isEmpty());
    }

    @Test
    public void extensionIsTakenFromTheName() {
        assertEquals(".txt", FileBrowserApi.extensionOf("a.txt"));
        assertEquals("", FileBrowserApi.extensionOf("passwd"));
        assertEquals("", FileBrowserApi.extensionOf(".bashrc"));
    }
}
