package com.whykangkang.wrthub.ui.services.ratelimit;

import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.whykangkang.wrthub.R;
import com.whykangkang.wrthub.api.ApiCallback;
import com.whykangkang.wrthub.api.ApiError;
import com.whykangkang.wrthub.api.EqosApi;
import com.whykangkang.wrthub.api.OpenWrtApi;
import com.whykangkang.wrthub.model.ConnectedDevice;
import com.whykangkang.wrthub.model.EqosDevice;
import com.whykangkang.wrthub.model.EqosGlobal;
import com.whykangkang.wrthub.ui.devices.ConnectedDeviceLoader;
import com.whykangkang.wrthub.util.NetworkUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 设备限速(luci-app-eqos),对应 iOS DeviceRateLimitViewController。
 *
 *  - 总开关 + WAN 总带宽(eqos 靠它算 HTB 根队列,填错所有限速都不准)
 *  - 每台设备一条上/下行上限,可从已连接设备里挑,省得手打 IP
 */
public class DeviceRateLimitActivity extends AppCompatActivity {

    private EqosGlobal global = EqosGlobal.defaults();
    private final List<EqosDevice> devices = new ArrayList<>();
    private boolean loaded;

    private SwipeRefreshLayout refresh;
    private LinearLayout globalContainer;
    private LinearLayout devicesContainer;
    private TextView devicesFooter;
    private TextView statusLabel;
    private View content;
    private boolean suppressSwitchCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_rate_limit);
        refresh = findViewById(R.id.refresh);
        globalContainer = findViewById(R.id.global_container);
        devicesContainer = findViewById(R.id.devices_container);
        devicesFooter = findViewById(R.id.devices_footer);
        statusLabel = findViewById(R.id.status_label);
        content = findViewById(R.id.content);
        refresh.setOnRefreshListener(this::loadSettings);
        findViewById(R.id.btn_add).setOnClickListener(v -> addDeviceTapped());
        loadSettings();
    }

    // =====================================================================
    // 数据
    // =====================================================================

    private void loadSettings() {
        EqosApi.getSettings(new ApiCallback<EqosApi.EqosSettings>() {
            @Override
            public void onSuccess(EqosApi.EqosSettings settings) {
                refresh.setRefreshing(false);
                global = settings.global;
                devices.clear();
                devices.addAll(settings.devices);
                loaded = true;
                statusLabel.setVisibility(View.GONE);
                content.setVisibility(View.VISIBLE);
                render();
            }

            @Override
            public void onFailure(ApiError error) {
                refresh.setRefreshing(false);
                loaded = false;
                content.setVisibility(View.GONE);
                statusLabel.setVisibility(View.VISIBLE);
                statusLabel.setText(friendlyMessage(error));
            }
        });
    }

    /** uci 读不到 eqos 配置,最常见的原因是插件没装 */
    private String friendlyMessage(ApiError error) {
        if (error.getType() == ApiError.Type.CONFIG_NOT_FOUND) {
            return getString(R.string.eqos_not_configured);
        }
        return error.getMessage() != null ? error.getMessage() : getString(R.string.eqos_load_failed);
    }

    // =====================================================================
    // 渲染
    // =====================================================================

    private void render() {
        globalContainer.removeAllViews();

        View toggleRow = LayoutInflater.from(this)
                .inflate(R.layout.view_switch_row, globalContainer, false);
        ((TextView) toggleRow.findViewById(R.id.row_label)).setText(R.string.eqos_enable);
        SwitchMaterial toggle = toggleRow.findViewById(R.id.row_switch);
        suppressSwitchCallback = true;
        toggle.setChecked(global.enabled);
        suppressSwitchCallback = false;
        toggle.setOnCheckedChangeListener((btn, checked) -> {
            if (suppressSwitchCallback) return;
            EqosGlobal next = new EqosGlobal(checked, global.download, global.upload);
            saveGlobal(next, ok -> {
                if (!ok) {
                    suppressSwitchCallback = true;
                    toggle.setChecked(global.enabled);
                    suppressSwitchCallback = false;
                }
            });
        });
        globalContainer.addView(toggleRow);
        globalContainer.addView(separator());
        globalContainer.addView(bandwidthRow(true));
        globalContainer.addView(separator());
        globalContainer.addView(bandwidthRow(false));

        devicesContainer.removeAllViews();
        if (devices.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.eqos_empty);
            empty.setTextColor(getColor(R.color.label_secondary));
            empty.setTextSize(15);
            int pad = dp(16);
            empty.setPadding(pad, pad, pad, pad);
            devicesContainer.addView(empty);
            devicesFooter.setText(R.string.eqos_unit_hint);
        } else {
            for (int i = 0; i < devices.size(); i++) {
                devicesContainer.addView(deviceRow(devices.get(i)));
                if (i < devices.size() - 1) devicesContainer.addView(separator());
            }
            devicesFooter.setText(R.string.eqos_devices_footer);
        }
    }

    private View bandwidthRow(boolean isDownload) {
        View row = LayoutInflater.from(this)
                .inflate(R.layout.view_value_row, globalContainer, false);
        ((TextView) row.findViewById(R.id.row_label))
                .setText(isDownload ? R.string.eqos_wan_down : R.string.eqos_wan_up);
        ((TextView) row.findViewById(R.id.row_value)).setText(
                (isDownload ? global.download : global.upload) + " Mbit/s");
        row.setOnClickListener(v -> editBandwidth(isDownload));
        return row;
    }

    private View deviceRow(EqosDevice device) {
        View row = LayoutInflater.from(this)
                .inflate(R.layout.item_samba_share, devicesContainer, false);
        TextView name = row.findViewById(R.id.share_name);
        TextView detail = row.findViewById(R.id.share_path);
        // 网页端可以单独停用某条规则而不删除,这里如实标出来
        name.setText(device.enabled ? device.displayName()
                : getString(R.string.eqos_disabled_suffix, device.displayName()));
        name.setTextColor(getColor(device.enabled
                ? R.color.label_primary : R.color.label_secondary));
        String ipPart = device.displayName().equals(device.ip) ? "" : device.ip + "   ";
        detail.setText(String.format(Locale.US, "%s↓ %d  ↑ %d Mbit/s",
                ipPart, device.download, device.upload));
        row.setOnClickListener(v -> editDevice(device, false));
        row.setOnLongClickListener(v -> {
            confirmDelete(device);
            return true;
        });
        return row;
    }

    // =====================================================================
    // 全局设置
    // =====================================================================

    private void editBandwidth(boolean isDownload) {
        int current = isDownload ? global.download : global.upload;
        EditText input = numberInput(String.valueOf(current));
        new MaterialAlertDialogBuilder(this)
                .setTitle(isDownload ? R.string.eqos_wan_down : R.string.eqos_wan_up)
                .setMessage(R.string.eqos_bandwidth_hint)
                .setView(wrap(input))
                .setPositiveButton(R.string.ok, (d, w) -> {
                    int value = parseInt(input.getText().toString());
                    if (value <= 0) {
                        error(getString(R.string.eqos_positive_required));
                        return;
                    }
                    EqosGlobal next = new EqosGlobal(global.enabled,
                            isDownload ? value : global.download,
                            isDownload ? global.upload : value);
                    saveGlobal(next, ok -> {
                    });
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private interface Done {
        void onDone(boolean ok);
    }

    private void saveGlobal(EqosGlobal next, Done done) {
        EqosApi.saveGlobal(next, new ApiCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean ok) {
                global = next;
                render();
                done.onDone(true);
            }

            @Override
            public void onFailure(ApiError err) {
                error(friendlyMessage(err));
                done.onDone(false);
            }
        });
    }

    // =====================================================================
    // 设备增删改
    // =====================================================================

    private void addDeviceTapped() {
        if (!loaded) return;
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.eqos_add_device)
                .setItems(new CharSequence[]{
                        getString(R.string.eqos_pick_connected),
                        getString(R.string.eqos_enter_ip)
                }, (d, which) -> {
                    if (which == 0) {
                        pickFromConnectedDevices();
                    } else {
                        editDevice(null, true);
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** 手机上敲 IP 太费劲,直接从租约列表里挑 */
    private void pickFromConnectedDevices() {
        new ConnectedDeviceLoader(OpenWrtApi.getInstance(), NetworkUtils.getLocalIpv4(this))
                .load(all -> {
                    // 已经限速过的不再列出来
                    Set<String> limited = new HashSet<>();
                    for (EqosDevice d : devices) limited.add(d.ip);
                    List<ConnectedDevice> candidates = new ArrayList<>();
                    for (ConnectedDevice d : all) {
                        if (d.ipAddress == null || limited.contains(d.ipAddress)) continue;
                        candidates.add(d);
                        if (candidates.size() >= 20) break;
                    }
                    if (candidates.isEmpty()) {
                        error(getString(R.string.eqos_no_candidates));
                        return;
                    }
                    CharSequence[] labels = new CharSequence[candidates.size()];
                    for (int i = 0; i < candidates.size(); i++) {
                        ConnectedDevice d = candidates.get(i);
                        labels[i] = d.getPrimaryTitle().equals(d.ipAddress)
                                ? d.ipAddress : d.getPrimaryTitle() + " · " + d.ipAddress;
                    }
                    new MaterialAlertDialogBuilder(this)
                            .setTitle(R.string.eqos_pick_title)
                            .setItems(labels, (dialog, which) -> {
                                ConnectedDevice d = candidates.get(which);
                                EqosDevice prefilled = new EqosDevice();
                                prefilled.ip = d.ipAddress;
                                prefilled.comment = d.getPrimaryTitle().equals(d.ipAddress)
                                        ? "" : d.getPrimaryTitle();
                                editDevice(prefilled, true);
                            })
                            .setNegativeButton(R.string.action_cancel, null)
                            .show();
                });
    }

    /** existing == null 表示手动新增;creating 用于区分「从连接设备预填」也是新增 */
    private void editDevice(EqosDevice existing, boolean creating) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        form.setPadding(pad, dp(8), pad, 0);

        EditText comment = textInput(getString(R.string.eqos_comment_hint),
                existing != null ? existing.comment : "");
        EditText ip = textInput(getString(R.string.eqos_ip_hint),
                existing != null ? existing.ip : "");
        // 已有条目的 IP 就是 uci 里的匹配键,改它等于换一台设备,容易误操作
        ip.setEnabled(creating);
        EditText down = numberInput(existing != null && existing.download > 0
                ? String.valueOf(existing.download) : "");
        down.setHint(R.string.eqos_down_hint);
        EditText up = numberInput(existing != null && existing.upload > 0
                ? String.valueOf(existing.upload) : "");
        up.setHint(R.string.eqos_up_hint);
        form.addView(comment);
        form.addView(ip);
        form.addView(down);
        form.addView(up);

        new MaterialAlertDialogBuilder(this)
                .setTitle(creating ? R.string.eqos_add_device : R.string.eqos_edit_device)
                .setMessage(R.string.eqos_device_hint)
                .setView(form)
                .setPositiveButton(R.string.btn_save, (d, w) -> {
                    String ipText = ip.getText().toString().trim();
                    int downValue = parseInt(down.getText().toString());
                    int upValue = parseInt(up.getText().toString());
                    if (!isValidIPv4(ipText)) {
                        error(getString(R.string.eqos_invalid_ip));
                        return;
                    }
                    // eqos 要求两个方向都填且 >= 1(datatype and(uinteger,min(1))),填 0 会被插件拒掉
                    if (downValue < 1 || upValue < 1) {
                        error(getString(R.string.eqos_both_required));
                        return;
                    }
                    EqosDevice device = new EqosDevice();
                    device.section = existing != null ? existing.section : "";
                    device.enabled = existing == null || existing.enabled;
                    device.ip = ipText;
                    device.download = downValue;
                    device.upload = upValue;
                    device.comment = comment.getText().toString().trim();
                    if (creating) {
                        createDevice(device);
                    } else {
                        saveDevice(device);
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void createDevice(EqosDevice device) {
        EqosApi.addDevice(device, new ApiCallback<String>() {
            @Override
            public void onSuccess(String section) {
                loadSettings();
            }

            @Override
            public void onFailure(ApiError err) {
                error(friendlyMessage(err));
            }
        });
    }

    private void saveDevice(EqosDevice device) {
        EqosApi.updateDevice(device, new ApiCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean ok) {
                loadSettings();
            }

            @Override
            public void onFailure(ApiError err) {
                error(friendlyMessage(err));
            }
        });
    }

    private void confirmDelete(EqosDevice device) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.eqos_remove_title)
                .setMessage(getString(R.string.eqos_remove_msg, device.displayName()))
                .setPositiveButton(R.string.ok, (d, w) ->
                        EqosApi.deleteDevice(device.section, new ApiCallback<Boolean>() {
                            @Override
                            public void onSuccess(Boolean ok) {
                                loadSettings();
                            }

                            @Override
                            public void onFailure(ApiError err) {
                                error(friendlyMessage(err));
                            }
                        }))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    // =====================================================================
    // 工具
    // =====================================================================

    static boolean isValidIPv4(String s) {
        if (s == null) return false;
        String[] parts = s.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) return false;
            for (int i = 0; i < part.length(); i++) {
                if (!Character.isDigit(part.charAt(i))) return false;
            }
            int v = Integer.parseInt(part);
            if (v < 0 || v > 255) return false;
        }
        return true;
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private EditText numberInput(String value) {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setText(value);
        return e;
    }

    private EditText textInput(String hint, String value) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setInputType(InputType.TYPE_CLASS_TEXT);
        return e;
    }

    private View wrap(View child) {
        LinearLayout box = new LinearLayout(this);
        int pad = dp(20);
        box.setPadding(pad, dp(8), pad, 0);
        box.addView(child, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }

    private void error(String message) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.eqos_action_failed)
                .setMessage(message)
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    private View separator() {
        View line = new View(this);
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.setMarginStart(dp(16));
        line.setLayoutParams(lp);
        line.setBackgroundColor(getColor(R.color.separator));
        return line;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
