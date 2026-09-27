package com.whykangkang.wrthub.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * cgi-exec 响应解码。
 * 之前是「能解就解」,把明文当 Base64 解出一堆乱码,卸载失败的提示全成了方块。
 */
public class CgiExecDecodeTest {

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void decodesRealBase64() {
        String payload = "{\"code\":1,\"stdout\":\"\",\"stderr\":\"Cannot remove package\"}";
        assertEquals(payload, PackageApi.decodeBase64OrRaw(b64(payload)));
    }

    @Test
    public void decodesBase64WithLineBreaks() {
        String payload = "Package: busybox\nVersion: 1.36.1-1\n";
        String wrapped = Base64.getMimeEncoder(16, "\n".getBytes(StandardCharsets.UTF_8))
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        assertEquals(payload, PackageApi.decodeBase64OrRaw(wrapped));
    }

    @Test
    public void keepsPlainJsonAsIs() {
        String json = "{\"code\":0,\"stdout\":\"Removing adblock-fast\\n\"}";
        assertEquals(json, PackageApi.decodeBase64OrRaw(json));
    }

    @Test
    public void keepsPlainControlTextAsIs() {
        String text = "Package: adblock-fast\nVersion: 1.1.1-r3\n";
        assertEquals(text, PackageApi.decodeBase64OrRaw(text));
    }

    @Test
    public void keepsTextThatMerelyLooksLikeBase64ButDecodesToGarbage() {
        // 纯字母数字、长度恰好 4 的倍数 —— 字符集过关,但解出来是二进制垃圾
        String looksLike = "Collectederrorsopkgremovepkg";
        assertTrue(PackageApi.looksLikeBase64(looksLike));
        assertEquals("解出来不像文本就该退回原文",
                looksLike, PackageApi.decodeBase64OrRaw(looksLike));
    }

    @Test
    public void rejectsNonBase64Charsets() {
        assertFalse(PackageApi.looksLikeBase64("Removing package foo ..."));
        assertFalse(PackageApi.looksLikeBase64("abc"));          // 长度不是 4 的倍数
        assertFalse(PackageApi.looksLikeBase64("AAAA=AAA"));     // 填充后还有数据
    }

    @Test
    public void textDetectorRejectsBinary() {
        assertTrue(PackageApi.looksLikeText("Collected errors:\n * cannot remove\n"));
        assertFalse(PackageApi.looksLikeText("\u0000\u0001\u0002\u0003\u0004\u0005"));
        assertFalse(PackageApi.looksLikeText("\uFFFD\uFFFD\uFFFD\uFFFDab"));
    }

    @Test
    public void emptyBodyStaysEmpty() {
        assertEquals("", PackageApi.decodeBase64OrRaw(""));
        assertEquals("", PackageApi.decodeBase64OrRaw(null));
    }
}
