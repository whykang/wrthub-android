package com.whykangkang.wrthub.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class FormattersTest {

    @Test
    public void bytes_scalesUnits() {
        assertEquals("512 B", Formatters.bytes(512));
        assertEquals("1.0 KB", Formatters.bytes(1024));
        assertEquals("1.5 KB", Formatters.bytes(1536));
        assertEquals("1.0 MB", Formatters.bytes(1024L * 1024));
        assertEquals("2.0 GB", Formatters.bytes(2L * 1024 * 1024 * 1024));
    }

    @Test
    public void rate_scalesUnits() {
        assertEquals("0.0 B/s", Formatters.rate(0));
        assertEquals("1.0 KB/s", Formatters.rate(1024));
        assertEquals("5.0 MB/s", Formatters.rate(5 * 1024 * 1024));
    }

    @Test
    public void uptime_formatsDaysAndClock() {
        assertEquals("00:00:00", Formatters.uptime(0, false));
        assertEquals("01:01:05", Formatters.uptime(3665, false));
        assertEquals("1天 00:00:00", Formatters.uptime(86400, true));
        assertEquals("2d 03:04:05", Formatters.uptime(2 * 86400 + 3 * 3600 + 4 * 60 + 5, false));
    }

    @Test
    public void linkSpeed_parsesDuplex() {
        assertEquals("1Gbps · 全双工", Formatters.linkSpeed("1000F", true));
        assertEquals("1Gbps · Full", Formatters.linkSpeed("1000F", false));
        assertEquals("100Mbps · Half", Formatters.linkSpeed("100H", false));
        assertEquals("", Formatters.linkSpeed("", true));
    }
}