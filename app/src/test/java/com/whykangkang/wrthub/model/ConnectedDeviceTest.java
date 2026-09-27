package com.whykangkang.wrthub.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class ConnectedDeviceTest {

    private ConnectedDevice device(String hostname, String ip, Integer dbm) {
        ConnectedDevice d = new ConnectedDevice();
        d.hostname = hostname;
        d.ipAddress = ip;
        d.signalDbm = dbm;
        return d;
    }

    @Test
    public void title_prefersHostname() {
        ConnectedDevice d = device("iPhone", "192.168.1.100", null);
        assertEquals("iPhone", d.getPrimaryTitle());
        assertEquals("192.168.1.100", d.getSecondaryTitle());
    }

    @Test
    public void title_fallsBackToIpWithoutRepeating() {
        ConnectedDevice d = device(null, "192.168.1.102", null);
        assertEquals("192.168.1.102", d.getPrimaryTitle());
        assertNull(d.getSecondaryTitle());
    }

    @Test
    public void title_ignoresWildcardHostname() {
        ConnectedDevice d = device("*", "192.168.1.103", null);
        assertEquals("192.168.1.103", d.getPrimaryTitle());
        assertNull(d.getSecondaryTitle());
    }

    @Test
    public void signalBars_bucketsByDbm() {
        assertEquals(-1, device(null, "1.1.1.1", null).signalBars());  // 有线
        assertEquals(4, device(null, "1.1.1.1", -50).signalBars());
        assertEquals(3, device(null, "1.1.1.1", -60).signalBars());
        assertEquals(2, device(null, "1.1.1.1", -70).signalBars());
        assertEquals(1, device(null, "1.1.1.1", -85).signalBars());
        assertEquals(0, device(null, "1.1.1.1", -95).signalBars());
    }
}