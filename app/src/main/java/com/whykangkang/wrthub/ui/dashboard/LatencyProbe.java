package com.whykangkang.wrthub.ui.dashboard;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.whykangkang.wrthub.api.ApiCallback;
import com.whykangkang.wrthub.api.ApiError;
import com.whykangkang.wrthub.api.ClashDelayApi;
import com.whykangkang.wrthub.api.OpenWrtApi;
import com.whykangkang.wrthub.util.Constants;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 首页「延迟」卡的测量逻辑,对应 iOS DashboardViewController 的
 * refreshLatency / handleUnreachableTarget / pingFallbackIP。
 *
 * 路由器侧 ping 目标一次并解析 time=xx ms。OpenClash fake-ip 模式下被代理域名
 * ping 不通(ICMP 不走代理,解析到 198.18.x 假 IP),此时自动改用 Clash API 测
 * 「经代理的 HTTP 延迟」;Clash 接口也不通则退回 ping 字面 IP 测出口线路 RTT。
 */
public class LatencyProbe {

    /** 一次测量的结果 */
    public static class Result {
        /** null 表示不可达 */
        public Double ms;
        /** true = 结果来自 Clash API(经代理的 HTTP 延迟) */
        public boolean viaProxy;
        /** 实际测的目标与用户设定的不同时(fake-ip 回退)记在这里 */
        public String probeHost;
    }

    public interface Listener {
        void onResult(Result result);
    }

    private static final Pattern TIME_PATTERN = Pattern.compile("time=([0-9.]+) ms");
    private static final Pattern RESOLVED_IP =
            Pattern.compile("\\((\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})\\)");

    private final OpenWrtApi api;
    /** 这台设备的 Clash 延迟接口试过且不通,不再每次重试 */
    private boolean clashDelayUnavailable;
    private boolean isPinging;

    public LatencyProbe(OpenWrtApi api) {
        this.api = api;
    }

    /** 换测试目标后重新给 Clash 接口一次机会 */
    public void resetClashAvailability() {
        clashDelayUnavailable = false;
    }

    /** 串行:上一轮没回来就跳过本轮(与 iOS isPinging 守卫一致) */
    public void probe(String host, Listener listener) {
        if (isPinging) return;
        isPinging = true;
        ping(host, 1, out -> {
            // 先判断是否被 fake-ip 接管 —— 必须优先于 time= 解析:
            // 有的固件 tun 接口会回包,能 ping 通并给出 0.x ms 的假延迟
            if (isFakeIpResponse(out)) {
                handleUnreachable(host, listener);
                return;
            }
            Double ms = parsePingTime(out);
            if (ms != null) {
                Result r = new Result();
                r.ms = ms;
                finish(listener, r);
                return;
            }
            // 目标没回包:被墙、对方禁 ICMP、或域名解析失败。
            // 这不代表「没网」,走兜底链而不是直接显示 --。
            if (Constants.FALLBACK_LATENCY_IP.equals(host)) {
                finish(listener, new Result());
            } else {
                handleUnreachable(host, listener);
            }
        });
    }

    /**
     * 目标 ping 不到时的兜底链:
     *   Clash API 的「经代理 HTTP 延迟」(被墙域名只有它测得出)
     *   → 不可用时退回 ping 字面 IP 测出口线路。
     */
    private void handleUnreachable(String host, Listener listener) {
        if (clashDelayUnavailable) {
            pingFallbackIp(listener);
            return;
        }
        ClashDelayApi.getHttpDelay(host, new ApiCallback<Integer>() {
            @Override
            public void onSuccess(Integer delay) {
                Result r = new Result();
                r.ms = (double) delay;
                r.viaProxy = true;
                finish(listener, r);
            }

            @Override
            public void onFailure(ApiError error) {
                // 这台设备走不通,本次会话不再重试
                clashDelayUnavailable = true;
                pingFallbackIp(listener);
            }
        });
    }

    /** ping 字面 IP(不经 DNS,绕开 fake-ip)。小于 1ms 说明仍是本机虚拟网卡回的包,判为无效。 */
    private void pingFallbackIp(Listener listener) {
        String ip = Constants.FALLBACK_LATENCY_IP;
        ping(ip, 2, out -> {
            Result r = new Result();
            Double ms = parsePingTime(out);
            if (ms != null && ms >= 1) {
                r.ms = ms;
                r.probeHost = ip;
            }
            finish(listener, r);
        });
    }

    private void finish(Listener listener, Result result) {
        isPinging = false;
        listener.onResult(result);
    }

    private interface OutputListener {
        void onOutput(String output);
    }

    private void ping(String host, int waitSec, OutputListener listener) {
        api.fileExec("/bin/ping", new String[]{"-c", "1", "-W", String.valueOf(waitSec), host},
                10, new ApiCallback<JsonObject>() {
                    @Override
                    public void onSuccess(JsonObject result) {
                        StringBuilder sb = new StringBuilder();
                        JsonElement stdout = result.get("stdout");
                        JsonElement stderr = result.get("stderr");
                        if (stdout != null && stdout.isJsonPrimitive()) {
                            sb.append(stdout.getAsString());
                        }
                        if (stderr != null && stderr.isJsonPrimitive()) {
                            sb.append(stderr.getAsString());
                        }
                        listener.onOutput(sb.toString());
                    }

                    @Override
                    public void onFailure(ApiError error) {
                        listener.onOutput("");
                    }
                });
    }

    /** 从 ping 输出里取出 "time=xx ms" 的毫秒数 */
    public static Double parsePingTime(String output) {
        if (output == null) return null;
        Matcher m = TIME_PATTERN.matcher(output);
        if (!m.find()) return null;
        try {
            return Double.parseDouble(m.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 判断 ping 输出是否指向代理的 fake-ip(此时延迟数值无意义)。
     * Clash 默认 fake-ip 池为 198.18.0.0/15;ICMP 被防火墙拒绝也归为同一情况。
     */
    public static boolean isFakeIpResponse(String output) {
        if (output == null) return false;
        if (output.contains("Operation not permitted")) return true;
        Matcher m = RESOLVED_IP.matcher(output);
        if (m.find()) {
            String ip = m.group(1);
            return ip.startsWith("198.18.") || ip.startsWith("198.19.");
        }
        return false;
    }
}
