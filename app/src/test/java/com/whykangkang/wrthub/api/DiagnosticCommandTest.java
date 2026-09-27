package com.whykangkang.wrthub.api;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

/**
 * 诊断命令的构造。
 * 原来写死了 /usr/bin/traceroute —— rpcd 的 file exec ACL 按命令原样匹配,
 * 白名单里是裸名字,而且 busybox 的 traceroute 在不同固件里路径也不一样,
 * 所以 IPv4 traceroute 在真机上直接不工作(iOS 用裸名字则正常)。
 */
public class DiagnosticCommandTest {

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    @Test
    public void commandsAreBareNamesNotAbsolutePaths() {
        assertEquals("ping", OpenWrtApi.diagnosticCommand(OpenWrtApi.DiagnosticType.PING));
        assertEquals("traceroute",
                OpenWrtApi.diagnosticCommand(OpenWrtApi.DiagnosticType.TRACEROUTE));
        assertEquals("nslookup", OpenWrtApi.diagnosticCommand(OpenWrtApi.DiagnosticType.NSLOOKUP));
    }

    @Test
    public void tracerouteArgsMatchIos() {
        assertArrayEquals(
                new String[]{"-4", "-q", "1", "-w", "1", "-n", "-m", "20", "baidu.com"},
                OpenWrtApi.diagnosticArgs(OpenWrtApi.DiagnosticType.TRACEROUTE,
                        "baidu.com", false));
        assertArrayEquals(
                new String[]{"-6", "-q", "1", "-w", "1", "-n", "-m", "20", "baidu.com"},
                OpenWrtApi.diagnosticArgs(OpenWrtApi.DiagnosticType.TRACEROUTE,
                        "baidu.com", true));
    }

    @Test
    public void pingAndNslookupArgsMatchIos() {
        assertArrayEquals(
                new String[]{"-4", "-c", "5", "-W", "1", "immortalwrt.org"},
                OpenWrtApi.diagnosticArgs(OpenWrtApi.DiagnosticType.PING,
                        "immortalwrt.org", false));
        assertArrayEquals(new String[]{"immortalwrt.org"},
                OpenWrtApi.diagnosticArgs(OpenWrtApi.DiagnosticType.NSLOOKUP,
                        "immortalwrt.org", false));
    }

    @Test
    public void outputJoinsStdoutAndStderr() {
        assertEquals("hop 1\nsome warning", OpenWrtApi.diagnosticOutput(
                json("{\"stdout\":\"hop 1\",\"stderr\":\"some warning\"}")));
        assertEquals("hop 1", OpenWrtApi.diagnosticOutput(
                json("{\"stdout\":\"hop 1\",\"stderr\":\"\"}")));
    }

    @Test
    public void emptyOutputReportsExitCode() {
        String text = OpenWrtApi.diagnosticOutput(
                json("{\"code\":127,\"stdout\":\"\",\"stderr\":\"\"}"));
        assertTrue(text, text.contains("127"));
    }
}
