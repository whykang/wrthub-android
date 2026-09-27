package com.whykangkang.wrthub.ui.login;

import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.whykangkang.wrthub.R;

import java.io.IOException;
import java.net.Inet4Address;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 路由器扫描,对应 iOS RouterScanViewController。
 * /24 全段扫描:固定 16 线程池限制并发(等价 Semaphore(16));
 * 每 IP 按 4 条路径回退探测;离开页面/选中设备立即取消全部在途请求。
 */
public class RouterScanActivity extends AppCompatActivity {

    public static final String EXTRA_HOST = "host";

    /** 每 IP 依次尝试的探测路径(§4.1:rpc/sys → 4 条回退) */
    private static final String[] PROBE_PATHS = {
            "/ubus",
            "/cgi-bin/luci/rpc/sys",
            "/cgi-bin/luci/",
            "/",
    };

    private final OkHttpClient scanClient = new OkHttpClient.Builder()
            .connectTimeout(1500, TimeUnit.MILLISECONDS)
            .readTimeout(2000, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .build();

    private ExecutorService scanExecutor;
    private final AtomicInteger completed = new AtomicInteger();
    private volatile boolean cancelled;

    private TextView statusView;
    private TextView resultsHeader;
    private ProgressBar progressBar;
    private ResultAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_router_scan);

        statusView = findViewById(R.id.scan_status);
        resultsHeader = findViewById(R.id.scan_results_header);
        progressBar = findViewById(R.id.scan_progress);
        RecyclerView results = findViewById(R.id.scan_results);
        adapter = new ResultAdapter();
        results.setLayoutManager(new LinearLayoutManager(this));
        results.setAdapter(adapter);

        String localIp = findLocalIpv4();
        if (localIp == null) {
            // 拿不到本机 IP 是取不到网段,不是「扫完了没发现」,别混为一谈
            statusView.setText(R.string.scan_no_local_ip);
            progressBar.setProgress(0);
            return;
        }
        String prefix = localIp.substring(0, localIp.lastIndexOf('.') + 1);
        showProgress(prefix, 0);
        startScan(prefix);
    }

    private void showProgress(String prefix, int done) {
        statusView.setText(getString(R.string.scan_progress, prefix + "x", done, 254));
    }

    /** 用 ConnectivityManager.getLinkProperties 取本机 IPv4(无需定位权限) */
    private String findLocalIpv4() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm == null) return null;
        Network network = cm.getActiveNetwork();
        if (network == null) return null;
        LinkProperties lp = cm.getLinkProperties(network);
        if (lp == null) return null;
        for (LinkAddress la : lp.getLinkAddresses()) {
            if (la.getAddress() instanceof Inet4Address && !la.getAddress().isLoopbackAddress()) {
                return la.getAddress().getHostAddress();
            }
        }
        return null;
    }

    private void startScan(String prefix) {
        // 固定 16 线程 = 并发上限 16(对应 iOS Semaphore(16))
        scanExecutor = Executors.newFixedThreadPool(16);
        for (int i = 1; i <= 254; i++) {
            String ip = prefix + i;
            scanExecutor.execute(() -> {
                if (!cancelled) {
                    probe(ip);
                }
                int done = completed.incrementAndGet();
                runOnUiThread(() -> {
                    progressBar.setProgress(done);
                    if (done == 254 && !cancelled) {
                        statusView.setText(getString(R.string.msg_scan_done, adapter.getItemCount()));
                    } else if (!cancelled) {
                        // iOS 会把进度写在文字里(正在扫描 192.168.1.x (12/254))
                        showProgress(prefix, done);
                    }
                });
            });
        }
    }

    /** 按 4 条路径回退探测一个 IP,任一命中即认定为 OpenWrt/LuCI 设备 */
    private void probe(String ip) {
        for (String path : PROBE_PATHS) {
            if (cancelled) return;
            if (probePath(ip, path)) {
                runOnUiThread(() -> {
                    if (!cancelled) {
                        adapter.add(ip);
                    }
                });
                return;
            }
        }
    }

    private boolean probePath(String ip, String path) {
        Request.Builder builder = new Request.Builder()
                .url("http://" + ip + path)
                .tag("scan");
        if ("/ubus".equals(path)) {
            builder.post(RequestBody.create(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"list\",\"params\":[]}",
                    MediaType.get("application/json")));
        }
        try (Response response = scanClient.newCall(builder.build()).execute()) {
            String server = response.header("Server", "");
            String body = "";
            if (response.body() != null) {
                // 只取前 4KB 判断特征,避免大响应拖慢扫描
                body = new String(response.peekBody(4096).bytes());
            }
            switch (path) {
                case "/ubus":
                    return body.contains("jsonrpc");
                case "/cgi-bin/luci/rpc/sys":
                    return response.code() == 200 || response.code() == 401
                            || response.code() == 403;
                default:
                    return server.toLowerCase().contains("uhttpd")
                            || body.contains("LuCI") || body.contains("OpenWrt");
            }
        } catch (IOException e) {
            return false;
        }
    }

    /** 离开页面 / 选中设备时取消全部在途请求(iOS 修过的登录被拖慢 bug) */
    private void cancelScan() {
        cancelled = true;
        if (scanExecutor != null) {
            scanExecutor.shutdownNow();
        }
        scanClient.dispatcher().cancelAll();
    }

    private void select(String ip) {
        cancelScan();
        Intent data = new Intent();
        data.putExtra(EXTRA_HOST, ip);
        setResult(RESULT_OK, data);
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cancelScan();
    }

    private class ResultAdapter extends RecyclerView.Adapter<ResultAdapter.Holder> {

        private final List<String> ips = new ArrayList<>();

        void add(String ip) {
            if (!ips.contains(ip)) {
                ips.add(ip);
                notifyItemInserted(ips.size() - 1);
                resultsHeader.setVisibility(View.VISIBLE);
            }
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_scan_result, parent, false);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            String ip = ips.get(position);
            holder.ip.setText(ip);
            holder.itemView.setOnClickListener(v -> select(ip));
        }

        @Override
        public int getItemCount() {
            return ips.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final TextView ip;

            Holder(@NonNull View itemView) {
                super(itemView);
                ip = itemView.findViewById(R.id.scan_ip);
            }
        }
    }
}