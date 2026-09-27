package com.whykangkang.wrthub.model;

/**
 * 一个 WiFi 网络,对应 iOS WiFiNetwork。
 * 既用于扫描结果,也用于已配置接口的展示。
 */
public class WiFiNetwork {

    public String ssid;
    public String bssid;
    public Integer channel;
    public Integer signalDbm;      // 扫描结果的信号;已配置接口为 null
    public String encryption;      // none / psk2 / sae / sae-mixed ...
    public String radioDevice;     // radio0 / radio1(扫描来源 / 配置所属)

    // 已配置接口专有
    public String sectionName;     // uci wifi-iface section(编辑用)
    public String mode;            // ap / sta
    public boolean disabled;
    public boolean configured;     // true=已配置接口,false=扫描结果

    /** 信号格 0-4(按 dBm),无信号返回 -1 */
    public int signalBars() {
        if (signalDbm == null) return -1;
        int dbm = signalDbm;
        if (dbm >= -55) return 4;
        if (dbm >= -66) return 3;
        if (dbm >= -77) return 2;
        if (dbm >= -88) return 1;
        return 0;
    }
}