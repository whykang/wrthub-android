package com.whykangkang.wrthub.api;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

/**
 * ubus service.list 的运行状态判定。
 * 报文形状取自 luci-app-filebrowser 的网页视图
 * (/luci-static/resources/view/filebrowser.js:res[name].instances.instance1.running)。
 */
public class ServiceRunningTest {

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    @Test
    public void detectsRunningInstance() {
        JsonObject result = json("{\"filebrowser\":{\"instances\":"
                + "{\"instance1\":{\"running\":true,\"pid\":1234}}}}");
        assertTrue(OpenWrtApi.serviceIsRunning(result, "filebrowser"));
    }

    @Test
    public void detectsStoppedInstance() {
        JsonObject result = json("{\"filebrowser\":{\"instances\":"
                + "{\"instance1\":{\"running\":false}}}}");
        assertFalse(OpenWrtApi.serviceIsRunning(result, "filebrowser"));
    }

    @Test
    public void looksAtEveryInstanceNotJustTheFirst() {
        // 网页端只看 instance1,多实例服务不该因为名字不同就被判成没运行
        JsonObject result = json("{\"filebrowser\":{\"instances\":"
                + "{\"main\":{\"running\":false},\"worker\":{\"running\":true}}}}");
        assertTrue(OpenWrtApi.serviceIsRunning(result, "filebrowser"));
    }

    @Test
    public void missingServiceOrInstancesIsNotRunning() {
        assertFalse(OpenWrtApi.serviceIsRunning(json("{}"), "filebrowser"));
        assertFalse(OpenWrtApi.serviceIsRunning(json("{\"filebrowser\":{}}"), "filebrowser"));
        assertFalse(OpenWrtApi.serviceIsRunning(
                json("{\"filebrowser\":{\"instances\":{}}}"), "filebrowser"));
        assertFalse(OpenWrtApi.serviceIsRunning(null, "filebrowser"));
    }

    @Test
    public void toleratesUnexpectedFieldTypes() {
        assertFalse(OpenWrtApi.serviceIsRunning(
                json("{\"filebrowser\":{\"instances\":{\"instance1\":"
                        + "{\"running\":\"yes-ish\"}}}}"), "filebrowser"));
        assertFalse(OpenWrtApi.serviceIsRunning(
                json("{\"filebrowser\":{\"instances\":\"nope\"}}"), "filebrowser"));
    }
}
