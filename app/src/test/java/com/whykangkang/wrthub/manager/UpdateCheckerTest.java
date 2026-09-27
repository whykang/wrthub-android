package com.whykangkang.wrthub.manager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UpdateCheckerTest {

    private static final String SAMPLE = "{\n"
            + "  \"versionCode\": 2,\n"
            + "  \"versionName\": \"1.1.0\",\n"
            + "  \"forceUpdate\": false,\n"
            + "  \"downloadUrl\": \"https://example.com/app.apk\",\n"
            + "  \"updateMessage\": \"修复已知问题,优化使用体验\"\n"
            + "}";

    @Test
    public void parsesSampleDocument() {
        UpdateChecker.UpdateInfo info = UpdateChecker.parse(SAMPLE);
        assertNotNull(info);
        assertEquals(2, info.versionCode);
        assertEquals("1.1.0", info.versionName);
        assertFalse(info.forceUpdate);
        assertEquals("https://example.com/app.apk", info.downloadUrl);
        assertEquals("修复已知问题,优化使用体验", info.updateMessage);
    }

    @Test
    public void readsForceUpdateFlag() {
        UpdateChecker.UpdateInfo info = UpdateChecker.parse(
                "{\"versionCode\":9,\"forceUpdate\":true}");
        assertNotNull(info);
        assertTrue(info.forceUpdate);
        assertEquals("", info.versionName);
        assertEquals("", info.downloadUrl);
    }

    @Test
    public void rejectsMalformedOrIncompletePayloads() {
        assertNull(UpdateChecker.parse(null));
        assertNull(UpdateChecker.parse(""));
        assertNull(UpdateChecker.parse("not json"));
        assertNull(UpdateChecker.parse("{\"versionName\":\"1.1.0\"}"));
        assertNull(UpdateChecker.parse("[1,2,3]"));
    }
}
