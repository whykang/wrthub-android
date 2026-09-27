package com.whykangkang.wrthub.ui.services.wifi;

import android.os.Bundle;
import android.widget.EditText;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.gson.JsonObject;
import com.whykangkang.wrthub.R;
import com.whykangkang.wrthub.api.ApiCallback;
import com.whykangkang.wrthub.api.ApiError;
import com.whykangkang.wrthub.api.OpenWrtApi;

/**
 * 手动添加 WiFi,对应 iOS WiFiAddViewController。
 *
 * 与 iOS 行为一致:先以**禁用**状态添加(安全措施),添加成功后再询问是否立即启用。
 * AP 模式接入 lan,Client(中继)模式走 wwan 创建流程(见 WifiRelayHelper)。
 */
public class WiFiAddActivity extends AppCompatActivity {

    private static final String[] ENC_KEYS = {"none", "psk2", "sae"};

    private EditText inputSsid;
    private EditText inputPassword;
    private MaterialButtonToggleGroup deviceGroup;
    private MaterialButtonToggleGroup modeGroup;
    private MaterialButtonToggleGroup encGroup;
    private MaterialButton btnAdd;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wifi_add);

        inputSsid = findViewById(R.id.input_ssid);
        inputPassword = findViewById(R.id.input_password);
        deviceGroup = findViewById(R.id.group_device);
        modeGroup = findViewById(R.id.group_mode);
        encGroup = findViewById(R.id.group_encryption);
        btnAdd = findViewById(R.id.btn_add);

        deviceGroup.check(R.id.device_24g);
        // 默认 AP:「添加 WiFi」绝大多数时候是要开一个新热点,不是接中继。
        // 这里有意和 iOS 不同 —— iOS 默认 Client。
        modeGroup.check(R.id.mode_ap);
        encGroup.check(R.id.enc_wpa2);
        // 选「开放」就把密码行收起来 —— 开放网络填密码是无效操作
        encGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) updatePasswordRow();
        });
        updatePasswordRow();
        btnAdd.setOnClickListener(v -> onAddTapped());
    }

    /** 开放网络隐藏密码行(连同上方分隔线),并清掉已填的内容 */
    private void updatePasswordRow() {
        boolean open = encryptionIndex() == 0;
        int visibility = open ? android.view.View.GONE : android.view.View.VISIBLE;
        findViewById(R.id.row_password).setVisibility(visibility);
        findViewById(R.id.sep_password).setVisibility(visibility);
        if (open) inputPassword.setText("");
    }

    private String device() {
        return deviceGroup.getCheckedButtonId() == R.id.device_5g ? "radio1" : "radio0";
    }

    private boolean isApMode() {
        return modeGroup.getCheckedButtonId() == R.id.mode_ap;
    }

    private int encryptionIndex() {
        int id = encGroup.getCheckedButtonId();
        if (id == R.id.enc_open) return 0;
        if (id == R.id.enc_wpa3) return 2;
        return 1;
    }

    private void onAddTapped() {
        String ssid = inputSsid.getText().toString().trim();
        if (ssid.isEmpty()) {
            error(getString(R.string.wifi_add_need_ssid));
            return;
        }
        String password = inputPassword.getText().toString();
        int enc = encryptionIndex();
        if (enc != 0) {
            if (password.isEmpty()) {
                error(getString(R.string.wifi_add_need_password));
                return;
            }
            if (password.length() < 8) {
                error(getString(R.string.wifi_add_password_short));
                return;
            }
        } else if (!password.isEmpty()) {
            error(getString(R.string.wifi_add_open_no_password));
            return;
        }
        confirmAdd(ssid, password);
    }

    private void confirmAdd(String ssid, String password) {
        int enc = encryptionIndex();
        String[] encNames = {getString(R.string.enc_none), getString(R.string.enc_wpa2),
                getString(R.string.enc_wpa3)};
        StringBuilder msg = new StringBuilder();
        msg.append(getString(R.string.wifi_ssid)).append(": ").append(ssid).append('\n');
        msg.append(getString(R.string.wifi_add_device)).append(": ")
                .append(deviceGroup.getCheckedButtonId() == R.id.device_5g ? "5G" : "2.4G")
                .append('\n');
        msg.append(getString(R.string.wifi_mode)).append(": ")
                .append(isApMode() ? "AP" : "Client").append('\n');
        msg.append(getString(R.string.wifi_encryption)).append(": ")
                .append(encNames[enc]).append('\n');
        if (enc != 0) {
            msg.append(getString(R.string.wifi_password)).append(": ••••••••\n");
        }
        msg.append('\n').append(getString(R.string.wifi_add_confirm_question));

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.wifi_add_confirm_title)
                .setMessage(msg.toString())
                .setPositiveButton(R.string.wifi_add_action, (d, w) -> performAdd(ssid, password))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void performAdd(String ssid, String password) {
        btnAdd.setEnabled(false);
        String encryption = ENC_KEYS[encryptionIndex()];
        if (!isApMode()) {
            // Client(中继):走 wwan 创建流程。同样先以停用状态加入,
            // 再询问是否启用 —— 密码填错时直接启用会把无线服务搞挂。
            new WifiRelayHelper(OpenWrtApi.getInstance())
                    .addStaRelay(device(), ssid, password, encryption, true,
                            new WifiRelayHelper.Callback() {
                                @Override
                                public void onDone(String section) {
                                    btnAdd.setEnabled(true);
                                    confirmEnable(section, ssid);
                                }

                                @Override
                                public void onError(ApiError e) {
                                    btnAdd.setEnabled(true);
                                    error(getString(R.string.msg_action_failed, e.getMessage()));
                                }
                            });
            return;
        }

        // AP:先以禁用状态添加(安全措施),再询问是否启用
        OpenWrtApi api = OpenWrtApi.getInstance();
        JsonObject values = new JsonObject();
        values.addProperty("device", device());
        values.addProperty("mode", "ap");
        values.addProperty("ssid", ssid);
        values.addProperty("network", "lan");
        values.addProperty("encryption", encryption);
        if (!"none".equals(encryption)) {
            values.addProperty("key", password);
        }
        values.addProperty("disabled", "1");
        api.uciAdd("wireless", "wifi-iface", null, values, new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                String section = result.has("section")
                        ? result.get("section").getAsString() : null;
                api.uciApplyOrCommit("wireless", new ApiCallback<JsonObject>() {
                    @Override
                    public void onSuccess(JsonObject r) {
                        btnAdd.setEnabled(true);
                        confirmEnable(section, ssid);
                    }

                    @Override
                    public void onFailure(ApiError e) {
                        btnAdd.setEnabled(true);
                        error(getString(R.string.msg_action_failed, e.getMessage()));
                    }
                });
            }

            @Override
            public void onFailure(ApiError e) {
                btnAdd.setEnabled(true);
                error(getString(R.string.msg_action_failed, e.getMessage()));
            }
        });
    }

    private void confirmEnable(String section, String ssid) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.wifi_add_success_title)
                .setMessage(getString(R.string.wifi_add_success_msg, ssid))
                .setPositiveButton(R.string.wifi_enable_now, (d, w) -> enable(section))
                .setNegativeButton(R.string.wifi_enable_later, (d, w) ->
                        done(getString(R.string.wifi_enable_later_hint)))
                .show();
    }

    private void enable(String section) {
        if (section == null) {
            done(getString(R.string.wifi_enable_later_hint));
            return;
        }
        OpenWrtApi api = OpenWrtApi.getInstance();
        JsonObject values = new JsonObject();
        values.addProperty("disabled", "0");
        api.uciSet("wireless", section, values, new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                api.uciApplyOrCommit("wireless", new ApiCallback<JsonObject>() {
                    @Override
                    public void onSuccess(JsonObject r) {
                        api.fileExec("/sbin/wifi", new String[]{"reload"}, null,
                                new ApiCallback<JsonObject>() {
                                    @Override
                                    public void onSuccess(JsonObject rr) {
                                        done(getString(R.string.wifi_enabled_ok));
                                    }

                                    @Override
                                    public void onFailure(ApiError e) {
                                        done(getString(R.string.wifi_enabled_ok));
                                    }
                                });
                    }

                    @Override
                    public void onFailure(ApiError e) {
                        error(getString(R.string.msg_action_failed, e.getMessage()));
                    }
                });
            }

            @Override
            public void onFailure(ApiError e) {
                error(getString(R.string.msg_action_failed, e.getMessage()));
            }
        });
    }

    private void done(String message) {
        new MaterialAlertDialogBuilder(this)
                .setMessage(message)
                .setPositiveButton(R.string.ok, (d, w) -> finish())
                .show();
    }

    private void error(String message) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.wifi_add_input_error)
                .setMessage(message)
                .setPositiveButton(R.string.ok, null)
                .show();
    }
}
