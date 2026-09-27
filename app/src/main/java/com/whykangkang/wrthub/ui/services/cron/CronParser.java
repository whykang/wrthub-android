package com.whykangkang.wrthub.ui.services.cron;

/**
 * crontab 解析(纯逻辑,便于单测),对应 iOS CronTasksViewController.parseCronTasks。
 *
 * 只认「每天定点」形式:`分 时 * * * 命令`。含 * / step 等的行跳过 ——
 * 本页只管自己写进去的三类任务,不去动用户手写的复杂规则。
 */
public final class CronParser {

    /** 一条解析出来的定点任务 */
    public static final class Entry {
        public final int hour;
        public final int minute;
        public final String command;

        Entry(int hour, int minute, String command) {
            this.hour = hour;
            this.minute = minute;
            this.command = command;
        }
    }

    private CronParser() {
    }

    /** 逐行解析;不合法或非定点的行返回 null */
    public static Entry parseLine(String raw) {
        if (raw == null) return null;
        String line = raw.trim();
        if (line.isEmpty() || line.startsWith("#")) return null;
        String[] parts = line.split("\\s+");
        if (parts.length < 6) return null;
        int minute;
        int hour;
        try {
            minute = Integer.parseInt(parts[0]);
            hour = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return null;
        }
        StringBuilder command = new StringBuilder();
        for (int i = 5; i < parts.length; i++) {
            if (command.length() > 0) command.append(' ');
            command.append(parts[i]);
        }
        return new Entry(hour, minute, command.toString());
    }

    /** 该命令是否是「关闭 WiFi」 */
    public static boolean isWifiDown(String command) {
        return command != null && command.contains("wifi")
                && (command.contains("down") || command.contains("off"));
    }

    /** 该命令是否是「开启 WiFi」 */
    public static boolean isWifiUp(String command) {
        return command != null && command.contains("wifi")
                && (command.contains("up") || command.contains("on"));
    }

    /**
     * 每天 hour:minute 的任务,从 now 起算的下一次执行时刻(毫秒)。
     * 今天这个点已经过了就顺延到明天。抽成静态方法是为了能单测。
     */
    public static long nextExecution(int hour, int minute, long nowMillis) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(nowMillis);
        c.set(java.util.Calendar.HOUR_OF_DAY, hour);
        c.set(java.util.Calendar.MINUTE, minute);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= nowMillis) {
            c.add(java.util.Calendar.DAY_OF_MONTH, 1);
        }
        return c.getTimeInMillis();
    }
}
