package com.whykangkang.wrthub.ui.settings;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.whykangkang.wrthub.R;
import com.whykangkang.wrthub.manager.HomeMetricsConfig;
import com.whykangkang.wrthub.manager.HomeMetricsConfig.HomeMetric;

import java.util.List;

/**
 * 首页编辑:6 个指标方块的显隐开关,对应 iOS HomeMetricsEditViewController。
 * 最少保留 2 个,再关会被拦下并提示。
 */
public class HomeMetricsEditActivity extends AppCompatActivity {

    private LinearLayout host;
    private TextView counter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home_metrics);
        host = findViewById(R.id.metrics_container);
        counter = findViewById(R.id.metrics_counter);
        render();
    }

    private void render() {
        host.removeAllViews();
        List<HomeMetric> visible = HomeMetricsConfig.visible(this);
        counter.setText(getString(R.string.home_metrics_counter,
                visible.size(), HomeMetric.values().length));
        LayoutInflater inflater = LayoutInflater.from(this);
        HomeMetric[] all = HomeMetric.values();
        for (int i = 0; i < all.length; i++) {
            HomeMetric metric = all[i];
            View row = inflater.inflate(R.layout.view_switch_row, host, false);
            ((TextView) row.findViewById(R.id.row_label)).setText(metric.titleRes);
            SwitchMaterial toggle = row.findViewById(R.id.row_switch);
            toggle.setChecked(visible.contains(metric));
            toggle.setOnClickListener(v -> {
                if (!HomeMetricsConfig.toggle(this, metric)) {
                    toggle.setChecked(true);
                    new MaterialAlertDialogBuilder(this)
                            .setMessage(getString(R.string.home_metrics_min,
                                    HomeMetricsConfig.MIN_VISIBLE))
                            .setPositiveButton(R.string.ok, null)
                            .show();
                    return;
                }
                render();
            });
            host.addView(row);
            if (i < all.length - 1) host.addView(separator());
        }
    }

    private View separator() {
        View line = new View(this);
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.setMarginStart(Math.round(16 * getResources().getDisplayMetrics().density));
        line.setLayoutParams(lp);
        line.setBackgroundColor(getColor(R.color.separator));
        return line;
    }
}
