package com.whykangkang.wrthub.ui.services.cron;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** crontab 行解析(对应 iOS parseCronTasks 的逐行处理) */
public class CronParseTest {

    @Test
    public void parsesDailyEntry() {
        CronParser.Entry entry = CronParser.parseLine("0 4 * * * /etc/init.d/network restart");
        assertNotNull(entry);
        assertEquals(4, entry.hour);
        assertEquals(0, entry.minute);
        assertEquals("/etc/init.d/network restart", entry.command);
    }

    @Test
    public void skipsCommentsAndBlankLines() {
        assertNull(CronParser.parseLine("# Cron tasks"));
        assertNull(CronParser.parseLine("   "));
        assertNull(CronParser.parseLine(null));
    }

    @Test
    public void skipsNonFixedTimeEntries() {
        // */5 这类步长写法不是「每天定点」,本页不接管
        assertNull(CronParser.parseLine("*/5 * * * * /usr/bin/something"));
    }

    @Test
    public void skipsTooShortLines() {
        assertNull(CronParser.parseLine("0 4 * * *"));
    }

    @Test
    public void detectsWifiScheduleCommands() {
        assertTrue(CronParser.isWifiDown("wifi down"));
        assertTrue(CronParser.isWifiUp("wifi up"));
        assertFalse(CronParser.isWifiDown("wifi reload"));
        assertFalse(CronParser.isWifiDown(null));
    }

    @Test
    public void handlesTabsAndMultiWordCommands() {
        CronParser.Entry entry = CronParser.parseLine("30\t23\t*\t*\t*\twifi down");
        assertNotNull(entry);
        assertEquals(23, entry.hour);
        assertEquals(30, entry.minute);
        assertEquals("wifi down", entry.command);
    }

    @Test
    public void nextExecutionRollsOverToTomorrowWhenTimeHasPassed() {
        java.util.Calendar now = java.util.Calendar.getInstance();
        now.set(2026, java.util.Calendar.SEPTEMBER, 19, 14, 30, 0);
        now.set(java.util.Calendar.MILLISECOND, 0);

        // 今天 08:00 已经过了 -> 明天 08:00
        java.util.Calendar next = java.util.Calendar.getInstance();
        next.setTimeInMillis(CronParser.nextExecution(8, 0, now.getTimeInMillis()));
        assertEquals(20, next.get(java.util.Calendar.DAY_OF_MONTH));
        assertEquals(8, next.get(java.util.Calendar.HOUR_OF_DAY));
        assertEquals(0, next.get(java.util.Calendar.MINUTE));

        // 今天 23:15 还没到 -> 就是今天
        next.setTimeInMillis(CronParser.nextExecution(23, 15, now.getTimeInMillis()));
        assertEquals(19, next.get(java.util.Calendar.DAY_OF_MONTH));
        assertEquals(23, next.get(java.util.Calendar.HOUR_OF_DAY));
        assertEquals(15, next.get(java.util.Calendar.MINUTE));
    }
}
