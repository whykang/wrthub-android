package com.whykangkang.wrthub.ui.services.speedtest;

import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.whykangkang.wrthub.R;
import com.whykangkang.wrthub.api.OpenWrtApi;
import com.whykangkang.wrthub.api.SpeedTestApi;
import com.whykangkang.wrthub.manager.SpeedTestHistory;
import com.whykangkang.wrthub.util.Formatters;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 局域网测速:手机 ↔ 路由器。
 *
 * 页面第一屏就把范围讲清楚 —— 这不是宽带测速,很容易被误会成 Ookla 那种。
 */
public class SpeedTestActivity extends AppCompatActivity {

    private TextView phaseLabel, liveValue, downValue, upValue, latencyValue;
    private ProgressBar progress;
    private MaterialButton btnStart;
    private View historyHeader, historyCard;
    private LinearLayout historyRows;

    private SpeedTestApi test;
    private boolean running;
    /** 记录按路由器分开存,换设备结果没有可比性 */
    private String host;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_speedtest);

        phaseLabel = findViewById(R.id.speed_phase);
        liveValue = findViewById(R.id.speed_live);
        downValue = findViewById(R.id.speed_down);
        upValue = findViewById(R.id.speed_up);
        latencyValue = findViewById(R.id.speed_latency);
        progress = findViewById(R.id.speed_progress);
        btnStart = findViewById(R.id.btn_start);
        historyHeader = findViewById(R.id.history_header);
        historyCard = findViewById(R.id.history_card);
        historyRows = findViewById(R.id.history_rows);
        btnStart.setOnClickListener(v -> toggle());
        findViewById(R.id.btn_clear_history).setOnClickListener(v -> confirmClear());

        host = OpenWrtApi.getInstance().getHost();
        renderHistory();
    }

    // =====================================================================
    // 历史记录
    // =====================================================================

    private void renderHistory() {
        List<SpeedTestHistory.Record> records = SpeedTestHistory.load(this, host);
        historyRows.removeAllViews();
        boolean has = !records.isEmpty();
        historyHeader.setVisibility(has ? View.VISIBLE : View.GONE);
        historyCard.setVisibility(has ? View.VISIBLE : View.GONE);
        if (!has) return;

        SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
        for (int i = 0; i < records.size(); i++) {
            if (i > 0) historyRows.addView(separator());
            historyRows.addView(historyRow(records.get(i), fmt));
        }
    }

    private View historyRow(SpeedTestHistory.Record record, SimpleDateFormat fmt) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(10), dp(16), dp(10));

        TextView time = new TextView(this);
        time.setText(fmt.format(new Date(record.timestamp)));
        time.setTextSize(13);
        time.setTextColor(getColor(R.color.label_secondary));
        time.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView value = new TextView(this);
        value.setText(getString(R.string.speedtest_history_row,
                Formatters.bitrate(record.downBps), Formatters.bitrate(record.upBps),
                String.format(Locale.US, "%.0f", record.latencyMs)));
        value.setTextSize(13);
        value.setTextColor(getColor(R.color.label_primary));

        row.addView(time);
        row.addView(value);
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

    private void confirmClear() {
        new MaterialAlertDialogBuilder(this)
                .setMessage(R.string.speedtest_clear_confirm)
                .setPositiveButton(R.string.action_delete, (d, w) -> {
                    SpeedTestHistory.clear(this, host);
                    renderHistory();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        // 离开页面要停掉,否则后台还在占满 WiFi
        if (test != null) test.cancel();
        super.onDestroy();
    }

    private void toggle() {
        if (running) {
            test.cancel();
            finishRun();
            phaseLabel.setText(R.string.speedtest_cancelled);
            return;
        }
        start();
    }

    private void start() {
        running = true;
        btnStart.setText(R.string.speedtest_stop);
        progress.setVisibility(View.VISIBLE);
        liveValue.setText(R.string.placeholder_value);
        downValue.setText(R.string.placeholder_value);
        upValue.setText(R.string.placeholder_value);
        latencyValue.setText(R.string.placeholder_value);

        test = new SpeedTestApi();
        test.start(new SpeedTestApi.Listener() {
            @Override
            public void onPhase(SpeedTestApi.Phase phase) {
                if (isFinishing() || isDestroyed()) return;
                switch (phase) {
                    case LATENCY:
                        phaseLabel.setText(R.string.speedtest_phase_latency);
                        break;
                    case DOWNLOAD:
                        phaseLabel.setText(R.string.speedtest_phase_download);
                        liveValue.setTextColor(getColor(R.color.ios_blue));
                        break;
                    case UPLOAD:
                        phaseLabel.setText(R.string.speedtest_phase_upload);
                        liveValue.setTextColor(getColor(R.color.ios_green));
                        break;
                    default:
                        phaseLabel.setText(R.string.speedtest_phase_done);
                        break;
                }
            }

            @Override
            public void onSample(SpeedTestApi.Phase phase, double bitsPerSecond) {
                if (isFinishing() || isDestroyed()) return;
                liveValue.setText(shortRate(bitsPerSecond));
                // 阶段内的实时值也写进对应的小格,跑的过程中就能看到趋势
                if (phase == SpeedTestApi.Phase.DOWNLOAD) {
                    downValue.setText(Formatters.bitrate(bitsPerSecond));
                } else if (phase == SpeedTestApi.Phase.UPLOAD) {
                    upValue.setText(Formatters.bitrate(bitsPerSecond));
                }
            }

            @Override
            public void onFinished(SpeedTestApi.Result result) {
                if (isFinishing() || isDestroyed()) return;
                finishRun();
                SpeedTestHistory.add(SpeedTestActivity.this, host,
                        new SpeedTestHistory.Record(System.currentTimeMillis(),
                                result.downBps, result.upBps,
                                result.latencyMs, result.jitterMs));
                renderHistory();
                downValue.setText(Formatters.bitrate(result.downBps));
                upValue.setText(Formatters.bitrate(result.upBps));
                latencyValue.setText(String.format(Locale.US, "%.1f ms", result.latencyMs));
                liveValue.setText(shortRate(Math.max(result.downBps, result.upBps)));
                phaseLabel.setText(getString(R.string.speedtest_done_jitter,
                        String.format(Locale.US, "%.1f", result.jitterMs)));
            }

            @Override
            public void onError(String message) {
                if (isFinishing() || isDestroyed()) return;
                finishRun();
                phaseLabel.setText(R.string.speedtest_idle);
                new MaterialAlertDialogBuilder(SpeedTestActivity.this)
                        .setMessage(getString(R.string.speedtest_failed, message))
                        .setPositiveButton(R.string.ok, null)
                        .show();
            }
        });
    }

    private void finishRun() {
        running = false;
        btnStart.setText(R.string.speedtest_start);
        progress.setVisibility(View.INVISIBLE);
    }

    /** 大字只显示数值,单位放小字里会更好读;这里简化成同一串 */
    private String shortRate(double bitsPerSecond) {
        return Formatters.bitrate(bitsPerSecond);
    }
}
