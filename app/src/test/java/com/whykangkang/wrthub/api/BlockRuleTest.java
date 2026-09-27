package com.whykangkang.wrthub.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

/** 封禁规则的去重判断与规则名清洗 */
public class BlockRuleTest {

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    @Test
    public void detectsExistingRuleRegardlessOfCaseOrListForm() {
        JsonObject config = json("{\"values\":{"
                + "\"cfg01\":{\".type\":\"rule\",\"src_mac\":\"aa:bb:cc:00:11:22\"},"
                + "\"cfg02\":{\".type\":\"rule\",\"src_mac\":[\"DE:AD:BE:EF:00:01\"]},"
                + "\"cfg03\":{\".type\":\"zone\",\"name\":\"lan\"}"
                + "}}");
        assertTrue(OpenWrtApi.hasBlockRule(config, "AA:BB:CC:00:11:22"));
        assertTrue("src_mac 也可能是列表", OpenWrtApi.hasBlockRule(config, "DE:AD:BE:EF:00:01"));
        assertFalse(OpenWrtApi.hasBlockRule(config, "11:22:33:44:55:66"));
    }

    @Test
    public void toleratesConfigWithoutValuesWrapper() {
        JsonObject config = json("{\"cfg01\":{\"src_mac\":\"AA:BB:CC:00:11:22\"}}");
        assertTrue(OpenWrtApi.hasBlockRule(config, "aa:bb:cc:00:11:22"));
    }

    @Test
    public void ruleNameStripsUnsafeCharacters() {
        // 空格和中文会让 fw4 reload 挂掉
        assertEquals("WrtHub-Block-KangiPhone",
                OpenWrtApi.blockRuleName("AA:BB:CC:00:11:22", "Kang 的 iPhone"));
        assertEquals("WrtHub-Block-my-laptop_01",
                OpenWrtApi.blockRuleName("AA:BB:CC:00:11:22", "my-laptop_01"));
    }

    @Test
    public void ruleNameFallsBackToMacWhenNothingSafeIsLeft() {
        assertEquals("WrtHub-Block-AA_BB_CC_00_11_22",
                OpenWrtApi.blockRuleName("AA:BB:CC:00:11:22", "客厅电视"));
        assertEquals("WrtHub-Block-AA_BB_CC_00_11_22",
                OpenWrtApi.blockRuleName("AA:BB:CC:00:11:22", null));
    }

    @Test
    public void ruleNameIsLengthCapped() {
        String longName = "abcdefghij".repeat(8);
        String result = OpenWrtApi.blockRuleName("AA:BB:CC:00:11:22", longName);
        assertEquals(OpenWrtApi.BLOCK_RULE_PREFIX.length() + 40, result.length());
    }

    @Test
    public void firstSrcMacHandlesStringAndListForms() {
        assertEquals("AA:BB:CC:00:11:22",
                OpenWrtApi.firstSrcMac(json("{\"src_mac\":\"AA:BB:CC:00:11:22\"}")));
        assertEquals("AA:BB:CC:00:11:22",
                OpenWrtApi.firstSrcMac(json("{\"src_mac\":[\"AA:BB:CC:00:11:22\",\"X\"]}")));
        assertNull(OpenWrtApi.firstSrcMac(json("{\"src_mac\":[]}")));
        assertNull(OpenWrtApi.firstSrcMac(json("{}")));
    }
}
