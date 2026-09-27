package com.whykangkang.wrthub.ui.settings;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.ColorRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.gson.JsonObject;
import com.whykangkang.wrthub.R;
import com.whykangkang.wrthub.api.AiAssistant;
import com.whykangkang.wrthub.api.ApiCallback;
import com.whykangkang.wrthub.api.ApiError;
import com.whykangkang.wrthub.api.OpenWrtApi;
import com.whykangkang.wrthub.api.PackageApi;
import com.whykangkang.wrthub.api.PluginChecker;
import com.whykangkang.wrthub.manager.HomeMetricsConfig;
import com.whykangkang.wrthub.manager.LocaleManager;
import com.whykangkang.wrthub.manager.RouterDeviceManager;
import com.whykangkang.wrthub.manager.SettingsManager;
import com.whykangkang.wrthub.manager.UpdateChecker;
import com.whykangkang.wrthub.model.RouterDevice;
import com.whykangkang.wrthub.ui.MainActivity;
import com.whykangkang.wrthub.ui.login.AddEditDeviceActivity;
import com.whykangkang.wrthub.ui.common.UpdatePrompt;
import com.whykangkang.wrthub.ui.login.DeviceListActivity;
import com.whykangkang.wrthub.ui.web.WebAccessActivity;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 设置页,对应 iOS SettingsViewController。
 *
 * 三节:
 *  通用   —— 自动刷新 / 刷新间隔 / 外观 / 语言 / 首页编辑(x/6) / 小组件设置
 *  连接信息 —— 路由器地址 / 用户名 / 登录时间
 *  其他   —— 关于 / 检测更新 / 网页访问 / 重启系统 / 切换设备 / 退出登录
 * 页脚显示当前设备。
 */
public class SettingsFragment extends Fragment {

    private static final long[] INTERVAL_VALUES = {1000, 5000, 10000, 30000, 60000, 300000};
    private static final int[] INTERVAL_LABELS = {
            R.string.interval_1s, R.string.interval_5s, R.string.interval_10s,
            R.string.interval_30s, R.string.interval_1m, R.string.interval_5m};

    private static final String[] APPEARANCE_KEYS = {"system", "light", "dark"};
    private static final int[] APPEARANCE_LABELS = {
            R.string.appearance_system, R.string.appearance_light, R.string.appearance_dark};

    private static final String[] LANGUAGE_KEYS = {"system", "zh", "en"};
    private static final int[] LANGUAGE_LABELS = {
            R.string.lang_system, R.string.lang_zh, R.string.lang_en};

    private SettingsManager settings;
    private LinearLayout generalRows;
    private LinearLayout connectionRows;
    private LinearLayout otherRows;
    private TextView footer;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_settings, container, false);
        settings = SettingsManager.getInstance(requireContext());
        generalRows = root.findViewById(R.id.general_rows);
        connectionRows = root.findViewById(R.id.connection_rows);
        otherRows = root.findViewById(R.id.other_rows);
        footer = root.findViewById(R.id.footer_device);
        return root;
    }

    @Override
    public void onResume() {
        super.onResume();
        // 从首页编辑等子页返回时刷新计数与设备信息
        render();
    }

    private void render() {
        buildGeneral();
        buildConnection();
        buildOther();
        showFooter();
    }

    // =====================================================================
    // 通用
    // =====================================================================

    private void buildGeneral() {
        generalRows.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(requireContext());

        View autoRow = inflater.inflate(R.layout.view_switch_row, generalRows, false);
        ((TextView) autoRow.findViewById(R.id.row_label)).setText(R.string.set_auto_refresh);
        SwitchMaterial autoRefresh = autoRow.findViewById(R.id.row_switch);
        autoRefresh.setChecked(settings.isAutoRefresh());
        autoRefresh.setOnCheckedChangeListener((b, checked) -> settings.setAutoRefresh(checked));
        generalRows.addView(autoRow);
        generalRows.addView(separator());

        generalRows.addView(valueRow(R.string.set_interval,
                getString(INTERVAL_LABELS[indexOf(INTERVAL_VALUES,
                        settings.getRefreshIntervalMs())]), this::pickInterval));
        generalRows.addView(separator());
        generalRows.addView(valueRow(R.string.set_appearance,
                getString(APPEARANCE_LABELS[indexOf(APPEARANCE_KEYS,
                        settings.getAppearance())]), this::pickAppearance));
        generalRows.addView(separator());
        generalRows.addView(valueRow(R.string.set_language,
                getString(LANGUAGE_LABELS[indexOf(LANGUAGE_KEYS,
                        settings.getLanguage())]), this::pickLanguage));
        generalRows.addView(separator());
        generalRows.addView(valueRow(R.string.set_home_edit,
                HomeMetricsConfig.visible(requireContext()).size() + "/"
                        + HomeMetricsConfig.HomeMetric.values().length,
                () -> startActivity(new Intent(requireContext(),
                        HomeMetricsEditActivity.class))));
        generalRows.addView(separator());
        generalRows.addView(valueRow(R.string.set_widget_config, "",
                () -> startActivity(new Intent(requireContext(),
                        WidgetConfigActivity.class))));
        generalRows.addView(separator());
        generalRows.addView(valueRow(R.string.ai_title,
                getString(AiAssistant.isEnabled(requireContext()) ? R.string.ai_on : R.string.ai_off),
                () -> startActivity(new Intent(requireContext(), AiSettingsActivity.class))));
    }

    private void pickInterval() {
        pick(R.string.set_interval, INTERVAL_LABELS,
                indexOf(INTERVAL_VALUES, settings.getRefreshIntervalMs()), which -> {
                    settings.setRefreshIntervalMs(INTERVAL_VALUES[which]);
                    render();
                });
    }

    private void pickAppearance() {
        pick(R.string.set_appearance, APPEARANCE_LABELS,
                indexOf(APPEARANCE_KEYS, settings.getAppearance()), which -> {
                    settings.setAppearance(APPEARANCE_KEYS[which]);
                    render();
                    LocaleManager.applyAppearance(APPEARANCE_KEYS[which]);
                });
    }

    private void pickLanguage() {
        pick(R.string.set_language, LANGUAGE_LABELS,
                indexOf(LANGUAGE_KEYS, settings.getLanguage()), which -> {
                    settings.setLanguage(LANGUAGE_KEYS[which]);
                    // 立即生效:按新语言重建界面
                    LocaleManager.apply(LANGUAGE_KEYS[which]);
                });
    }

    private interface Picked {
        void onPick(int which);
    }

    private void pick(@StringRes int titleRes, int[] labelRes, int current, Picked picked) {
        CharSequence[] items = new CharSequence[labelRes.length];
        for (int i = 0; i < items.length; i++) items[i] = getString(labelRes[i]);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(titleRes)
                .setSingleChoiceItems(items, current, (dialog, which) -> {
                    dialog.dismiss();
                    picked.onPick(which);
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    // =====================================================================
    // 连接信息
    // =====================================================================

    private void buildConnection() {
        connectionRows.removeAllViews();
        RouterDevice device = currentDevice();
        String notSet = getString(R.string.common_not_set);
        addInfoRow(R.string.set_router_address,
                device != null ? device.getDisplayAddress() : notSet);
        connectionRows.addView(separator());
        addInfoRow(R.string.set_username,
                device != null ? device.getUsername() : notSet);
        connectionRows.addView(separator());
        long at = settings.getLoginTimestamp();
        addInfoRow(R.string.set_login_time, at > 0
                ? new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(at))
                : getString(R.string.common_not_logged_in));
    }

    private void addInfoRow(@StringRes int labelRes, String value) {
        View row = LayoutInflater.from(requireContext())
                .inflate(R.layout.view_info_row, connectionRows, false);
        ((TextView) row.findViewById(R.id.row_label)).setText(labelRes);
        TextView valueView = row.findViewById(R.id.row_value);
        valueView.setText(value);
        valueView.setTextSize(13);
        connectionRows.addView(row);
    }

    // =====================================================================
    // 其他
    // =====================================================================

    private void buildOther() {
        otherRows.removeAllViews();
        otherRows.addView(actionRow(R.string.set_about, R.color.label_primary, this::showAbout));
        otherRows.addView(separator());
        otherRows.addView(actionRow(R.string.set_check_update, R.color.label_primary,
                this::checkUpdate));
        otherRows.addView(separator());
        otherRows.addView(actionRow(R.string.set_web_access, R.color.label_primary,
                this::openWebAccess));
        otherRows.addView(separator());
        otherRows.addView(actionRow(R.string.set_reboot, R.color.ios_red, this::confirmReboot));
        otherRows.addView(separator());
        otherRows.addView(actionRow(R.string.set_switch_device, R.color.label_primary,
                this::switchDevice));
        otherRows.addView(separator());
        otherRows.addView(actionRow(R.string.set_logout, R.color.ios_red, this::confirmLogout));
    }

    private void showAbout() {
        String version;
        int build;
        try {
            android.content.pm.PackageInfo info = requireContext().getPackageManager()
                    .getPackageInfo(requireContext().getPackageName(), 0);
            version = info.versionName;
            build = info.versionCode;
        } catch (Exception e) {
            version = "1.0";
            build = 1;
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.about_title)
                .setMessage(getString(R.string.about_full, version, build))
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    /**
     * 「检测更新」:拉取 whykangkang.com 上的版本 JSON,与本地 versionCode 比对。
     * 与启动时的自动检测不同,这里是用户主动点的,所以「已是最新」和失败都要给反馈。
     * 有新版本时的弹窗与下载跳转复用 {@link UpdatePrompt}。
     */
    private void checkUpdate() {
        androidx.appcompat.app.AlertDialog progress = new MaterialAlertDialogBuilder(requireContext())
                .setMessage(R.string.update_checking)
                .setCancelable(false)
                .show();
        UpdateChecker.check(requireContext(), new UpdateChecker.Callback() {
            @Override
            public void onResult(UpdateChecker.UpdateInfo info) {
                if (!isAdded()) return;
                progress.dismiss();
                if (info == null) {
                    alert(getString(R.string.update_latest,
                            UpdateChecker.currentVersionName(requireContext())));
                } else {
                    UpdatePrompt.show(requireActivity(), info);
                }
            }

            @Override
            public void onFailure(String message) {
                if (!isAdded()) return;
                progress.dismiss();
                alert(getString(R.string.update_failed, message));
            }
        });
    }

    private void openWebAccess() {
        startActivity(new Intent(requireContext(), WebAccessActivity.class));
    }

    private void confirmReboot() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.set_reboot)
                .setMessage(R.string.confirm_reboot)
                .setPositiveButton(R.string.set_reboot, (d, w) -> performReboot())
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void performReboot() {
        OpenWrtApi.getInstance().reboot(new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                alert(getString(R.string.reboot_sent));
            }

            @Override
            public void onFailure(ApiError error) {
                // reboot 常在响应返回前就断连,这不算失败
                alert(getString(R.string.reboot_sent));
            }
        });
    }

    /** 切换设备:弹出已保存设备列表,选中后直接登录并回到首页(无需先退出) */
    private void switchDevice() {
        RouterDeviceManager manager = RouterDeviceManager.getInstance(requireContext());
        List<RouterDevice> devices = manager.getDevices();
        String currentId = manager.getLastDeviceId();
        CharSequence[] labels = new CharSequence[devices.size() + 1];
        for (int i = 0; i < devices.size(); i++) {
            RouterDevice d = devices.get(i);
            labels[i] = d.getDisplayName() + " (" + d.getHost() + ":" + d.getPort() + ")"
                    + (d.getId().equals(currentId) ? " ✓" : "");
        }
        labels[devices.size()] = getString(R.string.set_add_device);

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.set_switch_device)
                .setItems(labels, (d, which) -> {
                    if (which == devices.size()) {
                        startActivity(new Intent(requireContext(), AddEditDeviceActivity.class));
                        return;
                    }
                    RouterDevice target = devices.get(which);
                    if (target.getId().equals(currentId)) return;   // 点当前设备不动作
                    performSwitch(target);
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void performSwitch(RouterDevice device) {
        androidx.appcompat.app.AlertDialog progress = new MaterialAlertDialogBuilder(
                requireContext())
                .setMessage(R.string.msg_connecting)
                .setCancelable(false)
                .show();
        OpenWrtApi api = OpenWrtApi.getInstance();
        api.configure(device.getHost(), device.getPort(), device.isUseHttps(),
                device.getUsername(), device.getPassword());
        // 切设备必须丢掉上一台的插件/软件包缓存
        PluginChecker.clearCache();
        PackageApi.clearAllCaches();
        api.login(new ApiCallback<Void>() {
            @Override
            public void onSuccess(Void result) {
                progress.dismiss();
                RouterDeviceManager.getInstance(requireContext())
                        .setLastDeviceId(device.getId());
                settings.setLoginTimestamp(System.currentTimeMillis());
                settings.setLoggedIn(true);
                Intent intent = new Intent(requireContext(), MainActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
            }

            @Override
            public void onFailure(ApiError error) {
                progress.dismiss();
                alert(getString(R.string.msg_login_failed, error.getMessage()));
            }
        });
    }

    private void confirmLogout() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.set_logout)
                .setMessage(R.string.confirm_logout)
                .setPositiveButton(R.string.set_logout, (d, w) -> logout())
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void logout() {
        OpenWrtApi.getInstance().logout();
        PluginChecker.clearCache();
        PackageApi.clearAllCaches();
        // 清掉已登录标记,下次冷启动才会停在设备列表
        settings.setLoggedIn(false);
        Intent intent = new Intent(requireContext(), DeviceListActivity.class);
        intent.putExtra(DeviceListActivity.EXTRA_PICK_DEVICE, true);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
    }

    // =====================================================================
    // 视图工具
    // =====================================================================

    private View valueRow(@StringRes int labelRes, String value, Runnable onClick) {
        View row = LayoutInflater.from(requireContext())
                .inflate(R.layout.view_value_row, generalRows, false);
        ((TextView) row.findViewById(R.id.row_label)).setText(labelRes);
        ((TextView) row.findViewById(R.id.row_value)).setText(value);
        row.setOnClickListener(v -> onClick.run());
        return row;
    }

    private View actionRow(@StringRes int labelRes, @ColorRes int colorRes, Runnable onClick) {
        View row = LayoutInflater.from(requireContext())
                .inflate(R.layout.view_value_row, otherRows, false);
        TextView label = row.findViewById(R.id.row_label);
        label.setText(labelRes);
        label.setTextColor(requireContext().getColor(colorRes));
        ((TextView) row.findViewById(R.id.row_value)).setText("");
        row.setOnClickListener(v -> onClick.run());
        return row;
    }

    private void showFooter() {
        RouterDevice device = currentDevice();
        footer.setText(getString(R.string.footer_device, device != null
                ? device.getDisplayName() + " (" + device.getHost() + ":" + device.getPort() + ")"
                : getString(R.string.common_not_set)));
    }

    private RouterDevice currentDevice() {
        return RouterDeviceManager.getInstance(requireContext()).getCurrentDevice();
    }

    private void alert(String message) {
        if (!isAdded()) return;
        new MaterialAlertDialogBuilder(requireContext())
                .setMessage(message)
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    private View separator() {
        View line = new View(requireContext());
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.setMarginStart(Math.round(16 * getResources().getDisplayMetrics().density));
        line.setLayoutParams(lp);
        line.setBackgroundColor(requireContext().getColor(R.color.separator));
        return line;
    }

    private static int indexOf(long[] arr, long value) {
        for (int i = 0; i < arr.length; i++) if (arr[i] == value) return i;
        return 0;
    }

    private static int indexOf(String[] arr, String value) {
        for (int i = 0; i < arr.length; i++) if (arr[i].equals(value)) return i;
        return 0;
    }
}
