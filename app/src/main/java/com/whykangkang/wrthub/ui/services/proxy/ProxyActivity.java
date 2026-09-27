package com.whykangkang.wrthub.ui.services.proxy;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.gson.JsonObject;
import com.whykangkang.wrthub.R;
import com.whykangkang.wrthub.api.ApiCallback;
import com.whykangkang.wrthub.api.ApiError;
import com.whykangkang.wrthub.api.OpenClashApi;
import com.whykangkang.wrthub.api.OpenWrtApi;
import com.whykangkang.wrthub.manager.RouterDeviceManager;
import com.whykangkang.wrthub.model.RouterDevice;
import com.whykangkang.wrthub.ui.web.WebAccessActivity;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 网络代理(OpenClash),对应 iOS ProxyViewController。
 *
 * 五张卡,顺序与 iOS 一致:
 *  状态   —— 盾牌标题 / 大状态块 / 代理模式分段 / 启停 + 重启 / 刷新状态
 *  配置文件 —— 当前配置 + 更新时间 + 订阅流量进度条 + 选择配置
 *  控制面板 —— Yacd / Zashboard 按钮(未运行时显示占位)
 *  访问检查 —— 四宫格,每格 名称 / 连接正常·失败 / 延迟
 *  IP 地址  —— 多来源出口 IP 卡片(来源 / IP / 归属地)
 * 全部走 LuCI CGI 通道(§2.3)。
 */
public class ProxyActivity extends AppCompatActivity {

    private static final String[] MODES = {"rule", "global", "direct"};

    private View statusDot;
    private TextView statusText;
    private TextView coreText;
    private ProgressBar statusSpinner;
    private MaterialButtonToggleGroup modeGroup;
    private MaterialButton btnToggle;
    private MaterialButton btnRestart;
    private MaterialButton btnRefreshStatus;

    private TextView configName;
    private TextView configUpdated;
    private ProgressBar trafficBar;
    private TextView trafficText;
    private MaterialButton btnSwitchConfig;

    private LinearLayout dashboardContainer;
    private LinearLayout checkContainer;
    private LinearLayout ipContainer;

    private boolean running;
    private boolean statusKnown;
    /** 回填模式时不触发切换 */
    private boolean applyingMode;
    private String currentMode = "rule";
    private String dashboardIp;
    private String dashboardPort;
    private String dashboardSecret;
    /** 探测到的可用面板:key -> 显示名 */
    private final Map<String, String> dashboards = new LinkedHashMap<>();

    private String currentConfig;
    private final List<String> configFiles = new ArrayList<>();
    private OpenClashApi.TrafficInfo traffic;
    private final Map<String, OpenClashApi.WebsiteCheck> checks = new LinkedHashMap<>();
    private boolean checking;
    /** 进页面后自动跑过一轮检测没有;服务从停止变成运行时会再跑一次 */
    private boolean autoChecked;
    private final List<OpenClashApi.IpCheck> ipResults = new ArrayList<>();
    private String ipPlaceholder;

    /** 重启分两步,中间要等,离开页面时把没跑的那一步撤掉 */
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_proxy);

        statusDot = findViewById(R.id.status_dot);
        statusText = findViewById(R.id.status_text);
        coreText = findViewById(R.id.core_text);
        statusSpinner = findViewById(R.id.status_spinner);
        modeGroup = findViewById(R.id.mode_group);
        btnToggle = findViewById(R.id.btn_toggle);
        btnRestart = findViewById(R.id.btn_restart);
        btnRefreshStatus = findViewById(R.id.btn_refresh_status);

        configName = findViewById(R.id.config_name);
        configUpdated = findViewById(R.id.config_updated);
        trafficBar = findViewById(R.id.traffic_bar);
        trafficText = findViewById(R.id.traffic_text);
        btnSwitchConfig = findViewById(R.id.btn_switch_config);

        dashboardContainer = findViewById(R.id.dashboard_container);
        checkContainer = findViewById(R.id.check_container);
        ipContainer = findViewById(R.id.ip_container);

        modeGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked && !applyingMode) onModePicked(modeForId(checkedId));
        });
        btnToggle.setOnClickListener(v -> onToggleTapped());
        btnRestart.setOnClickListener(v -> onRestartTapped());
        btnRefreshStatus.setOnClickListener(v -> refreshAll());
        btnSwitchConfig.setOnClickListener(v -> pickConfig());
        findViewById(R.id.btn_refresh_config).setOnClickListener(v -> loadConfigs());
        findViewById(R.id.btn_check).setOnClickListener(v -> runWebsiteChecks());
        findViewById(R.id.btn_myip).setOnClickListener(v -> loadIpInfo());

        renderStatus();
        renderConfig();
        renderDashboards();
        renderChecks();
        renderIp();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAll();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void refreshAll() {
        loadStatus();
        loadMode();
        loadConfigs();
    }

    // =====================================================================
    // 状态与控制
    // =====================================================================

    private void loadStatus() {
        setRefreshing(true);
        OpenWrtApi.getInstance().openclashStatus(new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                setRefreshing(false);
                statusKnown = true;
                running = result.has("clash") && result.get("clash").getAsBoolean();
                dashboardIp = optStr(result, "daip");
                dashboardPort = optStr(result, "cn_port");
                dashboardSecret = optStr(result, "dase");
                String core = optStr(result, "core_type");
                coreText.setText(core != null
                        ? getString(R.string.proxy_core) + ": " + core : "");
                if (!running) {
                    // 未运行时 iOS 会清空面板/检测/IP,避免显示上一次的陈旧数据
                    dashboards.clear();
                    checks.clear();
                    ipPlaceholder = getString(R.string.proxy_not_running);
                    renderChecks();
                    renderIp();
                }
                renderStatus();
                detectDashboards(result);
                autoCheck();
            }

            @Override
            public void onFailure(ApiError error) {
                setRefreshing(false);
                statusKnown = false;
                running = false;
                coreText.setText("");
                dashboards.clear();
                checks.clear();
                ipPlaceholder = getString(R.string.proxy_status_unknown);
                renderStatus();
                renderDashboards();
                renderChecks();
                renderIp();
            }
        });
    }

    /**
     * 服务在跑就自动跑一轮访问检查 + 出口 IP(对应 iOS loadStatus 末尾的
     * loadWebsiteCheckInfo / loadIPInfo)。每个页面实例只自动跑一次 ——
     * onResume 会重新拉状态,不加这道闸从浏览器返回都会重跑一遍网络请求。
     * 服务停着时不跑,等手动启动后状态刷新到 running 再补上。
     */
    private void autoCheck() {
        if (!running || autoChecked) return;
        autoChecked = true;
        runWebsiteChecks();
        loadIpInfo();
    }

    /** 状态块:运行中(绿) / 已停止(红) / 状态未知(灰),与 iOS updateStatusUI 一致 */
    private void renderStatus() {
        if (!statusKnown) {
            statusText.setText(R.string.proxy_status_unknown);
            statusText.setTextColor(getColor(R.color.label_secondary));
            statusDot.setBackgroundResource(R.drawable.dot_gray);
        } else if (running) {
            statusText.setText(R.string.proxy_status_running);
            statusText.setTextColor(getColor(R.color.ios_green));
            statusDot.setBackgroundResource(R.drawable.dot_green);
        } else {
            statusText.setText(R.string.proxy_status_stopped);
            statusText.setTextColor(getColor(R.color.ios_red));
            statusDot.setBackgroundResource(R.drawable.dot_red);
        }

        btnToggle.setEnabled(statusKnown);
        btnToggle.setText(running ? R.string.proxy_stop : R.string.proxy_start);
        btnToggle.setIconResource(running ? R.drawable.ic_stop : R.drawable.ic_play);
        btnToggle.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                getColor(running ? R.color.ios_red : R.color.ios_green)));
        btnToggle.setAlpha(statusKnown ? 1f : 0.5f);

        // 只有运行中才能重启
        btnRestart.setEnabled(statusKnown && running);
        btnRestart.setAlpha(statusKnown && running ? 1f : 0.5f);

        // 模式分段同样只有运行中可用(iOS updateProxyModeUI)
        modeGroup.setAlpha(running ? 1f : 0.5f);
    }

    private void setRefreshing(boolean refreshing) {
        statusSpinner.setVisibility(refreshing ? View.VISIBLE : View.GONE);
        btnRefreshStatus.setEnabled(!refreshing);
        btnRefreshStatus.setAlpha(refreshing ? 0.5f : 1f);
    }

    private void loadMode() {
        OpenWrtApi.getInstance().openclashRuleMode(new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                String mode = optStr(result, "mode");
                if (mode != null) currentMode = mode;
                applyMode(currentMode);
            }

            @Override
            public void onFailure(ApiError error) {
                applyMode(currentMode);
            }
        });
    }

    /** 回填分段选择,不触发切换 */
    private void applyMode(String mode) {
        applyingMode = true;
        modeGroup.check(idForMode(mode));
        applyingMode = false;
    }

    private void onModePicked(String mode) {
        if (!running) {
            applyMode(currentMode);          // 未运行时恢复原选择
            alert(getString(R.string.proxy_need_running));
            return;
        }
        if (mode.equals(currentMode)) return;
        switchMode(mode);
    }

    private void switchMode(String mode) {
        setRefreshing(true);
        OpenWrtApi.getInstance().openclashSwitchRuleMode(mode, new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                setRefreshing(false);
                currentMode = mode;
                toast(getString(R.string.proxy_mode_switched, modeLabel(mode)));
            }

            @Override
            public void onFailure(ApiError error) {
                setRefreshing(false);
                applyMode(currentMode);      // 切换失败恢复原选择
                fail(error);
            }
        });
    }

    private void onToggleTapped() {
        if (!running) {
            performToggle();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.proxy_stop_confirm_title)
                .setMessage(R.string.proxy_stop_confirm)
                .setPositiveButton(R.string.proxy_stop, (d, w) -> performToggle())
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void performToggle() {
        setRefreshing(true);
        ApiCallback<JsonObject> cb = new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                setRefreshing(false);
                loadStatus();
            }

            @Override
            public void onFailure(ApiError error) {
                setRefreshing(false);
                fail(error);
            }
        };
        if (running) {
            OpenWrtApi.getInstance().openclashStop(cb);
        } else {
            OpenWrtApi.getInstance().openclashStart(cb);
        }
    }

    private void onRestartTapped() {
        if (!running) {
            alert(getString(R.string.proxy_not_running_restart));
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.proxy_restart_confirm_title)
                .setMessage(R.string.proxy_restart_confirm)
                .setPositiveButton(R.string.proxy_restart, (d, w) -> performRestart())
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /**
     * 重启 = 停止 → 等 3 秒 → 启动 → 等 2 秒刷新状态(与 iOS performRestart 同步)。
     * OpenClash 的 startclash 紧跟 closeclash 会失败,中间这几秒是必需的。
     */
    private void performRestart() {
        setRefreshing(true);
        toast(getString(R.string.proxy_restarting));
        OpenWrtApi.getInstance().openclashStop(new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                if (isFinishing()) return;
                toast(getString(R.string.proxy_restart_stopped));
                handler.postDelayed(ProxyActivity.this::restartStepStart, 3000);
            }

            @Override
            public void onFailure(ApiError error) {
                setRefreshing(false);
                alert(getString(R.string.proxy_restart_stop_failed));
            }
        });
    }

    private void restartStepStart() {
        if (isFinishing()) return;
        OpenWrtApi.getInstance().openclashStart(new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                if (isFinishing()) return;
                setRefreshing(false);
                toast(getString(R.string.proxy_restart_done));
                handler.postDelayed(ProxyActivity.this::loadStatus, 2000);
            }

            @Override
            public void onFailure(ApiError error) {
                setRefreshing(false);
                alert(getString(R.string.proxy_restart_start_failed));
            }
        });
    }

    // =====================================================================
    // 控制面板
    // =====================================================================

    /** status 里没有面板字段时用 file.stat 兜底(iOS 修过的坑) */
    private void detectDashboards(JsonObject status) {
        dashboards.clear();
        renderDashboards();
        if (!running) return;
        probeDashboard("Yacd", "yacd", status);
        probeDashboard("Zashboard", "zashboard", status);
    }

    private void probeDashboard(String label, String key, JsonObject status) {
        Boolean flagged = status.has(key) && !status.get(key).isJsonNull()
                ? asBool(status, key) : null;
        if (Boolean.TRUE.equals(flagged)) {
            dashboards.put(key, label);
            renderDashboards();
            return;
        }
        OpenClashApi.detectDashboard(key, new ApiCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean installed) {
                if (installed) {
                    dashboards.put(key, label);
                    renderDashboards();
                }
            }

            @Override
            public void onFailure(ApiError error) {
                // 检测不到就不显示按钮
            }
        });
    }

    private void renderDashboards() {
        dashboardContainer.removeAllViews();
        if (dashboards.isEmpty()) {
            dashboardContainer.addView(placeholder(running
                    ? R.string.proxy_loading : R.string.proxy_not_running));
            return;
        }
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        boolean first = true;
        for (Map.Entry<String, String> entry : dashboards.entrySet()) {
            MaterialButton button = new MaterialButton(this);
            button.setText(entry.getValue());
            button.setAllCaps(false);
            button.setTextSize(15);
            button.setCornerRadius(dp(10));
            button.setInsetTop(0);
            button.setInsetBottom(0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(70), 1f);
            if (!first) lp.setMarginStart(dp(12));
            button.setLayoutParams(lp);
            button.setOnClickListener(v -> openDashboard(entry.getKey()));
            row.addView(button);
            first = false;
        }
        dashboardContainer.addView(row);
    }

    /**
     * 打开 Clash 面板。
     * 地址优先用当前登录的主机 —— 远程管理时 daip 是路由器内网 IP,手机根本连不上
     * (iOS updateDashboardUI 里踩过)。
     */
    private void openDashboard(String key) {
        String host = null;
        RouterDevice device = RouterDeviceManager.getInstance(this).getCurrentDevice();
        if (device != null) host = device.getHost();
        if (host == null || host.isEmpty()) host = dashboardIp;
        if (host == null || host.isEmpty() || dashboardPort == null) {
            alert(getString(R.string.proxy_panel_unavailable));
            return;
        }
        String secret = dashboardSecret == null ? "" : dashboardSecret;
        String url = "http://" + host + ":" + dashboardPort + "/ui/" + key + "/"
                + "?hostname=" + Uri.encode(host)
                + "&port=" + Uri.encode(dashboardPort)
                + "&secret=" + Uri.encode(secret);
        // 用内置浏览器打开,对应 iOS 的 SFSafariViewController(不跳外部浏览器)
        Intent intent = new Intent(this, WebAccessActivity.class);
        intent.putExtra(WebAccessActivity.EXTRA_URL, url);
        intent.putExtra(WebAccessActivity.EXTRA_TITLE, dashboards.get(key));
        startActivity(intent);
    }

    // =====================================================================
    // 配置文件
    // =====================================================================

    private void loadConfigs() {
        OpenClashApi.getCurrentConfig(new ApiCallback<String>() {
            @Override
            public void onSuccess(String name) {
                currentConfig = name;
                renderConfig();
                if (name != null) loadTraffic(name);
                listFiles();
            }

            @Override
            public void onFailure(ApiError error) {
                renderConfig();
                listFiles();
            }
        });
    }

    private void listFiles() {
        OpenClashApi.getConfigs(new ApiCallback<List<String>>() {
            @Override
            public void onSuccess(List<String> files) {
                configFiles.clear();
                configFiles.addAll(files);
                renderConfig();
            }

            @Override
            public void onFailure(ApiError error) {
                renderConfig();
            }
        });
    }

    private void loadTraffic(String fileName) {
        OpenClashApi.getConfigTraffic(fileName, new ApiCallback<OpenClashApi.TrafficInfo>() {
            @Override
            public void onSuccess(OpenClashApi.TrafficInfo info) {
                traffic = info;
                renderConfig();
            }

            @Override
            public void onFailure(ApiError error) {
                traffic = null;
                renderConfig();
            }
        });
    }

    private void renderConfig() {
        configName.setText(currentConfig != null ? currentConfig
                : getString(R.string.proxy_config_none));
        configUpdated.setText(getString(R.string.proxy_config_updated,
                new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())));

        if (traffic != null && !traffic.isEmpty()) {
            trafficBar.setProgress(parsePercent(traffic.percent));
            String line = getString(R.string.proxy_traffic_line,
                    traffic.used, traffic.total, traffic.percent);
            if (hasValue(traffic.expire)) {
                line = hasValue(traffic.dayLeft)
                        ? getString(R.string.proxy_traffic_expire_days,
                                line, traffic.expire, traffic.dayLeft)
                        : getString(R.string.proxy_traffic_expire, line, traffic.expire);
            }
            trafficText.setText(line);
            trafficText.setTextColor(getColor(R.color.label_primary));
        } else {
            trafficBar.setProgress(0);
            trafficText.setText(R.string.proxy_traffic_waiting);
            trafficText.setTextColor(getColor(R.color.label_tertiary));
        }

        boolean any = !configFiles.isEmpty();
        btnSwitchConfig.setText(any
                ? getString(R.string.proxy_pick_config_count, configFiles.size())
                : getString(R.string.proxy_pick_config));
        btnSwitchConfig.setEnabled(any);
        btnSwitchConfig.setAlpha(any ? 1f : 0.5f);
    }

    /** uci 里没值时会回 "null" 字符串,要一起挡掉 */
    private static boolean hasValue(String value) {
        return value != null && !value.isEmpty() && !"null".equals(value);
    }

    private static int parsePercent(String percent) {
        try {
            return Math.max(0, Math.min(100, (int) Math.round(Double.parseDouble(percent))));
        } catch (Exception e) {
            return 0;
        }
    }

    private void pickConfig() {
        if (configFiles.isEmpty()) {
            alert(getString(R.string.proxy_no_configs));
            return;
        }
        CharSequence[] items = configFiles.toArray(new CharSequence[0]);
        int current = currentConfig != null ? configFiles.indexOf(currentConfig) : -1;
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.proxy_pick_config)
                .setSingleChoiceItems(items, current, (dialog, which) -> {
                    dialog.dismiss();
                    confirmSwitchConfig(configFiles.get(which));
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void confirmSwitchConfig(String fileName) {
        if (fileName.equals(currentConfig)) return;
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.proxy_switch_config)
                .setMessage(getString(R.string.proxy_switch_confirm, fileName))
                .setPositiveButton(R.string.ok, (d, w) -> doSwitchConfig(fileName))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void doSwitchConfig(String fileName) {
        androidx.appcompat.app.AlertDialog progress = new MaterialAlertDialogBuilder(this)
                .setMessage(R.string.proxy_switching_config)
                .setCancelable(false)
                .show();
        OpenClashApi.switchConfig(fileName, new ApiCallback<Void>() {
            @Override
            public void onSuccess(Void unused) {
                progress.dismiss();
                currentConfig = fileName;
                traffic = null;
                renderConfig();
                loadTraffic(fileName);
                loadStatus();
            }

            @Override
            public void onFailure(ApiError error) {
                progress.dismiss();
                fail(error);
            }
        });
    }

    // =====================================================================
    // 访问检查
    // =====================================================================

    private void runWebsiteChecks() {
        if (checking) return;
        if (!running) {
            alert(getString(R.string.proxy_need_running));
            return;
        }
        checking = true;
        checks.clear();
        renderChecks();
        for (String domain : OpenClashApi.CHECK_DOMAINS) {
            OpenClashApi.checkWebsite(domain, new ApiCallback<OpenClashApi.WebsiteCheck>() {
                @Override
                public void onSuccess(OpenClashApi.WebsiteCheck result) {
                    checks.put(domain, result);
                    if (checks.size() == OpenClashApi.CHECK_DOMAINS.length) checking = false;
                    renderChecks();
                }

                @Override
                public void onFailure(ApiError error) {
                    if (checks.size() == OpenClashApi.CHECK_DOMAINS.length) checking = false;
                    renderChecks();
                }
            });
        }
    }

    /** 四宫格:两行两列,每格 80dp */
    private void renderChecks() {
        checkContainer.removeAllViews();
        if (!checking && checks.isEmpty()) {
            checkContainer.addView(placeholder(running
                    ? R.string.proxy_waiting : R.string.proxy_not_running));
            return;
        }
        LinearLayout row = null;
        for (int i = 0; i < OpenClashApi.CHECK_DOMAINS.length; i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) rowLp.topMargin = dp(8);
                row.setLayoutParams(rowLp);
                checkContainer.addView(row);
            }
            row.addView(checkCell(OpenClashApi.CHECK_DOMAINS[i], i % 2 == 1));
        }
    }

    private View checkCell(String domain, boolean second) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setBackgroundResource(R.drawable.bg_inset_10);
        cell.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(80), 1f);
        if (second) lp.setMarginStart(dp(8));
        cell.setLayoutParams(lp);

        TextView name = new TextView(this);
        name.setText(OpenClashApi.displayName(domain));
        name.setTextSize(15);
        name.setTextColor(getColor(R.color.label_primary));
        name.setSingleLine(true);

        OpenClashApi.WebsiteCheck result = checks.get(domain);
        TextView status = new TextView(this);
        status.setTextSize(13);
        TextView latency = new TextView(this);
        latency.setTextSize(15);

        if (result == null) {
            status.setText(checking ? R.string.proxy_checking : R.string.placeholder_value);
            status.setTextColor(getColor(R.color.label_secondary));
            latency.setText(R.string.proxy_ip_placeholder);
            latency.setTextColor(getColor(R.color.label_secondary));
        } else if (result.reachable) {
            status.setText(R.string.proxy_reachable);
            status.setTextColor(getColor(R.color.ios_green));
            latency.setText(result.latencyMs != null
                    ? result.latencyMs + " ms" : getString(R.string.proxy_ip_placeholder));
            latency.setTextColor(getColor(R.color.ios_green));
        } else {
            status.setText(R.string.proxy_unreachable);
            status.setTextColor(getColor(R.color.ios_red));
            latency.setText(R.string.proxy_ip_placeholder);
            latency.setTextColor(getColor(R.color.label_secondary));
        }

        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusLp.topMargin = dp(4);
        status.setLayoutParams(statusLp);

        cell.addView(name);
        cell.addView(status);
        cell.addView(latency);
        return cell;
    }

    // =====================================================================
    // IP 地址
    // =====================================================================

    private void loadIpInfo() {
        if (!running) {
            alert(getString(R.string.proxy_need_running));
            return;
        }
        ipPlaceholder = getString(R.string.proxy_loading);
        ipResults.clear();
        renderIp();

        OpenClashApi.getIpInfo(new ApiCallback<List<OpenClashApi.IpCheck>>() {
            @Override
            public void onSuccess(List<OpenClashApi.IpCheck> results) {
                ipResults.clear();
                ipResults.addAll(results);
                ipPlaceholder = getString(R.string.proxy_ip_placeholder);
                renderIp();
            }

            @Override
            public void onFailure(ApiError error) {
                ipResults.clear();
                ipPlaceholder = error.getMessage();
                renderIp();
            }
        });
    }

    /** 每行两个来源卡片 */
    private void renderIp() {
        ipContainer.removeAllViews();
        if (ipResults.isEmpty()) {
            ipContainer.addView(placeholderText(ipPlaceholder != null
                    ? ipPlaceholder : getString(R.string.proxy_ip_placeholder)));
            return;
        }
        LinearLayout row = null;
        for (int i = 0; i < ipResults.size(); i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) rowLp.topMargin = dp(12);
                row.setLayoutParams(rowLp);
                ipContainer.addView(row);
            }
            row.addView(ipCell(ipResults.get(i), i % 2 == 1));
        }
        // 奇数个时补一个空占位,免得最后一张卡拉成整行宽
        if (ipResults.size() % 2 == 1 && row != null) {
            View filler = new View(this);
            filler.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            row.addView(filler);
        }
    }

    private View ipCell(OpenClashApi.IpCheck result, boolean second) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setBackgroundResource(R.drawable.bg_inset_10);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setPadding(dp(8), dp(8), dp(8), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (second) lp.setMarginStart(dp(12));
        cell.setLayoutParams(lp);

        TextView source = new TextView(this);
        source.setText(ipSourceName(result.source));
        source.setTextSize(13);
        source.setTextColor(getColor(R.color.label_secondary));
        source.setGravity(Gravity.CENTER);

        TextView ip = new TextView(this);
        ip.setText(result.ip);
        ip.setTextSize(16);
        ip.setTypeface(ip.getTypeface(), android.graphics.Typeface.BOLD);
        ip.setTextColor(getColor(R.color.label_primary));
        ip.setGravity(Gravity.CENTER);
        ip.setSingleLine(true);

        TextView geo = new TextView(this);
        geo.setText(result.geo);
        geo.setTextSize(11);
        geo.setTextColor(getColor(R.color.label_secondary));
        geo.setGravity(Gravity.CENTER);
        geo.setMaxLines(2);

        LinearLayout.LayoutParams ipLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ipLp.topMargin = dp(8);
        ip.setLayoutParams(ipLp);
        LinearLayout.LayoutParams geoLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        geoLp.topMargin = dp(6);
        geo.setLayoutParams(geoLp);

        cell.addView(source);
        cell.addView(ip);
        cell.addView(geo);
        return cell;
    }

    /** 与 iOS getDisplayName 一致的来源显示名 */
    private static String ipSourceName(String source) {
        if (source == null || source.isEmpty()) return "";
        switch (source.toLowerCase(Locale.US)) {
            case "upaiyun": return "UpaiYun";
            case "ipip": return "IPIP.NET";
            case "ipsh":
            case "ipsb": return "IP.SB";
            case "ipify": return "IPIFY";
            default:
                return source.substring(0, 1).toUpperCase(Locale.US) + source.substring(1);
        }
    }

    // =====================================================================
    // 工具
    // =====================================================================

    private View placeholder(int textRes) {
        return placeholderText(getString(textRes));
    }

    /** 卡片里的居中灰字占位(iOS 的 "等待加载..." / "服务未运行") */
    private View placeholderText(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(15);
        view.setTextColor(getColor(R.color.label_tertiary));
        view.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(70));
        view.setLayoutParams(lp);
        return view;
    }

    private String modeForId(int id) {
        if (id == R.id.mode_global) return "global";
        if (id == R.id.mode_direct) return "direct";
        return "rule";
    }

    private int idForMode(String mode) {
        if ("global".equals(mode)) return R.id.mode_global;
        if ("direct".equals(mode)) return R.id.mode_direct;
        return R.id.mode_rule;
    }

    private String modeLabel(String mode) {
        if ("global".equals(mode)) return getString(R.string.proxy_mode_global);
        if ("direct".equals(mode)) return getString(R.string.proxy_mode_direct);
        return getString(R.string.proxy_mode_rule);
    }

    private void fail(ApiError error) {
        alert(getString(R.string.msg_action_failed, error.getMessage()));
    }

    private void alert(String message) {
        // 异步回调回来时页面可能已经关了,再弹窗会 BadTokenException 崩掉
        if (isFinishing() || isDestroyed()) return;
        new MaterialAlertDialogBuilder(this)
                .setMessage(message)
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    private void toast(String message) {
        // 异步回调回来时页面可能已经关了,再弹窗会 BadTokenException 崩掉
        if (isFinishing() || isDestroyed()) return;
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show();
    }

    private static String optStr(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() && o.get(k).isJsonPrimitive()
                ? o.get(k).getAsString() : null;
    }

    private static Boolean asBool(JsonObject o, String k) {
        try {
            return o.get(k).getAsBoolean();
        } catch (Exception e) {
            return null;
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
