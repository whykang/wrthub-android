package com.whykangkang.wrthub.model;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 卸载保护:判断一个软件包能否安全地从手机端卸载。
 * 对应 iOS Models/PackageProtection.swift。
 *
 * 判定原则不是「重要」,而是「卸掉之后还能不能救回来」:
 *  1. 卸掉会让路由器失去网络/无线/防火墙 → 用户可能连不上路由器
 *  2. 卸掉会让 WrtHub 自己失去控制通道(rpcd / uhttpd / luci-base / cgi-io)
 *  3. 内核模块:卸掉网卡驱动 = 直接失联
 */
public final class PackageProtection {

    /** 明确允许卸载的前缀:LuCI 应用与语言包,卸掉只影响一个功能页面 */
    private static final List<String> REMOVABLE_PREFIXES = Arrays.asList(
            "luci-app-", "luci-i18n-");

    /** 受保护的包名前缀 */
    private static final List<String> PROTECTED_PREFIXES = Arrays.asList(
            "kmod-",          // 内核模块:网卡/无线驱动卸掉就失联
            "luci",           // luci-base / luci-lib-* / luci-mod-*(luci-app- 已在上面放行)
            "rpcd",           // App 的 ubus 通道
            "uhttpd",         // Web 服务,ubus/HTTP 入口
            "libustream",     // uhttpd 的 TLS 后端
            "dnsmasq",        // DNS + DHCP
            "firewall",
            "odhcp",          // odhcpd / odhcp6c
            "ppp",            // 拨号
            "wpad", "hostapd", "iwinfo",   // 无线
            "libc", "libgcc", "musl", "libpthread", "librt",
            "libubox", "libubus", "libuci", "libblobmsg", "libnl",
            "libopenssl", "libmbedtls", "libwolfssl",
            "ca-bundle", "ca-certificates",
            "block-mount", "fstools", "procd", "ubox", "urngd");

    /** 受保护的精确包名 */
    private static final Set<String> PROTECTED_EXACT = new HashSet<>(Arrays.asList(
            "base-files", "busybox", "kernel", "libc", "opkg", "apk",
            "uci", "ubus", "ubusd", "netifd", "jsonfilter", "usign",
            "cgi-io",                       // 软件包管理本身走的就是它
            "dropbear",                     // SSH:出事后的唯一救援通道
            "fw3", "fw4", "nftables", "iptables", "ip6tables",
            "iw", "swconfig", "mtd", "logd",
            "urandom-seed", "getrandom", "zlib", "openssl-util",
            "luci"));

    private PackageProtection() {
    }

    /** 是否禁止从手机端卸载 */
    public static boolean isProtected(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        // 放行优先:luci-app-* / luci-i18n-* 虽然以 "luci" 开头,但属于可卸载的插件
        for (String p : REMOVABLE_PREFIXES) {
            if (n.startsWith(p)) return false;
        }
        if (PROTECTED_EXACT.contains(n)) return true;
        for (String p : PROTECTED_PREFIXES) {
            if (n.startsWith(p)) return true;
        }
        return false;
    }
}
