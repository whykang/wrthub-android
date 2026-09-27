package com.whykangkang.wrthub.ui.services.wifi;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.whykangkang.wrthub.R;
import com.whykangkang.wrthub.api.ApiCallback;
import com.whykangkang.wrthub.api.ApiError;
import com.whykangkang.wrthub.api.OpenWrtApi;
import com.whykangkang.wrthub.model.WiFiNetwork;
import com.whykangkang.wrthub.ui.common.SignalBarsView;

import java.util.ArrayList;
import java.util.List;

/**
 * WiFi 扫描,行为对齐 iOS WiFiScanViewController:
 * <ul>
 *   <li>一次只扫**一个**无线设备,顶部按钮切 2.4G/5G(iOS 放在导航栏)</li>
 *   <li>只列扫描到的网络,不再单独列「已配置网络」—— iOS 没有这一段</li>
 *   <li>点某个网络直接弹密码框加入(开放网络不显示密码框),不跳编辑页</li>
 *   <li>先以禁用状态加入,再问是否立即启用</li>
 * </ul>
 */
public class WiFiScanActivity extends AppCompatActivity {

    private LinearLayout container;
    private MaterialButton btnScan;
    private MaterialButton btnRadio;
    private TextView pageTitle;
    /** 当前扫描用的无线设备,与 iOS selectedDevice 一致,默认 radio0 */
    private String selectedRadio = "radio0";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wifi_scan);
        container = findViewById(R.id.wifi_container);
        btnScan = findViewById(R.id.btn_scan);
        btnRadio = findViewById(R.id.btn_radio);
        pageTitle = findViewById(R.id.page_title);
        btnScan.setOnClickListener(v -> startScan());
        btnRadio.setOnClickListener(v -> pickRadio());
        updateRadioLabel();
    }

    @Override
    protected void onResume() {
        super.onResume();
        startScan();
    }

    /** 选 2.4G / 5G,选完立刻重扫(与 iOS selectDeviceTapped 一致) */
    private void pickRadio() {
        CharSequence[] items = {
                getString(R.string.wifi_radio_24g_full), getString(R.string.wifi_radio_5g_full)};
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.wifi_pick_radio)
                .setItems(items, (d, which) -> {
                    selectedRadio = which == 0 ? "radio0" : "radio1";
                    updateRadioLabel();
                    startScan();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void updateRadioLabel() {
        boolean is5g = "radio1".equals(selectedRadio);
        btnRadio.setText(is5g ? R.string.wifi_radio_5g : R.string.wifi_radio_24g);
        pageTitle.setText(getString(R.string.wifi_scan_title,
                getString(is5g ? R.string.wifi_radio_5g : R.string.wifi_radio_24g)));
    }

    /** 只扫选中的那个无线设备 —— iOS 就是这么做的,不是把所有 radio 一起扫 */
    private void startScan() {
        btnScan.setEnabled(false);
        btnRadio.setEnabled(false);
        btnScan.setText(R.string.wifi_scanning);
        container.removeAllViews();

        OpenWrtApi.getInstance().scanWifi(selectedRadio, new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                if (isFinishing() || isDestroyed()) return;
                List<WiFiNetwork> found = new ArrayList<>();
                parseScan(result, selectedRadio, found);
                renderAvailable(found);
                finishScan();
            }

            @Override
            public void onFailure(ApiError error) {
                if (isFinishing() || isDestroyed()) return;
                finishScan();
                // iOS 扫描失败会弹窗,不是静默留一片空白
                if (error.getType() == ApiError.Type.CONFIG_NOT_FOUND
                        || error.getType() == ApiError.Type.ACCESS_DENIED) {
                    renderNoRadio();
                    return;
                }
                renderAvailable(new ArrayList<>());
                alert(getString(R.string.wifi_scan_failed, error.getMessage()));
            }
        });
    }

    private void parseScan(JsonObject result, String radio, List<WiFiNetwork> found) {
        if (!result.has("results") || !result.get("results").isJsonArray()) return;
        for (JsonElement e : result.getAsJsonArray("results")) {
            JsonObject o = e.getAsJsonObject();
            WiFiNetwork n = new WiFiNetwork();
            n.ssid = optStr(o, "ssid");
            n.bssid = optStr(o, "bssid");
            n.radioDevice = radio;
            if (o.has("channel")) n.channel = o.get("channel").getAsInt();
            if (o.has("signal")) n.signalDbm = o.get("signal").getAsInt();
            n.encryption = parseEncryption(o);
            if (n.ssid != null && !n.ssid.isEmpty()) found.add(n);
        }
    }

    private String parseEncryption(JsonObject o) {
        if (!o.has("encryption") || !o.get("encryption").isJsonObject()) return "none";
        JsonObject enc = o.getAsJsonObject("encryption");
        boolean enabled = enc.has("enabled") && enc.get("enabled").getAsBoolean();
        if (!enabled) return "none";
        if (enc.has("authentication") && enc.getAsJsonArray("authentication").toString().contains("sae")) {
            return "sae-mixed";
        }
        return "psk2";
    }

    // ---- 渲染 ----

    private void renderAvailable(List<WiFiNetwork> list) {
        addHeader(R.string.wifi_networks);
        if (list.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.wifi_empty);
            empty.setTextColor(getColor(R.color.label_tertiary));
            empty.setPadding(dp(4), dp(8), 0, 0);
            container.addView(empty);
            return;
        }
        MaterialCardView card = newCard();
        LinearLayout rows = card.findViewById(R.id.section_rows);
        for (int i = 0; i < list.size(); i++) {
            rows.addView(makeRow(list.get(i), false));
            if (i < list.size() - 1) rows.addView(makeSeparator());
        }
        container.addView(card);
    }

    private void renderNoRadio() {
        TextView tv = new TextView(this);
        tv.setText(R.string.wifi_no_radio);
        tv.setTextColor(getColor(R.color.label_tertiary));
        tv.setTextSize(15f);
        tv.setPadding(dp(4), dp(24), 0, 0);
        container.addView(tv);
    }

    private View makeRow(WiFiNetwork n, boolean configured) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_wifi, container, false);
        TextView ssid = row.findViewById(R.id.wifi_ssid);
        TextView meta = row.findViewById(R.id.wifi_meta);
        SignalBarsView signal = row.findViewById(R.id.wifi_signal);

        ssid.setText(n.ssid != null && !n.ssid.isEmpty() ? n.ssid : "(hidden)");
        StringBuilder sb = new StringBuilder();
        if (n.channel != null) {
            sb.append(getString(R.string.wifi_channel, String.valueOf(n.channel))).append(" · ");
        }
        sb.append(encLabel(n.encryption));
        meta.setText(sb.toString());
        signal.setBars(n.signalBars());

        row.setOnClickListener(v -> connectTo(n));
        return row;
    }

    /**
     * 点一个网络就地加入,不跳编辑页(与 iOS connectToWiFi 一致)。
     * 开放网络不显示密码框;其余要求至少 8 位。
     */
    private void connectTo(WiFiNetwork n) {
        boolean open = n.encryption == null || "none".equals(n.encryption);
        EditText input = new EditText(this);
        input.setHint(R.string.wifi_password);
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.wifi_connect_to, n.ssid))
                .setMessage(open ? getString(R.string.wifi_connect_open)
                        : getString(R.string.wifi_connect_enter_password))
                .setNegativeButton(R.string.action_cancel, null);
        if (!open) {
            int pad = dp(20);
            LinearLayout wrap = new LinearLayout(this);
            wrap.setPadding(pad, dp(8), pad, 0);
            wrap.addView(input, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            builder.setView(wrap);
        }
        builder.setPositiveButton(R.string.wifi_connect_action, (d, w) -> {
            String password = open ? null : input.getText().toString();
            if (!open) {
                if (password.isEmpty()) {
                    alert(getString(R.string.wifi_add_need_password));
                    return;
                }
                if (password.length() < 8) {
                    alert(getString(R.string.wifi_add_password_short));
                    return;
                }
            }
            addNetwork(n, password);
        });
        builder.show();
    }

    /** 先以**禁用**状态加入(密码填错时启用会把无线搞挂),成功后再问是否启用 */
    private void addNetwork(WiFiNetwork n, String password) {
        btnScan.setEnabled(false);
        new WifiRelayHelper(OpenWrtApi.getInstance())
                .addStaRelay(selectedRadio, n.ssid, password, n.encryption, true,
                        new WifiRelayHelper.Callback() {
                            @Override
                            public void onDone(String createdSection) {
                                btnScan.setEnabled(true);
                                confirmEnable(createdSection, n.ssid);
                            }

                            @Override
                            public void onError(ApiError error) {
                                btnScan.setEnabled(true);
                                alert(getString(R.string.wifi_add_failed, error.getMessage()));
                            }
                        });
    }

    private void confirmEnable(String section, String ssid) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.wifi_add_success_title)
                .setMessage(getString(R.string.wifi_relay_enable_msg, ssid))
                .setPositiveButton(R.string.wifi_enable_now, (d, w) -> enable(section, ssid))
                .setNegativeButton(R.string.wifi_enable_later, (d, w) ->
                        alert(getString(R.string.wifi_enable_later_hint)))
                .setCancelable(false)
                .show();
    }

    private void enable(String section, String ssid) {
        if (section == null) {
            alert(getString(R.string.wifi_enable_later_hint));
            return;
        }
        OpenWrtApi api = OpenWrtApi.getInstance();
        JsonObject values = new JsonObject();
        values.addProperty("disabled", "0");
        api.uciSet("wireless", section, values, new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject r) {
                api.uciApplyOrCommit("wireless", new ApiCallback<JsonObject>() {
                    @Override
                    public void onSuccess(JsonObject rr) {
                        api.fileExec("/sbin/wifi", new String[]{"reload"}, null,
                                new ApiCallback<JsonObject>() {
                                    @Override
                                    public void onSuccess(JsonObject x) {
                                        connected(ssid);
                                    }

                                    @Override
                                    public void onFailure(ApiError e) {
                                        connected(ssid);
                                    }
                                });
                    }

                    @Override
                    public void onFailure(ApiError e) {
                        alert(getString(R.string.wifi_enable_failed_msg, e.getMessage()));
                    }
                });
            }

            @Override
            public void onFailure(ApiError e) {
                alert(getString(R.string.wifi_enable_failed_msg, e.getMessage()));
            }
        });
    }

    private void connected(String ssid) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.wifi_enabled_ok)
                .setMessage(getString(R.string.wifi_relay_connecting, ssid))
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    private void alert(String message) {
        if (isFinishing() || isDestroyed()) return;
        new MaterialAlertDialogBuilder(this)
                .setMessage(message)
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    private String encLabel(String enc) {
        if (enc == null || "none".equals(enc)) return getString(R.string.enc_none);
        if (enc.startsWith("sae-mixed")) return getString(R.string.enc_wpa2wpa3);
        if (enc.startsWith("sae")) return getString(R.string.enc_wpa3);
        return getString(R.string.enc_wpa2);
    }

    private void addHeader(int res) {
        TextView header = new TextView(this);
        header.setText(res);
        header.setTextAppearance(R.style.TextAppearance_Wrthub_SectionHeader);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(4), dp(16), 0, dp(8));
        header.setLayoutParams(lp);
        container.addView(header);
    }

    private MaterialCardView newCard() {
        return (MaterialCardView) LayoutInflater.from(this)
                .inflate(R.layout.view_section_card, container, false);
    }

    private View makeSeparator() {
        View line = new View(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.setMarginStart(dp(16));
        line.setLayoutParams(lp);
        line.setBackgroundColor(getColor(R.color.separator));
        return line;
    }

    private void finishScan() {
        btnScan.setEnabled(true);
        btnRadio.setEnabled(true);
        btnScan.setText(R.string.wifi_scan);
    }

    private static String optStr(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}