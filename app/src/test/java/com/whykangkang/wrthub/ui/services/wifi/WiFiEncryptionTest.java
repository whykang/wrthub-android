package com.whykangkang.wrthub.ui.services.wifi;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * 加密方式的归一化。
 *
 * WiFiInfo.encryption 存的是**展示名**("WPA2-PSK"/"OPEN"),不是 uci 原始值。
 * 编辑页原来只认 uci 值,一旦拿到展示名就全部落到默认的 psk2 ——
 * 开放网络会被当成 WPA2,保存时就把它改坏了。
 */
public class WiFiEncryptionTest {

    @Test
    public void rawUciValuesArePreserved() {
        assertEquals("none", WiFiEditActivity.normalizeEnc("none"));
        assertEquals("psk2", WiFiEditActivity.normalizeEnc("psk2"));
        assertEquals("psk2", WiFiEditActivity.normalizeEnc("psk2+ccmp"));
        assertEquals("sae", WiFiEditActivity.normalizeEnc("sae"));
        assertEquals("sae-mixed", WiFiEditActivity.normalizeEnc("sae-mixed"));
    }

    @Test
    public void displayLabelsAlsoResolveCorrectly() {
        assertEquals("none", WiFiEditActivity.normalizeEnc("OPEN"));
        assertEquals("psk2", WiFiEditActivity.normalizeEnc("WPA2-PSK"));
        assertEquals("psk2", WiFiEditActivity.normalizeEnc("WPA/WPA2-PSK"));
        assertEquals("sae", WiFiEditActivity.normalizeEnc("WPA3-SAE"));
    }

    @Test
    public void openNetworkIsNeverMistakenForWpa2() {
        assertEquals("开放网络被当成 WPA2 会在保存时把配置改坏",
                "none", WiFiEditActivity.normalizeEnc("OPEN"));
        assertEquals("none", WiFiEditActivity.normalizeEnc("开放"));
    }

    @Test
    public void unknownOrEmptyFallsBackToWpa2() {
        assertEquals("psk2", WiFiEditActivity.normalizeEnc(null));
        assertEquals("psk2", WiFiEditActivity.normalizeEnc(""));
        assertEquals("psk2", WiFiEditActivity.normalizeEnc("something-else"));
    }
}
