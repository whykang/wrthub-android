package com.whykangkang.wrthub.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.whykangkang.wrthub.model.NetworkDevice;
import com.whykangkang.wrthub.model.RouterInfo;
import com.whykangkang.wrthub.model.StorageInfo;
import com.whykangkang.wrthub.model.WiFiInfo;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 首页数据源的解析(对应 iOS parseSystemInfo / getNetworkDevices / getStorageInfo / WiFi) */
public class RouterApiTest {

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    @Test
    public void parsesSystemInfoWithCpuAndTemperature() {
        RouterInfo info = RouterApi.parseSystemInfo(json("{"
                + "\"hostname\":\"ImmortalWrt\",\"uptime\":93784,"
                + "\"load\":[65536,32768,16384],"
                + "\"memory\":{\"total\":2000000000,\"free\":1000000000,\"cached\":0,\"shared\":5},"
                + "\"model\":\"x86/64\",\"release\":{\"description\":\"ImmortalWrt 23.05.4\"},"
                + "\"kernel\":\"5.15.167\","
                + "\"cpuinfo\":\"Intel(R) N100 x 4C 4T (973.352MHz, 27.0°C)\","
                + "\"cpuusage\":\"3%\","
                + "\"tempinfo\":\"CPU: 46.4°C, WiFi: 37.0°C\"}"));

        assertEquals("ImmortalWrt", info.hostname);
        assertEquals(93784, info.uptime);
        assertEquals("ImmortalWrt 23.05.4", info.release);
        assertEquals("5.15.167", info.kernel);
        // load 是 1/65536 定点数
        assertEquals(1.0, info.loadavg[0], 0.001);
        assertEquals(0.5, info.loadavg[1], 0.001);
        assertEquals(50.0, info.memory.usedPercentage(), 0.001);

        assertNotNull(info.cpu);
        assertEquals(3.0, info.cpu.usage, 0.001);
        assertEquals(4, info.cpu.count);
        // 温度优先取 tempinfo 的 CPU 值,而不是 cpuinfo 里的 27.0
        assertEquals(46.4, info.cpu.temperature, 0.001);
        assertEquals(973, (int) info.cpu.frequency);
        // 有 cpuinfo 时 system 换成完整 CPU 描述
        assertTrue(info.system.contains("N100"));
    }

    @Test
    public void parsesSystemInfoWithoutCpuSection() {
        // 固件不放行 luci.getCPUInfo 时 cpu 为 null,首页那张卡直接不显示
        RouterInfo info = RouterApi.parseSystemInfo(json(
                "{\"hostname\":\"OpenWrt\",\"uptime\":10,\"memory\":{\"total\":100,\"free\":40}}"));
        assertNull(info.cpu);
        assertEquals(60.0, info.memory.usedPercentage(), 0.001);
    }

    @Test
    public void parsesNetworkDevicesAndSortsPhysicalFirst() {
        List<NetworkDevice> devices = RouterApi.parseNetworkDevices(json("{"
                + "\"br-lan\":{\"up\":true,\"stats\":{\"rx_bytes\":10,\"tx_bytes\":20},"
                + "  \"link\":{\"carrier\":true}},"
                + "\"eth0\":{\"up\":true,\"mac\":\"AA:BB\",\"master\":\"br-lan\","
                + "  \"ipaddrs\":[{\"address\":\"192.168.1.1\",\"netmask\":\"24\"}],"
                + "  \"stats\":{\"rx_bytes\":2048,\"tx_bytes\":1024},"
                + "  \"link\":{\"carrier\":true,\"speed\":1000,\"duplex\":\"full\"}}}"));

        assertEquals(2, devices.size());
        assertEquals("eth0", devices.get(0).name);      // 物理口排前面
        assertEquals("br-lan", devices.get(1).name);
        assertEquals(1000, (int) devices.get(0).link.speed);
        assertEquals("2.00 KB", devices.get(0).rxBytesFormatted());
        assertTrue(devices.get(1).isBridge());
    }

    @Test
    public void storageKeepsImportantMountsAndSortsThem() {
        List<StorageInfo> list = RouterApi.parseStorage(json("{\"result\":["
                + "{\"device\":\"tmpfs\",\"mount\":\"/tmp\",\"size\":100,\"avail\":40,\"free\":40},"
                + "{\"device\":\"root\",\"mount\":\"/\",\"size\":100,\"avail\":10,\"free\":10},"
                + "{\"device\":\"overlay\",\"mount\":\"/overlay\",\"size\":100,\"avail\":50,\"free\":50},"
                + "{\"device\":\"dup\",\"mount\":\"/overlay\",\"size\":100,\"avail\":50,\"free\":50}"
                + "]}"));
        // "/" 被排除,/overlay 去重,顺序 overlay → tmp
        assertEquals(2, list.size());
        assertEquals("/overlay", list.get(0).mount);
        assertEquals("/tmp", list.get(1).mount);
        assertEquals(50.0, list.get(0).usedPercentage(), 0.001);
    }

    @Test
    public void parsesInterfacesAndJoinsDeviceStats() {
        JsonObject dump = json("{\"interface\":["
                + "{\"interface\":\"wan\",\"device\":\"eth1\",\"proto\":\"dhcp\",\"up\":true,"
                + " \"uptime\":600,\"ipv4-address\":[{\"address\":\"10.0.0.2\"}]}]}");
        JsonObject devices = json(
                "{\"eth1\":{\"stats\":{\"rx_bytes\":500,\"tx_bytes\":300}}}");
        List<com.whykangkang.wrthub.model.NetworkInterfaceInfo> list =
                RouterApi.parseInterfaces(dump, devices);
        assertEquals(1, list.size());
        assertEquals("wan", list.get(0).name);
        assertEquals("10.0.0.2", list.get(0).ipv4);
        assertEquals(500, list.get(0).rxBytes);
        assertEquals(300, list.get(0).txBytes);
    }

    @Test
    public void wirelessParsingIncludesDisabledInterfaces() {
        Map<String, JsonObject> disabled = RouterApi.collectDisabled(json("{\"values\":{"
                + "\"wifinet9\":{\".type\":\"wifi-iface\",\"disabled\":\"1\","
                + "  \"ssid\":\"OldNet\",\"device\":\"radio1\",\"encryption\":\"psk2\"}}}"));
        assertEquals(1, disabled.size());

        List<WiFiInfo> out = new ArrayList<>();
        Map<String, String> ifnames = new HashMap<>();
        RouterApi.parseWirelessDevices(json("{\"radio0\":{\"up\":true,\"interfaces\":["
                + "{\"section\":\"default_radio0\",\"ifname\":\"phy0-ap0\","
                + " \"config\":{\"ssid\":\"MyWiFi\",\"encryption\":\"psk2\",\"mode\":\"ap\"},"
                + " \"iwinfo\":{\"channel\":36}}]}}"), disabled, out, ifnames);

        assertEquals(2, out.size());
        WiFiInfo active = out.get(0);
        assertEquals("MyWiFi", active.ssid);
        assertEquals(36, active.channel);
        assertEquals("5G", active.frequency());
        assertEquals("WPA2-PSK", active.encryption);
        assertTrue(active.enabled);
        assertEquals("phy0-ap0", ifnames.get("default_radio0"));
        // uci 里被禁用的那条也要出现,且标记为未启用
        assertEquals("OldNet", out.get(1).ssid);
        assertTrue(!out.get(1).enabled);
    }

    @Test
    public void staWithoutIwinfoCountsAsDisabled() {
        // 客户端模式没有 iwinfo 说明没连上,按未启用显示(与 iOS 一致)
        List<WiFiInfo> out = new ArrayList<>();
        RouterApi.parseWirelessDevices(json("{\"radio0\":{\"up\":true,\"interfaces\":["
                        + "{\"section\":\"wifinet1\",\"config\":{\"ssid\":\"Uplink\",\"mode\":\"sta\"}}]}}"),
                new HashMap<>(), out, new HashMap<>());
        assertEquals(1, out.size());
        assertTrue(!out.get(0).enabled);
        assertEquals("Client", out.get(0).workModeText());
    }

    @Test
    public void encryptionLabelsMatchIos() {
        assertEquals("OPEN", RouterApi.encryptionLabel("none"));
        assertEquals("WPA2-PSK", RouterApi.encryptionLabel("psk2"));
        assertEquals("WPA/WPA2-PSK", RouterApi.encryptionLabel("psk-mixed"));
        assertEquals("WPA3-SAE", RouterApi.encryptionLabel("sae"));
        assertEquals("OWE", RouterApi.encryptionLabel("owe"));
    }
}
