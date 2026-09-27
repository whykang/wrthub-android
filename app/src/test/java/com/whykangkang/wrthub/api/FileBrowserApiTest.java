package com.whykangkang.wrthub.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * FileBrowser 客户端的纯逻辑部分。
 * 报文取自真机实测(File Browser v2.28.0)。
 */
public class FileBrowserApiTest {

    private static final String LISTING = "{\"items\":["
            + "{\"path\":\"/tmp/b.txt\",\"name\":\"b.txt\",\"size\":17,"
            + "\"extension\":\".txt\",\"modified\":\"2026-09-20T05:31:12.1Z\","
            + "\"isDir\":false,\"isSymlink\":false,\"type\":\"text\"},"
            + "{\"path\":\"/tmp/.uci\",\"name\":\".uci\",\"size\":140,"
            + "\"extension\":\".uci\",\"modified\":\"2026-09-19T13:06:36.6Z\","
            + "\"isDir\":true,\"isSymlink\":false,\"type\":\"\"},"
            + "{\"path\":\"/tmp/Apps\",\"name\":\"Apps\",\"size\":0,"
            + "\"extension\":\"\",\"modified\":\"2026-09-19T13:06:36.6Z\","
            + "\"isDir\":true,\"isSymlink\":false,\"type\":\"\"}"
            + "],\"numDirs\":2,\"numFiles\":1,\"path\":\"/tmp\"}";

    @Test
    public void foldersComeFirstThenCaseInsensitiveName() {
        List<FileBrowserApi.Entry> items = FileBrowserApi.parseListing(LISTING);
        assertEquals(3, items.size());
        assertEquals(".uci", items.get(0).name);
        assertTrue(items.get(0).isDir);
        assertEquals("Apps", items.get(1).name);
        assertEquals("b.txt", items.get(2).name);
        assertFalse(items.get(2).isDir);
        assertEquals(17, items.get(2).size);
    }

    @Test
    public void malformedListingYieldsEmptyList() {
        assertTrue(FileBrowserApi.parseListing("not json").isEmpty());
        assertTrue(FileBrowserApi.parseListing("{}").isEmpty());
        assertTrue(FileBrowserApi.parseListing("{\"items\":null}").isEmpty());
    }

    @Test
    public void pathEncodingKeepsSlashesAndEscapesTheRest() {
        assertEquals("/tmp/a%20b.txt", FileBrowserApi.encodePath("/tmp/a b.txt"));
        // # 和 ? 不转义会把 URL 截断
        assertEquals("/tmp/x%23y%3Fz", FileBrowserApi.encodePath("/tmp/x#y?z"));
        assertEquals("/%E6%96%87%E4%BB%B6/a.txt", FileBrowserApi.encodePath("/文件/a.txt"));
        assertEquals("/", FileBrowserApi.encodePath("/"));
        assertEquals("/", FileBrowserApi.encodePath(""));
    }

    @Test
    public void joinNeverProducesDoubleSlash() {
        assertEquals("/a.txt", FileBrowserApi.join("/", "a.txt"));
        assertEquals("/tmp/a.txt", FileBrowserApi.join("/tmp", "a.txt"));
        assertEquals("/tmp/a.txt", FileBrowserApi.join("/tmp/", "a.txt"));
    }

    @Test
    public void parentOfWalksUpAndStopsAtRoot() {
        assertEquals("/tmp", FileBrowserApi.parentOf("/tmp/a.txt"));
        assertEquals("/tmp", FileBrowserApi.parentOf("/tmp/sub/"));
        assertEquals("/", FileBrowserApi.parentOf("/tmp"));
        assertNull("根目录再往上就该退出页面", FileBrowserApi.parentOf("/"));
        assertNull(FileBrowserApi.parentOf(null));
    }

    /** GET /api/usage/ → {"total":2358194176,"used":244330496}(真机实测) */
    @Test
    public void usageIsParsed() {
        FileBrowserApi.Usage u = FileBrowserApi.parseUsage(
                "{\"total\":2358194176,\"used\":244330496}");
        assertEquals(2358194176L, u.total);
        assertEquals(244330496L, u.used);
        assertEquals(2358194176L - 244330496L, u.free());
        assertEquals(10, u.percent());
    }

    @Test
    public void usagePercentStaysInRange() {
        assertEquals("总量未知时不该算出 NaN 或除零", 0,
                FileBrowserApi.parseUsage("{\"total\":0,\"used\":123}").percent());
        assertEquals(100,
                FileBrowserApi.parseUsage("{\"total\":100,\"used\":999}").percent());
        assertEquals(0, FileBrowserApi.parseUsage("nonsense").total);
    }

    @Test
    public void shareHashIsReadFromResponse() {
        assertEquals("mae06K6t", FileBrowserApi.shareHash(
                "{\"hash\":\"mae06K6t\",\"path\":\"/b.txt\",\"userID\":1,\"expire\":0}"));
        assertNull(FileBrowserApi.shareHash("{}"));
        assertNull(FileBrowserApi.shareHash("nope"));
    }
}
