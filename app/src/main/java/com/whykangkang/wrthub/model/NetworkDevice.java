package com.whykangkang.wrthub.model;

import android.content.Context;

import com.whykangkang.wrthub.R;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 一个网络设备(物理口 / 网桥 / 隧道),对应 iOS Models/NetworkDevice.swift。
 * 首页「网络端口」卡片用它渲染名称、状态("1Gbps · 全双工")与收发流量。
 */
public class NetworkDevice {

    public String name = "";
    public boolean up;
    public boolean wireless;
    public Integer mtu;
    public String mac;
    /** 所属网桥 */
    public String master;
    public final List<IpAddress> ipaddrs = new ArrayList<>();
    public final Stats stats = new Stats();
    public final Link link = new Link();

    public static class IpAddress {
        public String address = "";
        public String netmask;
        public String broadcast;
    }

    public static class Stats {
        public long rxBytes;
        public long txBytes;
        public long rxPackets;
        public long txPackets;
    }

    public static class Link {
        public Integer speed;
        public String duplex;
        public boolean carrier;
    }

    // =====================================================================
    // 展示
    // =====================================================================

    /** 端口角色判定,逐行对照 iOS displayName */
    public String displayName(Context ctx) {
        if (!ipaddrs.isEmpty()) {
            String ip = ipaddrs.get(0).address;
            if (ip.startsWith("192.168.1.")) {
                if (ip.equals("192.168.1.1")) {
                    return ctx.getString(name.equals("br-lan")
                            ? R.string.net_lan_bridge : R.string.net_lan_port_plain);
                }
            } else if (!ip.startsWith("127.")) {
                if (name.startsWith("eth") && !name.startsWith("br-")) {
                    return ctx.getString(R.string.net_wan_port, name);
                }
            }
        }
        if ("br-lan".equals(master)) {
            if (name.startsWith("eth")) {
                return ctx.getString(R.string.net_lan_port, name);
            }
            return name;
        }
        if (name.equals("br-lan")) return ctx.getString(R.string.net_lan_bridge);
        if (name.equals("lo")) return ctx.getString(R.string.net_loopback);
        if (name.startsWith("singtun")) return ctx.getString(R.string.net_vpn_tunnel, name);
        if (name.startsWith("phy")) {
            return wireless ? ctx.getString(R.string.net_wifi_iface, name) : name;
        }
        if (name.startsWith("eth")) return ctx.getString(R.string.net_port, name);
        return name;
    }

    private static String speedText(int mbps) {
        if (mbps < 1000) return mbps + "Mbps";
        double gbps = mbps / 1000.0;
        return gbps == Math.rint(gbps)
                ? String.format(Locale.US, "%.0fGbps", gbps)
                : String.format(Locale.US, "%.1fGbps", gbps);
    }

    private String duplexText(Context ctx) {
        if (link.duplex == null) return null;
        switch (link.duplex.toLowerCase(Locale.ROOT)) {
            case "full":
                return ctx.getString(R.string.net_full_duplex);
            case "half":
                return ctx.getString(R.string.net_half_duplex);
            default:
                return null;
        }
    }

    /** 状态文案,与 iOS statusText(bridgeMembers:) 一致 */
    public String statusText(Context ctx, Integer bridgeMembers) {
        if (isBridge()) {
            if (bridgeMembers != null && bridgeMembers > 0) {
                return ctx.getString(R.string.net_bridge_ports, bridgeMembers);
            }
            return ctx.getString(link.carrier ? R.string.net_connected : R.string.net_down);
        }
        if (isVpn()) {
            if (link.carrier || !ipaddrs.isEmpty()) return ctx.getString(R.string.net_connected);
            return ctx.getString(up ? R.string.net_waiting : R.string.net_down);
        }
        if (link.carrier) {
            if (link.speed != null && link.speed > 0) {
                String duplex = duplexText(ctx);
                if (duplex != null) return speedText(link.speed) + " · " + duplex;
                return speedText(link.speed);
            }
            return ctx.getString(ipaddrs.isEmpty()
                    ? R.string.net_plugged : R.string.net_connected);
        }
        if (!ipaddrs.isEmpty()) return ctx.getString(R.string.net_connected);
        if (up) return ctx.getString(R.string.net_waiting);
        return ctx.getString(R.string.net_down);
    }

    /** green / orange / gray */
    public String statusColor() {
        if (link.carrier) return "green";
        if (!ipaddrs.isEmpty()) return "green";
        if (up) return "orange";
        return "gray";
    }

    public String rxBytesFormatted() {
        return formatBytes(stats.rxBytes);
    }

    public String txBytesFormatted() {
        return formatBytes(stats.txBytes);
    }

    public String totalBytesFormatted() {
        return formatBytes(stats.rxBytes + stats.txBytes);
    }

    /** 与 iOS formatBytes 一致:GB/MB 保留两位,KB 两位,B 原样 */
    public static String formatBytes(long bytes) {
        double kb = bytes / 1024.0;
        double mb = kb / 1024.0;
        double gb = mb / 1024.0;
        if (gb >= 1.0) return String.format(Locale.US, "%.2f GB", gb);
        if (mb >= 1.0) return String.format(Locale.US, "%.2f MB", mb);
        if (kb >= 1.0) return String.format(Locale.US, "%.2f KB", kb);
        return bytes + " B";
    }

    // =====================================================================
    // 分类
    // =====================================================================

    /** 内核占位设备:名字里带 tun 会被 isVpn 误判,首页需先排掉 */
    private static final Set<String> KERNEL_PLACEHOLDERS = new HashSet<>(Arrays.asList(
            "lo", "sit0", "ip6tnl0", "tunl0", "gre0", "gretap0", "ip6gre0", "erspan0", "teql0"));

    public boolean isKernelPlaceholder() {
        return KERNEL_PLACEHOLDERS.contains(name);
    }

    public boolean isPhysicalPort() {
        return name.startsWith("eth");
    }

    public boolean isBridge() {
        return name.startsWith("br-");
    }

    public boolean isVpn() {
        return name.contains("tun") || name.contains("tap");
    }
}
