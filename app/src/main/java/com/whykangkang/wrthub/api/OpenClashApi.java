package com.whykangkang.wrthub.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * OpenClash 的配置文件 / 订阅流量 / 连通性检测 / 出口 IP,
 * 对应 iOS OpenWrtAPI 的「OpenClash Configuration Files」「Website Connectivity Check」
 * 与 getOpenClashIPInfo 三部分。除文件列表走 ubus 外,其余都走 LuCI CGI 通道。
 */
public final class OpenClashApi {

    private static final String CONFIG_DIR = "/etc/openclash/config/";
    private static final String CGI_BASE = "/admin/services/openclash";

    /** 订阅流量信息 */
    public static final class TrafficInfo {
        public String used = "";
        public String total = "";
        public String surplus = "";
        public String percent = "0";
        public String expire;
        public String dayLeft;

        public boolean isEmpty() {
            return used.isEmpty() && total.isEmpty();
        }
    }

    /** 一条连通性检测结果 */
    public static final class WebsiteCheck {
        public final String domain;
        public final boolean reachable;
        public final Integer latencyMs;

        WebsiteCheck(String domain, boolean reachable, Integer latencyMs) {
            this.domain = domain;
            this.reachable = reachable;
            this.latencyMs = latencyMs;
        }
    }

    /** 一条出口 IP 检测结果 */
    public static final class IpCheck {
        public final String source;
        public final String ip;
        public final String geo;

        IpCheck(String source, String ip, String geo) {
            this.source = source;
            this.ip = ip;
            this.geo = geo;
        }
    }

    /** 与 iOS 连通性检测四宫格一致的目标 */
    public static final String[] CHECK_DOMAINS = {
            "www.baidu.com", "music.163.com", "github.com", "www.google.com"};

    private static final Map<String, String> DISPLAY_NAMES = new HashMap<>();

    static {
        DISPLAY_NAMES.put("www.baidu.com", "百度搜索");
        DISPLAY_NAMES.put("music.163.com", "网易云音乐");
        DISPLAY_NAMES.put("github.com", "GitHub");
        DISPLAY_NAMES.put("www.google.com", "Google");
    }

    public static String displayName(String domain) {
        String name = DISPLAY_NAMES.get(domain);
        return name != null ? name : domain;
    }

    private OpenClashApi() {
    }

    private static OpenWrtApi api() {
        return OpenWrtApi.getInstance();
    }

    // =====================================================================
    // 配置文件
    // =====================================================================

    /** 列出 /etc/openclash/config/ 下的 yaml/yml */
    public static void getConfigs(ApiCallback<List<String>> cb) {
        JsonObject params = new JsonObject();
        params.addProperty("path", CONFIG_DIR);
        api().makeUbusCall("file", "list", params, null, new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                List<String> files = new ArrayList<>();
                JsonElement entries = result.get("entries");
                if (entries != null && entries.isJsonArray()) {
                    for (JsonElement el : entries.getAsJsonArray()) {
                        if (!el.isJsonObject()) continue;
                        JsonObject entry = el.getAsJsonObject();
                        String name = str(entry, "name");
                        String type = str(entry, "type");
                        if (name == null || !"file".equals(type)) continue;
                        if (name.endsWith(".yaml") || name.endsWith(".yml")) {
                            files.add(name);
                        }
                    }
                }
                cb.onSuccess(files);
            }

            @Override
            public void onFailure(ApiError error) {
                cb.onFailure(error);
            }
        });
    }

    /** 当前生效的配置文件名(uci openclash.@openclash[0].config_path 的 basename) */
    public static void getCurrentConfig(ApiCallback<String> cb) {
        api().uciGet("openclash", "@openclash[0]", "config_path",
                new ApiCallback<JsonObject>() {
                    @Override
                    public void onSuccess(JsonObject result) {
                        String value = str(result, "value");
                        if (value == null || value.isEmpty()) {
                            cb.onSuccess(null);
                            return;
                        }
                        int slash = value.lastIndexOf('/');
                        cb.onSuccess(slash >= 0 ? value.substring(slash + 1) : value);
                    }

                    @Override
                    public void onFailure(ApiError error) {
                        cb.onFailure(error);
                    }
                });
    }

    /** 切换配置文件(CGI:switch_config?config_file=...) */
    public static void switchConfig(String fileName, ApiCallback<Void> cb) {
        String path = CONFIG_DIR + fileName;
        api().cgiGetAsBrowser(CGI_BASE + "/switch_config?config_file=" + encode(path)
                + "&" + System.currentTimeMillis(), 30, new ApiCallback<String>() {
            @Override
            public void onSuccess(String text) {
                // 只要 200 就算成功(响应格式各版本不同,与 iOS 一致)
                cb.onSuccess(null);
            }

            @Override
            public void onFailure(ApiError error) {
                cb.onFailure(error);
            }
        });
    }

    /**
     * 订阅流量信息(CGI:sub_info_get?filename=...,不带扩展名)。
     * 刚切换配置时后端还没算好,iOS 用 10 次重试等它 —— 这里同样重试。
     */
    public static void getConfigTraffic(String fileName, ApiCallback<TrafficInfo> cb) {
        String base = fileName.replace(".yaml", "").replace(".yml", "");
        fetchTraffic(base, 0, cb);
    }

    private static final int TRAFFIC_MAX_RETRY = 10;

    private static void fetchTraffic(String base, int attempt, ApiCallback<TrafficInfo> cb) {
        api().cgiGetAsBrowser(CGI_BASE + "/sub_info_get?filename=" + encode(base)
                + "&" + System.currentTimeMillis(), 15, new ApiCallback<String>() {
            @Override
            public void onSuccess(String text) {
                TrafficInfo info = parseTraffic(text);
                if (info != null && !info.isEmpty()) {
                    cb.onSuccess(info);
                } else if (attempt < TRAFFIC_MAX_RETRY) {
                    retry();
                } else {
                    cb.onFailure(new ApiError(ApiError.Type.INVALID_RESPONSE, "无订阅信息"));
                }
            }

            @Override
            public void onFailure(ApiError error) {
                if (attempt < TRAFFIC_MAX_RETRY) {
                    retry();
                } else {
                    cb.onFailure(error);
                }
            }

            private void retry() {
                api().backgroundExecutor().execute(() -> {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    fetchTraffic(base, attempt + 1, cb);
                });
            }
        });
    }

    /** 兼容两种结构:providers 数组(x86 版)与顶层字段(其他版本) */
    static TrafficInfo parseTraffic(String text) {
        JsonObject json = parseJson(text);
        if (json == null) return null;
        JsonObject source = json;
        JsonElement providers = json.get("providers");
        if (providers != null && providers.isJsonArray() && providers.getAsJsonArray().size() > 0
                && providers.getAsJsonArray().get(0).isJsonObject()) {
            source = providers.getAsJsonArray().get(0).getAsJsonObject();
        }
        TrafficInfo info = new TrafficInfo();
        info.used = nz(str(source, "used"));
        info.total = nz(str(source, "total"));
        info.surplus = nz(str(source, "surplus"));
        String percent = str(source, "percent");
        if (percent != null && !percent.isEmpty()) info.percent = percent;
        String expire = str(source, "expire");
        if (expire != null && !expire.isEmpty() && !"null".equals(expire)) info.expire = expire;
        String dayLeft = str(source, "day_left");
        if (dayLeft != null && !dayLeft.isEmpty() && !"null".equals(dayLeft)) {
            info.dayLeft = dayLeft;
        }
        return info;
    }

    // =====================================================================
    // 连通性检测 / 出口 IP
    // =====================================================================

    /** 响应形如 {"error":"","success":true,"response_time":85} */
    public static void checkWebsite(String domain, ApiCallback<WebsiteCheck> cb) {
        api().cgiGetAsBrowser(CGI_BASE + "/website_check?domain=" + encode(domain), 15,
                new ApiCallback<String>() {
                    @Override
                    public void onSuccess(String text) {
                        JsonObject json = parseJson(text);
                        if (json == null) {
                            cb.onSuccess(new WebsiteCheck(domain, false, null));
                            return;
                        }
                        boolean success = json.has("success")
                                && json.get("success").getAsBoolean();
                        String error = nz(str(json, "error"));
                        Integer time = null;
                        if (json.has("response_time") && !json.get("response_time").isJsonNull()) {
                            try {
                                time = json.get("response_time").getAsInt();
                            } catch (NumberFormatException ignored) {
                                time = null;
                            }
                        }
                        cb.onSuccess(new WebsiteCheck(domain, success && error.isEmpty(), time));
                    }

                    @Override
                    public void onFailure(ApiError err) {
                        // 网络错误视为不可达(与 iOS 一致)
                        cb.onSuccess(new WebsiteCheck(domain, false, null));
                    }
                });
    }

    /** myip_check 返回 {"ipify":{"ip":..,"geo":..}, "upaiyun":{...}} */
    public static void getIpInfo(ApiCallback<List<IpCheck>> cb) {
        api().cgiGetAsBrowser(CGI_BASE + "/myip_check", 15, new ApiCallback<String>() {
            @Override
            public void onSuccess(String text) {
                JsonObject json = parseJson(text);
                List<IpCheck> results = new ArrayList<>();
                if (json != null) {
                    for (String source : json.keySet()) {
                        JsonElement el = json.get(source);
                        if (!el.isJsonObject()) continue;
                        JsonObject o = el.getAsJsonObject();
                        String ip = str(o, "ip");
                        String geo = str(o, "geo");
                        if (ip != null) results.add(new IpCheck(source, ip, nz(geo)));
                    }
                }
                if (results.isEmpty()) {
                    cb.onFailure(new ApiError(ApiError.Type.INVALID_RESPONSE,
                            "No IP information available"));
                } else {
                    cb.onSuccess(results);
                }
            }

            @Override
            public void onFailure(ApiError error) {
                cb.onFailure(error);
            }
        });
    }

    // =====================================================================
    // 面板检测
    // =====================================================================

    /**
     * status 里没有面板字段时,用 file.stat 看 /usr/share/openclash/ui/&lt;name&gt; 在不在
     * (iOS 修过的兜底:部分固件的 status 不带 dashboard 标记)。
     */
    public static void detectDashboard(String name, ApiCallback<Boolean> cb) {
        api().fileStat("/usr/share/openclash/ui/" + name, new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                String type = str(result, "type");
                cb.onSuccess("directory".equals(type) || "dir".equals(type));
            }

            @Override
            public void onFailure(ApiError error) {
                cb.onSuccess(false);
            }
        });
    }

    // =====================================================================
    // 工具
    // =====================================================================

    static JsonObject parseJson(String text) {
        if (text == null) return null;
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            return com.google.gson.JsonParser.parseString(text.substring(start, end + 1))
                    .getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return null;
        return e.getAsString();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String encode(String s) {
        StringBuilder sb = new StringBuilder();
        for (byte b : s.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append((char) c);
            } else {
                sb.append('%').append(String.format(Locale.US, "%02X", c));
            }
        }
        return sb.toString();
    }
}
