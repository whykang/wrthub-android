package com.whykangkang.wrthub.model;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 卸载保护判定(对应 iOS PackageProtection) */
public class PackageProtectionTest {

    @Test
    public void protectsCoreComponents() {
        assertTrue(PackageProtection.isProtected("kmod-mt7915e"));
        assertTrue(PackageProtection.isProtected("luci-base"));
        assertTrue(PackageProtection.isProtected("dropbear"));
        assertTrue(PackageProtection.isProtected("cgi-io"));
        assertTrue(PackageProtection.isProtected("firewall4"));
    }

    @Test
    public void allowsLuciAppsAndTranslations() {
        // luci-app-* / luci-i18n-* 虽以 luci 开头,但属于可卸载插件
        assertFalse(PackageProtection.isProtected("luci-app-eqos"));
        assertFalse(PackageProtection.isProtected("luci-i18n-eqos-zh-cn"));
    }

    @Test
    public void allowsOrdinaryPackages() {
        assertFalse(PackageProtection.isProtected("htop"));
        assertFalse(PackageProtection.isProtected("adblock-fast"));
    }
}
