package com.whykangkang.wrthub.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.whykangkang.wrthub.model.PackageInfo;

import org.junit.Test;

import java.util.List;

/**
 * apk 模式(OpenWrt 24.10+)的兼容。
 *
 * package-manager-call 在装了 apk 的系统上走的是
 * {@code apk query --fields all --format json},输出是 **JSON 数组**,
 * 不是 opkg 的 Debian control 段落 —— 原来的解析器认不出来,所以整个
 * 软件包管理界面在 apk 模式下是空的。
 */
public class PackageApkModeTest {

    /** apk query --fields all --format json 的真实形状 */
    private static final String APK_JSON = "["
            + "{\"name\":\"busybox\",\"version\":\"1.37.0-r2\","
            + "\"description\":\"Core utilities for embedded Linux\","
            + "\"installed-size\":225280,\"file-size\":198000,\"license\":\"GPL-2.0\"},"
            + "{\"name\":\"luci-app-opkg\",\"version\":\"24.10.0-r1\","
            + "\"description\":\"LuCI package management\",\"file-size\":12000},"
            + "{\"name\":\"Adblock\",\"version\":\"4.2.3-r3\",\"description\":\"\"}"
            + "]";

    @Test
    public void apkJsonListIsParsed() {
        List<PackageInfo> list = PackageApi.parsePackageList(APK_JSON);
        assertEquals(3, list.size());
        // 按名称小写排序
        assertEquals("adblock", list.get(0).searchKey);
        assertEquals("busybox", list.get(1).name);
        assertEquals("1.37.0-r2", list.get(1).version);
        assertEquals("Core utilities for embedded Linux", list.get(1).description);
    }

    @Test
    public void installedSizeWinsOverFileSize() {
        List<PackageInfo> list = PackageApi.parsePackageList(APK_JSON);
        PackageInfo busybox = list.stream()
                .filter(p -> p.name.equals("busybox")).findFirst().orElseThrow(AssertionError::new);
        assertEquals("装机占用比下载体积更有意义", 225280, busybox.installedSize);
        PackageInfo luci = list.stream()
                .filter(p -> p.name.equals("luci-app-opkg")).findFirst()
                .orElseThrow(AssertionError::new);
        assertEquals("没有 installed-size 时退回 file-size", 12000, luci.installedSize);
    }

    @Test
    public void opkgControlFormatStillWorks() {
        String opkg = "Package: kmod-nft-tproxy\nVersion: 5.15.167-1\n"
                + "Description: Netfilter nf_tables tproxy support\nSection: kernel\n"
                + "Installed-Size: 8192\n\n"
                + "Package: busybox\nVersion: 1.36.1-1\n\n";
        List<PackageInfo> list = PackageApi.parsePackageList(opkg);
        assertEquals(2, list.size());
        assertEquals("busybox", list.get(0).name);
        assertEquals("kmod-nft-tproxy", list.get(1).name);
        assertEquals(8192, list.get(1).installedSize);
    }

    @Test
    public void malformedApkJsonDoesNotCrash() {
        assertTrue(PackageApi.parsePackageList("[").isEmpty());
        assertTrue(PackageApi.parsePackageList("[]").isEmpty());
        assertTrue(PackageApi.parsePackageList("[{\"noname\":1}]").isEmpty());
    }

    /**
     * 两个后端的 update ACL 写法是**相反**的:
     *   opkg-call            "update *"  → 必须带参数
     *   package-manager-call "update"    → 精确匹配,带参数反而 403
     */
    @Test
    public void updateArgumentDiffersPerBackend() {
        assertFalse("opkg-call 的 ACL 带通配符,不带参数会 403",
                PackageApi.Backend.OPKG_CALL.updateSuffix.isEmpty());
        assertEquals("package-manager-call 的 ACL 是精确的 \"update\"",
                "", PackageApi.Backend.PACKAGE_MANAGER_CALL.updateSuffix);
    }

    /** 真机实测的 apk 卸载拦截输出(截图原文) */
    private static final String APK_NOT_REMOVED =
            "World updated, but the following packages are not removed due to:\n"
            + "  luci-app-filebrowser: luci-i18n-filebrowser-zh-cn\n"
            + "\n"
            + "OK: 60.3 MiB in 224 packages\n";

    @Test
    public void apkRemoveBlockedByDependentIsRecognised() {
        assertTrue("没认出来就只会弹一段生硬的英文,走不到级联卸载",
                PackageApi.isDependencyBlocked(APK_NOT_REMOVED));
        assertTrue(PackageApi.removeFailed(APK_NOT_REMOVED));
        assertEquals(List.of("luci-i18n-filebrowser-zh-cn"),
                PackageApi.parseBlockingDependents(APK_NOT_REMOVED));
    }

    @Test
    public void apkNotRemovedHandlesSeveralDependents() {
        String out = "World updated, but the following packages are not removed due to:\n"
                + "  luci-app-eqos: luci-i18n-eqos-zh-cn luci-i18n-eqos-de\n"
                + "\n"
                + "OK: 60.3 MiB in 224 packages\n";
        assertEquals(List.of("luci-i18n-eqos-zh-cn", "luci-i18n-eqos-de"),
                PackageApi.parseBlockingDependents(out));
    }

    @Test
    public void apkStatsLineIsNotMistakenForAPackage() {
        // "OK: 60.3 MiB in 224 packages" 里冒号右边不是包名
        assertFalse(PackageApi.parseBlockingDependents(APK_NOT_REMOVED).contains("60.3"));
        assertEquals(1, PackageApi.parseBlockingDependents(APK_NOT_REMOVED).size());
    }

    @Test
    public void apkDependencyRefusalIsRecognised() {
        String out = "ERROR: unable to select packages:\n"
                + "  luci-app-eqos-1.0-r1:\n"
                + "    required by: luci-i18n-eqos-zh-cn-1.0-r1[luci-app-eqos]\n";
        assertTrue(PackageApi.isDependencyBlocked(out));
        assertTrue(PackageApi.removeFailed(out));
        assertEquals(List.of("luci-i18n-eqos-zh-cn"), PackageApi.parseBlockingDependents(out));
    }

    @Test
    public void opkgDependencyRefusalStillParses() {
        String out = "* print_dependents_warning: Package luci-app-eqos is depended upon by packages:\n"
                + "* print_dependents_warning:     luci-i18n-eqos-zh-cn\n";
        assertEquals(List.of("luci-i18n-eqos-zh-cn"), PackageApi.parseBlockingDependents(out));
    }

    /**
     * apk 对数据库上独占锁,而上游的 package-manager-call 只给
     * install/update/remove 包了 flock,**不包 list-installed / list-available**。
     * 界面同时拉「已安装」和「可安装」就会撞锁 —— 真机实测报文如下。
     */
    @Test
    public void databaseLockIsRecognised() {
        String apk = "ERROR: Unable to lock database: Resource temporarily unavailable\n"
                + "ERROR: Failed to open apk database: Resource temporarily unavailable";
        assertTrue(PackageApi.isDatabaseLocked(apk));
        assertTrue(PackageApi.isDatabaseLocked(
                "opkg: Could not lock /var/lock/opkg.lock: Resource temporarily unavailable"));
    }

    @Test
    public void normalOutputIsNotMistakenForALock() {
        assertFalse(PackageApi.isDatabaseLocked(null));
        assertFalse(PackageApi.isDatabaseLocked(""));
        assertFalse(PackageApi.isDatabaseLocked("Installing luci-app-vnstat2..."));
        assertFalse(PackageApi.isDatabaseLocked(APK_NOT_REMOVED));
    }

    @Test
    public void apkVersionSuffixIsStripped() {
        assertEquals("luci-app-eqos", PackageApi.stripApkVersion("luci-app-eqos-1.0-r1"));
        assertEquals("busybox", PackageApi.stripApkVersion("busybox-1.37.0-r2"));
        assertEquals("no-version-here", PackageApi.stripApkVersion("no-version-here"));
    }
}
