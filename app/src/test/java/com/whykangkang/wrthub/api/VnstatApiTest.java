package com.whykangkang.wrthub.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.util.List;

/**
 * vnStat 数据解析。报文取自真机实测(vnStat 2.11,jsonversion 2)。
 */
public class VnstatApiTest {

    private static final String JSON = "{\"vnstatversion\":\"2.11\",\"jsonversion\":\"2\","
            + "\"interfaces\":[{\"name\":\"br-lan\",\"alias\":\"\","
            + "\"created\":{\"date\":{\"year\":2026,\"month\":9,\"day\":20},"
            + "\"timestamp\":1789871544},"
            + "\"updated\":{\"date\":{\"year\":2026,\"month\":9,\"day\":20},"
            + "\"time\":{\"hour\":13,\"minute\":35},\"timestamp\":1789882500},"
            + "\"traffic\":{\"total\":{\"rx\":108676977,\"tx\":889236726},"
            + "\"fiveminute\":["
            + "{\"id\":34,\"date\":{\"year\":2026,\"month\":9,\"day\":20},"
            + "\"time\":{\"hour\":13,\"minute\":30},\"rx\":11,\"tx\":22},"
            + "{\"id\":35,\"date\":{\"year\":2026,\"month\":9,\"day\":20},"
            + "\"time\":{\"hour\":13,\"minute\":35},\"rx\":33,\"tx\":44}],"
            + "\"top\":[{\"id\":1,\"date\":{\"year\":2026,\"month\":9,\"day\":20},"
            + "\"rx\":108676977,\"tx\":889236726}],"
            + "\"hour\":["
            + "{\"id\":7,\"date\":{\"year\":2026,\"month\":9,\"day\":20},"
            + "\"time\":{\"hour\":12,\"minute\":0},\"rx\":100,\"tx\":200},"
            + "{\"id\":8,\"date\":{\"year\":2026,\"month\":9,\"day\":20},"
            + "\"time\":{\"hour\":13,\"minute\":0},\"rx\":36404248,\"tx\":158357281}],"
            + "\"day\":[{\"id\":2,\"date\":{\"year\":2026,\"month\":9,\"day\":20},"
            + "\"rx\":108676977,\"tx\":889236726}],"
            + "\"month\":[{\"id\":2,\"date\":{\"year\":2026,\"month\":9},"
            + "\"rx\":108676977,\"tx\":889236726}]}},"
            + "{\"name\":\"eth1\",\"traffic\":{\"total\":{\"rx\":1,\"tx\":2}}}]}";

    @Test
    public void interfacesAndTotalsAreParsed() {
        List<VnstatApi.Interface> list = VnstatApi.parseJson(JSON);
        assertEquals(2, list.size());
        assertEquals("br-lan", list.get(0).name);
        assertEquals(108676977L, list.get(0).totalRx);
        assertEquals(889236726L, list.get(0).totalTx);
        assertEquals("eth1", list.get(1).name);
    }

    @Test
    public void newestEntryComesFirst() {
        // vnstat 给的是旧→新;界面要最近的排最前
        VnstatApi.Interface br = VnstatApi.parseJson(JSON).get(0);
        assertEquals(2, br.hours.size());
        assertEquals("09-20 13:00", br.hours.get(0).label);
        assertEquals(36404248L, br.hours.get(0).rx);
        assertEquals("09-20 12:00", br.hours.get(1).label);
    }

    @Test
    public void labelsMatchThePeriod() {
        VnstatApi.Interface br = VnstatApi.parseJson(JSON).get(0);
        assertEquals("2026-09-20", br.days.get(0).label);
        assertEquals("2026-09", br.months.get(0).label);
    }

    @Test
    public void totalIsRxPlusTx() {
        VnstatApi.Interface br = VnstatApi.parseJson(JSON).get(0);
        assertEquals(108676977L + 889236726L, br.days.get(0).total());
    }

    @Test
    public void fiveMinuteEntriesAreParsedNewestFirst() {
        VnstatApi.Interface br = VnstatApi.parseJson(JSON).get(0);
        assertEquals(2, br.fiveMinutes.size());
        assertEquals("13:35", br.fiveMinutes.get(0).label);
        assertEquals(33L, br.fiveMinutes.get(0).rx);
        assertEquals("13:30", br.fiveMinutes.get(1).label);
    }

    @Test
    public void topDaysAreParsed() {
        VnstatApi.Interface br = VnstatApi.parseJson(JSON).get(0);
        assertEquals(1, br.tops.size());
        assertEquals("2026-09-20", br.tops.get(0).label);
    }

    @Test
    public void createdAndUpdatedStampsAreReadable() {
        VnstatApi.Interface br = VnstatApi.parseJson(JSON).get(0);
        assertEquals("建库时间只有日期", "2026-09-20", br.created);
        assertEquals("更新时间带时分", "2026-09-20 13:35", br.updated);
    }

    /**
     * 平均速率要和 vnStat 自己的 "avg. rate" 对上。
     * 真机 `vnstat -5` 的一行:56.46 MiB / 300s → 1.58 Mbit/s,拿它当基准。
     */
    @Test
    public void fiveMinuteAverageMatchesVnstatOutput() {
        long total = 59202232L;   // 56.46 MiB
        VnstatApi.Entry e = new VnstatApi.Entry("11:40", total / 2, total - total / 2, 300);
        assertEquals(1.58, e.avgBitsPerSecond() / 1e6, 0.01);
    }

    @Test
    public void periodLengthsAreCorrect() {
        VnstatApi.Interface br = VnstatApi.parseJson(JSON).get(0);
        assertEquals(300, br.fiveMinutes.get(0).periodSeconds);
        assertEquals(3600, br.hours.get(0).periodSeconds);
        assertEquals(86400, br.days.get(0).periodSeconds);
        // 2026-09 是 30 天
        assertEquals(30 * 86400L, br.months.get(0).periodSeconds);
    }

    /** 月份长度不能一律按 30 天算 */
    @Test
    public void daysInMonthHandlesLeapYears() {
        assertEquals(31, VnstatApi.daysInMonth(2026, 1));
        assertEquals(28, VnstatApi.daysInMonth(2026, 2));
        assertEquals(29, VnstatApi.daysInMonth(2024, 2));
        assertEquals(28, VnstatApi.daysInMonth(2100, 2));   // 整百年不闰
        assertEquals(29, VnstatApi.daysInMonth(2000, 2));   // 400 的倍数闰
        assertEquals(30, VnstatApi.daysInMonth(2026, 9));
    }

    @Test
    public void unknownPeriodGivesZeroRate() {
        assertEquals(0.0, new VnstatApi.Entry("x", 100, 200).avgBitsPerSecond(), 0.0001);
    }

    /** 累计平均速率 = 总流量 ÷ 实际记录时长(用 timestamp 算,不是格式化后的字符串) */
    @Test
    public void allTimeAverageUsesTheRealRecordingSpan() {
        VnstatApi.Interface br = VnstatApi.parseJson(JSON).get(0);
        assertEquals(1789871544L, br.createdTs);
        assertEquals(1789882500L, br.updatedTs);
        long span = 1789882500L - 1789871544L;
        double expected = (108676977L + 889236726L) * 8.0 / span;
        assertEquals(expected, br.avgBitsPerSecond(), 0.01);
    }

    @Test
    public void allTimeAverageIsZeroWhenTheSpanIsUnknown() {
        // 刚建库时 created == updated,不能除零
        VnstatApi.Interface br = VnstatApi.parseJson(JSON).get(1);
        assertEquals(0.0, br.avgBitsPerSecond(), 0.0001);
    }

    @Test
    public void malformedJsonYieldsEmptyList() {
        assertTrue(VnstatApi.parseJson("not json").isEmpty());
        assertTrue(VnstatApi.parseJson("{}").isEmpty());
        assertTrue(VnstatApi.parseJson("").isEmpty());
    }

    /** interface 是 list 选项;真机上回的是数组 */
    @Test
    public void monitoredInterfacesAreReadFromAnonymousSection() {
        JsonObject uci = JsonParser.parseString(
                "{\"values\":{\"cfg015065\":{\".anonymous\":true,\".type\":\"vnstat\","
                        + "\".name\":\"cfg015065\",\"interface\":[\"br-lan\",\"eth1\"]}}}")
                .getAsJsonObject();
        assertEquals(List.of("br-lan", "eth1"), VnstatApi.parseMonitored(uci));
        assertEquals("cfg015065", VnstatApi.findSection(uci));
    }

    /** 只配一个接口时 uci 可能回字符串而不是数组 */
    @Test
    public void singleInterfaceMayComeBackAsAString() {
        JsonObject uci = JsonParser.parseString(
                "{\"values\":{\"cfg1\":{\".type\":\"vnstat\",\"interface\":\"br-lan\"}}}")
                .getAsJsonObject();
        assertEquals(List.of("br-lan"), VnstatApi.parseMonitored(uci));
    }

    @Test
    public void missingConfigIsHandled() {
        JsonObject empty = JsonParser.parseString("{\"values\":{}}").getAsJsonObject();
        assertTrue(VnstatApi.parseMonitored(empty).isEmpty());
        assertNull(VnstatApi.findSection(empty));
    }

    /** 分开取的几份结果按接口名合并:各周期只填空着的,不认识的接口忽略 */
    @Test
    public void merge_combinesPerModeResults() {
        String base = "{\"interfaces\":[{\"name\":\"br-lan\",\"traffic\":{\"total\":{\"rx\":10,\"tx\":20},"
                + "\"month\":[{\"date\":{\"year\":2026,\"month\":9},\"rx\":1,\"tx\":2}]}},"
                + "{\"name\":\"eth0\",\"traffic\":{\"total\":{\"rx\":0,\"tx\":0},\"month\":[]}}]}";
        String hours = "{\"interfaces\":[{\"name\":\"br-lan\",\"traffic\":{\"total\":{\"rx\":10,\"tx\":20},"
                + "\"hour\":[{\"date\":{\"year\":2026,\"month\":9,\"day\":25},\"time\":{\"hour\":1,\"minute\":0},\"rx\":3,\"tx\":4},"
                + "{\"date\":{\"year\":2026,\"month\":9,\"day\":25},\"time\":{\"hour\":2,\"minute\":0},\"rx\":5,\"tx\":6}]}},"
                + "{\"name\":\"ghost\",\"traffic\":{\"hour\":[]}}]}";
        List<VnstatApi.Interface> list = VnstatApi.parseJson(base);
        VnstatApi.merge(list, VnstatApi.parseJson(hours));
        assertEquals(2, list.size());
        VnstatApi.Interface br = list.get(0);
        assertEquals(10, br.totalRx);
        assertEquals(1, br.months.size());
        assertEquals(2, br.hours.size());
        // 最近的排最前
        assertEquals(5, br.hours.get(0).rx);
        assertTrue(list.get(1).hours.isEmpty());
    }
}
