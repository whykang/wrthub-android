package com.whykangkang.wrthub.api;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * opkg 卸载失败时不一定返回非零退出码,必须按输出再判一次,
 * 否则会报「卸载成功」而包还在。
 */
public class PackageRemoveOutputTest {

    @Test
    public void plainSuccessOutputIsNotAFailure() {
        assertFalse(PackageApi.removeFailed(
                "Removing package luci-app-example from root...\n"));
        assertFalse(PackageApi.removeFailed(""));
        assertFalse(PackageApi.removeFailed(null));
    }

    @Test
    public void dependencyRefusalIsAFailure() {
        String output = "Collected errors:\n"
                + " * print_dependents_warning: Package luci-lib-nixio is depended upon by"
                + " packages:\n"
                + " * opkg_remove_pkg: Cannot remove package luci-lib-nixio.\n";
        assertTrue(PackageApi.removeFailed(output));
        assertTrue("这种输出同时也该被识别成「被依赖挡下」",
                PackageApi.isDependencyBlocked(output));
    }

    @Test
    public void nothingRemovedIsAFailure() {
        assertTrue(PackageApi.removeFailed("No packages removed.\n"));
    }

    @Test
    public void anyCollectedErrorsBlockIsAFailure() {
        assertTrue(PackageApi.removeFailed(
                "Collected errors:\n * opkg_install_cmd: Cannot install package foo.\n"));
    }
}
