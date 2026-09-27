package com.whykangkang.wrthub.model;

/**
 * 一台已连接的客户端设备,对应 iOS ConnectedDevice。
 * 由 DHCP 租约 + hostHints + WiFi assoclist 拼装而成。
 */
public class ConnectedDevice {

    public String hostname;
    public String ipAddress;
    public String macAddress;
    /**
     * DHCP 租约**剩余秒数**,0 表示没有租约(静态 IP / 只被扫描发现)。
     * LuCI 的 dhcp_leases.expires 给的就是剩余秒数而不是到期时间戳 ——
     * 当成 epoch 去减当前时间会得到一个巨大的负数,整列全变「已过期」。
     */
    public long leaseRemainingSec;
    public Integer signalDbm;      // WiFi 信号 dBm,null 表示有线
    public String ssid;            // 所在 WiFi
    public boolean isLocal;        // 本机
    public long rxBytes;
    public long txBytes;

    /** 主标题:有主机名用主机名,否则用 IP(iOS 逻辑:无主机名不重复显示 IP) */
    public String getPrimaryTitle() {
        if (hostname != null && !hostname.trim().isEmpty() && !hostname.equals("*")) {
            return hostname;
        }
        return ipAddress;
    }

    /** 副标题:有主机名时显示 IP;否则返回 null(避免重复) */
    public String getSecondaryTitle() {
        if (hostname != null && !hostname.trim().isEmpty() && !hostname.equals("*")) {
            return ipAddress;
        }
        return null;
    }

    /** 信号格数 0-4(按 dBm 分档),有线返回 -1 */
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