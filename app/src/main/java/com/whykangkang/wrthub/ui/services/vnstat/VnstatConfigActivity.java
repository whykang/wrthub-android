package com.whykangkang.wrthub.ui.services.vnstat;

import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.whykangkang.wrthub.R;
import com.whykangkang.wrthub.api.ApiCallback;
import com.whykangkang.wrthub.api.ApiError;
import com.whykangkang.wrthub.api.OpenWrtApi;
import com.whykangkang.wrthub.api.VnstatApi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 配置 vnStat 监控哪些网络接口。
 *
 * 对应 uci {@code vnstat.@vnstat[0].interface}(**列表**选项)。
 * 候选来自三处并集:当前已配置的、数据库里已有记录的、系统上存在的网络设备。
 */
public class VnstatConfigActivity extends AppCompatActivity {

    private LinearLayout rows;
    private TextView statusLabel;
    private MaterialButton btnSave;

    /** uci 里那个匿名 section 的名字,写回要用 */
    private String section;
    private final List<String> candidates = new ArrayList<>();
    private final Set<String> monitored = new LinkedHashSet<>();
    private final Set<String> original = new LinkedHashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_vnstat_config);
        rows = findViewById(R.id.iface_rows);
        statusLabel = findViewById(R.id.config_status);
        btnSave = findViewById(R.id.btn_save);
        btnSave.setOnClickListener(v -> save());
        load();
    }

    private void load() {
        setStatus(getString(R.string.fb_preview_loading));
        OpenWrtApi.getInstance().uciGetConfig(VnstatApi.CONFIG, new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject uci) {
                if (isFinishing() || isDestroyed()) return;
                section = VnstatApi.findSection(uci);
                monitored.clear();
                monitored.addAll(VnstatApi.parseMonitored(uci));
                original.clear();
                original.addAll(monitored);
                loadCandidates();
            }

            @Override
            public void onFailure(ApiError error) {
                if (isFinishing() || isDestroyed()) return;
                // 配置文件不存在 ≠ 没装 vnStat:全新安装时 /etc/config/vnstat 可能还没有,
                // 这种情况照常把候选接口列出来,保存时再建段。
                // 其它错误要**照实说**,别一律扣上「没有 vnStat」的帽子 ——
                // 权限不足、网络超时都会被这句话盖掉,人根本不知道该查什么。
                if (error.getType() == ApiError.Type.CONFIG_NOT_FOUND) {
                    section = null;
                    monitored.clear();
                    original.clear();
                    loadCandidates();
                } else {
                    setStatus(getString(R.string.vnstat_config_failed,
                            error.getType() + ": " + error.getMessage()));
                }
            }
        });
    }

    /** 候选 = 已配置 ∪ 数据库里有记录的 ∪ 系统上的网络设备 */
    private void loadCandidates() {
        VnstatApi.loadDatabaseInterfaces(new ApiCallback<List<String>>() {
            @Override
            public void onSuccess(List<String> dbList) {
                loadDevices(dbList);
            }

            @Override
            public void onFailure(ApiError error) {
                loadDevices(new ArrayList<>());
            }
        });
    }

    private void loadDevices(List<String> dbList) {
        OpenWrtApi.getInstance().getNetworkDevices(new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                finishLoading(dbList, deviceNames(result));
            }

            @Override
            public void onFailure(ApiError error) {
                finishLoading(dbList, new ArrayList<>());
            }
        });
    }

    /** 可单测:luci-rpc getNetworkDevices 的响应 → 设备名列表 */
    static List<String> deviceNames(JsonObject result) {
        List<String> names = new ArrayList<>();
        if (result == null) return names;
        for (String key : result.keySet()) {
            JsonElement el = result.get(key);
            if (!el.isJsonObject()) continue;
            JsonObject dev = el.getAsJsonObject();
            // 回环和已下线的虚拟口没有统计意义
            if ("lo".equals(key)) continue;
            String name = dev.has("device") && !dev.get("device").isJsonNull()
                    ? dev.get("device").getAsString() : key;
            if (!name.isEmpty() && !names.contains(name)) names.add(name);
        }
        Collections.sort(names);
        return names;
    }

    private void finishLoading(List<String> dbList, List<String> devices) {
        if (isFinishing() || isDestroyed()) return;
        Set<String> all = new LinkedHashSet<>();
        all.addAll(monitored);   // 已配置的排最前,用户一眼看到自己选了什么
        all.addAll(dbList);
        all.addAll(devices);
        candidates.clear();
        candidates.addAll(all);
        setStatus(null);
        renderRows();
    }

    private void renderRows() {
        rows.removeAllViews();
        if (candidates.isEmpty()) {
            setStatus(getString(R.string.vnstat_no_devices));
            return;
        }
        for (int i = 0; i < candidates.size(); i++) {
            if (i > 0) rows.addView(separator());
            rows.addView(ifaceRow(candidates.get(i)));
        }
    }

    private View ifaceRow(String name) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(6), dp(16), dp(6));
        row.setMinimumHeight(dp(48));

        TextView label = new TextView(this);
        label.setText(name);
        label.setTextSize(16);
        label.setTextColor(getColor(R.color.label_primary));
        label.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        SwitchMaterial toggle = new SwitchMaterial(this);
        toggle.setChecked(monitored.contains(name));
        toggle.setOnCheckedChangeListener((v, checked) -> {
            if (checked) {
                monitored.add(name);
            } else {
                monitored.remove(name);
            }
        });

        row.addView(label);
        row.addView(toggle);
        return row;
    }

    private void save() {
        if (monitored.equals(original)) {
            alert(getString(R.string.wifi_nothing_changed));
            return;
        }
        btnSave.setEnabled(false);
        setStatus(getString(R.string.vnstat_saving));
        // 配置段可能还不存在(全新安装),先确保有一个再写
        VnstatApi.ensureSection(new ApiCallback<String>() {
            @Override
            public void onSuccess(String name) {
                section = name;
                doSave();
            }

            @Override
            public void onFailure(ApiError error) {
                if (isFinishing() || isDestroyed()) return;
                btnSave.setEnabled(true);
                setStatus(null);
                alert(getString(R.string.msg_action_failed, error.getMessage()));
            }
        });
    }

    private void doSave() {
        VnstatApi.saveMonitored(section, new ArrayList<>(monitored),
                new ApiCallback<JsonObject>() {
                    @Override
                    public void onSuccess(JsonObject result) {
                        if (isFinishing() || isDestroyed()) return;
                        btnSave.setEnabled(true);
                        setStatus(null);
                        original.clear();
                        original.addAll(monitored);
                        // 新加的接口要等 vnstatd 建库并跑满一个周期才有数据
                        new MaterialAlertDialogBuilder(VnstatConfigActivity.this)
                                .setMessage(R.string.vnstat_saved)
                                .setPositiveButton(R.string.ok, (d, w) -> finish())
                                .setOnDismissListener(d -> finish())
                                .show();
                    }

                    @Override
                    public void onFailure(ApiError error) {
                        if (isFinishing() || isDestroyed()) return;
                        btnSave.setEnabled(true);
                        setStatus(null);
                        alert(getString(R.string.msg_action_failed, error.getMessage()));
                    }
                });
    }

    private void setStatus(String message) {
        statusLabel.setText(message);
        statusLabel.setVisibility(message == null ? View.GONE : View.VISIBLE);
    }

    private void alert(String message) {
        if (isFinishing() || isDestroyed()) return;
        new MaterialAlertDialogBuilder(this)
                .setMessage(message)
                .setPositiveButton(R.string.ok, null)
                .show();
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

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
