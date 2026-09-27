package com.whykangkang.wrthub.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

/** ubus log.read 的结构化条目 → logread 风格文本 */
public class LogFormatTest {

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    @Test
    public void systemLogGetsTimeAndLevelPrefix() {
        JsonObject result = json("{\"log\":["
                + "{\"msg\":\"hostapd: AP-STA-CONNECTED\",\"priority\":5,"
                + "\"source\":1,\"time\":1700000000000}"
                + "]}");
        String text = OpenWrtApi.formatLogRead(result, false);
        assertTrue(text, text.contains("[notice] "));
        assertTrue(text, text.contains("hostapd: AP-STA-CONNECTED"));
        assertTrue(text, text.endsWith("\n"));
    }

    @Test
    public void kernelOnlyKeepsSourceZeroAndDropsPrefix() {
        JsonObject result = json("{\"log\":["
                + "{\"msg\":\"[    0.000000] Booting Linux\",\"priority\":6,\"source\":0,\"time\":1}"
                + ",{\"msg\":\"userspace noise\",\"priority\":6,\"source\":1,\"time\":1}"
                + "]}");
        String text = OpenWrtApi.formatLogRead(result, true);
        assertEquals("[    0.000000] Booting Linux\n", text);
        assertFalse(text.contains("userspace noise"));
        assertFalse(text.contains("[info]"));
    }

    @Test
    public void skipsBlankMessagesAndHandlesMissingFields() {
        JsonObject result = json("{\"log\":["
                + "{\"msg\":\"   \"},{\"msg\":\"kept\"},{\"priority\":3}"
                + "]}");
        assertEquals("kept\n", OpenWrtApi.formatLogRead(result, false));
    }

    @Test
    public void toleratesMissingOrMalformedLogArray() {
        assertEquals("", OpenWrtApi.formatLogRead(json("{}"), false));
        assertEquals("", OpenWrtApi.formatLogRead(json("{\"log\":\"nope\"}"), false));
        assertEquals("", OpenWrtApi.formatLogRead(json("{\"log\":[]}"), true));
    }

    @Test
    public void logReadIsOnTheOptionalAclWhitelist() {
        // 两条通道都没开时 log.read 的 -32002 不该触发重登循环
        assertTrue(OpenWrtApi.isOptionalAclMethod("log", "read"));
    }
}
