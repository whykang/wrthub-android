package com.whykangkang.wrthub.manager;

import android.content.Context;
import android.content.Intent;

import androidx.annotation.ColorRes;
import androidx.annotation.DrawableRes;
import androidx.annotation.StringRes;

import com.whykangkang.wrthub.R;
import com.whykangkang.wrthub.api.ApiCallback;
import com.whykangkang.wrthub.api.PluginChecker;
import com.whykangkang.wrthub.ui.services.cron.CronTasksActivity;
import com.whykangkang.wrthub.ui.services.diagnostics.NetworkDiagnosticsActivity;
import com.whykangkang.wrthub.ui.services.firewall.FirewallManagementActivity;
import com.whykangkang.wrthub.ui.services.filebrowser.FileBrowserActivity;
import com.whykangkang.wrthub.ui.services.speedtest.SpeedTestActivity;
import com.whykangkang.wrthub.ui.services.vnstat.VnstatActivity;
import com.whykangkang.wrthub.ui.services.ftp.FtpServiceActivity;
import com.whykangkang.wrthub.ui.services.interfaces.NetworkInterfacesActivity;
import com.whykangkang.wrthub.ui.services.led.LedConfigActivity;
import com.whykangkang.wrthub.ui.services.log.SystemLogActivity;
import com.whykangkang.wrthub.ui.services.packages.PackageManagerActivity;
import com.whykangkang.wrthub.ui.services.proxy.ProxyActivity;
import com.whykangkang.wrthub.ui.services.ratelimit.DeviceRateLimitActivity;
import com.whykangkang.wrthub.ui.services.samba.SambaServiceActivity;
import com.whykangkang.wrthub.ui.services.ssh.SshConfigActivity;
import com.whykangkang.wrthub.ui.services.terminal.TerminalActivity;
import com.whykangkang.wrthub.ui.services.wol.WolActivity;
import com.whykangkang.wrthub.ui.web.WebAccessActivity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 服务功能目录:首页「常用功能」与服务页共用的单一数据源,
 * 对应 iOS Services/ServiceCatalog.swift。
 */
public final class ServiceCatalog {

    /** 服务功能的稳定标识(用于收藏持久化,请勿随意改名) */
    public enum ServiceId {
        WEB_ACCESS("webAccess"),
        FIREWALL("firewall"),
        NETWORK_INTERFACES("networkInterfaces"),
        NETWORK_DIAGNOSTICS("networkDiagnostics"),
        DEVICE_RATE_LIMIT("deviceRateLimit"),
        VNSTAT("vnstat"),
        SPEEDTEST("speedtest"),
        PROXY("proxy"),
        SAMBA("samba"),
        FTP("ftp"),
        FILEBROWSER("filebrowser"),
        SSH("ssh"),
        LOGS("logs"),
        PACKAGES("packages"),
        CRON("cron"),
        LED("led"),
        TERMINAL("terminal"),
        WOL("wol");

        public final String key;

        ServiceId(String key) {
            this.key = key;
        }

        public static ServiceId fromKey(String key) {
            for (ServiceId id : values()) {
                if (id.key.equals(key)) return id;
            }
            return null;
        }
    }

    /** 单个服务功能的描述:图标、标题、打开方式、可用性检查 */
    public static final class Descriptor {
        public final ServiceId id;
        @StringRes
        public final int titleRes;
        @StringRes
        public final int subtitleRes;
        @DrawableRes
        public final int iconRes;
        @ColorRes
        public final int iconColorRes;
        public final Class<?> target;
        /** null 表示内置功能,始终可用;否则为插件可用性检查 */
        public final PluginChecker.Plugin plugin;
        /** 插件未安装时,「去安装」在软件包管理里预填的搜索词 */
        public final String installKeyword;
        /** 插件未安装提示的说明文案 */
        @StringRes
        public final int notInstalledMsgRes;

        Descriptor(ServiceId id, int titleRes, int subtitleRes, int iconRes, int iconColorRes,
                   Class<?> target, PluginChecker.Plugin plugin, String installKeyword,
                   int notInstalledMsgRes) {
            this.id = id;
            this.titleRes = titleRes;
            this.subtitleRes = subtitleRes;
            this.iconRes = iconRes;
            this.iconColorRes = iconColorRes;
            this.target = target;
            this.plugin = plugin;
            this.installKeyword = installKeyword;
            this.notInstalledMsgRes = notInstalledMsgRes;
        }

        /** 检查该功能当前是否可用(内置功能始终可用) */
        public void checkAvailability(ApiCallback<Boolean> cb) {
            if (plugin == null) {
                cb.onSuccess(true);
                return;
            }
            PluginChecker.check(plugin, cb);
        }

        /** 打开该功能 */
        public void open(Context context) {
            context.startActivity(new Intent(context, target));
        }
    }

    private static final List<Descriptor> ALL = Arrays.asList(
            // 网页访问:固定功能,用应用内浏览器打开本机 OpenWrt 后台
            new Descriptor(ServiceId.WEB_ACCESS, R.string.svc_web_access,
                    R.string.svc_web_access_sub, R.drawable.svc_web, R.color.ios_blue,
                    WebAccessActivity.class, null, null, 0),
            new Descriptor(ServiceId.FIREWALL, R.string.svc_firewall,
                    R.string.svc_firewall_sub, R.drawable.svc_firewall, R.color.ios_blue,
                    FirewallManagementActivity.class, null, null, 0),
            new Descriptor(ServiceId.NETWORK_INTERFACES, R.string.svc_interfaces,
                    R.string.svc_interfaces_sub, R.drawable.svc_interfaces, R.color.ios_blue,
                    NetworkInterfacesActivity.class, null, null, 0),
            new Descriptor(ServiceId.NETWORK_DIAGNOSTICS, R.string.svc_diagnostics,
                    R.string.svc_diagnostics_sub, R.drawable.svc_diagnostics, R.color.ios_blue,
                    NetworkDiagnosticsActivity.class, null, null, 0),
            new Descriptor(ServiceId.PROXY, R.string.svc_proxy,
                    R.string.svc_proxy_sub, R.drawable.svc_proxy, R.color.ios_purple,
                    ProxyActivity.class, PluginChecker.Plugin.OPENCLASH, "openclash",
                    R.string.svc_need_openclash),
            new Descriptor(ServiceId.DEVICE_RATE_LIMIT, R.string.svc_rate_limit,
                    R.string.svc_rate_limit_sub, R.drawable.svc_ratelimit, R.color.ios_orange,
                    DeviceRateLimitActivity.class, PluginChecker.Plugin.EQOS, "eqos",
                    R.string.svc_need_eqos),
            new Descriptor(ServiceId.VNSTAT, R.string.svc_vnstat,
                    R.string.svc_vnstat_sub, R.drawable.svc_vnstat, R.color.ios_teal,
                    VnstatActivity.class, PluginChecker.Plugin.VNSTAT, "luci-app-vnstat2",
                    R.string.svc_need_vnstat),
            // 内置功能:只靠 App 自己收发数据,不依赖路由器上的任何插件
            new Descriptor(ServiceId.SPEEDTEST, R.string.svc_speedtest,
                    R.string.svc_speedtest_sub, R.drawable.svc_speedtest, R.color.ios_orange,
                    SpeedTestActivity.class, null, null, 0),
            new Descriptor(ServiceId.SAMBA, R.string.svc_nas,
                    R.string.svc_nas_sub, R.drawable.svc_nas, R.color.ios_indigo,
                    SambaServiceActivity.class, PluginChecker.Plugin.SAMBA, "samba4",
                    R.string.svc_need_samba),
            new Descriptor(ServiceId.FILEBROWSER, R.string.svc_filebrowser,
                    R.string.svc_filebrowser_sub, R.drawable.svc_filebrowser, R.color.ios_indigo,
                    FileBrowserActivity.class, PluginChecker.Plugin.FILEBROWSER,
                    "luci-app-filebrowser", R.string.svc_need_filebrowser),
            new Descriptor(ServiceId.FTP, R.string.svc_ftp,
                    R.string.svc_ftp_sub, R.drawable.svc_ftp, R.color.ios_purple,
                    FtpServiceActivity.class, PluginChecker.Plugin.FTP, "vsftpd",
                    R.string.svc_need_vsftpd),
            new Descriptor(ServiceId.TERMINAL, R.string.svc_ai_terminal,
                    R.string.svc_ai_terminal_sub, R.drawable.svc_ai_terminal, R.color.ios_purple,
                    TerminalActivity.class, PluginChecker.Plugin.TTYD, "luci-app-ttyd",
                    R.string.svc_need_ttyd),
            // etherwake 或 wol 有一个就行;装 luci-app-wol 会一并装上
            new Descriptor(ServiceId.WOL, R.string.svc_wol,
                    R.string.svc_wol_sub, R.drawable.svc_wol, R.color.ios_green,
                    WolActivity.class, PluginChecker.Plugin.WOL, "wol",
                    R.string.svc_need_wol),
            new Descriptor(ServiceId.SSH, R.string.svc_ssh,
                    R.string.svc_ssh_sub, R.drawable.svc_ssh, R.color.ios_teal,
                    SshConfigActivity.class, null, null, 0),
            new Descriptor(ServiceId.LOGS, R.string.svc_logs,
                    R.string.svc_logs_sub, R.drawable.svc_logs, R.color.ios_teal,
                    SystemLogActivity.class, null, null, 0),
            new Descriptor(ServiceId.PACKAGES, R.string.svc_packages,
                    R.string.svc_packages_sub, R.drawable.svc_packages, R.color.ios_teal,
                    PackageManagerActivity.class, null, null, 0),
            new Descriptor(ServiceId.CRON, R.string.svc_cron,
                    R.string.svc_cron_sub, R.drawable.svc_cron, R.color.ios_teal,
                    CronTasksActivity.class, null, null, 0),
            new Descriptor(ServiceId.LED, R.string.svc_led,
                    R.string.svc_led_sub, R.drawable.svc_led, R.color.ios_yellow,
                    LedConfigActivity.class, null, null, 0)
    );

    private ServiceCatalog() {
    }

    public static List<Descriptor> all() {
        return ALL;
    }

    public static Descriptor descriptor(ServiceId id) {
        for (Descriptor d : ALL) {
            if (d.id == id) return d;
        }
        return null;
    }

    /** 服务页分组:网络服务 / 文件服务 / 系统服务(与 iOS ServicesViewController 一致) */
    public static List<Descriptor> group(ServiceId... ids) {
        List<Descriptor> out = new ArrayList<>();
        for (ServiceId id : ids) {
            Descriptor d = descriptor(id);
            if (d != null) out.add(d);
        }
        return out;
    }
}
