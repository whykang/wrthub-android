package com.whykangkang.wrthub.api;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 局域网测速:手机 ↔ 路由器这一跳。
 *
 * <p><b>测的不是宽带。</b>数据只在手机和路由器之间跑,不出公网,
 * 所以结果反映的是 WiFi 链路(或网线)的质量,不是运营商给的带宽。
 *
 * <h3>为什么这么实现</h3>
 * 让路由器自己跑测速要用 curl/wget,而 rpcd 的 file.exec ACL **不放行**这些命令
 * (真机实测返回 code 6 权限被拒),所以只能靠 App 主动收发数据:
 * <ul>
 *   <li><b>下行</b>:并发 GET 路由器上的静态资源,带随机查询串防缓存</li>
 *   <li><b>上行</b>:反复 POST 到 cgi-upload 的 {@code /tmp/upload.ipk}
 *       —— 实测只有白名单路径能写,随便换个路径会被 ACL 拒</li>
 * </ul>
 *
 * <p>上传目标在 tmpfs(内存)里,每次上传都覆盖同一个文件,所以峰值占用只有一个
 * 分块大小;跑完再写 1 字节把它缩掉,不给路由器留几十 MB 的垃圾。
 */
public final class SpeedTestApi {

    /** 每个方向测多久 */
    private static final long PHASE_MS = 6000;
    /** 下行并发连接数。单连接受 TCP 窗口限制,跑不满 WiFi */
    private static final int DOWNLOAD_STREAMS = 4;
    /** 上行分块大小(2 MB)。tmpfs 是内存,别一次塞太大 */
    private static final int UPLOAD_CHUNK = 2 * 1024 * 1024;
    /** 测延迟取几次 */
    private static final int LATENCY_ROUNDS = 7;

    /** cgi-upload 的可写白名单之一,专给"上传软件包"用的临时路径 */
    private static final String UPLOAD_PATH = "/tmp/upload.ipk";

    /**
     * 下行用的静态资源候选,按体积从大到小。
     * 逐个探测,选第一个拿得到的 —— 主题和 LuCI 版本不同,文件不一定都在。
     */
    private static final List<String> DOWNLOAD_CANDIDATES = Arrays.asList(
            "/luci-static/argon/css/cascade.css",
            "/luci-static/argon/js/jquery.min.js",
            "/luci-static/resources/form.js",
            "/luci-static/resources/luci.js",
            "/luci-static/bootstrap/cascade.css");

    public enum Phase { LATENCY, DOWNLOAD, UPLOAD, DONE }

    public static final class Result {
        public final double downBps;
        public final double upBps;
        public final double latencyMs;
        public final double jitterMs;

        Result(double downBps, double upBps, double latencyMs, double jitterMs) {
            this.downBps = downBps;
            this.upBps = upBps;
            this.latencyMs = latencyMs;
            this.jitterMs = jitterMs;
        }
    }

    public interface Listener {
        void onPhase(Phase phase);

        /** 当前瞬时速率(bit/s),用来动进度条 */
        void onSample(Phase phase, double bitsPerSecond);

        void onFinished(Result result);

        void onError(String message);
    }

    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();

    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public void cancel() {
        cancelled.set(true);
    }

    private static OpenWrtApi api() {
        return OpenWrtApi.getInstance();
    }

    private static void post(Runnable action) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(action);
    }

    // =====================================================================

    public void start(Listener listener) {
        cancelled.set(false);
        EXECUTOR.execute(() -> {
            try {
                runAll(listener);
            } catch (Exception e) {
                String message = e.getMessage() == null
                        ? e.getClass().getSimpleName() : e.getMessage();
                post(() -> listener.onError(message));
            }
        });
    }

    private void runAll(Listener listener) throws Exception {
        if (!api().isConfigured()) {
            post(() -> listener.onError("未配置设备"));
            return;
        }

        post(() -> listener.onPhase(Phase.LATENCY));
        double[] latency = measureLatency();

        String source = pickDownloadSource();
        if (source == null) {
            post(() -> listener.onError("找不到可用于测速的资源"));
            return;
        }

        post(() -> listener.onPhase(Phase.DOWNLOAD));
        double down = measureDownload(source, listener);
        if (cancelled.get()) return;

        post(() -> listener.onPhase(Phase.UPLOAD));
        double up = measureUpload(listener);
        cleanupUpload();
        if (cancelled.get()) return;

        Result result = new Result(down, up, latency[0], latency[1]);
        post(() -> {
            listener.onPhase(Phase.DONE);
            listener.onFinished(result);
        });
    }

    // =====================================================================
    // 延迟
    // =====================================================================

    /** @return {中位数, 抖动} 毫秒 */
    private double[] measureLatency() {
        List<Double> samples = new ArrayList<>();
        for (int i = 0; i < LATENCY_ROUNDS && !cancelled.get(); i++) {
            long start = System.nanoTime();
            Request request = new Request.Builder()
                    .url(api().baseUrl() + "/cgi-bin/luci/?nocache=" + System.nanoTime())
                    .head()
                    .build();
            try (Response response = api().clientWithTimeout(5).newCall(request).execute()) {
                response.code();
                samples.add((System.nanoTime() - start) / 1_000_000.0);
            } catch (IOException ignored) {
                // 单次失败不影响整体,少一个样本而已
            }
        }
        return medianAndJitter(samples);
    }

    /** 可单测:中位数抗偶发抖动,比平均值稳 */
    static double[] medianAndJitter(List<Double> samples) {
        if (samples == null || samples.isEmpty()) return new double[]{0, 0};
        List<Double> sorted = new ArrayList<>(samples);
        Collections.sort(sorted);
        double median = sorted.size() % 2 == 1
                ? sorted.get(sorted.size() / 2)
                : (sorted.get(sorted.size() / 2 - 1) + sorted.get(sorted.size() / 2)) / 2;
        double jitter = 0;
        for (double v : sorted) jitter += Math.abs(v - median);
        return new double[]{median, jitter / sorted.size()};
    }

    // =====================================================================
    // 下行
    // =====================================================================

    /** 探测一个拿得到的静态资源 */
    private String pickDownloadSource() {
        for (String path : DOWNLOAD_CANDIDATES) {
            if (cancelled.get()) return null;
            Request request = new Request.Builder()
                    .url(api().baseUrl() + path + "?cb=" + System.nanoTime())
                    .build();
            try (Response response = api().clientWithTimeout(5).newCall(request).execute()) {
                ResponseBody body = response.body();
                if (response.isSuccessful() && body != null && body.bytes().length > 4096) {
                    return path;
                }
            } catch (IOException ignored) {
                // 换下一个
            }
        }
        return null;
    }

    private double measureDownload(String path, Listener listener) throws InterruptedException {
        AtomicLong bytes = new AtomicLong();
        long start = System.currentTimeMillis();
        long until = start + PHASE_MS;
        CountDownLatch latch = new CountDownLatch(DOWNLOAD_STREAMS);

        for (int i = 0; i < DOWNLOAD_STREAMS; i++) {
            EXECUTOR.execute(() -> {
                byte[] buffer = new byte[16 * 1024];
                while (System.currentTimeMillis() < until && !cancelled.get()) {
                    Request request = new Request.Builder()
                            .url(api().baseUrl() + path + "?cb=" + System.nanoTime())
                            .header("Cache-Control", "no-store")
                            .build();
                    try (Response response = api().clientWithTimeout(15)
                            .newCall(request).execute()) {
                        ResponseBody body = response.body();
                        if (body == null) break;
                        try (InputStream in = body.byteStream()) {
                            int read;
                            while ((read = in.read(buffer)) != -1) {
                                bytes.addAndGet(read);
                                if (System.currentTimeMillis() >= until || cancelled.get()) break;
                            }
                        }
                    } catch (IOException e) {
                        break;
                    }
                }
                latch.countDown();
            });
        }

        reportProgress(listener, Phase.DOWNLOAD, bytes, start, until, latch);
        latch.await(PHASE_MS + 10_000, TimeUnit.MILLISECONDS);
        return rate(bytes.get(), System.currentTimeMillis() - start);
    }

    // =====================================================================
    // 上行
    // =====================================================================

    private double measureUpload(Listener listener) {
        byte[] payload = new byte[UPLOAD_CHUNK];
        // 全零会被某些中间层压掉,填点随机内容更接近真实传输
        new java.util.Random().nextBytes(payload);

        AtomicLong bytes = new AtomicLong();
        long start = System.currentTimeMillis();
        long until = start + PHASE_MS;
        while (System.currentTimeMillis() < until && !cancelled.get()) {
            if (!uploadOnce(payload)) break;
            bytes.addAndGet(payload.length);
            double elapsed = (System.currentTimeMillis() - start) / 1000.0;
            if (elapsed > 0) {
                double bps = bytes.get() * 8.0 / elapsed;
                post(() -> listener.onSample(Phase.UPLOAD, bps));
            }
        }
        return rate(bytes.get(), System.currentTimeMillis() - start);
    }

    private boolean uploadOnce(byte[] payload) {
        OpenWrtApi api = api();
        String token = api.sessionToken();
        if (token == null) token = api.cgiSysauthToken();
        String boundary = "----WrtHubSpeed" + UUID.randomUUID().toString().replace("-", "");
        MultipartBody body = new MultipartBody.Builder(boundary)
                .setType(MultipartBody.FORM)
                .addFormDataPart("sessionid", token)
                .addFormDataPart("filename", UPLOAD_PATH)
                .addFormDataPart("filedata", "speedtest.bin",
                        RequestBody.create(payload, MediaType.parse("application/octet-stream")))
                .build();
        Request request = new Request.Builder()
                .url(api.baseUrl() + "/cgi-bin/cgi-upload?" + System.nanoTime())
                .post(body)
                .header("Cookie", "sysauth_http=" + api.cgiSysauthToken())
                .build();
        try (Response response = api.clientWithTimeout(30).newCall(request).execute()) {
            return response.isSuccessful();
        } catch (IOException e) {
            return false;
        }
    }

    /** 跑完把 tmpfs 里那个文件缩到 1 字节,不留几十 MB 占内存 */
    private void cleanupUpload() {
        uploadOnce(new byte[]{0});
    }

    // =====================================================================

    private void reportProgress(Listener listener, Phase phase, AtomicLong bytes,
                                long start, long until, CountDownLatch latch) {
        EXECUTOR.execute(() -> {
            while (System.currentTimeMillis() < until && !cancelled.get()
                    && latch.getCount() > 0) {
                double elapsed = (System.currentTimeMillis() - start) / 1000.0;
                if (elapsed > 0.2) {
                    double bps = bytes.get() * 8.0 / elapsed;
                    post(() -> listener.onSample(phase, bps));
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });
    }

    /** 可单测:字节数 + 耗时 → bit/s */
    static double rate(long bytes, long millis) {
        if (millis <= 0) return 0;
        return bytes * 8.0 / (millis / 1000.0);
    }
}
