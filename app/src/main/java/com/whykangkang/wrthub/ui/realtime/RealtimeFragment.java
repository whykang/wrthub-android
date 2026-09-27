package com.whykangkang.wrthub.ui.realtime;

import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.Description;
import com.github.mikephil.charting.components.Legend;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipDrawable;
import com.google.android.material.chip.ChipGroup;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.whykangkang.wrthub.R;
import com.whykangkang.wrthub.api.ApiCallback;
import com.whykangkang.wrthub.api.ApiError;
import com.whykangkang.wrthub.api.OpenWrtApi;
import com.whykangkang.wrthub.api.RouterApi;
import com.whykangkang.wrthub.model.NetworkDevice;
import com.whykangkang.wrthub.util.Formatters;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 实时页,对应 iOS RealtimeViewController。
 *
 * 顶部三段:流量 / 连接 / 负载。
 *  - 流量:接口选择 + 上下行大字 + 曲线 + 当前/平均/峰值 + 累计收发
 *  - 连接:按协议(TCP/UDP/其他)曲线与统计 + 活动连接列表(按流量排序)
 *  - 负载:1 分钟负载曲线 + 1/5/15 分钟统计
 * 3 秒采样,onPause 停 onResume 起。
 */
public class RealtimeFragment extends Fragment {

    private static final long SAMPLE_INTERVAL_MS = 3000L;

    /**
     * 第一次采样只能建基准,算不出速率(速率靠两次计数器的差分)。
     * 按 3 秒的正常节奏,进页面要等到第二次采样才有数字 —— 白屏三四秒。
     * 所以第二次采样**提前**到这个间隔,拿到第一个读数后再回到正常节奏。
     */
    private static final long PRIME_INTERVAL_MS = 700L;
    private static final int MAX_POINTS = 40;

    private static final int MODE_TRAFFIC = 0;
    private static final int MODE_CONNECTIONS = 1;
    private static final int MODE_LOAD = 2;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private OpenWrtApi api;
    private boolean running;
    private boolean loading;
    private int tick;
    private int mode = MODE_TRAFFIC;

    private LineChart chart;
    private TextView chartTitle;
    private TextView speedDown;
    private TextView speedUp;
    private View interfaceCard;
    private View speedCard;
    private View extraCard;
    private ChipGroup interfaceChips;
    private LinearLayout statsContainer;
    private LinearLayout extraContainer;

    /** 当前监控的设备名(null = 自动选 WAN) */
    private String selectedDevice;
    /** 可选接口:设备名 */
    private final List<String> availableDevices = new ArrayList<>();
    /** 上一次渲染出来的接口与选中项,用来避免每秒无谓重建 */
    private final List<String> renderedDevices = new ArrayList<>();
    private String renderedSelection;

    private final List<Entry> downloadPoints = new ArrayList<>();
    private final List<Entry> uploadPoints = new ArrayList<>();
    private final List<Entry> tcpPoints = new ArrayList<>();
    private final List<Entry> udpPoints = new ArrayList<>();
    private final List<Entry> otherPoints = new ArrayList<>();
    private final List<Entry> loadPoints = new ArrayList<>();

    // 流量差分状态
    private long lastRx = -1;
    private long lastTx = -1;
    private long lastTime = -1;
    private long totalRx;
    private long totalTx;
    private double peakDownload;
    private double peakUpload;
    private double sumDownload;
    private double sumUpload;
    private int trafficSamples;

    // 连接统计
    private int tcpCount;
    private int udpCount;
    private int otherCount;
    private int peakTcp;
    private int peakUdp;
    private int peakOther;
    private long sumTcp;
    private long sumUdp;
    private long sumOther;
    private int connSamples;
    private final List<String[]> activeConnections = new ArrayList<>();

    // 负载
    private double load1;
    private double load5;
    private double load15;

    /** 已经拿到过一次速率读数,可以回到正常采样节奏 */
    private boolean primed;

    private final Runnable sampleTick = new Runnable() {
        @Override
        public void run() {
            sample();
            if (running) {
                handler.postDelayed(this, primed ? SAMPLE_INTERVAL_MS : PRIME_INTERVAL_MS);
            }
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_realtime, container, false);
        chart = root.findViewById(R.id.chart);
        chartTitle = root.findViewById(R.id.chart_title);
        speedDown = root.findViewById(R.id.speed_down);
        speedUp = root.findViewById(R.id.speed_up);
        interfaceCard = root.findViewById(R.id.interface_card);
        speedCard = root.findViewById(R.id.speed_card);
        extraCard = root.findViewById(R.id.extra_card);
        interfaceChips = root.findViewById(R.id.interface_chips);
        statsContainer = root.findViewById(R.id.stats_container);
        extraContainer = root.findViewById(R.id.extra_container);
        styleChart(chart);

        MaterialButtonToggleGroup tabs = root.findViewById(R.id.mode_tabs);
        tabs.check(R.id.tab_traffic);
        tabs.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            if (checkedId == R.id.tab_connections) {
                mode = MODE_CONNECTIONS;
            } else if (checkedId == R.id.tab_load) {
                mode = MODE_LOAD;
            } else {
                mode = MODE_TRAFFIC;
            }
            applyMode();
        });
        api = OpenWrtApi.getInstance();
        applyMode();
        return root;
    }

    @Override
    public void onResume() {
        super.onResume();
        running = true;
        primed = false;   // 每次回到页面都重新预热一次,否则息屏回来又要等 3 秒
        handler.post(sampleTick);
    }

    @Override
    public void onPause() {
        super.onPause();
        running = false;
        handler.removeCallbacks(sampleTick);
    }

    private void applyMode() {
        boolean traffic = mode == MODE_TRAFFIC;
        interfaceCard.setVisibility(traffic ? View.VISIBLE : View.GONE);
        speedCard.setVisibility(traffic ? View.VISIBLE : View.GONE);
        extraCard.setVisibility(mode == MODE_LOAD ? View.GONE : View.VISIBLE);
        chartTitle.setText(traffic ? R.string.realtime_traffic
                : mode == MODE_CONNECTIONS ? R.string.realtime_connections
                : R.string.realtime_load);
        redraw();
    }

    // =====================================================================
    // 采样
    // =====================================================================

    private void sample() {
        if (loading || !api.isConfigured()) return;
        loading = true;
        RouterApi.getNetworkDevices(new ApiCallback<List<NetworkDevice>>() {
            @Override
            public void onSuccess(List<NetworkDevice> devices) {
                onTraffic(devices);
                fetchLoad();
            }

            @Override
            public void onFailure(ApiError error) {
                fetchLoad();
            }
        });
    }

    private void fetchLoad() {
        api.getSystemInfo(new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject info) {
                onLoad(info);
                fetchConnections();
            }

            @Override
            public void onFailure(ApiError error) {
                fetchConnections();
            }
        });
    }

    /** luci.getConntrackList 给出逐条连接,可按协议分类并列出流量最大的几条 */
    private void fetchConnections() {
        api.makeUbusCall("luci", "getConntrackList", null, null,
                new ApiCallback<JsonObject>() {
                    @Override
                    public void onSuccess(JsonObject result) {
                        onConnections(result);
                        finishSample();
                    }

                    @Override
                    public void onFailure(ApiError error) {
                        // 没有 getConntrackList 权限时退回只取总数
                        api.getConntrack(new ApiCallback<JsonObject>() {
                            @Override
                            public void onSuccess(JsonObject ct) {
                                if (ct.has("count")) {
                                    tcpCount = ct.get("count").getAsInt();
                                    udpCount = 0;
                                    otherCount = 0;
                                    recordConnectionSample();
                                }
                                finishSample();
                            }

                            @Override
                            public void onFailure(ApiError e) {
                                finishSample();
                            }
                        });
                    }
                });
    }

    private void finishSample() {
        loading = false;
        tick++;
        if (isAdded()) redraw();
    }

    private void onTraffic(List<NetworkDevice> devices) {
        availableDevices.clear();
        NetworkDevice target = null;
        for (NetworkDevice d : devices) {
            if (d.isKernelPlaceholder()) continue;
            if (!(d.isPhysicalPort() || d.isBridge() || d.isVpn() || d.wireless)) continue;
            availableDevices.add(d.name);
            if (selectedDevice != null && selectedDevice.equals(d.name)) target = d;
        }
        if (target == null) {
            // 未选择时自动挑 WAN 侧:名字含 wan 的优先,否则第一块物理口
            for (NetworkDevice d : devices) {
                if (d.name.contains("wan")) {
                    target = d;
                    break;
                }
            }
            if (target == null) {
                for (NetworkDevice d : devices) {
                    if (d.isPhysicalPort()) {
                        target = d;
                        break;
                    }
                }
            }
            if (target != null && selectedDevice == null) selectedDevice = target.name;
        }
        if (target == null) return;

        long rx = target.stats.rxBytes;
        long tx = target.stats.txBytes;
        totalRx = rx;
        totalTx = tx;
        long now = System.currentTimeMillis();
        if (lastTime > 0 && now > lastTime && lastRx >= 0) {
            double dt = (now - lastTime) / 1000.0;
            double down = Math.max(0, (rx - lastRx) / dt);
            double up = Math.max(0, (tx - lastTx) / dt);
            addPoint(downloadPoints, (float) down);
            addPoint(uploadPoints, (float) up);
            peakDownload = Math.max(peakDownload, down);
            peakUpload = Math.max(peakUpload, up);
            sumDownload += down;
            sumUpload += up;
            trafficSamples++;
            primed = true;   // 有读数了,回到 3 秒节奏
        }
        lastRx = rx;
        lastTx = tx;
        lastTime = now;
    }

    private void onLoad(JsonObject info) {
        JsonElement loadEl = info.get("load");
        if (loadEl == null || !loadEl.isJsonArray()) return;
        JsonArray load = loadEl.getAsJsonArray();
        // ubus load 是定点数(<<16)
        if (load.size() > 0) load1 = load.get(0).getAsLong() / 65536.0;
        if (load.size() > 1) load5 = load.get(1).getAsLong() / 65536.0;
        if (load.size() > 2) load15 = load.get(2).getAsLong() / 65536.0;
        addPoint(loadPoints, (float) load1);
    }

    private void onConnections(JsonObject result) {
        tcpCount = 0;
        udpCount = 0;
        otherCount = 0;
        activeConnections.clear();
        JsonElement listEl = result.get("result");
        if (listEl != null && listEl.isJsonArray()) {
            List<Object[]> rows = new ArrayList<>();
            for (JsonElement el : listEl.getAsJsonArray()) {
                if (!el.isJsonObject()) continue;
                JsonObject entry = el.getAsJsonObject();
                String proto = str(entry, "layer4", "tcp").toUpperCase(Locale.ROOT);
                if ("TCP".equals(proto)) {
                    tcpCount++;
                } else if ("UDP".equals(proto)) {
                    udpCount++;
                } else {
                    otherCount++;
                }
                long bytes = num(entry, "bytes");
                rows.add(new Object[]{bytes, proto,
                        str(entry, "src", "0.0.0.0") + ":" + num(entry, "sport"),
                        str(entry, "dst", "0.0.0.0") + ":" + num(entry, "dport")});
            }
            rows.sort((a, b) -> Long.compare((Long) b[0], (Long) a[0]));
            for (int i = 0; i < Math.min(rows.size(), 10); i++) {
                Object[] r = rows.get(i);
                activeConnections.add(new String[]{
                        (String) r[1], r[2] + " → " + r[3], Formatters.bytes((Long) r[0])});
            }
        }
        recordConnectionSample();
    }

    private void recordConnectionSample() {
        addPoint(tcpPoints, tcpCount);
        addPoint(udpPoints, udpCount);
        addPoint(otherPoints, otherCount);
        peakTcp = Math.max(peakTcp, tcpCount);
        peakUdp = Math.max(peakUdp, udpCount);
        peakOther = Math.max(peakOther, otherCount);
        sumTcp += tcpCount;
        sumUdp += udpCount;
        sumOther += otherCount;
        connSamples++;
    }

    private void addPoint(List<Entry> series, float value) {
        series.add(new Entry(tick, value));
        while (series.size() > MAX_POINTS) {
            series.remove(0);
        }
    }

    // =====================================================================
    // 渲染
    // =====================================================================

    private void redraw() {
        if (!isAdded()) return;
        switch (mode) {
            case MODE_CONNECTIONS:
                drawConnections();
                break;
            case MODE_LOAD:
                drawLoad();
                break;
            default:
                drawTraffic();
        }
    }

    private void drawTraffic() {
        renderInterfaceChips();
        speedDown.setText(Formatters.rate(current(downloadPoints)));
        speedUp.setText(Formatters.rate(current(uploadPoints)));

        LineDataSet down = makeSet(new ArrayList<>(downloadPoints),
                getString(R.string.realtime_download), 0xFF007AFF);
        LineDataSet up = makeSet(new ArrayList<>(uploadPoints),
                getString(R.string.realtime_upload), 0xFF34C759);
        chart.setData(new LineData(down, up));
        chart.invalidate();

        statsContainer.removeAllViews();
        statsContainer.addView(statsHeader(R.string.realtime_stats));
        LinearLayout row = statsRow();
        row.addView(statColumn(getString(R.string.realtime_download),
                Formatters.rate(current(downloadPoints)),
                Formatters.rate(trafficSamples > 0 ? sumDownload / trafficSamples : 0),
                Formatters.rate(peakDownload)));
        row.addView(statColumn(getString(R.string.realtime_upload),
                Formatters.rate(current(uploadPoints)),
                Formatters.rate(trafficSamples > 0 ? sumUpload / trafficSamples : 0),
                Formatters.rate(peakUpload)));
        statsContainer.addView(row);

        extraContainer.removeAllViews();
        extraContainer.addView(statsHeader(R.string.realtime_totals));
        LinearLayout totals = statsRow();
        totals.addView(valueColumn(getString(R.string.realtime_total_rx),
                Formatters.bytes(totalRx)));
        totals.addView(valueColumn(getString(R.string.realtime_total_tx),
                Formatters.bytes(totalTx)));
        extraContainer.addView(totals);
    }

    /**
     * 接口选择用药丸形 Chip。
     *
     * 只在接口集合或选中项变化时重建 —— 这个方法每秒都会被 drawTraffic 调到,
     * 每次都 removeAllViews 再加回去的话,点击会被打断、滚动位置也会跳回开头。
     */
    private void renderInterfaceChips() {
        if (availableDevices.equals(renderedDevices)
                && java.util.Objects.equals(selectedDevice, renderedSelection)) {
            return;
        }
        renderedDevices.clear();
        renderedDevices.addAll(availableDevices);
        renderedSelection = selectedDevice;

        interfaceChips.removeAllViews();
        for (String name : availableDevices) {
            Chip chip = new Chip(requireContext());
            chip.setChipDrawable(ChipDrawable.createFromAttributes(requireContext(),
                    null, 0, com.google.android.material.R.style.Widget_MaterialComponents_Chip_Choice));
            chip.setText(name);
            chip.setTextSize(13);
            chip.setCheckable(true);
            chip.setChecked(name.equals(selectedDevice));
            chip.setOnClickListener(v -> selectInterface(name));
            interfaceChips.addView(chip);
        }
    }

    /** 换接口要清空差分基准与统计,否则第一帧会算出一个巨大的假速率 */
    private void selectInterface(String name) {
        if (name.equals(selectedDevice)) return;
        selectedDevice = name;
        renderedSelection = null;   // 强制下一帧重建,让选中态跟上
        primed = false;             // 基准被清了,重新快速预热一次
        lastRx = -1;
        lastTx = -1;
        lastTime = -1;
        downloadPoints.clear();
        uploadPoints.clear();
        peakDownload = 0;
        peakUpload = 0;
        sumDownload = 0;
        sumUpload = 0;
        trafficSamples = 0;
        redraw();
    }

    private void drawConnections() {
        LineDataSet tcp = makeSet(new ArrayList<>(tcpPoints), "TCP", 0xFF007AFF);
        LineDataSet udp = makeSet(new ArrayList<>(udpPoints), "UDP", 0xFF34C759);
        LineDataSet other = makeSet(new ArrayList<>(otherPoints),
                getString(R.string.realtime_other), 0xFFFF9500);
        chart.setData(new LineData(tcp, udp, other));
        chart.invalidate();

        statsContainer.removeAllViews();
        statsContainer.addView(statsHeader(R.string.realtime_stats));
        LinearLayout row = statsRow();
        row.addView(statColumn("TCP", String.valueOf(tcpCount),
                String.valueOf(connSamples > 0 ? sumTcp / connSamples : 0),
                String.valueOf(peakTcp)));
        row.addView(statColumn("UDP", String.valueOf(udpCount),
                String.valueOf(connSamples > 0 ? sumUdp / connSamples : 0),
                String.valueOf(peakUdp)));
        row.addView(statColumn(getString(R.string.realtime_other), String.valueOf(otherCount),
                String.valueOf(connSamples > 0 ? sumOther / connSamples : 0),
                String.valueOf(peakOther)));
        statsContainer.addView(row);

        extraContainer.removeAllViews();
        extraContainer.addView(statsHeader(R.string.realtime_active_connections));
        if (activeConnections.isEmpty()) {
            TextView empty = new TextView(requireContext());
            empty.setText(R.string.realtime_waiting);
            empty.setTextColor(requireContext().getColor(R.color.label_tertiary));
            empty.setTextSize(14);
            empty.setPadding(0, dp(8), 0, 0);
            extraContainer.addView(empty);
            return;
        }
        for (String[] conn : activeConnections) {
            LinearLayout row2 = new LinearLayout(requireContext());
            row2.setOrientation(LinearLayout.HORIZONTAL);
            row2.setPadding(0, dp(6), 0, dp(6));
            TextView proto = new TextView(requireContext());
            proto.setText(conn[0]);
            proto.setTextSize(12);
            proto.setTextColor(requireContext().getColor(R.color.ios_blue));
            proto.setWidth(dp(44));
            TextView detail = new TextView(requireContext());
            detail.setText(conn[1]);
            detail.setTextSize(12);
            detail.setSingleLine(true);
            detail.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            detail.setTextColor(requireContext().getColor(R.color.label_secondary));
            detail.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView bytes = new TextView(requireContext());
            bytes.setText(conn[2]);
            bytes.setTextSize(12);
            bytes.setTextColor(requireContext().getColor(R.color.label_tertiary));
            row2.addView(proto);
            row2.addView(detail);
            row2.addView(bytes);
            extraContainer.addView(row2);
        }
    }

    private void drawLoad() {
        LineDataSet set = makeSet(new ArrayList<>(loadPoints),
                getString(R.string.realtime_load), 0xFFFF3B30);
        chart.setData(new LineData(set));
        chart.invalidate();

        statsContainer.removeAllViews();
        statsContainer.addView(statsHeader(R.string.realtime_load_avg));
        LinearLayout row = statsRow();
        row.addView(valueColumn(getString(R.string.realtime_load_1),
                String.format(Locale.US, "%.2f", load1)));
        row.addView(valueColumn(getString(R.string.realtime_load_5),
                String.format(Locale.US, "%.2f", load5)));
        row.addView(valueColumn(getString(R.string.realtime_load_15),
                String.format(Locale.US, "%.2f", load15)));
        statsContainer.addView(row);
    }

    // =====================================================================
    // 视图工具
    // =====================================================================

    private TextView statsHeader(int titleRes) {
        TextView header = new TextView(requireContext());
        header.setText(titleRes);
        header.setTextSize(15);
        header.setTypeface(header.getTypeface(), Typeface.BOLD);
        header.setTextColor(requireContext().getColor(R.color.label_primary));
        return header;
    }

    private LinearLayout statsRow() {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        row.setLayoutParams(lp);
        return row;
    }

    /** 一列:标题 + 当前 / 平均 / 峰值 */
    private View statColumn(String title, String current, String avg, String peak) {
        LinearLayout column = new LinearLayout(requireContext());
        column.setOrientation(LinearLayout.VERTICAL);
        column.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        column.addView(smallText(title, R.color.label_secondary, 12, true));
        column.addView(smallText(getString(R.string.realtime_current, current),
                R.color.label_primary, 13, false));
        column.addView(smallText(getString(R.string.realtime_avg, avg),
                R.color.label_secondary, 12, false));
        column.addView(smallText(getString(R.string.realtime_peak, peak),
                R.color.label_secondary, 12, false));
        return column;
    }

    /** 一列:标题 + 一个值 */
    private View valueColumn(String title, String value) {
        LinearLayout column = new LinearLayout(requireContext());
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView t = smallText(title, R.color.label_secondary, 12, false);
        t.setGravity(Gravity.CENTER);
        TextView v = smallText(value, R.color.label_primary, 17, true);
        v.setGravity(Gravity.CENTER);
        column.addView(t);
        column.addView(v);
        return column;
    }

    private TextView smallText(String text, int colorRes, int size, boolean bold) {
        TextView view = new TextView(requireContext());
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(requireContext().getColor(colorRes));
        if (bold) view.setTypeface(view.getTypeface(), Typeface.BOLD);
        view.setPadding(0, dp(2), 0, 0);
        return view;
    }

    private float current(List<Entry> series) {
        return series.isEmpty() ? 0 : series.get(series.size() - 1).getY();
    }

    private LineDataSet makeSet(List<Entry> entries, String label, int color) {
        LineDataSet set = new LineDataSet(entries, label);
        set.setColor(color);
        set.setLineWidth(2f);
        set.setDrawCircles(false);
        set.setDrawValues(false);
        set.setMode(LineDataSet.Mode.CUBIC_BEZIER);
        set.setDrawFilled(true);
        set.setFillColor(color);
        set.setFillAlpha(40);
        return set;
    }

    private void styleChart(LineChart chart) {
        Description desc = new Description();
        desc.setText("");
        chart.setDescription(desc);
        chart.setTouchEnabled(false);
        chart.setDrawGridBackground(false);
        chart.setMinOffset(4f);
        chart.setNoDataText(getString(R.string.realtime_waiting));

        int gridColor = 0x22888888;
        int textColor = requireContext().getColor(R.color.label_secondary);

        XAxis x = chart.getXAxis();
        x.setDrawLabels(false);
        x.setDrawGridLines(false);
        x.setDrawAxisLine(false);

        YAxis left = chart.getAxisLeft();
        left.setTextColor(textColor);
        left.setGridColor(gridColor);
        left.setDrawAxisLine(false);
        left.setAxisMinimum(0f);

        chart.getAxisRight().setEnabled(false);
        Legend legend = chart.getLegend();
        legend.setTextColor(textColor);
        legend.setEnabled(true);
    }

    private static String str(JsonObject o, String key, String def) {
        JsonElement e = o.get(key);
        return e != null && !e.isJsonNull() && e.isJsonPrimitive() ? e.getAsString() : def;
    }

    private static long num(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return 0;
        try {
            return e.getAsLong();
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
