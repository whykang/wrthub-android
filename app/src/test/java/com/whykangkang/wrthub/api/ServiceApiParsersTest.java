package com.whykangkang.wrthub.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.whykangkang.wrthub.model.EqosDevice;

import org.junit.Test;

import java.util.List;

/** Samba / FTP / eqos / OpenClash / arp-scan 的响应解析 */
public class ServiceApiParsersTest {

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    @Test
    public void sambaParsesGlobalAndShares() {
        SambaApi.SambaConfig config = SambaApi.parse(json("{\"values\":{"
                + "\"samba\":{\".type\":\"samba\",\"workgroup\":\"WG\","
                + "  \"description\":\"NAS\",\"interface\":\"lan\"},"
                + "\"share1\":{\".type\":\"sambashare\",\"name\":\"Media\","
                + "  \"path\":\"/mnt/sda1\",\"read_only\":\"yes\",\"guest_ok\":\"yes\"}}}"));
        assertEquals("WG", config.workgroup);
        assertEquals("NAS", config.description);
        assertTrue(config.enabled);
        assertEquals("samba", config.globalSection);
        assertEquals(1, config.shares.size());
        assertEquals("Media", config.shares.get(0).displayName);
        assertTrue(config.shares.get(0).readOnly);
        assertTrue(config.shares.get(0).guestOk);
    }

    @Test
    public void sambaEmptyInterfaceMeansDisabled() {
        SambaApi.SambaConfig config = SambaApi.parse(json(
                "{\"values\":{\"samba\":{\".type\":\"samba\",\"interface\":\"\"}}}"));
        assertFalse(config.enabled);
    }

    @Test
    public void ftpParsesListenSectionAndUsers() {
        JsonObject response = json("{\"values\":{"
                + "\"listen\":{\".type\":\"listen\",\"port\":\"2121\",\"enable4\":\"1\"},"
                + "\"user1\":{\".type\":\"user\",\".name\":\"user1\","
                + "  \"username\":\"ftpuser\",\"home\":\"/home/ftp\"}}}");
        FtpApi.FtpConfig config = FtpApi.parseConfig(response);
        assertEquals("2121", config.port);
        assertTrue(config.enable4);

        List<FtpApi.FtpUser> users = FtpApi.parseUsers(response);
        assertEquals(1, users.size());
        assertEquals("ftpuser", users.get(0).username);
        assertEquals("/home/ftp", users.get(0).home);
    }

    @Test
    public void eqosParsesGlobalAndSortsDevicesNumerically() {
        EqosApi.EqosSettings settings = EqosApi.parse(json("{\"values\":{"
                + "\"config\":{\".type\":\"eqos\",\"enabled\":\"1\","
                + "  \"download\":\"300\",\"upload\":\"30\"},"
                + "\"cfg02\":{\".type\":\"device\",\"ip\":\"192.168.1.10\","
                + "  \"download\":\"20\",\"upload\":\"5\",\"comment\":\"TV\"},"
                + "\"cfg01\":{\".type\":\"device\",\"ip\":\"192.168.1.9\","
                + "  \"download\":\"5\",\"upload\":\"2\"},"
                + "\"cfg03\":{\".type\":\"device\",\"download\":\"1\",\"upload\":\"1\"}}}"));

        assertTrue(settings.global.enabled);
        assertEquals(300, settings.global.download);
        // 没有 ip 的坏数据段被跳过
        assertEquals(2, settings.devices.size());
        // .9 要排在 .10 前面
        assertEquals("192.168.1.9", settings.devices.get(0).ip);
        assertEquals("192.168.1.10", settings.devices.get(1).ip);
        assertEquals("TV", settings.devices.get(1).displayName());
        // 没写 enabled 的条目默认启用
        assertTrue(settings.devices.get(0).enabled);
    }

    @Test
    public void eqosDeviceDisplayNameFallsBackToIp() {
        EqosDevice device = new EqosDevice();
        device.ip = "10.0.0.5";
        assertEquals("10.0.0.5", device.displayName());
        device.comment = "  ";
        assertEquals("10.0.0.5", device.displayName());
    }

    @Test
    public void clashDelayParsesBothShapes() {
        assertEquals(Integer.valueOf(123),
                ClashDelayApi.parseDelay("junk before {\"delay\":123} junk after"));
        assertNull(ClashDelayApi.parseDelay("{\"message\":\"Timeout\"}"));
        assertNull(ClashDelayApi.parseDelay("not json"));
        assertNull(ClashDelayApi.parseDelay(null));
    }

    @Test
    public void clashDelayPathEncodesTarget() {
        String path = ClashDelayApi.delayPath("www.google.com");
        assertTrue(path.startsWith("/proxies/GLOBAL/delay?timeout=5000&url="));
        assertTrue(path.contains("https%3A%2F%2Fwww.google.com"));
    }

    @Test
    public void openClashTrafficReadsProvidersArray() {
        OpenClashApi.TrafficInfo info = OpenClashApi.parseTraffic(
                "{\"providers\":[{\"used\":\"95.7 GB\",\"total\":\"300.0 GB\","
                        + "\"surplus\":\"204.3 GB\",\"percent\":\"31.9\","
                        + "\"expire\":\"2026-12-12\",\"day_left\":\"319\"}]}");
        assertNotNull(info);
        assertEquals("95.7 GB", info.used);
        assertEquals("31.9", info.percent);
        assertEquals("319", info.dayLeft);
    }

    @Test
    public void openClashTrafficReadsTopLevelFields() {
        OpenClashApi.TrafficInfo info = OpenClashApi.parseTraffic(
                "{\"used\":\"1 GB\",\"total\":\"2 GB\",\"expire\":\"null\"}");
        assertNotNull(info);
        assertEquals("1 GB", info.used);
        // "null" 字符串当作没有到期时间
        assertNull(info.expire);
    }

    @Test
    public void arpScanParsesDataLinesOnly() {
        String output = "Interface: br-lan, datalink type: EN10MB\n"
                + "Starting arp-scan 1.9.7\n"
                + "192.168.1.1\t00:11:22:33:44:55\tTP-Link\n"
                + "192.168.1.23\t3c:22:fb:aa:bb:cc\t(Unknown)\n"
                + "192.168.1.23\t3C:22:FB:AA:BB:CC\tDuplicate\n"
                + "\n2 packets received by filter";
        List<ArpScanApi.Entry> entries = ArpScanApi.parseOutput(output);
        assertEquals(2, entries.size());
        assertEquals("00:11:22:33:44:55".toUpperCase(), entries.get(0).mac);
        assertEquals("TP-Link", entries.get(0).vendor);
        // (Unknown) 视为没有厂商名;重复 MAC 只保留一条
        assertEquals("", entries.get(1).vendor);
    }

    @Test
    public void arpScanDetectsDeniedOutput() {
        assertTrue(ArpScanApi.looksDenied("arp-scan: Permission denied"));
        assertFalse(ArpScanApi.looksDenied("192.168.1.1\t00:11:22:33:44:55"));
    }
}
