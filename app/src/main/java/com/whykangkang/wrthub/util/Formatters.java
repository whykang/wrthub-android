package com.whykangkang.wrthub.util;

import java.util.Locale;

/**
 * 字节/速率/时长格式化,对应 iOS 的 ByteCountFormatter 与 Extensions.swift。
 */
public final class Formatters {

    private Formatters() {
    }

    /** 字节数:B/KB/MB/GB/TB(1024 进制) */
    public static String bytes(long bytes) {
        if (bytes < 0) bytes = 0;
        double b = bytes;
        String[] units = {"B", "KB", "MB", "GB", "TB", "PB"};
        int i = 0;
        while (b >= 1024 && i < units.length - 1) {
            b /= 1024;
            i++;
        }
        if (i == 0) {
            return (long) b + " " + units[i];
        }
        return String.format(Locale.US, "%.1f %s", b, units[i]);
    }

    /** 速率:字节/秒 → "%.1f MB/s" 等(自适应单位) */
    public static String rate(double bytesPerSec) {
        if (bytesPerSec < 0) bytesPerSec = 0;
        double b = bytesPerSec;
        String[] units = {"B/s", "KB/s", "MB/s", "GB/s"};
        int i = 0;
        while (b >= 1024 && i < units.length - 1) {
            b /= 1024;
            i++;
        }
        return String.format(Locale.US, "%.1f %s", b, units[i]);
    }

    /**
     * 比特率,与 vnStat 自己的输出对齐(它用 bit/s,不是 byte/s)。
     * 这样在 App 里看到的数字能和网页端 vnstat 的 "avg. rate" 直接对上。
     */
    public static String bitrate(double bitsPerSec) {
        if (bitsPerSec <= 0) return "0 bit/s";
        if (bitsPerSec >= 1e9) {
            return String.format(Locale.US, "%.2f Gbit/s", bitsPerSec / 1e9);
        }
        if (bitsPerSec >= 1e6) {
            return String.format(Locale.US, "%.2f Mbit/s", bitsPerSec / 1e6);
        }
        if (bitsPerSec >= 1e3) {
            return String.format(Locale.US, "%.2f kbit/s", bitsPerSec / 1e3);
        }
        return String.format(Locale.US, "%.0f bit/s", bitsPerSec);
    }

    /** 运行时长:秒 → "N天 HH:MM:SS" / 英文 "Nd HH:MM:SS" */
    public static String uptime(long seconds, boolean chinese) {
        if (seconds < 0) seconds = 0;
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        StringBuilder sb = new StringBuilder();
        if (days > 0) {
            sb.append(days).append(chinese ? "天 " : "d ");
        }
        sb.append(String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, secs));
        return sb.toString();
    }

    /** 百分比:0-100 的整数 → "42%" */
    public static String percent(double value) {
        return Math.round(value) + "%";
    }

    /** 链路速率标签:speed 字段如 "1000F" → "1Gbps · 全双工" */
    public static String linkSpeed(String speed, boolean chinese) {
        if (speed == null || speed.isEmpty()) {
            return "";
        }
        boolean fullDuplex = speed.endsWith("F");
        boolean halfDuplex = speed.endsWith("H");
        String digits = speed.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return speed;
        }
        long mbps = Long.parseLong(digits);
        String rate;
        if (mbps >= 1000) {
            rate = (mbps / 1000) + "Gbps";
        } else {
            rate = mbps + "Mbps";
        }
        if (fullDuplex) {
            return rate + (chinese ? " · 全双工" : " · Full");
        } else if (halfDuplex) {
            return rate + (chinese ? " · 半双工" : " · Half");
        }
        return rate;
    }
}