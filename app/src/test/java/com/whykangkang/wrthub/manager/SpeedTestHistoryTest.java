package com.whykangkang.wrthub.manager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** 测速记录的存取 */
public class SpeedTestHistoryTest {

    private static SpeedTestHistory.Record rec(long ts, double down) {
        return new SpeedTestHistory.Record(ts, down, down / 2, 3.5, 0.4);
    }

    @Test
    public void roundTripsThroughJson() {
        List<SpeedTestHistory.Record> list = new ArrayList<>();
        list.add(rec(1789882500000L, 240_000_000));
        list.add(rec(1789882000000L, 180_000_000));

        List<SpeedTestHistory.Record> back =
                SpeedTestHistory.parse(SpeedTestHistory.serialize(list));

        assertEquals(2, back.size());
        assertEquals(1789882500000L, back.get(0).timestamp);
        assertEquals(240_000_000.0, back.get(0).downBps, 0.01);
        assertEquals(120_000_000.0, back.get(0).upBps, 0.01);
        assertEquals(3.5, back.get(0).latencyMs, 0.001);
        assertEquals(0.4, back.get(0).jitterMs, 0.001);
    }

    @Test
    public void emptyAndNullParseToEmptyList() {
        assertTrue(SpeedTestHistory.parse(null).isEmpty());
        assertTrue(SpeedTestHistory.parse("").isEmpty());
        assertTrue(SpeedTestHistory.parse("[]").isEmpty());
    }

    /** 存坏了不能把整页搞崩 */
    @Test
    public void corruptedDataIsIgnoredNotThrown() {
        assertTrue(SpeedTestHistory.parse("not json").isEmpty());
        assertTrue(SpeedTestHistory.parse("{\"t\":1}").isEmpty());
        // 数组里混进非对象,跳过它但保留好的那条
        List<SpeedTestHistory.Record> mixed =
                SpeedTestHistory.parse("[123,{\"t\":5,\"d\":1,\"u\":2,\"l\":3,\"j\":4}]");
        assertEquals(1, mixed.size());
        assertEquals(5L, mixed.get(0).timestamp);
    }

    @Test
    public void missingFieldsDefaultToZero() {
        List<SpeedTestHistory.Record> list = SpeedTestHistory.parse("[{\"t\":9}]");
        assertEquals(1, list.size());
        assertEquals(0.0, list.get(0).downBps, 0.0001);
        assertEquals(0.0, list.get(0).latencyMs, 0.0001);
    }

    @Test
    public void serializingAnEmptyListIsValidJson() {
        assertEquals("[]", SpeedTestHistory.serialize(new ArrayList<>()));
    }
}
