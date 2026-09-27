package com.whykangkang.wrthub.ui.services.wol;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.whykangkang.wrthub.R;
import com.whykangkang.wrthub.api.ApiCallback;
import com.whykangkang.wrthub.api.ApiError;
import com.whykangkang.wrthub.api.OpenWrtApi;
import com.whykangkang.wrthub.api.WolApi;

import java.util.ArrayList;
import java.util.List;

/**
 * 网络唤醒,对应 iOS WOLViewController。
 *
 * 三组:目标主机 / 设置(两个工具都在时选唤醒程序;etherwake 才有发包网卡和广播)/ 唤醒按钮。
 * 记住上次唤醒的目标和网卡 —— WOL 多数时候是反复唤醒同一台机器。
 */
public class WolActivity extends AppCompatActivity {

    private static final String PREFS = "wol";

    private LinearLayout sections;
    private SharedPreferences prefs;

    private WolApi.Tools tools = new WolApi.Tools();
    private List<WolApi.Host> hosts = new ArrayList<>();
    private List<WolApi.Iface> interfaces = new ArrayList<>();
    private String selectedMac;
    private String executable = WolApi.ETHERWAKE;
    private String iface;
    private boolean broadcast;
    private boolean waking;
    private boolean loaded;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wol);
        sections = findViewById(R.id.wol_sections);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        selectedMac = prefs.getString(lastMacKey(), null);
        iface = prefs.getString(lastIfaceKey(), null);
        render();
        load();
    }

    private String lastMacKey() {
        return "wol.lastMAC." + OpenWrtApi.getInstance().getHost();
    }

    private String lastIfaceKey() {
        return "wol.lastIface." + OpenWrtApi.getInstance().getHost();
    }

    /** 当前选中目标的显示名(可能不在列表里 —— 关机的机器没有租约) */
    private String selectedDisplay() {
        if (selectedMac == null) return null;
        for (WolApi.Host h : hosts) {
            if (h.mac.equals(selectedMac)) return h.display(this);
        }
        return new WolApi.Host(selectedMac, "", "").display(this);
    }

    // =====================================================================
    // 载入
    // =====================================================================

    private void load() {
        WolApi.checkTools(new ApiCallback<WolApi.Tools>() {
            @Override
            public void onSuccess(WolApi.Tools result) {
                tools = result;
                executable = result.preferred();
                loaded = true;
                render();
            }

            @Override
            public void onFailure(ApiError error) {
                loaded = true;
                render();
            }
        });
        WolApi.loadHosts(this, new ApiCallback<List<WolApi.Host>>() {
            @Override
            public void onSuccess(List<WolApi.Host> result) {
                hosts = result;
                render();
            }

            @Override
            public void onFailure(ApiError error) {
            }
        });
        WolApi.loadInterfaces(new ApiCallback<List<WolApi.Iface>>() {
            @Override
            public void onSuccess(List<WolApi.Iface> list) {
                interfaces = list;
                // 没选过(或上次选的网卡已经不在了)就默认第一个 —— 网桥排在最前
                boolean found = false;
                for (WolApi.Iface i : list) if (i.name.equals(iface)) found = true;
                if (iface == null || !found) iface = list.isEmpty() ? null : list.get(0).name;
                render();
            }

            @Override
            public void onFailure(ApiError error) {
            }
        });
    }

    // =====================================================================
    // 界面
    // =====================================================================

    /** etherwake 才有网卡和广播选项;两个工具都在时才需要选程序 */
    private List<String> settingRows() {
        List<String> rows = new ArrayList<>();
        if (tools.etherwake && tools.wol) rows.add("executable");
        if (WolApi.ETHERWAKE.equals(executable)) {
            rows.add("iface");
            rows.add("broadcast");
        }
        return rows;
    }

    private void render() {
        if (isFinishing()) return;
        sections.removeAllViews();
        String notSelected = getString(R.string.wol_not_selected);

        // 目标主机
        sections.addView(header(R.string.wol_section_target));
        LinearLayout target = card();
        String display = selectedDisplay();
        target.addView(valueRow(getString(R.string.wol_host),
                display != null ? display : notSelected, this::pickHost));

        // 设置
        List<String> rows = settingRows();
        if (!rows.isEmpty()) {
            sections.addView(header(R.string.wol_section_settings));
            LinearLayout settings = card();
            for (int i = 0; i < rows.size(); i++) {
                if (i > 0) settings.addView(separator());
                switch (rows.get(i)) {
                    case "executable":
                        settings.addView(valueRow(getString(R.string.wol_executable),
                                WolApi.ETHERWAKE.equals(executable) ? "Etherwake" : "WoL",
                                this::pickExecutable));
                        break;
                    case "iface":
                        settings.addView(valueRow(getString(R.string.wol_iface),
                                iface != null ? iface : notSelected, this::pickInterface));
                        break;
                    default:
                        View row = LayoutInflater.from(this)
                                .inflate(R.layout.view_switch_row, settings, false);
                        ((TextView) row.findViewById(R.id.row_label)).setText(R.string.wol_broadcast);
                        SwitchMaterial toggle = row.findViewById(R.id.row_switch);
                        toggle.setChecked(broadcast);
                        toggle.setOnCheckedChangeListener((b, on) -> broadcast = on);
                        settings.addView(row);
                }
            }
        }

        // 唤醒按钮
        spacer(dp(24));
        LinearLayout action = card();
        TextView wake = new TextView(this);
        wake.setText(waking ? R.string.wol_sending : R.string.wol_wake);
        wake.setGravity(Gravity.CENTER);
        wake.setTextSize(16);
        wake.setTextColor(getColor(waking ? R.color.label_secondary : R.color.ios_blue));
        wake.setMinHeight(dp(48));
        wake.setBackgroundResource(selectableBackground());
        wake.setOnClickListener(v -> {
            if (!waking) wake();
        });
        action.addView(wake, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 页脚
        TextView footer = new TextView(this);
        footer.setText(loaded && !tools.any() ? R.string.wol_no_tools : R.string.wol_footer);
        footer.setTextSize(13);
        footer.setTextColor(getColor(R.color.label_secondary));
        footer.setLineSpacing(0, 1.1f);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.setMargins(dp(16), dp(8), dp(16), 0);
        sections.addView(footer, flp);
    }

    private TextView header(@StringRes int res) {
        TextView t = new TextView(this);
        t.setText(res);
        t.setTextAppearance(R.style.TextAppearance_Wrthub_SectionHeader);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(16), dp(20), 0, dp(8));
        t.setLayoutParams(lp);
        return t;
    }

    /** 新建一张分组卡片,加到页面上,返回卡片里放行的容器 */
    private LinearLayout card() {
        MaterialCardView card = (MaterialCardView) LayoutInflater.from(this)
                .inflate(R.layout.view_section_card, sections, false);
        sections.addView(card);
        return card.findViewById(R.id.section_rows);
    }

    private void spacer(int height) {
        sections.addView(new View(this), new LinearLayout.LayoutParams(1, height));
    }

    private View valueRow(String label, String value, Runnable onClick) {
        View row = LayoutInflater.from(this).inflate(R.layout.view_value_row, sections, false);
        ((TextView) row.findViewById(R.id.row_label)).setText(label);
        TextView v = row.findViewById(R.id.row_value);
        v.setText(value);
        // MAC + 名字可能很长:限制在一行,从中间省略
        v.setSingleLine(true);
        v.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        v.setMaxWidth(getResources().getDisplayMetrics().widthPixels * 3 / 5);
        row.setOnClickListener(x -> onClick.run());
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

    private int selectableBackground() {
        android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        return tv.resourceId;
    }

    /** 弹窗标题 + 说明(列表弹窗不能同时 setMessage,用自定义标题带上说明) */
    private View titleView(@StringRes int title, @StringRes int message) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(20), dp(24), dp(4));
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(20);
        t.setTextColor(getColor(R.color.label_primary));
        box.addView(t);
        TextView m = new TextView(this);
        m.setText(message);
        m.setTextSize(14);
        m.setTextColor(getColor(R.color.label_secondary));
        m.setPadding(0, dp(8), 0, 0);
        box.addView(m);
        return box;
    }

    // =====================================================================
    // 交互
    // =====================================================================

    private void pickHost() {
        int n = Math.min(40, hosts.size());
        CharSequence[] items = new CharSequence[n + 1];
        for (int i = 0; i < n; i++) items[i] = hosts.get(i).display(this);
        items[n] = getString(R.string.wol_manual_mac);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.wol_pick_host)
                .setItems(items, (d, which) -> {
                    if (which == n) promptMac();
                    else select(hosts.get(which).mac);
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** 要唤醒的机器多半已经关机,不在 DHCP 租约里,所以手动输入是常用路径而不是兜底 */
    private void promptMac() {
        EditText field = new EditText(this);
        field.setHint("AA:BB:CC:DD:EE:FF");
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setSingleLine(true);
        if (selectedMac != null) field.setText(selectedMac);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(20), dp(4), dp(20), 0);
        box.addView(field);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.wol_enter_mac)
                .setMessage(R.string.wol_enter_mac_hint)
                .setView(box)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.ok, (d, w) -> {
                    String mac = WolApi.normalizeMac(field.getText().toString());
                    if (mac == null) {
                        alert(getString(R.string.wol_bad_mac));
                        return;
                    }
                    select(mac);
                })
                .show();
        field.requestFocus();
    }

    private void select(String mac) {
        selectedMac = mac;
        prefs.edit().putString(lastMacKey(), mac).apply();
        render();
    }

    private void pickExecutable() {
        List<String> paths = new ArrayList<>();
        List<CharSequence> labels = new ArrayList<>();
        if (tools.etherwake) {
            paths.add(WolApi.ETHERWAKE);
            labels.add("Etherwake");
        }
        if (tools.wol) {
            paths.add(WolApi.WOL);
            labels.add("WoL");
        }
        new MaterialAlertDialogBuilder(this)
                .setCustomTitle(titleView(R.string.wol_executable, R.string.wol_executable_hint))
                .setSingleChoiceItems(labels.toArray(new CharSequence[0]),
                        paths.indexOf(executable), (d, which) -> {
                            d.dismiss();
                            executable = paths.get(which);
                            render();
                        })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** 与网页端 widgets.DeviceSelect 一样是选,不是填 —— 填错网卡名 etherwake 直接报错 */
    private void pickInterface() {
        if (interfaces.isEmpty()) {
            alert(getString(R.string.wol_no_iface));
            return;
        }
        CharSequence[] items = new CharSequence[interfaces.size()];
        int checked = -1;
        for (int i = 0; i < interfaces.size(); i++) {
            WolApi.Iface it = interfaces.get(i);
            items[i] = it.type.isEmpty() ? it.name : it.name + "  [" + it.type + "]";
            if (it.name.equals(iface)) checked = i;
        }
        new MaterialAlertDialogBuilder(this)
                .setCustomTitle(titleView(R.string.wol_iface, R.string.wol_iface_hint))
                .setSingleChoiceItems(items, checked, (d, which) -> {
                    d.dismiss();
                    iface = interfaces.get(which).name;
                    prefs.edit().putString(lastIfaceKey(), iface).apply();
                    render();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void wake() {
        if (selectedMac == null || selectedMac.isEmpty()) {
            alert(getString(R.string.wol_select_first));
            return;
        }
        if (!tools.any()) {
            alert(getString(R.string.wol_no_tools));
            return;
        }
        String mac = selectedMac;
        waking = true;
        render();
        WolApi.wake(mac, executable, iface, broadcast, new ApiCallback<String>() {
            @Override
            public void onSuccess(String output) {
                waking = false;
                render();
                String detail = output == null ? "" : output.trim();
                // 唤醒包是单向的,发出去就没有回执 —— 别说成「已唤醒」
                alert(getString(R.string.wol_sent, mac) + (detail.isEmpty() ? "" : "\n\n" + detail));
            }

            @Override
            public void onFailure(ApiError error) {
                waking = false;
                render();
                alert(getString(R.string.wol_failed, error.getMessage()));
            }
        });
    }

    private void alert(String message) {
        if (isFinishing()) return;
        new MaterialAlertDialogBuilder(this)
                .setMessage(message)
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
