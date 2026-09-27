package com.whykangkang.wrthub.ui.services;

import android.content.Intent;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.fragment.app.Fragment;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.whykangkang.wrthub.R;
import com.whykangkang.wrthub.api.ApiCallback;
import com.whykangkang.wrthub.api.ApiError;
import com.whykangkang.wrthub.manager.ServiceCatalog;
import com.whykangkang.wrthub.manager.ServiceCatalog.Descriptor;
import com.whykangkang.wrthub.manager.ServiceCatalog.ServiceId;
import com.whykangkang.wrthub.ui.services.packages.PackageManagerActivity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务页,对应 iOS ServicesViewController。
 *
 * 三组入口:网络服务 / 文件服务 / 系统服务,每项带彩色图标与副标题。
 * 依赖插件的功能在 onResume 时检测安装状态(30 秒节流,见 PluginChecker),
 * 未安装的置灰;点进去弹「插件未安装」并可一键跳到软件包管理预填搜索。
 */
public class ServicesFragment extends Fragment {

    private static class Group {
        @StringRes
        final int titleRes;
        final List<Descriptor> items;

        Group(int titleRes, List<Descriptor> items) {
            this.titleRes = titleRes;
            this.items = items;
        }
    }

    private final List<Group> groups = new ArrayList<>();
    /** ServiceId -> 该行的视图,检测回来后就地更新样式 */
    private final Map<ServiceId, View> rowViews = new HashMap<>();
    /** ServiceId -> 是否可用 */
    private final Map<ServiceId, Boolean> availability = new HashMap<>();

    /**
     * 新功能:标题后显示「NEW」标签,用户第一次打开后消失(与 iOS newBadgeKey 一致)。
     * 值是 SharedPreferences 里记录「已看过」的 key。
     */
    private static final Map<ServiceId, String> NEW_BADGES = new HashMap<>();

    static {
        NEW_BADGES.put(ServiceId.TERMINAL, "newBadgeSeen.aiTerminal");
    }

    private static final String BADGE_PREFS = "new_badges";

    private boolean isNew(ServiceId id) {
        String key = NEW_BADGES.get(id);
        return key != null && !requireContext().getSharedPreferences(BADGE_PREFS, 0)
                .getBoolean(key, false);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_services, container, false);
        buildGroups();
        render(inflater, root.findViewById(R.id.services_container));
        return root;
    }

    /** 上次插件检测时刻;30 秒内不重复查(对应 iOS 的 checkInterval) */
    private long lastPluginCheckMs;

    @Override
    public void onResume() {
        super.onResume();
        long now = System.currentTimeMillis();
        if (lastPluginCheckMs != 0 && now - lastPluginCheckMs < 30_000L) return;
        lastPluginCheckMs = now;
        checkPluginAvailability();
    }

    private void buildGroups() {
        groups.clear();
        groups.add(new Group(R.string.svc_group_network, ServiceCatalog.group(
                ServiceId.FIREWALL,
                ServiceId.NETWORK_INTERFACES,
                ServiceId.NETWORK_DIAGNOSTICS,
                ServiceId.PROXY,
                ServiceId.DEVICE_RATE_LIMIT,
                ServiceId.VNSTAT,
                ServiceId.SPEEDTEST)));
        groups.add(new Group(R.string.svc_group_file, ServiceCatalog.group(
                ServiceId.SAMBA,
                ServiceId.FILEBROWSER,
                ServiceId.FTP)));
        groups.add(new Group(R.string.svc_group_system, ServiceCatalog.group(
                ServiceId.TERMINAL,
                ServiceId.WOL,
                ServiceId.SSH,
                ServiceId.LOGS,
                ServiceId.PACKAGES,
                ServiceId.CRON,
                ServiceId.LED)));
    }

    private void render(LayoutInflater inflater, LinearLayout host) {
        host.removeAllViews();
        rowViews.clear();
        for (Group group : groups) {
            host.addView(makeSectionHeader(group.titleRes));
            MaterialCardView card = (MaterialCardView) inflater.inflate(
                    R.layout.view_section_card, host, false);
            LinearLayout rows = card.findViewById(R.id.section_rows);
            for (int i = 0; i < group.items.size(); i++) {
                Descriptor item = group.items.get(i);
                View row = inflater.inflate(R.layout.view_service_cell, rows, false);
                ((TextView) row.findViewById(R.id.service_title)).setText(item.titleRes);
                ((TextView) row.findViewById(R.id.service_subtitle)).setText(item.subtitleRes);
                ((ImageView) row.findViewById(R.id.service_icon)).setImageResource(item.iconRes);
                row.findViewById(R.id.service_new_badge).setVisibility(
                        isNew(item.id) ? View.VISIBLE : View.GONE);
                row.setOnClickListener(v -> openItem(item));
                rows.addView(row);
                rowViews.put(item.id, row);
                // 内置功能立即上色;插件功能先按上次结果(没有则先按可用)渲染,检测回来再更新
                Boolean known = availability.get(item.id);
                applyStyle(row, item, known == null || known);
                if (i < group.items.size() - 1) {
                    rows.addView(makeSeparator());
                }
            }
            host.addView(card);
        }
    }

    /**
     * 可用:图标彩色 + 10% 同色底;不可用:整行灰掉、透明度 0.6
     * (与 iOS ServiceCell.configure 的两套样式一致)
     */
    private void applyStyle(View row, Descriptor item, boolean isAvailable) {
        ImageView icon = row.findViewById(R.id.service_icon);
        View iconBg = row.findViewById(R.id.icon_container);
        TextView title = row.findViewById(R.id.service_title);
        TextView subtitle = row.findViewById(R.id.service_subtitle);
        ImageView chevron = row.findViewById(R.id.service_chevron);

        int color = isAvailable
                ? requireContext().getColor(item.iconColorRes)
                : requireContext().getColor(R.color.ios_gray3);
        icon.setColorFilter(color, PorterDuff.Mode.SRC_IN);

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(20 * getResources().getDisplayMetrics().density);
        bg.setColor(isAvailable
                ? (color & 0x00FFFFFF) | 0x1A000000
                : requireContext().getColor(R.color.ios_gray5));
        iconBg.setBackground(bg);

        title.setTextColor(requireContext().getColor(
                isAvailable ? R.color.label_primary : R.color.ios_gray));
        subtitle.setTextColor(requireContext().getColor(
                isAvailable ? R.color.label_secondary : R.color.ios_gray3));
        chevron.setColorFilter(requireContext().getColor(
                isAvailable ? R.color.ios_gray3 : R.color.ios_gray5), PorterDuff.Mode.SRC_IN);
        row.setAlpha(isAvailable ? 1f : 0.6f);
    }

    // =====================================================================
    // 插件可用性
    // =====================================================================

    private void checkPluginAvailability() {
        for (Group group : groups) {
            for (Descriptor item : group.items) {
                if (item.plugin == null) continue;
                item.checkAvailability(new ApiCallback<Boolean>() {
                    @Override
                    public void onSuccess(Boolean installed) {
                        updateAvailability(item, installed);
                    }

                    @Override
                    public void onFailure(ApiError error) {
                        updateAvailability(item, false);
                    }
                });
            }
        }
    }

    private void updateAvailability(Descriptor item, boolean installed) {
        availability.put(item.id, installed);
        if (!isAdded()) return;
        View row = rowViews.get(item.id);
        if (row != null) {
            applyStyle(row, item, installed);
        }
    }

    private void openItem(Descriptor item) {
        Boolean available = availability.get(item.id);
        if (item.plugin != null && Boolean.FALSE.equals(available)) {
            showPluginNotInstalledAlert(item);
            return;
        }
        // 真正能打开时才算看过;插件没装只弹提示,标签先留着
        String badgeKey = NEW_BADGES.get(item.id);
        if (badgeKey != null) {
            requireContext().getSharedPreferences(BADGE_PREFS, 0).edit()
                    .putBoolean(badgeKey, true).apply();
            View row = rowViews.get(item.id);
            if (row != null) row.findViewById(R.id.service_new_badge).setVisibility(View.GONE);
        }
        item.open(requireContext());
    }

    /**
     * 插件未安装提示。点「去安装」直接进软件包管理并预填搜索,
     * 省得用户自己找名字(与 iOS showPluginNotInstalledAlert 一致)。
     */
    private void showPluginNotInstalledAlert(Descriptor item) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.svc_plugin_missing)
                .setMessage(item.notInstalledMsgRes)
                .setPositiveButton(R.string.svc_go_install, (d, w) -> {
                    Intent intent = new Intent(requireContext(), PackageManagerActivity.class);
                    intent.putExtra(PackageManagerActivity.EXTRA_PREFILL, item.installKeyword);
                    startActivity(intent);
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    // =====================================================================
    // 视图工具
    // =====================================================================

    private TextView makeSectionHeader(@StringRes int titleRes) {
        TextView header = new TextView(requireContext());
        header.setText(titleRes);
        header.setTextAppearance(R.style.TextAppearance_Wrthub_SectionHeader);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(4), dp(16), 0, dp(8));
        header.setLayoutParams(lp);
        return header;
    }

    private View makeSeparator() {
        View line = new View(requireContext());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.setMarginStart(dp(68));   // 与图标右缘对齐
        line.setLayoutParams(lp);
        line.setBackgroundColor(requireContext().getColor(R.color.separator));
        return line;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
