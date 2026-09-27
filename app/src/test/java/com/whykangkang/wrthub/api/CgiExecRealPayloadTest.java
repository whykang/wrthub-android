package com.whykangkang.wrthub.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * 用真机抓包(99999966.har)里的实际报文做回归。
 * 这台固件的 cgi-exec **返回明文**,不是 Base64 —— 之前按 Base64 硬解,
 * 卸载失败的原因被解成乱码,于是一路误报「卸载成功」。
 */
public class CgiExecRealPayloadTest {

    /** 抓包里 remove 的真实响应体,一字不改 */
    private static final String REMOVE_RESPONSE =
            "{ \"code\": 0, \"stdout\": \"Removing package luci-app-adblock-fast from root..."
            + "\\nStopping adblock-fast service... OK\\nRemoving rc.d symlink for adblock-fast..."
            + " OK\\nRemoving package adblock-fast from root...\", \"stderr\": "
            + "\"Command failed: ubus call service delete { \\\"name\\\": "
            + "\\\"adblock-fast\\\" } (Not found)\" }\n";

    private static final String LIST_RESPONSE =
            "Package: kmod-nft-tproxy\nVersion: 5.15.167-1\n"
            + "Status: install ok installed\n\n";

    @Test
    public void plainJsonResponseSurvivesDecoding() {
        assertEquals(REMOVE_RESPONSE, PackageApi.decodeBase64OrRaw(REMOVE_RESPONSE));
    }

    @Test
    public void plainControlListSurvivesDecoding() {
        assertEquals(LIST_RESPONSE, PackageApi.decodeBase64OrRaw(LIST_RESPONSE));
        assertEquals(1, PackageApi.parsePackageList(LIST_RESPONSE).size());
    }

    /** 这次是真的删掉了:stdout 里没有任何失败标记 */
    @Test
    public void successfulRemovalIsNotFlaggedAsFailure() {
        String stdout = "Removing package luci-app-adblock-fast from root...\n"
                + "Stopping adblock-fast service... OK\n"
                + "Removing package adblock-fast from root...";
        assertFalse(PackageApi.removeFailed(stdout));
    }

    /** 不带 --force-removal-of-dependent-packages 时 opkg 的真实拒绝输出 */
    @Test
    public void dependencyRefusalIsParsedIntoDependentList() {
        String output = "Collected errors:\n"
                + " * print_dependents_warning: Package adblock-fast is depended upon by packages:\n"
                + " * print_dependents_warning: \tluci-app-adblock-fast\n"
                + " * print_dependents_warning: These might cease to work if package"
                + " adblock-fast is removed.\n"
                + " * opkg_remove_pkg: Cannot remove package adblock-fast.\n";

        assertTrue(PackageApi.removeFailed(output));
        assertTrue(PackageApi.isDependencyBlocked(output));

        List<String> dependents = PackageApi.parseBlockingDependents(output);
        assertEquals(1, dependents.size());
        assertEquals("luci-app-adblock-fast", dependents.get(0));
    }
}
