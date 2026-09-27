package com.whykangkang.wrthub.ui.services.firewall;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.whykangkang.wrthub.R;
import com.whykangkang.wrthub.api.ApiCallback;
import com.whykangkang.wrthub.api.ApiError;
import com.whykangkang.wrthub.api.OpenWrtApi;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 防火墙管理,对应 iOS FirewallManagementViewController。
 * 顶部分段:状态 / 端口转发 / 流量规则,三段共用一次 uci get firewall 的结果。
 */
public class FirewallManagementActivity extends AppCompatActivity {

    private static final int SEG_STATUS = 0;
    private static final int SEG_PORT_FORWARD = 1;
    private static final int SEG_TRAFFIC = 2;

    /** 一条规则(端口转发 / 流量规则通用) */
    private static class Rule {
        String section;
        String name;
        String detail;
    }

    /** 一个防火墙区域 */
    private static class Zone {
        String name;
        String input;
        String output;
        String forward;
    }

    private int segment = SEG_STATUS;

    private final List<Zone> zones = new ArrayList<>();
    private final List<Rule> portForwards = new ArrayList<>();
    private final List<Rule> trafficRules = new ArrayList<>();

    private LinearLayout container;
    private MaterialButton btnRestart;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_firewall_management);
        container = findViewById(R.id.segment_container);

        MaterialButtonToggleGroup tabs = findViewById(R.id.tabs);
        tabs.check(R.id.tab_status);
        tabs.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            if (checkedId == R.id.tab_port_forward) {
                segment = SEG_PORT_FORWARD;
            } else if (checkedId == R.id.tab_traffic) {
                segment = SEG_TRAFFIC;
            } else {
                segment = SEG_STATUS;
            }
            render();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadAllData();
    }

    // =====================================================================
    // 数据
    // =====================================================================

    private void loadAllData() {
        OpenWrtApi.getInstance().uciGetConfig("firewall", new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject config) {
                parse(config);
                render();
            }

            @Override
            public void onFailure(ApiError error) {
                zones.clear();
                portForwards.clear();
                trafficRules.clear();
                render();
            }
        });
    }

    private void parse(JsonObject config) {
        zones.clear();
        portForwards.clear();
        trafficRules.clear();
        JsonObject values = config.has("values") && config.get("values").isJsonObject()
                ? config.getAsJsonObject("values") : config;
        for (String sec : values.keySet()) {
            JsonElement el = values.get(sec);
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            String type = optStr(o, ".type");
            if ("zone".equals(type)) {
                Zone z = new Zone();
                z.name = optStr(o, "name");
                z.input = optStr(o, "input");
                z.output = optStr(o, "output");
                z.forward = optStr(o, "forward");
                zones.add(z);
            } else if ("redirect".equals(type)) {
                Rule r = new Rule();
                r.section = sec;
                r.name = optStr(o, "name") != null ? optStr(o, "name") : sec;
                String proto = optStr(o, "proto");
                r.detail = (proto != null ? proto.toUpperCase(Locale.US) : "") + "  "
                        + nz(optStr(o, "src_dport")) + " → "
                        + nz(optStr(o, "dest_ip")) + ":" + nz(optStr(o, "dest_port"));
                portForwards.add(r);
            } else if ("rule".equals(type)) {
                String name = optStr(o, "name");
                // 排除 App 黑名单封禁规则(在设备页黑名单管理)
                if (name != null && name.startsWith(OpenWrtApi.BLOCK_RULE_PREFIX)) continue;
                Rule r = new Rule();
                r.section = sec;
                r.name = name != null ? name : sec;
                StringBuilder sb = new StringBuilder();
                String target = optStr(o, "target");
                String proto = optStr(o, "proto");
                String dport = optStr(o, "dest_port");
                if (target != null) sb.append(target);
                if (proto != null) sb.append("  ").append(proto.toUpperCase(Locale.US));
                if (dport != null) sb.append("  :").append(dport);
                r.detail = sb.toString();
                trafficRules.add(r);
            }
        }
    }

    private static String nz(String s) {
        return s != null ? s : "?";
    }

    private static String optStr(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && !e.isJsonNull() && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    // =====================================================================
    // 渲染
    // =====================================================================

    private void render() {
        container.removeAllViews();
        switch (segment) {
            case SEG_PORT_FORWARD:
                renderRules(portForwards, R.string.pf_empty, R.string.pf_add,
                        PortForwardAddActivity.class);
                break;
            case SEG_TRAFFIC:
                renderRules(trafficRules, R.string.tr_empty, R.string.tr_add,
                        TrafficRuleAddActivity.class);
                break;
            default:
                renderStatus();
        }
    }

    private void renderStatus() {
        MaterialCardView card = card();
        LinearLayout rows = cardRows(card);
        View statusRow = LayoutInflater.from(this)
                .inflate(R.layout.view_value_row, rows, false);
        ((TextView) statusRow.findViewById(R.id.row_label)).setText(R.string.fw_status);
        TextView value = statusRow.findViewById(R.id.row_value);
        value.setText(R.string.fw_running_mark);
        value.setTextColor(getColor(R.color.ios_green));
        statusRow.findViewById(R.id.row_value).setClickable(false);
        statusRow.setClickable(false);
        rows.addView(statusRow);
        rows.addView(separator());
        View zoneRow = LayoutInflater.from(this).inflate(R.layout.view_value_row, rows, false);
        ((TextView) zoneRow.findViewById(R.id.row_label)).setText(R.string.fw_zones);
        ((TextView) zoneRow.findViewById(R.id.row_value)).setText(String.valueOf(zones.size()));
        zoneRow.setClickable(false);
        rows.addView(zoneRow);
        container.addView(card);

        btnRestart = new MaterialButton(this, null,
                com.google.android.material.R.attr.materialButtonStyle);
        btnRestart.setText(R.string.fw_restart);
        btnRestart.setAllCaps(false);
        btnRestart.setBackgroundColor(getColor(R.color.ios_red));
        btnRestart.setTextColor(getColor(R.color.white));
        btnRestart.setCornerRadius(dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        lp.topMargin = dp(20);
        btnRestart.setLayoutParams(lp);
        btnRestart.setOnClickListener(v -> confirmRestart());
        container.addView(btnRestart);

        if (!zones.isEmpty()) {
            TextView header = sectionHeader(getString(R.string.fw_zones_header));
            container.addView(header);
            for (Zone z : zones) {
                container.addView(zoneCard(z));
            }
        }
    }

    private View zoneCard(Zone zone) {
        MaterialCardView card = card();
        LinearLayout rows = cardRows(card);
        rows.setPadding(dp(16), dp(12), dp(16), dp(12));

        TextView name = new TextView(this);
        name.setText(getString(R.string.fw_zone_name,
                zone.name != null ? zone.name.toUpperCase(Locale.US) : "?"));
        name.setTextSize(15);
        name.setTextColor(getColor(R.color.label_primary));
        rows.addView(name);

        LinearLayout policies = new LinearLayout(this);
        policies.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.topMargin = dp(8);
        policies.setLayoutParams(plp);
        if (zone.input != null) {
            policies.addView(policyView(getString(R.string.fw_policy_in), zone.input));
        }
        if (zone.output != null) {
            policies.addView(policyView(getString(R.string.fw_policy_out), zone.output));
        }
        if (zone.forward != null) {
            policies.addView(policyView(getString(R.string.fw_policy_forward), zone.forward));
        }
        rows.addView(policies);
        return card;
    }

    /** 策略小标签:ACCEPT 绿、REJECT/DROP 红、其它灰(与 iOS createPolicyLabel 一致) */
    private View policyView(String title, String policy) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        box.setLayoutParams(lp);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(11);
        t.setTextColor(getColor(R.color.label_secondary));
        t.setGravity(android.view.Gravity.CENTER);

        TextView p = new TextView(this);
        p.setText(policy.toUpperCase(Locale.US));
        p.setTextSize(13);
        p.setGravity(android.view.Gravity.CENTER);
        String upper = policy.toUpperCase(Locale.US);
        int color;
        if ("ACCEPT".equals(upper)) {
            color = R.color.ios_green;
        } else if ("REJECT".equals(upper) || "DROP".equals(upper)) {
            color = R.color.ios_red;
        } else {
            color = R.color.label_secondary;
        }
        p.setTextColor(getColor(color));

        box.addView(t);
        box.addView(p);
        return box;
    }

    private void renderRules(List<Rule> rules, int emptyRes, int addRes, Class<?> addTarget) {
        MaterialButton add = new MaterialButton(this, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        add.setText(getString(R.string.fw_add_prefix, getString(addRes)));
        add.setAllCaps(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        add.setLayoutParams(lp);
        add.setOnClickListener(v -> startActivity(new Intent(this, addTarget)));
        container.addView(add);

        if (rules.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(emptyRes);
            empty.setTextColor(getColor(R.color.label_tertiary));
            empty.setTextSize(15);
            empty.setGravity(android.view.Gravity.CENTER);
            empty.setPadding(0, dp(40), 0, 0);
            container.addView(empty);
            return;
        }
        MaterialCardView card = card();
        LinearLayout rows = cardRows(card);
        for (int i = 0; i < rules.size(); i++) {
            Rule rule = rules.get(i);
            View row = LayoutInflater.from(this).inflate(R.layout.item_blacklist, rows, false);
            ((TextView) row.findViewById(R.id.block_name)).setText(rule.name);
            ((TextView) row.findViewById(R.id.block_mac)).setText(rule.detail);
            Button action = row.findViewById(R.id.btn_unblock);
            action.setText(R.string.action_delete);
            action.setOnClickListener(v -> confirmDelete(rule));
            rows.addView(row);
            if (i < rules.size() - 1) rows.addView(separator());
        }
        container.addView(card);
    }

    // =====================================================================
    // 操作
    // =====================================================================

    private void confirmRestart() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.fw_restart_title)
                .setMessage(R.string.fw_restart_warning)
                .setPositiveButton(R.string.ok, (d, w) -> restart())
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void restart() {
        btnRestart.setEnabled(false);
        btnRestart.setText(R.string.fw_restarting);
        OpenWrtApi.getInstance().initdControl("firewall", "restart",
                new ApiCallback<JsonObject>() {
                    @Override
                    public void onSuccess(JsonObject result) {
                        finishRestart(null);
                    }

                    @Override
                    public void onFailure(ApiError error) {
                        finishRestart(error);
                    }
                });
    }

    private void finishRestart(ApiError error) {
        btnRestart.setEnabled(true);
        btnRestart.setText(R.string.fw_restart);
        new MaterialAlertDialogBuilder(this)
                .setMessage(error == null ? getString(R.string.fw_restarted)
                        : getString(R.string.msg_action_failed, error.getMessage()))
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    private void confirmDelete(Rule rule) {
        new MaterialAlertDialogBuilder(this)
                .setMessage(getString(R.string.fw_confirm_delete_rule, rule.name))
                .setPositiveButton(R.string.action_delete, (d, w) ->
                        OpenWrtApi.getInstance().deleteFirewallSection(rule.section,
                                new ApiCallback<Void>() {
                                    @Override
                                    public void onSuccess(Void result) {
                                        loadAllData();
                                    }

                                    @Override
                                    public void onFailure(ApiError error) {
                                        // 回调可能在页面关闭后才到,不加这道判断弹窗会 BadTokenException
                                        if (isFinishing() || isDestroyed()) return;
                                        new MaterialAlertDialogBuilder(
                                                FirewallManagementActivity.this)
                                                .setMessage(getString(R.string.msg_action_failed,
                                                        error.getMessage()))
                                                .setPositiveButton(R.string.ok, null)
                                                .show();
                                    }
                                }))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    // =====================================================================
    // 视图工具
    // =====================================================================

    private MaterialCardView card() {
        MaterialCardView card = (MaterialCardView) LayoutInflater.from(this)
                .inflate(R.layout.view_section_card, container, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        card.setLayoutParams(lp);
        return card;
    }

    private LinearLayout cardRows(MaterialCardView card) {
        return card.findViewById(R.id.section_rows);
    }

    private TextView sectionHeader(String text) {
        TextView header = new TextView(this);
        header.setText(text);
        header.setTextAppearance(R.style.TextAppearance_Wrthub_SectionHeader);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(4), dp(24), 0, dp(8));
        header.setLayoutParams(lp);
        return header;
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
