package com.whykangkang.wrthub.model;

import java.util.Locale;

/** 软件包信息(opkg / apk 通用),对应 iOS Models/PackageInfo.swift */
public class PackageInfo {

    public final String name;
    public final String version;
    /** opkg 的 Description 字段,续行已拼成一段 */
    public final String description;
    /** Section 字段,如 luci / net / admin */
    public final String section;
    /** Installed-Size,字节;缺失为 0 */
    public final int installedSize;
    /** 预先算好的小写名,供搜索过滤使用(可用包上万条,避免每次输入重复 toLowerCase) */
    public final String searchKey;

    public PackageInfo(String name, String version) {
        this(name, version, "", "", 0);
    }

    public PackageInfo(String name, String version, String description,
                       String section, int installedSize) {
        this.name = name;
        this.version = version;
        this.description = description;
        this.section = section;
        this.installedSize = installedSize;
        this.searchKey = name.toLowerCase(Locale.ROOT);
    }

    public boolean hasVersion() {
        return version != null && !version.isEmpty();
    }

    /** 安装体积的可读形式;未知时返回 null */
    public String sizeText() {
        if (installedSize <= 0) return null;
        if (installedSize >= 1024 * 1024) {
            return String.format(Locale.US, "%.1f MB", installedSize / 1048576.0);
        }
        return String.format(Locale.US, "%.0f KB", installedSize / 1024.0);
    }
}
