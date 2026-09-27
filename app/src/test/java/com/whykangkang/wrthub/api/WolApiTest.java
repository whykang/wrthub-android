package com.whykangkang.wrthub.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.util.List;

public class WolApiTest {

    @Test
    public void normalizeMac_acceptsCommonFormats() {
        assertEquals("AA:BB:CC:DD:EE:FF", WolApi.normalizeMac("aa:bb:cc:dd:ee:ff"));
        assertEquals("AA:BB:CC:DD:EE:FF", WolApi.normalizeMac("AA-BB-CC-DD-EE-FF"));
        assertEquals("AA:BB:CC:DD:EE:FF", WolApi.normalizeMac(" aabb.ccdd.eeff "));
    }

    @Test
    public void normalizeMac_rejectsWrongLength() {
        assertNull(WolApi.normalizeMac("AA:BB:CC:DD:EE"));
        assertNull(WolApi.normalizeMac("AA:BB:CC:DD:EE:FF:00"));
        assertNull(WolApi.normalizeMac(null));
    }

    @Test
    public void parseInterfaces_bridgeFirstSkipsLoAndDown() {
        JsonObject data = JsonParser.parseString("{"
                + "\"lo\":{\"up\":true,\"devtype\":\"ethernet\"},"
                + "\"sit0\":{\"up\":false},"
                + "\"eth1\":{\"up\":true,\"devtype\":\"ethernet\"},"
                + "\"br-lan\":{\"up\":true,\"devtype\":\"bridge\"},"
                + "\"eth0\":{\"up\":true,\"devtype\":\"ethernet\"}}").getAsJsonObject();
        List<WolApi.Iface> list = WolApi.parseInterfaces(data);
        assertEquals(3, list.size());
        assertEquals("br-lan", list.get(0).name);
        assertEquals("eth0", list.get(1).name);
        assertEquals("eth1", list.get(2).name);
    }
}
