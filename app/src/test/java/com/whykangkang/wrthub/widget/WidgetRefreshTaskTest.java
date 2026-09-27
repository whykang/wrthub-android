package com.whykangkang.wrthub.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.whykangkang.wrthub.model.NetworkDevice;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** 小组件自取数据时挑哪块网卡 —— 必须和实时页同一套规则,否则两处数字对不上 */
public class WidgetRefreshTaskTest {

    private static NetworkDevice dev(String name) {
        NetworkDevice d = new NetworkDevice();
        d.name = name;
        return d;
    }

    @Test
    public void wanIsPreferredByName() {
        List<NetworkDevice> devices = new ArrayList<>();
        devices.add(dev("br-lan"));
        devices.add(dev("wan"));
        assertEquals("wan", WidgetRefreshTask.wanDevice(devices).name);
    }

    @Test
    public void matchesAnyNameContainingWan() {
        List<NetworkDevice> devices = new ArrayList<>();
        devices.add(dev("br-lan"));
        devices.add(dev("pppoe-wan"));
        assertEquals("pppoe-wan", WidgetRefreshTask.wanDevice(devices).name);
    }

    @Test
    public void physicalPortWinsOverBridgeWhenNoWan() {
        List<NetworkDevice> devices = new ArrayList<>();
        devices.add(dev("br-lan"));
        devices.add(dev("eth1"));
        // 网桥统计的是内网流量,不是出口
        assertEquals("eth1", WidgetRefreshTask.wanDevice(devices).name);
    }

    @Test
    public void fallsBackToTheFirstDeviceWhenNothingMatches() {
        List<NetworkDevice> devices = new ArrayList<>();
        devices.add(dev("br-lan"));
        devices.add(dev("tun0"));
        assertEquals("br-lan", WidgetRefreshTask.wanDevice(devices).name);
    }

    @Test
    public void emptyOrNullInputIsHandled() {
        assertNull(WidgetRefreshTask.wanDevice(null));
        assertNull(WidgetRefreshTask.wanDevice(new ArrayList<>()));
    }

    @Test
    public void wanIpIsEmptyWhenTheDeviceHasNoAddress() {
        assertEquals("", WidgetRefreshTask.wanIp(null));
        assertEquals("", WidgetRefreshTask.wanIp(dev("wan")));
    }

    @Test
    public void wanIpUsesTheFirstAddress() {
        NetworkDevice d = dev("wan");
        NetworkDevice.IpAddress ip = new NetworkDevice.IpAddress();
        ip.address = "192.168.1.8";
        d.ipaddrs.add(ip);
        assertEquals("192.168.1.8", WidgetRefreshTask.wanIp(d));
    }
}
