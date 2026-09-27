package com.whykangkang.wrthub.model;

import java.util.Locale;

/** 路由器系统信息,对应 iOS Models/RouterInfo.swift 的 RouterInfo */
public class RouterInfo {

    public String hostname = "OpenWrt";
    public long uptime;
    /** 1/5/15 分钟负载 */
    public final double[] loadavg = new double[3];
    public MemoryInfo memory = new MemoryInfo();
    /** 固件不支持 luci.getCPUInfo 时为 null */
    public CpuInfo cpu;
    public String model;
    /** 有 cpuinfo 时为完整 CPU 描述,否则为 system 字段(架构) */
    public String system;
    public String release;
    public String kernel;

    public static class MemoryInfo {
        public long total;
        public long free;
        public long buffered;
        public long cached;
        public long shared;
        public Long available;

        public double usedPercentage() {
            if (total <= 0) return 0;
            return (double) (total - free) / total * 100.0;
        }

        public double totalGB() {
            return total / 1024.0 / 1024 / 1024;
        }

        public double usedGB() {
            return (total - free) / 1024.0 / 1024 / 1024;
        }

        public double totalMB() {
            return total / 1024.0 / 1024;
        }

        public double usedMB() {
            return (total - free) / 1024.0 / 1024;
        }
    }

    public static class CpuInfo {
        public double usage;
        public int count = 4;
        public Double temperature;
        public Integer frequency;
    }

    /** "3d 4h 5m 6s" —— 与 iOS uptimeDetailString 一致 */
    public String uptimeDetail() {
        long days = uptime / 86400;
        long hours = (uptime % 86400) / 3600;
        long minutes = (uptime % 3600) / 60;
        long seconds = uptime % 60;
        if (days > 0) {
            return String.format(Locale.US, "%dd %dh %dm %ds", days, hours, minutes, seconds);
        }
        if (hours > 0) {
            return String.format(Locale.US, "%dh %dm %ds", hours, minutes, seconds);
        }
        return String.format(Locale.US, "%dm %ds", minutes, seconds);
    }

    public String loadavgText() {
        return String.format(Locale.US, "%.2f, %.2f, %.2f", loadavg[0], loadavg[1], loadavg[2]);
    }
}
