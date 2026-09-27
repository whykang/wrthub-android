package com.whykangkang.wrthub.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AddressParserTest {

    @Test
    public void plainHost_defaultsToHttp80() {
        AddressParser.ParsedAddress p = AddressParser.parse("192.168.1.1");
        assertEquals("192.168.1.1", p.host);
        assertEquals(80, p.port);
        assertFalse(p.useHttps);
        assertFalse(p.explicitPort);
    }

    @Test
    public void hostWithPort() {
        AddressParser.ParsedAddress p = AddressParser.parse("192.168.1.1:8080");
        assertEquals("192.168.1.1", p.host);
        assertEquals(8080, p.port);
        assertTrue(p.explicitPort);
    }

    @Test
    public void httpsUrl_setsHttpsAndDefaultPort() {
        AddressParser.ParsedAddress p = AddressParser.parse("https://router.lan");
        assertEquals("router.lan", p.host);
        assertEquals(443, p.port);
        assertTrue(p.useHttps);
    }

    @Test
    public void fullUrlWithPortAndPath() {
        AddressParser.ParsedAddress p = AddressParser.parse("http://10.0.0.1:8080/cgi-bin/luci");
        assertEquals("10.0.0.1", p.host);
        assertEquals(8080, p.port);
        assertFalse(p.useHttps);
        assertTrue(p.explicitPort);
    }

    @Test
    public void invalidInputs_returnNull() {
        assertNull(AddressParser.parse(null));
        assertNull(AddressParser.parse("   "));
        assertNull(AddressParser.parse("http://"));
        assertNull(AddressParser.parse("host:99999"));
        assertNull(AddressParser.parse("host:abc"));
    }
}