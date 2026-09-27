package com.whykangkang.wrthub.ui.dashboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 首页延迟卡的 ping 解析与 fake-ip 判定(对应 iOS parsePingTime / isFakeIPResponse) */
public class LatencyProbeTest {

    @Test
    public void parsePingTime_readsSinglePacketTime() {
        String out = "PING www.baidu.com (39.156.66.10): 56 data bytes\n"
                + "64 bytes from 39.156.66.10: seq=0 ttl=51 time=23.456 ms\n";
        assertEquals(23.456, LatencyProbe.parsePingTime(out), 0.001);
    }

    @Test
    public void parsePingTime_returnsNullWhenUnreachable() {
        assertNull(LatencyProbe.parsePingTime(
                "PING x (1.2.3.4): 56 data bytes\n--- x ping statistics ---\n"
                        + "1 packets transmitted, 0 packets received, 100% packet loss"));
        assertNull(LatencyProbe.parsePingTime(null));
    }

    @Test
    public void isFakeIpResponse_detectsFakeIpPool() {
        // Clash 默认 fake-ip 池 198.18.0.0/15
        assertTrue(LatencyProbe.isFakeIpResponse(
                "PING www.google.com (198.18.0.7): 56 data bytes"));
        assertTrue(LatencyProbe.isFakeIpResponse(
                "PING www.google.com (198.19.3.1): 56 data bytes"));
    }

    @Test
    public void isFakeIpResponse_detectsIcmpRejected() {
        assertTrue(LatencyProbe.isFakeIpResponse(
                "ping: sendto: Operation not permitted"));
    }

    @Test
    public void isFakeIpResponse_falseForRealAddress() {
        assertFalse(LatencyProbe.isFakeIpResponse(
                "PING www.baidu.com (39.156.66.10): 56 data bytes\n"
                        + "64 bytes from 39.156.66.10: seq=0 ttl=51 time=23.4 ms"));
        assertFalse(LatencyProbe.isFakeIpResponse(null));
    }
}
