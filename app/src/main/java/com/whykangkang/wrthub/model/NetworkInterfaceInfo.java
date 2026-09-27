package com.whykangkang.wrthub.model;

/**
 * 一个逻辑接口(network.interface.dump 的一项),对应 iOS NetworkInterface。
 * 改名避免与 java.net.NetworkInterface 冲突。
 */
public class NetworkInterfaceInfo {

    /** 逻辑接口名,如 lan / wan / wwan */
    public String name = "";
    public boolean up;
    /** L3 设备名,如 br-lan / pppoe-wan */
    public String device;
    public String ipv4;
    public String proto = "";
    public Integer uptime;
    public long rxBytes;
    public long txBytes;
}
