package com.whykangkang.wrthub.model;

import android.content.Context;

import com.whykangkang.wrthub.R;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** 挂载点用量,对应 iOS Models/StorageInfo.swift */
public class StorageInfo {

    public String device = "";
    public String mount = "";
    public long size;
    public long avail;
    public long free;

    /** 挂载点的中文/英文显示名 */
    public String displayName(Context ctx) {
        switch (mount) {
            case "/":
                return ctx.getString(R.string.storage_root);
            case "/rom":
                return ctx.getString(R.string.storage_rom);
            case "/overlay":
                return ctx.getString(R.string.storage_overlay);
            case "/tmp":
                return ctx.getString(R.string.storage_tmp);
            case "/boot":
                return ctx.getString(R.string.storage_boot);
            case "/dev":
                return ctx.getString(R.string.storage_dev);
            default:
                return mount;
        }
    }

    public long usedBytes() {
        return size - avail;
    }

    public double usedPercentage() {
        if (size <= 0) return 0;
        return (double) usedBytes() / size * 100.0;
    }

    public String formattedSize() {
        return format(size);
    }

    public String formattedAvail() {
        return format(avail);
    }

    public String formattedUsed() {
        return format(usedBytes());
    }

    private static String format(long bytes) {
        double gb = bytes / 1024.0 / 1024 / 1024;
        if (gb >= 1.0) return String.format(Locale.US, "%.2f GB", gb);
        return String.format(Locale.US, "%.0f MB", bytes / 1024.0 / 1024);
    }

    /** green / orange / red */
    public String statusColor() {
        double p = usedPercentage();
        if (p < 70) return "green";
        if (p < 85) return "orange";
        return "red";
    }

    private static final List<String> EXCLUDED = Arrays.asList("/", "/dev", "/sys", "/proc");
    private static final List<String> IMPORTANT = Arrays.asList("/rom", "/overlay", "/boot", "/tmp");

    public boolean isImportant() {
        if (IMPORTANT.contains(mount)) return true;
        if (EXCLUDED.contains(mount)) return false;
        return size > 0;
    }
}
