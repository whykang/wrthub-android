package com.whykangkang.wrthub.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.whykangkang.wrthub.model.PackageInfo;

import org.junit.Test;

import java.util.List;

/** 软件包列表解析与卸载依赖判定(对应 iOS parsePackageList / parseBlockingDependents) */
public class PackageApiTest {

    @Test
    public void parsesDebianControlFormat() {
        String text = "Package: kmod-nft-tproxy\n"
                + "Version: 5.15.167-1\n"
                + "Section: kernel\n"
                + "Installed-Size: 20480\n"
                + "Description: Netfilter nf_tproxy support\n"
                + " Continued description line\n"
                + "\n"
                + "Package: busybox\n"
                + "Version: 1.36.1-1\n";
        List<PackageInfo> list = PackageApi.parsePackageList(text);
        assertEquals(2, list.size());
        // 按名称排序:busybox 在前
        assertEquals("busybox", list.get(0).name);
        PackageInfo kmod = list.get(1);
        assertEquals("kmod-nft-tproxy", kmod.name);
        assertEquals("5.15.167-1", kmod.version);
        assertEquals("kernel", kmod.section);
        assertEquals(20480, kmod.installedSize);
        assertEquals("Netfilter nf_tproxy support Continued description line", kmod.description);
    }

    @Test
    public void parsesSimpleFormat() {
        List<PackageInfo> list = PackageApi.parsePackageList("busybox - 1.36.1-1\nuci - 2023");
        assertEquals(2, list.size());
        assertEquals("busybox", list.get(0).name);
        assertEquals("1.36.1-1", list.get(0).version);
    }

    @Test
    public void parsesBlockingDependents() {
        String out = "Collected errors:\n"
                + " * print_dependents_warning: Package luci-app-eqos is depended upon by packages:\n"
                + " * print_dependents_warning:     luci-i18n-eqos-zh-cn\n"
                + " * print_dependents_warning: These might cease to work if removed.\n";
        List<String> names = PackageApi.parseBlockingDependents(out);
        assertEquals(1, names.size());
        assertEquals("luci-i18n-eqos-zh-cn", names.get(0));
    }

    @Test
    public void detectsDependencyBlocked() {
        assertTrue(PackageApi.isDependencyBlocked("is depended upon by packages"));
        assertFalse(PackageApi.isDependencyBlocked("some other error"));
    }

    @Test
    public void percentEncodesEveryNonAlphanumeric() {
        assertEquals("%2Fusr%2Flibexec%2Fopkg%2Dcall%20list%2Dinstalled",
                PackageApi.percentEncodeAll("/usr/libexec/opkg-call list-installed"));
    }
}
