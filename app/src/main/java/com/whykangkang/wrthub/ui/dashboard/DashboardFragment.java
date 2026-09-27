package com.whykangkang.wrthub.ui.dashboard;

import android.content.Context;
import android.content.Intent;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.util.TypedValue;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.ColorRes;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.gson.JsonObject;
import com.whykangkang.wrthub.R;
import com.whykangkang.wrthub.api.ApiCallback;
import com.whykangkang.wrthub.api.ApiError;
import com.whykangkang.wrthub.api.OpenWrtApi;
import com.whykangkang.wrthub.manager.FavoriteServicesManager;
import com.whykangkang.wrthub.manager.HomeMetricsConfig;
import com.whykangkang.wrthub.manager.HomeMetricsConfig.HomeMetric;
import com.whykangkang.wrthub.manager.RatingManager;
import com.whykangkang.wrthub.manager.RouterDeviceManager;
import com.whykangkang.wrthub.manager.ServiceCatalog;
import com.whykangkang.wrthub.manager.ServiceCatalog.Descriptor;
import com.whykangkang.wrthub.manager.ServiceCatalog.ServiceId;
import com.whykangkang.wrthub.manager.SettingsManager;
import com.whykangkang.wrthub.model.NetworkDevice;
import com.whykangkang.wrthub.model.RouterInfo;
import com.whykangkang.wrthub.model.StorageInfo;
import com.whykangkang.wrthub.model.WiFiInfo;
import com.whykangkang.wrthub.ui.login.DeviceListActivity;
import com.whykangkang.wrthub.ui.services.wifi.WiFiAddActivity;
import com.whykangkang.wrthub.ui.services.wifi.WiFiEditActivity;
import com.whykangkang.wrthub.ui.services.wifi.WiFiScanActivity;
import com.whykangkang.wrthub.ui.settings.FavoritePickerActivity;
import com.whykangkang.wrthub.util.Formatters;
import com.whykangkang.wrthub.util.NetworkUtils;
import com.whykangkang.wrthub.widget.WidgetSnapshot;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 总览页,对应 iOS DashboardViewController。
 *
 * 结构与 iOS 一一对应:指标网格(按首页编辑配置)→ 常用功能 → 系统信息 →
 * 网络端口 → WiFi → 存储 → 备份升级 → 安全,后六张为可折叠卡片(默认收起)。
 * 定时刷新读设置(默认 1s),isLoadingData 防叠加,onPause 停 onResume 起。
 */
public class DashboardFragment extends Fragment {

    /** 折叠卡片的展开状态(默认收起,与 iOS cardExpandedStates 一致) */
    private final Map<String, Boolean> cardExpanded = new HashMap<>();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private DashboardLoader loader;
    private LatencyProbe latencyProbe;
    private SettingsManager settings;
    private BackupUpgradeController backup;

    private LinearLayout container;
    private SwipeRefreshLayout refresh;
    private View centerLoading;
    private TextView loadingLabel;

    private DashboardData data;
    private boolean isLoadingData;
    private boolean running;

    // 延迟卡:ping 异步返回时单独刷新这两个 label,不重建整页
    private TextView latencyValue;
    private TextView latencySubtitle;
    private Double latencyMs;
    private boolean latencyViaProxy;
    private String latencyProbeHost;

    // 常用功能可用性(按当前设备实时检测,30 秒节流)
    private final Map<ServiceId, Boolean> favoriteAvailability = new HashMap<>();
    private long lastFavoritesCheck;
    private View favoritesCard;

    /** 评分 engagement 每次启动只记一次 */
    private static boolean engagementRecordedThisSession;

    private final Runnable refreshTick = new Runnable() {
        @Override
        public void run() {
            loadData();
            if (running && settings.isAutoRefresh()) {
                handler.postDelayed(this, settings.getRefreshIntervalMs());
            }
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup parent,
                             @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_dashboard, parent, false);
        container = root.findViewById(R.id.content_container);
        refresh = root.findViewById(R.id.refresh);
        centerLoading = root.findViewById(R.id.center_loading);
        loadingLabel = root.findViewById(R.id.loading_label);
        refresh.setOnRefreshListener(this::loadData);
        settings = SettingsManager.getInstance(requireContext());
        latencyProbe = new LatencyProbe(OpenWrtApi.getInstance());
        return root;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 文件选择器必须在 onCreate 之前/之中注册
        backup = new BackupUpgradeController(this);
    }

    @Override
    public void onResume() {
        super.onResume();
        // 回到前台先丢掉可能已失效的复用连接,免得首个请求干等超时
        OpenWrtApi.getInstance().resetSessionIfStale();
        if (loader == null) {
            loader = new DashboardLoader(OpenWrtApi.getInstance(),
                    NetworkUtils.getLocalIpv4(requireContext()));
        }
        if (data == null) {
            showCenterLoading(true, getString(R.string.msg_loading_data));
        }
        running = true;
        handler.post(refreshTick);
    }

    @Override
    public void onPause() {
        super.onPause();
        running = false;
        handler.removeCallbacks(refreshTick);
    }

    // =====================================================================
    // 数据加载
    // =====================================================================

    private void loadData() {
        // 防止刷新叠加:定时器/下拉/onResume 可能同时触发,上一轮没结束就跳过本次
        if (isLoadingData || !OpenWrtApi.getInstance().isConfigured()) {
            refresh.setRefreshing(false);
            return;
        }
        isLoadingData = true;
        loader.load(result -> {
            isLoadingData = false;
            refresh.setRefreshing(false);
            if (!isAdded()) return;
            showCenterLoading(false, null);
            data = result;
            updateUI();
            if (!result.hadError) {
                recordEngagementOnce();
                refreshLatency();
                writeWidgetSnapshot();
            }
        });
    }

    private void showCenterLoading(boolean show, String message) {
        centerLoading.setVisibility(show ? View.VISIBLE : View.GONE);
        if (message != null) loadingLabel.setText(message);
    }

    private void recordEngagementOnce() {
        if (engagementRecordedThisSession || getActivity() == null) return;
        engagementRecordedThisSession = true;
        RatingManager.getInstance(requireContext()).recordEngagement(getActivity());
    }

    /** 把当前仪表盘数据写入 SharedPreferences,供桌面小组件显示 */
    private void writeWidgetSnapshot() {
        if (data == null) return;
        String name = null;
        if (RouterDeviceManager.getInstance(requireContext()).getCurrentDevice() != null) {
            name = RouterDeviceManager.getInstance(requireContext())
                    .getCurrentDevice().getDisplayName();
        }
        if (name == null && data.routerInfo != null) name = data.routerInfo.hostname;
        WidgetSnapshot.write(requireContext(), name != null ? name : "OpenWrt",
                data.wanDownSpeed, data.wanUpSpeed, latencyMs, latencyViaProxy,
                data.connectedDevices.size(),
                data.routerInfo != null && data.routerInfo.cpu != null
                        ? data.routerInfo.cpu.usage : null,
                data.routerInfo != null ? data.routerInfo.memory.usedPercentage() : null,
                DashboardLoader.wanIp(data));
    }

    // =====================================================================
    // 页面装配
    // =====================================================================

    private void updateUI() {
        container.removeAllViews();
        if (data == null || data.routerInfo == null) {
            container.addView(buildConnectionFailedCard());
            return;
        }
        container.addView(buildMetricsGrid());
        favoritesCard = buildFavoritesCard();
        container.addView(favoritesCard);
        container.addView(buildSystemInfoCard(data.routerInfo));
        if (!data.networkDevices.isEmpty()) {
            container.addView(buildNetworkDevicesCard());
        }
        if (!data.wifi.isEmpty()) {
            container.addView(buildWiFiCard());
        }
        if (!data.storage.isEmpty()) {
            container.addView(buildStorageCard());
        }
        container.addView(buildBackupUpgradeCard());
        container.addView(buildSecurityCard());
        refreshFavoritesAvailabilityIfNeeded();
    }

    // =====================================================================
    // 指标网格
    // =====================================================================

    private View buildMetricsGrid() {
        LinearLayout grid = new LinearLayout(requireContext());
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setLayoutParams(marginParams(0, 0, 0, dp(4)));

        List<HomeMetric> visible = HomeMetricsConfig.visible(requireContext());
        LinearLayout row = null;
        int inRow = 0;
        for (HomeMetric metric : visible) {
            View card = buildMetricCard(metric);
            if (card == null) continue;
            if (inRow == 0) {
                row = new LinearLayout(requireContext());
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setLayoutParams(marginParams(0, 0, 0, dp(12)));
                grid.addView(row);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (inRow == 1) lp.setMarginStart(dp(12));
            card.setLayoutParams(lp);
            row.addView(card);
            inRow = (inRow + 1) % 2;
        }
        // 奇数张时补一个占位,保证最后一张不铺满整行
        if (inRow == 1 && row != null) {
            View spacer = new View(requireContext());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            lp.setMarginStart(dp(12));
            spacer.setLayoutParams(lp);
            row.addView(spacer);
        }
        return grid;
    }

    /** 单个指标卡;数据尚未就绪的指标返回 null(不占位),与 iOS makeMetricCard 一致 */
    @Nullable
    private View buildMetricCard(HomeMetric metric) {
        RouterInfo info = data.routerInfo;
        switch (metric) {
            case CPU: {
                if (info.cpu == null) return null;
                StringBuilder sub = new StringBuilder();
                if (info.cpu.count > 0) {
                    sub.append(getString(R.string.metric_cores, info.cpu.count));
                }
                if (info.cpu.temperature != null) {
                    if (sub.length() > 0) sub.append(" · ");
                    sub.append(String.format(Locale.US, "%.1f°C", info.cpu.temperature));
                }
                return metricCard(R.drawable.metric_cpu, R.color.ios_blue,
                        getString(R.string.metric_cpu),
                        String.format(Locale.US, "%.0f%%", info.cpu.usage),
                        sub.length() > 0 ? sub.toString() : null, null);
            }
            case MEMORY: {
                RouterInfo.MemoryInfo mem = info.memory;
                if (mem.total <= 0) return null;
                boolean gb = mem.totalGB() >= 1.0;
                return metricCard(R.drawable.metric_memory, R.color.ios_green,
                        getString(R.string.metric_memory),
                        String.format(Locale.US, gb ? "%.1f%%" : "%.0f%%", mem.usedPercentage()),
                        gb ? String.format(Locale.US, "%.1f/%.1f GB", mem.usedGB(), mem.totalGB())
                                : String.format(Locale.US, "%.0f/%.0f MB",
                                mem.usedMB(), mem.totalMB()),
                        null);
            }
            case CONNECTIONS: {
                if (data.conntrack == null) return null;
                int color = "green".equals(data.conntrack.statusColor()) ? R.color.ios_green
                        : "orange".equals(data.conntrack.statusColor()) ? R.color.ios_orange
                        : R.color.ios_red;
                return metricCard(R.drawable.metric_connections, color,
                        getString(R.string.metric_connections),
                        String.valueOf(data.conntrack.current),
                        String.format(Locale.US, "%.0f%%", data.conntrack.percentage()), null);
            }
            case DEVICES:
                return metricCard(R.drawable.metric_devices, R.color.ios_purple,
                        getString(R.string.metric_devices_short),
                        String.valueOf(data.connectedDevices.size()),
                        getString(R.string.metric_devices), null);
            case SPEED: {
                String down = data.wanDownSpeed != null
                        ? formatSpeedShort(data.wanDownSpeed) : "--";
                String up = "↑ " + (data.wanUpSpeed != null
                        ? formatSpeedShort(data.wanUpSpeed) : "--");
                return metricCard(R.drawable.metric_speed, R.color.ios_teal,
                        getString(R.string.metric_speed), down, up, null);
            }
            case LATENCY:
            default:
                return buildLatencyCard();
        }
    }

    private View metricCard(@DrawableRes int icon, @ColorRes int colorRes, String title,
                            String value, String subtitle, Runnable onClick) {
        View card = LayoutInflater.from(requireContext())
                .inflate(R.layout.view_metric_card, container, false);
        int color = requireContext().getColor(colorRes);
        ImageView iconView = card.findViewById(R.id.metric_icon);
        iconView.setImageResource(icon);
        iconView.setColorFilter(color, PorterDuff.Mode.SRC_IN);
        ((TextView) card.findViewById(R.id.metric_label)).setText(title);
        TextView valueView = card.findViewById(R.id.metric_value);
        valueView.setText(value);
        valueView.setTextColor(color);
        TextView subView = card.findViewById(R.id.metric_subtitle);
        if (subtitle != null) {
            subView.setText(subtitle);
            subView.setVisibility(View.VISIBLE);
        } else {
            subView.setVisibility(View.GONE);
        }
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(16 * getResources().getDisplayMetrics().density);
        bg.setColor((color & 0x00FFFFFF) | 0x1A000000);
        card.setBackground(bg);
        if (onClick != null) card.setOnClickListener(v -> onClick.run());
        return card;
    }

    static String formatSpeedShort(double bps) {
        if (bps >= 1048576) return String.format(Locale.US, "%.1f MB/s", bps / 1048576);
        if (bps >= 1024) return String.format(Locale.US, "%.0f KB/s", bps / 1024);
        return String.format(Locale.US, "%.0f B/s", bps);
    }

    // =====================================================================
    // 延迟卡
    // =====================================================================

    private String latencyHost() {
        return settings.getLatencyTarget();
    }

    private View buildLatencyCard() {
        View card = metricCard(R.drawable.metric_latency, R.color.ios_purple,
                getString(R.string.metric_latency), "--", latencyHost(),
                this::showLatencyTargetDialog);
        latencyValue = card.findViewById(R.id.metric_value);
        latencySubtitle = card.findViewById(R.id.metric_subtitle);
        latencySubtitle.setVisibility(View.VISIBLE);
        updateLatencyLabels();
        return card;
    }

    private void refreshLatency() {
        // 延迟卡被隐藏时不再发起 ping
        if (!HomeMetricsConfig.isVisible(requireContext(), HomeMetric.LATENCY)) return;
        latencyProbe.probe(latencyHost(), result -> {
            latencyMs = result.ms;
            latencyViaProxy = result.viaProxy;
            latencyProbeHost = result.probeHost;
            if (isAdded()) updateLatencyLabels();
        });
    }

    private void updateLatencyLabels() {
        if (latencyValue == null) return;
        if (latencyMs != null) {
            // 局域网目标可能只有 0.x ms,取整会显示成 0
            latencyValue.setText(latencyMs < 10
                    ? String.format(Locale.US, "%.1f ms", latencyMs)
                    : String.format(Locale.US, "%.0f ms", latencyMs));
            latencyValue.setTextColor(requireContext().getColor(latencyColor(latencyMs)));
        } else {
            latencyValue.setText("--");
            latencyValue.setTextColor(requireContext().getColor(R.color.ios_gray));
        }
        if (latencySubtitle == null) return;
        // 经 Clash 代理测得时标注来源;改测线路 IP 时显示真正测的地址
        if (latencyViaProxy) {
            latencySubtitle.setText(latencyHost() + " · " + getString(R.string.latency_via_proxy));
        } else if (latencyProbeHost != null) {
            latencySubtitle.setText(latencyProbeHost + " · "
                    + getString(R.string.latency_fallback));
        } else {
            latencySubtitle.setText(latencyHost());
        }
    }

    @ColorRes
    private int latencyColor(double ms) {
        if (ms < 50) return R.color.ios_green;
        if (ms < 150) return R.color.ios_orange;
        return R.color.ios_red;
    }

    private void showLatencyTargetDialog() {
        EditText input = new EditText(requireContext());
        input.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        input.setText(latencyHost());
        input.setHint("www.baidu.com");
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.latency_target_title)
                .setMessage(R.string.latency_target_msg)
                .setView(padded(input))
                .setPositiveButton(R.string.ok, (d, w) -> {
                    String host = input.getText().toString().trim();
                    if (host.isEmpty()) return;
                    settings.setLatencyTarget(host);
                    latencyMs = null;
                    latencyViaProxy = false;
                    latencyProbeHost = null;
                    latencyProbe.resetClashAvailability();
                    updateLatencyLabels();
                    refreshLatency();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    // =====================================================================
    // 常用功能
    // =====================================================================

    private View buildFavoritesCard() {
        MaterialCardView card = newCard();
        LinearLayout body = cardBody(card);
        body.setPadding(dp(16), dp(16), dp(16), dp(16));

        LinearLayout header = new LinearLayout(requireContext());
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(requireContext());
        icon.setImageResource(R.drawable.ic_star);
        icon.setLayoutParams(new LinearLayout.LayoutParams(dp(20), dp(20)));
        TextView title = new TextView(requireContext());
        title.setText(R.string.fav_title);
        title.setTextSize(18);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setTextColor(requireContext().getColor(R.color.label_primary));
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleLp.setMarginStart(dp(8));
        title.setLayoutParams(titleLp);
        MaterialButton manage = new MaterialButton(requireContext(), null,
                com.google.android.material.R.attr.borderlessButtonStyle);
        manage.setText(R.string.fav_manage);
        manage.setAllCaps(false);
        manage.setMinWidth(0);
        manage.setTextColor(requireContext().getColor(R.color.ios_blue));
        manage.setOnClickListener(v -> openFavoritePicker());
        header.addView(icon);
        header.addView(title);
        header.addView(manage);
        body.addView(header);

        // 每行 4 个磁贴,末尾固定一个「添加」磁贴
        List<Descriptor> favorites =
                FavoriteServicesManager.getInstance(requireContext()).getFavorites();
        LinearLayout row = null;
        int inRow = 0;
        int total = favorites.size() + 1;
        for (int i = 0; i < total; i++) {
            if (inRow == 0) {
                row = new LinearLayout(requireContext());
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setLayoutParams(marginParams(0, dp(16), 0, 0));
                body.addView(row);
            }
            View tile;
            if (i < favorites.size()) {
                Descriptor d = favorites.get(i);
                // 可用性未知时(尚未检测完)默认按可用显示,避免可用项被误灰显闪烁
                Boolean available = favoriteAvailability.get(d.id);
                tile = favoriteTile(d.iconRes, d.iconColorRes, getString(d.titleRes),
                        available == null || available, () -> openFavorite(d));
            } else {
                tile = favoriteTile(R.drawable.ic_plus, R.color.ios_gray,
                        getString(R.string.fav_add), true, this::openFavoritePicker);
            }
            tile.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(tile);
            inRow = (inRow + 1) % 4;
        }
        // 用空视图补满一行,保持磁贴左对齐、宽度一致
        if (inRow != 0 && row != null) {
            for (int i = inRow; i < 4; i++) {
                View spacer = new View(requireContext());
                spacer.setLayoutParams(new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                row.addView(spacer);
            }
        }
        return card;
    }

    private View favoriteTile(@DrawableRes int iconRes, @ColorRes int colorRes,
                              String title, boolean isAvailable, Runnable onClick) {
        LinearLayout tile = new LinearLayout(requireContext());
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER_HORIZONTAL);
        tile.setPadding(dp(2), 0, dp(2), 0);

        int tint = requireContext().getColor(isAvailable ? colorRes : R.color.ios_gray3);
        FrameWrap wrap = new FrameWrap(requireContext());
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(14 * getResources().getDisplayMetrics().density);
        bg.setColor(isAvailable ? (tint & 0x00FFFFFF) | 0x1F000000
                : requireContext().getColor(R.color.ios_gray5));
        wrap.setBackground(bg);
        LinearLayout.LayoutParams wrapLp = new LinearLayout.LayoutParams(dp(48), dp(48));
        wrap.setLayoutParams(wrapLp);
        ImageView icon = new ImageView(requireContext());
        icon.setImageResource(iconRes);
        icon.setColorFilter(tint, PorterDuff.Mode.SRC_IN);
        android.widget.FrameLayout.LayoutParams iconLp =
                new android.widget.FrameLayout.LayoutParams(dp(24), dp(24));
        iconLp.gravity = Gravity.CENTER;
        icon.setLayoutParams(iconLp);
        wrap.addView(icon);

        TextView label = new TextView(requireContext());
        label.setText(title);
        label.setTextSize(12);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER);
        label.setTextColor(requireContext().getColor(
                isAvailable ? R.color.label_primary : R.color.ios_gray));
        label.setLayoutParams(marginParams(0, dp(6), 0, 0));

        tile.addView(wrap);
        tile.addView(label);
        tile.setOnClickListener(v -> onClick.run());
        return tile;
    }

    /** FrameLayout 的薄包装,只为让磁贴图标能居中放在圆角底上 */
    private static class FrameWrap extends android.widget.FrameLayout {
        FrameWrap(Context context) {
            super(context);
        }
    }

    private void openFavorite(Descriptor descriptor) {
        Boolean available = favoriteAvailability.get(descriptor.id);
        if (descriptor.plugin != null && Boolean.FALSE.equals(available)) {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.svc_plugin_missing)
                    .setMessage(getString(R.string.fav_plugin_missing_msg,
                            getString(descriptor.titleRes)))
                    .setPositiveButton(R.string.pkg_got_it, null)
                    .show();
            return;
        }
        descriptor.open(requireContext());
    }

    private void openFavoritePicker() {
        // 收藏变化后强制重新检测可用性
        lastFavoritesCheck = 0;
        startActivity(new Intent(requireContext(), FavoritePickerActivity.class));
    }

    /** 30 秒节流,避免每次定时刷新都打一轮插件检测 */
    private void refreshFavoritesAvailabilityIfNeeded() {
        if (System.currentTimeMillis() - lastFavoritesCheck < 30_000) return;
        lastFavoritesCheck = System.currentTimeMillis();
        List<Descriptor> favorites =
                FavoriteServicesManager.getInstance(requireContext()).getFavorites();
        if (favorites.isEmpty()) return;
        // 全部检测回来后只重建一次卡片,逐项重建会让磁贴闪烁
        java.util.concurrent.atomic.AtomicInteger remaining =
                new java.util.concurrent.atomic.AtomicInteger(favorites.size());
        for (Descriptor d : favorites) {
            d.checkAvailability(new ApiCallback<Boolean>() {
                @Override
                public void onSuccess(Boolean available) {
                    done(available);
                }

                @Override
                public void onFailure(ApiError error) {
                    done(false);
                }

                private void done(boolean available) {
                    favoriteAvailability.put(d.id, available);
                    if (remaining.decrementAndGet() == 0) {
                        rebuildFavoritesCard();
                    }
                }
            });
        }
    }

    /** 仅重建「常用功能」这一张卡片(避免整页刷新闪烁) */
    private void rebuildFavoritesCard() {
        if (!isAdded() || favoritesCard == null) return;
        int index = container.indexOfChild(favoritesCard);
        if (index < 0) return;
        View rebuilt = buildFavoritesCard();
        container.removeViewAt(index);
        container.addView(rebuilt, index);
        favoritesCard = rebuilt;
    }

    // =====================================================================
    // 连接失败
    // =====================================================================

    private View buildConnectionFailedCard() {
        MaterialCardView card = newCard();
        LinearLayout body = cardBody(card);
        body.setPadding(dp(24), dp(32), dp(24), dp(32));
        body.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(requireContext());
        title.setText(R.string.conn_failed_title);
        title.setTextSize(18);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setTextColor(requireContext().getColor(R.color.label_primary));
        title.setGravity(Gravity.CENTER);

        TextView message = new TextView(requireContext());
        message.setText(R.string.conn_failed_msg);
        message.setTextSize(15);
        message.setTextColor(requireContext().getColor(R.color.label_secondary));
        message.setGravity(Gravity.CENTER);
        message.setLayoutParams(marginParams(0, dp(12), 0, dp(20)));

        MaterialButton retry = new MaterialButton(requireContext());
        retry.setText(R.string.conn_retry);
        retry.setAllCaps(false);
        retry.setBackgroundColor(requireContext().getColor(R.color.ios_blue));
        retry.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        retry.setOnClickListener(v -> loadData());

        MaterialButton toLogin = new MaterialButton(requireContext(), null,
                com.google.android.material.R.attr.borderlessButtonStyle);
        toLogin.setText(R.string.conn_switch_device);
        toLogin.setAllCaps(false);
        toLogin.setTextColor(requireContext().getColor(R.color.ios_blue));
        toLogin.setLayoutParams(marginParams(0, dp(8), 0, 0));
        toLogin.setOnClickListener(v -> {
            Intent intent = new Intent(requireContext(), DeviceListActivity.class);
            // 连不上才点的这个按钮,必须真的停在设备列表,不能被「已登录」逻辑弹回首页
            intent.putExtra(DeviceListActivity.EXTRA_PICK_DEVICE, true);
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            requireActivity().finish();
        });

        body.addView(title);
        body.addView(message);
        body.addView(retry);
        body.addView(toLogin);
        return card;
    }

    // =====================================================================
    // 系统信息 / 网络端口 / WiFi / 存储 / 备份 / 安全
    // =====================================================================

    private View buildSystemInfoCard(RouterInfo info) {
        LinearLayout content = verticalBox();
        addInfoRow(content, R.string.info_hostname, info.hostname);
        if (notEmpty(info.model)) addInfoRow(content, R.string.info_model, info.model);
        if (notEmpty(info.system)) addInfoRow(content, R.string.info_arch, info.system);
        if (notEmpty(info.release)) addInfoRow(content, R.string.info_firmware, info.release);
        if (notEmpty(info.kernel)) addInfoRow(content, R.string.info_kernel, info.kernel);
        addInfoRow(content, R.string.info_uptime, info.uptimeDetail());
        addInfoRow(content, R.string.info_load, info.loadavgText());
        return collapsibleCard("system", R.drawable.ic_server, R.color.ios_blue,
                getString(R.string.section_system_info), content, null, null);
    }

    private View buildNetworkDevicesCard() {
        LinearLayout content = verticalBox();
        // 网桥成员数按各设备的 master 字段统计,不依赖固件的成员列表字段名
        Map<String, Integer> memberCounts = new HashMap<>();
        for (NetworkDevice d : data.networkDevices) {
            if (d.master != null && !d.master.isEmpty()) {
                Integer n = memberCounts.get(d.master);
                memberCounts.put(d.master, n == null ? 1 : n + 1);
            }
        }
        int shown = 0;
        for (NetworkDevice device : data.networkDevices) {
            if (device.isKernelPlaceholder()) continue;
            if (!(device.isPhysicalPort() || device.isBridge() || device.isVpn())) continue;
            if (shown >= 6) break;
            if (shown > 0) content.addView(separator());
            content.addView(networkDeviceRow(device, memberCounts.get(device.name)));
            shown++;
        }
        return collapsibleCard("network", R.drawable.svc_interfaces, R.color.ios_blue,
                getString(R.string.section_network), content, null, null);
    }

    private View networkDeviceRow(NetworkDevice device, Integer bridgeMembers) {
        LinearLayout box = verticalBox();
        box.setPadding(0, dp(10), 0, dp(10));

        LinearLayout top = new LinearLayout(requireContext());
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = new TextView(requireContext());
        name.setText(device.displayName(requireContext()));
        name.setTextSize(16);
        name.setTypeface(name.getTypeface(), android.graphics.Typeface.BOLD);
        name.setTextColor(requireContext().getColor(R.color.label_primary));
        name.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView status = new TextView(requireContext());
        status.setText(device.statusText(requireContext(), bridgeMembers));
        status.setTextSize(14);
        status.setTextColor(requireContext().getColor(statusColorRes(device.statusColor())));
        top.addView(name);
        top.addView(status);
        box.addView(top);

        if (device.stats.rxBytes > 0 || device.stats.txBytes > 0) {
            LinearLayout traffic = new LinearLayout(requireContext());
            traffic.setOrientation(LinearLayout.HORIZONTAL);
            traffic.setLayoutParams(marginParams(0, dp(6), 0, 0));
            traffic.addView(smallLabel("↓ " + device.rxBytesFormatted(), R.color.ios_blue, 0));
            traffic.addView(smallLabel("↑ " + device.txBytesFormatted(), R.color.ios_orange,
                    dp(16)));
            TextView total = smallLabel(getString(R.string.net_total,
                    device.totalBytesFormatted()), R.color.label_secondary, dp(16));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMarginStart(dp(16));
            total.setLayoutParams(lp);
            total.setGravity(Gravity.END);
            traffic.addView(total);
            box.addView(traffic);
        }
        if (!device.ipaddrs.isEmpty()) {
            TextView ip = smallLabel("IP: " + device.ipaddrs.get(0).address,
                    R.color.label_tertiary, 0);
            ip.setTextSize(12);
            ip.setLayoutParams(marginParams(0, dp(4), 0, 0));
            box.addView(ip);
        }
        return box;
    }

    private View buildWiFiCard() {
        LinearLayout content = verticalBox();
        for (int i = 0; i < data.wifi.size(); i++) {
            if (i > 0) content.addView(separator());
            content.addView(wifiRow(data.wifi.get(i)));
        }
        // 底部扫描 / 添加按钮
        LinearLayout buttons = new LinearLayout(requireContext());
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setLayoutParams(marginParams(0, dp(12), 0, dp(4)));
        MaterialButton scan = new MaterialButton(requireContext());
        scan.setText(R.string.wifi_scan_short);
        scan.setAllCaps(false);
        scan.setTextSize(16);
        scan.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        scan.setCornerRadius(dp(8));
        scan.setInsetTop(0);
        scan.setInsetBottom(0);
        scan.setBackgroundColor(requireContext().getColor(R.color.ios_blue));
        LinearLayout.LayoutParams scanLp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        scan.setLayoutParams(scanLp);
        scan.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), WiFiScanActivity.class)));
        MaterialButton add = new MaterialButton(requireContext());
        add.setText(R.string.wifi_add_short);
        add.setAllCaps(false);
        add.setTextSize(16);
        add.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        add.setCornerRadius(dp(8));
        add.setInsetTop(0);
        add.setInsetBottom(0);
        add.setBackgroundColor(requireContext().getColor(R.color.ios_green));
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        addLp.setMarginStart(dp(12));
        add.setLayoutParams(addLp);
        add.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), WiFiAddActivity.class)));
        buttons.addView(scan);
        buttons.addView(add);
        content.addView(buttons);

        return collapsibleCard("wifi", R.drawable.ic_wifi, R.color.ios_blue,
                getString(R.string.section_wifi), content, null, null);
    }

    private View wifiRow(WiFiInfo wifi) {
        LinearLayout box = verticalBox();
        box.setPadding(0, dp(10), 0, dp(10));

        LinearLayout header = new LinearLayout(requireContext());
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        View dot = new View(requireContext());
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dotBg.setColor(requireContext().getColor(
                wifi.enabled ? R.color.ios_green : R.color.ios_gray));
        dot.setBackground(dotBg);
        dot.setLayoutParams(new LinearLayout.LayoutParams(dp(8), dp(8)));

        TextView ssid = new TextView(requireContext());
        ssid.setText(wifi.ssid);
        ssid.setTextSize(16);
        // iOS 用的是 semibold,Android 的 BOLD 偏重,sans-serif-medium 才对得上
        ssid.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        ssid.setTextColor(requireContext().getColor(
                wifi.enabled ? R.color.label_primary : R.color.label_secondary));
        LinearLayout.LayoutParams ssidLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ssidLp.setMarginStart(dp(8));
        ssid.setLayoutParams(ssidLp);

        header.addView(dot);
        header.addView(ssid);
        if (!wifi.enabled) {
            TextView disabled = new TextView(requireContext());
            disabled.setText(R.string.wifi_disabled);
            disabled.setTextSize(12);
            disabled.setTextColor(requireContext().getColor(R.color.ios_red));
            // 这里是**横向**布局,不能用 marginParams —— 它返回的是 MATCH_PARENT 宽度,
            // 会把整行吃光:SSID(weight 1)被挤成 0 宽后逐字换行撑出一大片空白,
            // 频段徽章和编辑按钮则被挤出屏幕。禁用的网络才走到这里,所以只有它显示异常。
            LinearLayout.LayoutParams disabledLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            disabledLp.setMarginStart(dp(8));
            disabled.setLayoutParams(disabledLp);
            header.addView(disabled);
        }
        TextView badge = new TextView(requireContext());
        badge.setText(wifi.frequency());
        badge.setTextSize(11);
        badge.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        badge.setTextColor(requireContext().getColor(R.color.white));
        // iOS:最小宽 45、固定高 18、文字居中
        badge.setMinWidth(dp(45));
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(6), 0, dp(6), 0);
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setCornerRadius(6 * getResources().getDisplayMetrics().density);
        badgeBg.setColor(requireContext().getColor(
                wifi.enabled ? R.color.ios_green : R.color.ios_gray));
        badge.setBackground(badgeBg);
        LinearLayout.LayoutParams badgeLp =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(18));
        badgeLp.setMarginStart(dp(8));
        badge.setLayoutParams(badgeLp);
        header.addView(badge);

        // iOS 用的是 pencil.circle.fill 图标(28x28),不是「编辑」文字按钮
        ImageView edit = new ImageView(requireContext());
        edit.setImageResource(R.drawable.ic_edit_circle);
        edit.setContentDescription(getString(R.string.action_edit));
        LinearLayout.LayoutParams editLp = new LinearLayout.LayoutParams(dp(28), dp(28));
        editLp.setMarginStart(dp(8));
        edit.setLayoutParams(editLp);
        TypedValue ripple = new TypedValue();
        requireContext().getTheme().resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless, ripple, true);
        edit.setBackgroundResource(ripple.resourceId);
        edit.setOnClickListener(v -> {
            // 原来只传了 section 和 ssid,其余全走默认值 —— 编辑页显示的加密方式、
            // 启用状态、工作模式/频段/信道因此全是错的或空的
            Intent intent = new Intent(requireContext(), WiFiEditActivity.class);
            intent.putExtra(WiFiEditActivity.EXTRA_SECTION, wifi.id);
            intent.putExtra(WiFiEditActivity.EXTRA_SSID, wifi.ssid);
            intent.putExtra(WiFiEditActivity.EXTRA_ENCRYPTION, wifi.encryptionRaw);
            intent.putExtra(WiFiEditActivity.EXTRA_PASSWORD, wifi.password);
            intent.putExtra(WiFiEditActivity.EXTRA_RADIO, wifi.device);
            intent.putExtra(WiFiEditActivity.EXTRA_MODE, wifi.workMode);
            intent.putExtra(WiFiEditActivity.EXTRA_CHANNEL, wifi.channel);
            // 用 uci 的 disabled,不是运行时的 enabled(见 WiFiInfo.uciDisabled 注释)
            intent.putExtra(WiFiEditActivity.EXTRA_DISABLED, wifi.uciDisabled);
            startActivity(intent);
        });
        header.addView(edit);
        box.addView(header);

        LinearLayout details = new LinearLayout(requireContext());
        details.setOrientation(LinearLayout.HORIZONTAL);
        details.setLayoutParams(marginParams(0, dp(8), 0, 0));
        details.addView(detailCell(getString(R.string.wifi_mode), wifi.workModeText(), false));
        details.addView(detailCell(getString(R.string.wifi_channel_label),
                String.valueOf(wifi.channel), false));
        details.addView(detailCell(getString(R.string.wifi_encryption), wifi.encryption, false));
        details.addView(detailCell(getString(R.string.wifi_clients), wifi.clientsText(), true));
        box.addView(details);
        return box;
    }

    /** 无线卡片里的「模式/频道/加密/设备」小格。iOS 是左对齐,不是居中。 */
    private View detailCell(String title, String value, boolean last) {
        LinearLayout cell = new LinearLayout(requireContext());
        cell.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        // iOS detailsStack.spacing = 12
        if (!last) cellLp.setMarginEnd(dp(12));
        cell.setLayoutParams(cellLp);

        TextView t = new TextView(requireContext());
        t.setText(title);
        t.setTextSize(11);
        t.setTextColor(requireContext().getColor(R.color.label_secondary));

        TextView v = new TextView(requireContext());
        v.setText(value);
        v.setTextSize(13);
        v.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        v.setSingleLine(true);
        v.setEllipsize(android.text.TextUtils.TruncateAt.END);
        v.setTextColor(requireContext().getColor(R.color.label_primary));
        v.setLayoutParams(marginParams(0, dp(2), 0, 0));

        cell.addView(t);
        cell.addView(v);
        return cell;
    }

    private View buildStorageCard() {
        LinearLayout content = verticalBox();
        for (int i = 0; i < data.storage.size(); i++) {
            if (i > 0) content.addView(separator());
            content.addView(storageRow(data.storage.get(i)));
        }
        return collapsibleCard("storage", R.drawable.svc_nas, R.color.ios_indigo,
                getString(R.string.section_storage), content, null, null);
    }

    private View storageRow(StorageInfo storage) {
        LinearLayout box = verticalBox();
        box.setPadding(0, dp(10), 0, dp(10));

        LinearLayout top = new LinearLayout(requireContext());
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = new TextView(requireContext());
        name.setText(storage.displayName(requireContext()));
        name.setTextSize(15);
        name.setTypeface(name.getTypeface(), android.graphics.Typeface.BOLD);
        name.setTextColor(requireContext().getColor(R.color.label_primary));
        name.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView percent = new TextView(requireContext());
        percent.setText(String.format(Locale.US, "%.1f%%", storage.usedPercentage()));
        percent.setTextSize(14);
        percent.setTextColor(requireContext().getColor(statusColorRes(storage.statusColor())));
        top.addView(name);
        top.addView(percent);
        box.addView(top);

        ProgressBar bar = new ProgressBar(requireContext(), null,
                android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress((int) Math.round(storage.usedPercentage()));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(6));
        barLp.topMargin = dp(8);
        bar.setLayoutParams(barLp);
        box.addView(bar);

        LinearLayout bottom = new LinearLayout(requireContext());
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setLayoutParams(marginParams(0, dp(6), 0, 0));
        bottom.addView(evenLabel(getString(R.string.storage_total,
                storage.formattedSize()), Gravity.START));
        bottom.addView(evenLabel(getString(R.string.storage_used,
                storage.formattedUsed()), Gravity.CENTER));
        bottom.addView(evenLabel(getString(R.string.storage_avail,
                storage.formattedAvail()), Gravity.END));
        box.addView(bottom);
        return box;
    }

    private View buildBackupUpgradeCard() {
        LinearLayout content = verticalBox();
        TextView version = new TextView(requireContext());
        version.setText(data.routerInfo != null && notEmpty(data.routerInfo.release)
                ? data.routerInfo.release : getString(R.string.backup_unknown_version));
        version.setTextSize(14);
        version.setTextColor(requireContext().getColor(R.color.label_secondary));
        version.setPadding(0, dp(8), 0, dp(8));
        content.addView(version);
        content.addView(separator());
        content.addView(actionRow(R.drawable.ic_backup, R.color.ios_blue,
                R.string.backup_config, R.string.backup_config_sub,
                () -> backup.startBackup()));
        content.addView(actionRow(R.drawable.ic_restore, R.color.ios_green,
                R.string.restore_config, R.string.restore_config_sub,
                () -> backup.pickBackupFile()));
        content.addView(separator());
        content.addView(actionRow(R.drawable.ic_upgrade, R.color.ios_orange,
                R.string.upgrade_firmware, R.string.upgrade_firmware_sub,
                () -> backup.pickFirmwareFile()));
        return collapsibleCard("backup", R.drawable.ic_backup, R.color.ios_blue,
                getString(R.string.section_backup), content, null, null);
    }

    private View buildSecurityCard() {
        LinearLayout content = verticalBox();
        content.addView(actionRow(R.drawable.ic_key, R.color.ios_orange,
                R.string.security_change_password, R.string.security_change_password_sub,
                this::showChangePasswordDialog));
        return collapsibleCard("security", R.drawable.svc_proxy, R.color.ios_orange,
                getString(R.string.section_security), content, null, null);
    }

    private View actionRow(@DrawableRes int iconRes, @ColorRes int colorRes,
                           int titleRes, int subtitleRes, Runnable onClick) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, dp(12));
        row.setBackgroundResource(outValueSelectableBackground());

        ImageView icon = new ImageView(requireContext());
        icon.setImageResource(iconRes);
        icon.setColorFilter(requireContext().getColor(colorRes), PorterDuff.Mode.SRC_IN);
        icon.setLayoutParams(new LinearLayout.LayoutParams(dp(24), dp(24)));

        LinearLayout texts = new LinearLayout(requireContext());
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textLp.setMarginStart(dp(12));
        texts.setLayoutParams(textLp);
        TextView title = new TextView(requireContext());
        title.setText(titleRes);
        title.setTextSize(16);
        title.setTextColor(requireContext().getColor(R.color.label_primary));
        TextView subtitle = new TextView(requireContext());
        subtitle.setText(subtitleRes);
        subtitle.setTextSize(13);
        subtitle.setTextColor(requireContext().getColor(R.color.label_secondary));
        texts.addView(title);
        texts.addView(subtitle);

        ImageView chevron = new ImageView(requireContext());
        chevron.setImageResource(R.drawable.ic_chevron);
        chevron.setLayoutParams(new LinearLayout.LayoutParams(dp(8), dp(14)));

        row.addView(icon);
        row.addView(texts);
        row.addView(chevron);
        row.setOnClickListener(v -> onClick.run());
        return row;
    }

    private int outValueSelectableBackground() {
        android.util.TypedValue outValue = new android.util.TypedValue();
        requireContext().getTheme().resolveAttribute(
                android.R.attr.selectableItemBackground, outValue, true);
        return outValue.resourceId;
    }

    // =====================================================================
    // 修改管理员密码
    // =====================================================================

    private void showChangePasswordDialog() {
        LinearLayout form = new LinearLayout(requireContext());
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        form.setPadding(pad, dp(8), pad, 0);
        EditText pwd = passwordInput(getString(R.string.security_new_password));
        EditText confirm = passwordInput(getString(R.string.security_confirm_password));
        form.addView(pwd);
        form.addView(confirm);

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.security_change_password_title)
                .setMessage(R.string.security_change_password_msg)
                .setView(form)
                .setPositiveButton(R.string.security_confirm_change, (d, w) -> {
                    String newPassword = pwd.getText().toString();
                    String confirmPassword = confirm.getText().toString();
                    if (newPassword.isEmpty()) {
                        alert(getString(R.string.security_password_empty));
                        return;
                    }
                    if (!newPassword.equals(confirmPassword)) {
                        alert(getString(R.string.security_password_mismatch));
                        return;
                    }
                    confirmPasswordChange(newPassword);
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void confirmPasswordChange(String newPassword) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.security_confirm_title)
                .setMessage(R.string.security_confirm_msg)
                .setPositiveButton(R.string.ok, (d, w) -> performPasswordChange(newPassword))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void performPasswordChange(String newPassword) {
        String username = OpenWrtApi.getInstance().getUsername();
        if (username == null || username.isEmpty()) {
            alert(getString(R.string.security_no_username));
            return;
        }
        JsonObject params = new JsonObject();
        params.addProperty("username", username);
        params.addProperty("password", newPassword);
        OpenWrtApi.getInstance().makeUbusCall("luci", "setPassword", params, 20,
                new ApiCallback<JsonObject>() {
                    @Override
                    public void onSuccess(JsonObject result) {
                        alert(getString(R.string.security_password_changed));
                    }

                    @Override
                    public void onFailure(ApiError error) {
                        // 多数固件不放行 luci.setPassword,直接告诉用户去网页端改
                        alert(getString(R.string.security_change_unavailable));
                    }
                });
    }

    private EditText passwordInput(String hint) {
        EditText e = new EditText(requireContext());
        e.setHint(hint);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return e;
    }

    // =====================================================================
    // 视图工具
    // =====================================================================

    /**
     * 可折叠卡片:点标题行展开/收起,状态记在 cardExpanded(默认收起)。
     * actionText 非空时在标题右侧放一个操作按钮(对应 iOS 的 createCollapsibleCardWithAction)。
     */
    private View collapsibleCard(String cardId, @DrawableRes int iconRes,
                                 @ColorRes int colorRes, String title, View content,
                                 String actionText, Runnable action) {
        MaterialCardView card = newCard();
        LinearLayout body = cardBody(card);
        View header = LayoutInflater.from(requireContext())
                .inflate(R.layout.view_card_header, body, false);
        ImageView icon = header.findViewById(R.id.card_icon);
        icon.setImageResource(iconRes);
        icon.setColorFilter(requireContext().getColor(colorRes), PorterDuff.Mode.SRC_IN);
        ((TextView) header.findViewById(R.id.card_title)).setText(title);
        ImageView chevron = header.findViewById(R.id.card_chevron);
        MaterialButton actionButton = header.findViewById(R.id.card_action);
        if (actionText != null && action != null) {
            actionButton.setVisibility(View.VISIBLE);
            actionButton.setText(actionText);
            actionButton.setOnClickListener(v -> action.run());
        }
        body.addView(header);

        LinearLayout holder = new LinearLayout(requireContext());
        holder.setOrientation(LinearLayout.VERTICAL);
        holder.setPadding(dp(16), 0, dp(16), dp(12));
        holder.addView(content);
        body.addView(holder);

        boolean expanded = Boolean.TRUE.equals(cardExpanded.get(cardId));
        holder.setVisibility(expanded ? View.VISIBLE : View.GONE);
        chevron.setRotation(expanded ? 180 : 0);
        header.setOnClickListener(v -> {
            boolean next = holder.getVisibility() != View.VISIBLE;
            cardExpanded.put(cardId, next);
            holder.setVisibility(next ? View.VISIBLE : View.GONE);
            chevron.setRotation(next ? 180 : 0);
        });
        return card;
    }

    private MaterialCardView newCard() {
        MaterialCardView card = (MaterialCardView) LayoutInflater.from(requireContext())
                .inflate(R.layout.view_section_card, container, false);
        card.setLayoutParams(marginParams(0, 0, 0, dp(16)));
        return card;
    }

    private LinearLayout cardBody(MaterialCardView card) {
        return card.findViewById(R.id.section_rows);
    }

    private LinearLayout verticalBox() {
        LinearLayout box = new LinearLayout(requireContext());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }

    private void addInfoRow(LinearLayout parent, int labelRes, String value) {
        View row = LayoutInflater.from(requireContext())
                .inflate(R.layout.view_info_row, parent, false);
        ((TextView) row.findViewById(R.id.row_label)).setText(labelRes);
        ((TextView) row.findViewById(R.id.row_value)).setText(value);
        parent.addView(row);
    }

    private TextView smallLabel(String text, @ColorRes int colorRes, int startMargin) {
        TextView label = new TextView(requireContext());
        label.setText(text);
        label.setTextSize(13);
        label.setTextColor(requireContext().getColor(colorRes));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(startMargin);
        label.setLayoutParams(lp);
        return label;
    }

    private TextView evenLabel(String text, int gravity) {
        TextView label = new TextView(requireContext());
        label.setText(text);
        label.setTextSize(12);
        label.setTextColor(requireContext().getColor(R.color.label_secondary));
        label.setGravity(gravity);
        label.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return label;
    }

    @ColorRes
    private int statusColorRes(String name) {
        switch (name) {
            case "green":
                return R.color.ios_green;
            case "orange":
                return R.color.ios_orange;
            case "red":
                return R.color.ios_red;
            case "gray":
                return R.color.ios_gray;
            default:
                return R.color.label_secondary;
        }
    }

    private View separator() {
        View line = new View(requireContext());
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
        line.setLayoutParams(lp);
        line.setBackgroundColor(requireContext().getColor(R.color.separator));
        return line;
    }

    private View padded(View child) {
        LinearLayout box = new LinearLayout(requireContext());
        int pad = dp(20);
        box.setPadding(pad, dp(8), pad, 0);
        box.addView(child, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }

    private LinearLayout.LayoutParams marginParams(int start, int top, int end, int bottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(start, top, end, bottom);
        return lp;
    }

    void alert(String message) {
        if (!isAdded()) return;
        new MaterialAlertDialogBuilder(requireContext())
                .setMessage(message)
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.isEmpty();
    }

    int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
