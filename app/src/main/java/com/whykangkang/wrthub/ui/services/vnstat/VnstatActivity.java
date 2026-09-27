package com.whykangkang.wrthub.ui.services.vnstat;

import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.github.mikephil.charting.charts.BarChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.data.BarData;
import com.github.mikephil.charting.data.BarDataSet;
import com.github.mikephil.charting.data.BarEntry;
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter;
import com.github.mikephil.charting.formatter.ValueFormatter;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.gson.JsonObject;
import com.whykangkang.wrthub.R;
import com.whykangkang.wrthub.api.ApiCallback;
import com.whykangkang.wrthub.api.ApiError;
import com.whykangkang.wrthub.api.VnstatApi;
import com.whykangkang.wrthub.util.Formatters;

import java.util.ArrayList;
import java.util.List;

import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

/**
 * vnStat 流量监控。
 *
 * 数据走 {@code vnstat --json},自己渲染 —— 网页端画的是 vnstati 生成的
 * 固定尺寸 PNG,那东西在手机上既不能缩放也不跟深色模式。
 */
public class VnstatActivity extends AppCompatActivity {

    /** 图表最多画多少根柱子,再多手机上挤成一团 */
    private static final int MAX_BARS = 24;

    private TextView ifaceLabel, statusLabel, totalRx, totalTx, totalAll, avgRate, emptyLabel;
    private LinearLayout rows, summary;
    private BarChart chart;
    private MaterialButtonToggleGroup periodGroup;
    private View btnReset;
    private SwipeRefreshLayout swipeRefresh;

    private final List<VnstatApi.Interface> interfaces = new ArrayList<>();
    private int selected;
    private boolean statusKnown;
    private boolean running;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_vnstat);

        ifaceLabel = findViewById(R.id.vnstat_iface);
        statusLabel = findViewById(R.id.vnstat_status);
        totalRx = findViewById(R.id.vnstat_total_rx);
        totalTx = findViewById(R.id.vnstat_total_tx);
        totalAll = findViewById(R.id.vnstat_total_all);
        avgRate = findViewById(R.id.vnstat_avg_rate);
        emptyLabel = findViewById(R.id.vnstat_empty);
        rows = findViewById(R.id.vnstat_rows);
        summary = findViewById(R.id.vnstat_summary);
        chart = findViewById(R.id.vnstat_chart);
        periodGroup = findViewById(R.id.group_period);
        btnReset = findViewById(R.id.btn_reset);
        btnReset.setOnClickListener(v -> confirmReset());
        swipeRefresh = findViewById(R.id.swipe_refresh);

        periodGroup.check(R.id.period_day);
        periodGroup.addOnButtonCheckedListener((g, id, checked) -> {
            if (checked) render();
        });
        findViewById(R.id.btn_pick_iface).setOnClickListener(v -> pickInterface());
        findViewById(R.id.btn_config).setOnClickListener(v -> openConfig());
        swipeRefresh.setOnRefreshListener(this::load);
        setupChart();
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    // =====================================================================
    // 载入
    // =====================================================================

    private void load() {
        swipeRefresh.setRefreshing(true);
        VnstatApi.serviceRunning(new ApiCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean value) {
                statusKnown = true;
                running = value;
                renderStatus();
            }

            @Override
            public void onFailure(ApiError error) {
                statusKnown = false;
                renderStatus();
            }
        });
        VnstatApi.loadTraffic(new ApiCallback<List<VnstatApi.Interface>>() {
            @Override
            public void onSuccess(List<VnstatApi.Interface> list) {
                if (isFinishing() || isDestroyed()) return;
                swipeRefresh.setRefreshing(false);
                interfaces.clear();
                interfaces.addAll(list);
                if (selected >= interfaces.size()) selected = 0;
                render();
            }

            @Override
            public void onFailure(ApiError error) {
                if (isFinishing() || isDestroyed()) return;
                swipeRefresh.setRefreshing(false);
                interfaces.clear();
                render();
                if (error.getType() == ApiError.Type.CONFIG_NOT_FOUND
                        || error.getType() == ApiError.Type.PERMISSION_DENIED) {
                    showEmpty(getString(R.string.vnstat_not_installed));
                } else {
                    showEmpty(getString(R.string.vnstat_load_failed, error.getMessage()));
                }
            }
        });
    }

    // =====================================================================
    // 渲染
    // =====================================================================

    private void renderStatus() {
        if (!statusKnown) {
            statusLabel.setText(R.string.fb_status_unknown);
            statusLabel.setTextColor(getColor(R.color.label_secondary));
            return;
        }
        statusLabel.setText(running ? R.string.fb_running : R.string.vnstat_not_running);
        statusLabel.setTextColor(getColor(running ? R.color.ios_green : R.color.ios_red));
    }

    private void render() {
        rows.removeAllViews();
        if (interfaces.isEmpty()) {
            summary.removeAllViews();
            ifaceLabel.setText("—");
            totalRx.setText("—");
            totalTx.setText("—");
            totalAll.setText("—");
            avgRate.setText("—");
            chart.setVisibility(View.GONE);
            btnReset.setVisibility(View.GONE);
            showEmpty(getString(R.string.vnstat_no_interfaces));
            return;
        }
        emptyLabel.setVisibility(View.GONE);
        btnReset.setVisibility(View.VISIBLE);
        VnstatApi.Interface iface = interfaces.get(selected);
        ifaceLabel.setText(iface.name);
        totalRx.setText(Formatters.bytes(iface.totalRx));
        totalTx.setText(Formatters.bytes(iface.totalTx));
        totalAll.setText(Formatters.bytes(iface.totalRx + iface.totalTx));
        // 累计平均 = 总流量 ÷ 实际记录时长;刚建库时跨度为 0,显示占位
        double avg = iface.avgBitsPerSecond();
        avgRate.setText(avg > 0 ? Formatters.bitrate(avg) : "—");
        renderSummary(iface);

        List<VnstatApi.Entry> entries = currentEntries(iface);
        if (entries.isEmpty()) {
            chart.setVisibility(View.GONE);
            showEmpty(getString(R.string.vnstat_no_data));
            return;
        }
        chart.setVisibility(View.VISIBLE);
        renderChart(entries);
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) rows.addView(separator());
            rows.addView(entryRow(entries.get(i)));
        }
    }

    private List<VnstatApi.Entry> currentEntries(VnstatApi.Interface iface) {
        int id = periodGroup.getCheckedButtonId();
        if (id == R.id.period_5min) return iface.fiveMinutes;
        if (id == R.id.period_hour) return iface.hours;
        if (id == R.id.period_month) return iface.months;
        return iface.days;
    }

    /**
     * 摘要:今日 / 本月 / 累计,外加流量最高的几天。
     * 对应网页端 vnstati 的 Summary 和 Top 两个视图。
     */
    private void renderSummary(VnstatApi.Interface iface) {
        summary.removeAllViews();
        // days/months 已经是最近在前,取第 0 条就是今日/本月
        VnstatApi.Entry today = iface.days.isEmpty() ? null : iface.days.get(0);
        VnstatApi.Entry month = iface.months.isEmpty() ? null : iface.months.get(0);

        addSummaryRow(getString(R.string.vnstat_today), today);
        summary.addView(separator());
        addSummaryRow(getString(R.string.vnstat_this_month), month);
        summary.addView(separator());
        addSummaryRow(getString(R.string.vnstat_all_time),
                new VnstatApi.Entry("", iface.totalRx, iface.totalTx));

        if (!iface.tops.isEmpty()) {
            summary.addView(separator());
            summary.addView(sectionLabel(getString(R.string.vnstat_top_days)));
            // top 是按流量降序的,parseJson 统一反转过,最后一条就是最高的那天
            summary.addView(entryRow(iface.tops.get(iface.tops.size() - 1)));
        }
        if (!iface.created.isEmpty() || !iface.updated.isEmpty()) {
            summary.addView(separator());
            StringBuilder sb = new StringBuilder();
            if (!iface.created.isEmpty()) {
                sb.append(getString(R.string.vnstat_since, iface.created));
            }
            if (!iface.updated.isEmpty()) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(getString(R.string.vnstat_updated, iface.updated));
            }
            summary.addView(sectionLabel(sb.toString()));
        }
    }

    private void addSummaryRow(String title, VnstatApi.Entry entry) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(10), dp(16), dp(10));

        TextView label = new TextView(this);
        label.setText(title);
        label.setTextSize(15);
        label.setTextColor(getColor(R.color.label_primary));
        label.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);

        TextView value = new TextView(this);
        value.setTextSize(13);
        value.setTextColor(getColor(R.color.label_secondary));
        if (entry == null) {
            value.setText("—");
        } else {
            value.setText(getString(R.string.vnstat_row_value,
                    Formatters.bytes(entry.rx), Formatters.bytes(entry.tx),
                    Formatters.bytes(entry.total())));
        }
        right.addView(value);

        if (entry != null && entry.periodSeconds > 0) {
            TextView rate = new TextView(this);
            rate.setText(Formatters.bitrate(entry.avgBitsPerSecond()));
            rate.setTextSize(12);
            rate.setTextColor(getColor(R.color.label_tertiary));
            rate.setGravity(Gravity.END);
            right.addView(rate);
        }

        row.addView(label);
        row.addView(right);
        summary.addView(row);
    }

    private View sectionLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(12);
        label.setTextColor(getColor(R.color.label_secondary));
        label.setPadding(dp(16), dp(10), dp(16), dp(6));
        return label;
    }

    private void showEmpty(String message) {
        emptyLabel.setText(message);
        emptyLabel.setVisibility(View.VISIBLE);
    }

    private void setupChart() {
        chart.getDescription().setEnabled(false);
        chart.setDrawGridBackground(false);
        chart.setScaleYEnabled(false);
        chart.getAxisRight().setEnabled(false);
        chart.getLegend().setTextColor(getColor(R.color.label_secondary));
        chart.getAxisLeft().setTextColor(getColor(R.color.label_secondary));
        chart.getAxisLeft().setAxisMinimum(0f);
        chart.getAxisLeft().setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return Formatters.bytes((long) value);
            }
        });
        XAxis x = chart.getXAxis();
        x.setPosition(XAxis.XAxisPosition.BOTTOM);
        x.setDrawGridLines(false);
        x.setTextColor(getColor(R.color.label_secondary));
        x.setGranularity(1f);
    }

    /** 收/发画成一组并排的柱子,和总览页的配色保持一致 */
    private void renderChart(List<VnstatApi.Entry> all) {
        // 最近的在列表最前面,画图要按时间从左到右,所以取前 N 条再反过来
        List<VnstatApi.Entry> entries = new ArrayList<>(
                all.subList(0, Math.min(all.size(), MAX_BARS)));
        java.util.Collections.reverse(entries);

        List<BarEntry> rx = new ArrayList<>();
        List<BarEntry> tx = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            rx.add(new BarEntry(i, entries.get(i).rx));
            tx.add(new BarEntry(i, entries.get(i).tx));
            labels.add(entries.get(i).label);
        }
        BarDataSet rxSet = new BarDataSet(rx, getString(R.string.vnstat_rx));
        rxSet.setColor(getColor(R.color.ios_green));
        rxSet.setDrawValues(false);
        BarDataSet txSet = new BarDataSet(tx, getString(R.string.vnstat_tx));
        txSet.setColor(getColor(R.color.ios_blue));
        txSet.setDrawValues(false);

        BarData data = new BarData(rxSet, txSet);
        // 一组两根柱子:组宽 + 两根柱宽 + 两侧间隙 必须正好等于 1
        float barWidth = 0.38f;
        float barSpace = 0.02f;
        float groupSpace = 1f - (barWidth + barSpace) * 2;
        data.setBarWidth(barWidth);
        chart.setData(data);
        chart.getXAxis().setValueFormatter(new IndexAxisValueFormatter(labels));
        chart.getXAxis().setLabelCount(Math.min(labels.size(), 6));
        chart.getXAxis().setAxisMinimum(0f);
        chart.getXAxis().setAxisMaximum(entries.size());
        chart.groupBars(0f, groupSpace, barSpace);
        chart.invalidate();
    }

    private View entryRow(VnstatApi.Entry entry) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(10), dp(16), dp(10));

        TextView label = new TextView(this);
        label.setText(entry.label);
        label.setTextSize(14);
        label.setTextColor(getColor(R.color.label_primary));
        label.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);

        TextView value = new TextView(this);
        value.setText(getString(R.string.vnstat_row_value,
                Formatters.bytes(entry.rx), Formatters.bytes(entry.tx),
                Formatters.bytes(entry.total())));
        value.setTextSize(13);
        value.setTextColor(getColor(R.color.label_secondary));
        right.addView(value);

        if (entry.periodSeconds > 0) {
            TextView rate = new TextView(this);
            rate.setText(Formatters.bitrate(entry.avgBitsPerSecond()));
            rate.setTextSize(12);
            rate.setTextColor(getColor(R.color.label_tertiary));
            rate.setGravity(Gravity.END);
            right.addView(rate);
        }

        row.addView(label);
        row.addView(right);
        return row;
    }

    private View separator() {
        View line = new View(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.setMarginStart(dp(16));
        line.setLayoutParams(lp);
        line.setBackgroundColor(getColor(R.color.separator));
        return line;
    }

    // =====================================================================
    // 交互
    // =====================================================================

    private void pickInterface() {
        if (interfaces.isEmpty()) {
            alert(getString(R.string.vnstat_no_interfaces));
            return;
        }
        CharSequence[] names = new CharSequence[interfaces.size()];
        for (int i = 0; i < names.length; i++) names[i] = interfaces.get(i).name;
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.vnstat_switch_iface)
                .setSingleChoiceItems(names, selected, (d, which) -> {
                    selected = which;
                    render();
                    d.dismiss();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** 配置监控哪些接口(uci vnstat 的 interface 列表) */
    private void openConfig() {
        startActivity(new android.content.Intent(this, VnstatConfigActivity.class));
    }

    /**
     * 清空当前接口的历史数据。
     *
     * 走两步确认:这是**不可撤销**的删除,而且第一步很容易在滑动时误触。
     * 第二步的按钮明确写「永久删除」,不用含糊的「确定」。
     */
    private void confirmReset() {
        if (interfaces.isEmpty()) return;
        String name = interfaces.get(selected).name;
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.vnstat_reset)
                .setMessage(getString(R.string.vnstat_reset_confirm1, name))
                .setPositiveButton(R.string.vnstat_reset_continue, (d, w) -> confirmResetAgain(name))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void confirmResetAgain(String name) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.vnstat_reset_final_title)
                .setMessage(getString(R.string.vnstat_reset_confirm2, name))
                .setPositiveButton(R.string.vnstat_reset_do, (d, w) -> performReset(name))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void performReset(String name) {
        btnReset.setEnabled(false);
        swipeRefresh.setRefreshing(true);
        VnstatApi.resetInterface(name, new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                if (isFinishing() || isDestroyed()) return;
                btnReset.setEnabled(true);
                alert(getString(R.string.vnstat_reset_done, name));
                load();
            }

            @Override
            public void onFailure(ApiError error) {
                if (isFinishing() || isDestroyed()) return;
                btnReset.setEnabled(true);
                swipeRefresh.setRefreshing(false);
                alert(getString(R.string.msg_action_failed, error.getMessage()));
            }
        });
    }

    private void alert(String message) {
        if (isFinishing() || isDestroyed()) return;
        new MaterialAlertDialogBuilder(this)
                .setMessage(message)
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
