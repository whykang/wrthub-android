package com.whykangkang.wrthub.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 测速的纯计算部分 */
public class SpeedTestApiTest {

    @Test
    public void rateConvertsBytesAndMillisToBitsPerSecond() {
        // 1 MB / 1 秒 = 8 Mbit/s
        assertEquals(8_000_000.0, SpeedTestApi.rate(1_000_000L, 1000L), 0.01);
        // 12.5 MB / 6 秒 ≈ 16.67 Mbit/s
        assertEquals(16_666_666.7, SpeedTestApi.rate(12_500_000L, 6000L), 1.0);
    }

    @Test
    public void rateIsZeroWhenNoTimeElapsed() {
        // 不能除零
        assertEquals(0.0, SpeedTestApi.rate(1234L, 0L), 0.0001);
        assertEquals(0.0, SpeedTestApi.rate(1234L, -5L), 0.0001);
    }

    /** 用中位数而不是平均值 —— 偶尔一次卡顿不该把整体延迟拉高 */
    @Test
    public void latencyUsesMedianSoOneSpikeDoesNotSkewIt() {
        List<Double> samples = new ArrayList<>(
                Arrays.asList(3.0, 3.2, 3.1, 250.0, 3.3, 2.9, 3.0));
        double[] r = SpeedTestApi.medianAndJitter(samples);
        assertEquals("中位数应该挑中 3.1 左右", 3.1, r[0], 0.05);
        assertTrue("平均值会被 250 拉到 38 上下", r[0] < 10);
    }

    @Test
    public void medianHandlesEvenSampleCounts() {
        double[] r = SpeedTestApi.medianAndJitter(new ArrayList<>(
                Arrays.asList(2.0, 4.0, 6.0, 8.0)));
        assertEquals(5.0, r[0], 0.0001);
    }

    @Test
    public void jitterIsMeanDeviationFromTheMedian() {
        double[] r = SpeedTestApi.medianAndJitter(new ArrayList<>(
                Arrays.asList(4.0, 5.0, 6.0)));
        assertEquals(5.0, r[0], 0.0001);
        assertEquals((1 + 0 + 1) / 3.0, r[1], 0.0001);
    }

    @Test
    public void emptySamplesGiveZeroes() {
        double[] r = SpeedTestApi.medianAndJitter(new ArrayList<>());
        assertEquals(0.0, r[0], 0.0001);
        assertEquals(0.0, r[1], 0.0001);
        double[] n = SpeedTestApi.medianAndJitter(null);
        assertEquals(0.0, n[0], 0.0001);
    }
}
