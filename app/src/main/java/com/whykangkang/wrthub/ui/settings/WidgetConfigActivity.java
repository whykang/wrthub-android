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
import com.whykangkang.wrthub.widget.WidgetSnapshot;
import com.whykangkang.wrthub.widget.WidgetSnapshot.Metric;

import java.util.ArrayList;
import java.util.List;

/**
 * 设置 → 小组件设置,对应 iOS WidgetConfigViewController。
 * 选择桌面小组件的主参数(大字显示位)与要展示的指标(最多 4 个)。
 */
public class WidgetConfigActivity extends AppCompatActivity {

    private LinearLayout primaryHost;
    private LinearLayout itemsHost;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_widget_config);
        primaryHost = findViewById(R.id.primary_container);
        itemsHost = findViewById(R.id.items_container);
        render();
    }

    private void render() {
        LayoutInflater inflater = LayoutInflater.from(this);
        Metric primary = WidgetSnapshot.primary(this);
        List<Metric> items = WidgetSnapshot.items(this);

        primaryHost.removeAllViews();
        itemsHost.removeAllViews();
        Metric[] all = Metric.values();
        for (int i = 0; i < all.length; i++) {
            Metric metric = all[i];

            View primaryRow = inflater.inflate(R.layout.view_value_row, primaryHost, false);
            ((TextView) primaryRow.findViewById(R.id.row_label)).setText(labelRes(metric));
            TextView mark = primaryRow.findViewById(R.id.row_value);
            mark.setText(metric == primary ? "✓" : "");
            mark.setTextColor(getColor(R.color.ios_blue));
            primaryRow.setOnClickListener(v -> {
                WidgetSnapshot.setPrimary(this, metric);
                render();
            });
            primaryHost.addView(primaryRow);

            View itemRow = inflater.inflate(R.layout.view_switch_row, itemsHost, false);
            ((TextView) itemRow.findViewById(R.id.row_label)).setText(labelRes(metric));
            SwitchMaterial toggle = itemRow.findViewById(R.id.row_switch);
            toggle.setChecked(items.contains(metric));
            toggle.setOnClickListener(v -> toggleItem(metric, toggle));
            itemsHost.addView(itemRow);

            if (i < all.length - 1) {
                primaryHost.addView(separator());
                itemsHost.addView(separator());
            }
        }
    }

    private void toggleItem(Metric metric, SwitchMaterial toggle) {
        List<Metric> items = new ArrayList<>(WidgetSnapshot.items(this));
        if (items.contains(metric)) {
            if (items.size() <= 1) {
                toggle.setChecked(true);
                tip(getString(R.string.widget_min_items));
                return;
            }
            items.remove(metric);
        } else {
            if (items.size() >= WidgetSnapshot.MAX_ITEMS) {
                toggle.setChecked(false);
                tip(getString(R.string.widget_max_items, WidgetSnapshot.MAX_ITEMS));
                return;
            }
            items.add(metric);
        }
        WidgetSnapshot.setItems(this, items);
        render();
    }

    private void tip(String message) {
        new MaterialAlertDialogBuilder(this)
                .setMessage(message)
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    private int labelRes(Metric metric) {
        switch (metric) {
            case LATENCY:
                return R.string.metric_latency;
            case DEVICES:
                return R.string.metric_devices;
            case CPU:
                return R.string.metric_cpu;
            case MEMORY:
                return R.string.metric_memory;
            case SPEED:
            default:
                return R.string.metric_speed;
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
