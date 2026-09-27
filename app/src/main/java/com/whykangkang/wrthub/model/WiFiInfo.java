package com.whykangkang.wrthub.model;

import java.util.Locale;

/** 一个无线接口(wifi-iface),对应 iOS Models/RouterInfo.swift 的 WiFiInfo */
public class WiFiInfo {

    /** radio 名,如 radio0 */
    public String device = "";
    public int channel;
    public String ssid = "";
    /** 已翻好的加密显示名,如 "WPA2-PSK" —— 只用于展示 */
    public String encryption = "";
    /** uci 里的原始加密值,如 psk2 / sae / none —— 写回配置用这个,别用上面那个 */
    public String encryptionRaw = "";
    /** "2.4GHz" / "5GHz" */
    public String mode = "";
    public int clients;
    public boolean enabled;
    /** uci section 名,如 wifinet0 —— 编辑/删除的键 */
    public String id = "";
    /** 工作模式:ap / sta / mesh ... */
    public String workMode;
    /**
     * uci 里的 disabled 标志。
     * 注意和 {@link #enabled} 不是一回事:enabled 是**运行时**状态
     * (客户端模式没连上也算未启用),拿它当 disabled 写回去会把正常的网络关掉。
     */
    public boolean uciDisabled;
    public String password;

    /** 频段徽章文案 */
    public String frequency() {
        if (device.contains("5g") || channel > 14) return "5G";
        return "2.4G";
    }

    public String clientsText() {
        return String.valueOf(clients);
    }

    /** 工作模式的展示名(与 iOS workModeText 一致) */
    public String workModeText() {
        if (workMode == null) return "AP";
        switch (workMode.toLowerCase(Locale.ROOT)) {
            case "ap":
            case "master":
                return "AP";
            case "sta":
            case "client":
                return "Client";
            case "mesh":
            case "mesh point":
                return "Mesh";
            case "adhoc":
                return "Ad-Hoc";
            case "monitor":
                return "Monitor";
            case "wds":
                return "WDS";
            default:
                return workMode.toUpperCase(Locale.ROOT);
        }
    }
}
