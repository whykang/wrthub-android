package com.whykangkang.wrthub.ui.devices;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.whykangkang.wrthub.model.ConnectedDevice;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 设备列表只由 DHCP 租约决定成员,hostHints / assoclist 只补信息。
 * 之前这两步会自己建条目,导致 Android 列出一堆早就离线的设备(iOS 不会)。
 */
public class ConnectedDeviceLoaderTest {

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    private ConnectedDeviceLoader loader() {
        return new ConnectedDeviceLoader(null, "192.168.1.5");
    }

    private Map<String, ConnectedDevice> leases(String leaseJson) {
        Map<String, ConnectedDevice> byMac = new LinkedHashMap<>();
        loader().parseLeases(json(leaseJson), byMac);
        return byMac;
    }

    @Test
    public void leasesDefineTheList() {
        Map<String, ConnectedDevice> byMac = leases("{\"dhcp_leases\":["
                + "{\"macaddr\":\"AA:BB:CC:00:00:01\",\"ipaddr\":\"192.168.1.10\","
                + "\"hostname\":\"phone\",\"expires\":3600}]}");
        assertEquals(1, byMac.size());
        ConnectedDevice d = byMac.get("aa:bb:cc:00:00:01");
        assertNotNull(d);
        assertEquals("192.168.1.10", d.ipAddress);
        assertEquals(3600, d.leaseRemainingSec);
    }

    @Test
    public void hostHintsEnrichButNeverAdd() {
        Map<String, ConnectedDevice> byMac = leases("{\"dhcp_leases\":["
                + "{\"macaddr\":\"AA:BB:CC:00:00:01\",\"ipaddr\":\"192.168.1.10\",\"expires\":60}]}");
        loader().mergeHostHints(json("{"
                + "\"AA:BB:CC:00:00:01\":{\"name\":\"kitchen-pi\",\"ipaddrs\":[\"192.168.1.10\"]},"
                + "\"DE:AD:BE:EF:00:99\":{\"name\":\"long-gone\",\"ipaddrs\":[\"192.168.1.77\"]}"
                + "}"), byMac);

        assertEquals(1, byMac.size());
        assertEquals("kitchen-pi", byMac.get("aa:bb:cc:00:00:01").hostname);
        assertNull(byMac.get("de:ad:be:ef:00:99"));
    }

    @Test
    public void assoclistEnrichesButNeverAdds() {
        Map<String, ConnectedDevice> byMac = leases("{\"dhcp_leases\":["
                + "{\"macaddr\":\"AA:BB:CC:00:00:01\",\"ipaddr\":\"192.168.1.10\",\"expires\":60}]}");
        loader().mergeAssoclist(json("{\"results\":["
                + "{\"mac\":\"AA:BB:CC:00:00:01\",\"signal\":-52,"
                + "\"rx\":{\"bytes\":1000},\"tx\":{\"bytes\":2000}},"
                + "{\"mac\":\"11:22:33:44:55:66\",\"signal\":-70}"
                + "]}"), "MyWiFi", byMac);

        assertEquals(1, byMac.size());
        ConnectedDevice d = byMac.get("aa:bb:cc:00:00:01");
        assertEquals(Integer.valueOf(-52), d.signalDbm);
        assertEquals("MyWiFi", d.ssid);
        assertEquals(1000, d.rxBytes);
        assertEquals(2000, d.txBytes);
        assertNull(byMac.get("11:22:33:44:55:66"));
    }

    @Test
    public void hostHintsFillMissingIpAndMarkLocal() {
        Map<String, ConnectedDevice> byMac = leases("{\"dhcp_leases\":["
                + "{\"macaddr\":\"AA:BB:CC:00:00:02\",\"expires\":60}]}");
        assertFalse(byMac.isEmpty());
        loader().mergeHostHints(json("{\"AA:BB:CC:00:00:02\":{\"ipaddrs\":[\"192.168.1.5\"]}}"), byMac);

        ConnectedDevice d = byMac.get("aa:bb:cc:00:00:02");
        assertEquals("192.168.1.5", d.ipAddress);
        assertTrue("本机 IP 命中时要打「本机」标记", d.isLocal);
    }
}
