package com.whykangkang.wrthub.ui.services.wifi;

import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.gson.JsonObject;
import com.whykangkang.wrthub.R;
import com.whykangkang.wrthub.api.ApiCallback;
import com.whykangkang.wrthub.api.ApiError;
import com.whykangkang.wrthub.api.OpenWrtApi;

/**
 * WiFi 添加/编辑,对应 iOS WiFiAdd/EditViewController。
 * 编辑模式改现有 wifi-iface;添加为 STA 模式走 wwan 中继创建流程。
 */
public class WiFiEditActivity extends AppCompatActivity {

    public static final String EXTRA_SSID = "ssid";
    public static final String EXTRA_ENCRYPTION = "encryption";
    public static final String EXTRA_RADIO = "radio";
    public static final String EXTRA_SECTION = "section";     // 编辑模式
    public static final String EXTRA_DISABLED = "disabled";
    public static final String EXTRA_ADD_STA = "add_sta";     // 添加为中继
    public static final String EXTRA_MODE = "mode";           // ap / sta(只读展示)
    public static final String EXTRA_CHANNEL = "channel";     // 信道(只读展示)
    public static final String EXTRA_PASSWORD = "password";   // 现有密码,预填

    private static final String[] ENC_KEYS = {"none", "psk2", "sae", "sae-mixed"};
    private static final int[] ENC_LABELS = {
            R.string.enc_none, R.string.enc_wpa2, R.string.enc_wpa3, R.string.enc_wpa2wpa3};

    private EditText inputSsid, inputPassword;
    private TextView valueEncryption;
    private SwitchMaterial switchEnabled;
    private MaterialButton btnSave;

    private String encryption = "psk2";
    private String section;   // 编辑模式的 wifi-iface section
    private String radio;
    private boolean addSta;

    // 进来时的原值。保存**只写改过的字段** —— 把没动过的也一并写回去,
    // 等于拿界面上的默认值覆盖真实配置(开放网络会被写成 psk2 而没有密码)。
    private String originalSsid = "";
    private String originalEnc = "";
    private String originalPassword = "";
    private boolean originalDisabled;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wifi_edit);

        inputSsid = findViewById(R.id.input_ssid);
        inputPassword = findViewById(R.id.input_password);
        valueEncryption = findViewById(R.id.value_encryption);
        switchEnabled = findViewById(R.id.switch_enabled);
        btnSave = findViewById(R.id.btn_save);

        section = getIntent().getStringExtra(EXTRA_SECTION);
        radio = getIntent().getStringExtra(EXTRA_RADIO);
        addSta = getIntent().getBooleanExtra(EXTRA_ADD_STA, false);
        String ssid = getIntent().getStringExtra(EXTRA_SSID);
        String enc = getIntent().getStringExtra(EXTRA_ENCRYPTION);
        boolean disabled = getIntent().getBooleanExtra(EXTRA_DISABLED, false);

        String password = getIntent().getStringExtra(EXTRA_PASSWORD);

        if (ssid != null) inputSsid.setText(ssid);
        if (enc != null && !enc.isEmpty()) encryption = normalizeEnc(enc);
        if (password != null) inputPassword.setText(password);
        switchEnabled.setChecked(!disabled);

        originalSsid = ssid == null ? "" : ssid;
        originalEnc = encryption;
        originalPassword = password == null ? "" : password;
        originalDisabled = disabled;

        ((TextView) findViewById(R.id.page_title)).setText(
                addSta ? R.string.wifi_add_sta : R.string.wifi_edit);
        if (addSta) {
            TextView hint = findViewById(R.id.relay_hint);
            hint.setVisibility(TextView.VISIBLE);
            hint.setText(R.string.wifi_creating_relay);
        }

        renderReadOnlyRows();
        setupPasswordToggle();

        // 删除只对已存在的 wifi-iface 有意义(新加中继还没建出来)
        MaterialButton btnDelete = findViewById(R.id.btn_delete);
        if (section != null && !addSta) {
            btnDelete.setVisibility(View.VISIBLE);
            btnDelete.setOnClickListener(v -> confirmDelete());
        }

        updateEncLabel();
        updatePasswordRow();
        findViewById(R.id.row_encryption).setOnClickListener(v -> pickEncryption());
        btnSave.setOnClickListener(v -> save());
    }

    /**
     * 工作模式 / 频段 / 信道 三行只读。
     * iOS 的工作模式是可切换的分段控件,这里只展示 —— 在已有接口上改 ap/sta
     * 还要跟着重挂网络与防火墙 zone,弄错就把无线整个弄没了,没敢照搬。
     */
    private void renderReadOnlyRows() {
        String mode = getIntent().getStringExtra(EXTRA_MODE);
        int channel = getIntent().getIntExtra(EXTRA_CHANNEL, 0);

        View modeRow = findViewById(R.id.row_mode);
        if (mode != null && !mode.isEmpty()) {
            modeRow.setVisibility(View.VISIBLE);
            boolean sta = mode.equalsIgnoreCase("sta") || mode.equalsIgnoreCase("client");
            ((TextView) findViewById(R.id.value_mode)).setText(
                    sta ? R.string.wifi_mode_client : R.string.wifi_mode_ap);
        } else {
            modeRow.setVisibility(View.GONE);
        }

        View bandRow = findViewById(R.id.row_band);
        View channelRow = findViewById(R.id.row_channel);
        if (channel > 0) {
            bandRow.setVisibility(View.VISIBLE);
            channelRow.setVisibility(View.VISIBLE);
            // 信道 1-14 是 2.4G,其余按 5G 算
            ((TextView) findViewById(R.id.value_band)).setText(
                    channel <= 14 ? "2.4 GHz" : "5 GHz");
            ((TextView) findViewById(R.id.value_channel)).setText(String.valueOf(channel));
        } else {
            bandRow.setVisibility(View.GONE);
            channelRow.setVisibility(View.GONE);
        }
    }

    /** 密码明文/密文切换(iOS 输入框右侧的眼睛) */
    private void setupPasswordToggle() {
        android.widget.ImageView toggle = findViewById(R.id.btn_password_toggle);
        toggle.setOnClickListener(v -> {
            boolean hidden = (inputPassword.getInputType()
                    & android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0;
            inputPassword.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                    | (hidden ? android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                              : android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD));
            toggle.setImageResource(hidden ? R.drawable.ic_eye_off : R.drawable.ic_eye);
            inputPassword.setSelection(inputPassword.getText().length());
        });
    }

    private void confirmDelete() {
        String ssid = inputSsid.getText().toString().trim();
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.wifi_delete_title)
                .setMessage(getString(R.string.wifi_delete_confirm, ssid))
                .setPositiveButton(R.string.action_delete, (d, w) -> performDelete())
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** 删除 wifi-iface 后提交并 reload 无线 */
    private void performDelete() {
        btnSave.setEnabled(false);
        OpenWrtApi api = OpenWrtApi.getInstance();
        api.uciDelete("wireless", section, null, new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                api.uciApplyOrCommit("wireless", new ApiCallback<JsonObject>() {
                    @Override
                    public void onSuccess(JsonObject r) {
                        api.fileExec("/sbin/wifi", new String[]{"reload"}, null,
                                new ApiCallback<JsonObject>() {
                                    @Override
                                    public void onSuccess(JsonObject rr) {
                                        deleted();
                                    }

                                    @Override
                                    public void onFailure(ApiError error) {
                                        deleted();   // 配置已删,reload 失败不致命
                                    }
                                });
                    }

                    @Override
                    public void onFailure(ApiError error) {
                        btnSave.setEnabled(true);
                        fail(error);
                    }
                });
            }

            @Override
            public void onFailure(ApiError error) {
                btnSave.setEnabled(true);
                fail(error);
            }
        });
    }

    private void deleted() {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setMessage(R.string.wifi_deleted)
                .setPositiveButton(R.string.ok, (d, w) -> finish())
                .setOnDismissListener(d -> finish())
                .show();
    }

    /**
     * 既认 uci 原始值(psk2 / sae / none),也认展示名(WPA2-PSK / OPEN / WPA3-SAE)。
     * 认展示名是为了兜底:调用方传错了至少不会把开放网络当成 WPA2。
     */
    static String normalizeEnc(String enc) {
        if (enc == null || enc.isEmpty()) return "psk2";
        String v = enc.toLowerCase(java.util.Locale.ROOT);
        if (v.startsWith("sae-mixed")) return "sae-mixed";
        if (v.startsWith("sae")) return "sae";
        if (v.startsWith("psk")) return "psk2";
        if ("none".equals(v)) return "none";
        // 展示名
        if (v.contains("open") || v.contains("开放")) return "none";
        if (v.contains("wpa2/wpa3") || v.contains("wpa2-wpa3")) return "sae-mixed";
        if (v.contains("wpa3")) return "sae";
        if (v.contains("wpa")) return "psk2";
        return "psk2";
    }

    private void updateEncLabel() {
        int idx = indexOf(ENC_KEYS, encryption);
        valueEncryption.setText(ENC_LABELS[idx]);
    }

    /** 开放网络隐藏密码行(连同上方分隔线),并清掉已填的内容 */
    private void updatePasswordRow() {
        boolean open = "none".equals(encryption);
        int visibility = open ? View.GONE : View.VISIBLE;
        findViewById(R.id.row_password).setVisibility(visibility);
        findViewById(R.id.sep_password).setVisibility(visibility);
        if (open) inputPassword.setText("");
    }

    private void pickEncryption() {
        CharSequence[] items = new CharSequence[ENC_LABELS.length];
        for (int i = 0; i < items.length; i++) items[i] = getString(ENC_LABELS[i]);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.wifi_encryption)
                .setSingleChoiceItems(items, indexOf(ENC_KEYS, encryption), (d, which) -> {
                    encryption = ENC_KEYS[which];
                    updateEncLabel();
                    updatePasswordRow();
                    d.dismiss();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void save() {
        String ssid = inputSsid.getText().toString().trim();
        if (ssid.isEmpty()) {
            inputSsid.setError(getString(R.string.wifi_ssid));
            return;
        }
        String password = inputPassword.getText().toString();
        String passwordError = validatePassword(password);
        if (passwordError != null) {
            new MaterialAlertDialogBuilder(this)
                    .setMessage(passwordError)
                    .setPositiveButton(R.string.ok, null)
                    .show();
            return;
        }
        btnSave.setEnabled(false);

        if (addSta) {
            saveAsRelay(ssid, password);
        } else {
            saveEdit(ssid, password);
        }
    }

    /**
     * 加为中继:先以**停用**状态写入(密码填错时启用会把无线服务搞挂),
     * 成功后再询问是否立即启用 —— 与 iOS WiFiScanViewController 的流程一致。
     */
    private void saveAsRelay(String ssid, String password) {
        new WifiRelayHelper(OpenWrtApi.getInstance())
                .addStaRelay(radio, ssid, password, encryption, true,
                        new WifiRelayHelper.Callback() {
                            @Override
                            public void onDone(String createdSection) {
                                btnSave.setEnabled(true);
                                confirmEnable(createdSection, ssid);
                            }

                            @Override
                            public void onError(ApiError error) {
                                fail(error);
                            }
                        });
    }

    private void confirmEnable(String createdSection, String ssid) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.wifi_add_success_title)
                .setMessage(getString(R.string.wifi_relay_enable_msg, ssid))
                .setPositiveButton(R.string.wifi_enable_now,
                        (d, w) -> enableRelay(createdSection, ssid))
                .setNegativeButton(R.string.wifi_enable_later, (d, w) -> {
                    new MaterialAlertDialogBuilder(this)
                            .setMessage(R.string.wifi_enable_later_hint)
                            .setPositiveButton(R.string.ok, (dd, ww) -> finish())
                            .show();
                })
                .setCancelable(false)
                .show();
    }

    private void enableRelay(String createdSection, String ssid) {
        if (createdSection == null) {
            new MaterialAlertDialogBuilder(this)
                    .setMessage(R.string.wifi_enable_later_hint)
                    .setPositiveButton(R.string.ok, (d, w) -> finish())
                    .show();
            return;
        }
        OpenWrtApi api = OpenWrtApi.getInstance();
        JsonObject values = new JsonObject();
        values.addProperty("disabled", "0");
        api.uciSet("wireless", createdSection, values, new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                api.uciApplyOrCommit("wireless", new ApiCallback<JsonObject>() {
                    @Override
                    public void onSuccess(JsonObject r) {
                        api.fileExec("/sbin/wifi", new String[]{"reload"}, null,
                                new ApiCallback<JsonObject>() {
                                    @Override
                                    public void onSuccess(JsonObject rr) {
                                        connectionStarted(ssid);
                                    }

                                    @Override
                                    public void onFailure(ApiError e) {
                                        connectionStarted(ssid);
                                    }
                                });
                    }

                    @Override
                    public void onFailure(ApiError e) {
                        enableFailed(createdSection, e);
                    }
                });
            }

            @Override
            public void onFailure(ApiError e) {
                enableFailed(createdSection, e);
            }
        });
    }

    private void connectionStarted(String ssid) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.wifi_enabled_ok)
                .setMessage(getString(R.string.wifi_relay_connecting, ssid))
                .setPositiveButton(R.string.ok, (d, w) -> finish())
                .show();
    }

    /** 启用失败时给删除入口,免得这条坏配置影响其它 WiFi(与 iOS showEnableError 一致) */
    private void enableFailed(String createdSection, ApiError error) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.wifi_enable_failed)
                .setMessage(getString(R.string.wifi_enable_failed_msg, error.getMessage()))
                .setPositiveButton(R.string.wifi_delete_config,
                        (d, w) -> deleteRelay(createdSection))
                .setNegativeButton(R.string.ok, (d, w) -> finish())
                .show();
    }

    private void deleteRelay(String createdSection) {
        OpenWrtApi api = OpenWrtApi.getInstance();
        api.uciDelete("wireless", createdSection, null, new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                api.uciApplyOrCommit("wireless", new ApiCallback<JsonObject>() {
                    @Override
                    public void onSuccess(JsonObject r) {
                        finish();
                    }

                    @Override
                    public void onFailure(ApiError e) {
                        finish();
                    }
                });
            }

            @Override
            public void onFailure(ApiError e) {
                finish();
            }
        });
    }

    private void saveEdit(String ssid, String password) {
        JsonObject values = changedValues(ssid, password);
        if (values.size() == 0) {
            btnSave.setEnabled(true);
            new MaterialAlertDialogBuilder(this)
                    .setMessage(R.string.wifi_nothing_changed)
                    .setPositiveButton(R.string.ok, null)
                    .show();
            return;
        }
        // 开放网络要把旧密码清掉,不然 key 还留在配置里
        if ("none".equals(encryption) && !originalEnc.equals("none")) {
            values.addProperty("key", "");
        }

        OpenWrtApi api = OpenWrtApi.getInstance();
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
                                        finishOk();
                                    }

                                    @Override
                                    public void onFailure(ApiError error) {
                                        finishOk();
                                    }
                                });
                    }

                    @Override
                    public void onFailure(ApiError error) {
                        fail(error);
                    }
                });
            }

            @Override
            public void onFailure(ApiError error) {
                fail(error);
            }
        });
    }

    /**
     * 密码校验,与添加页同一套规则:非开放网络至少 8 位。
     *
     * 只在密码**真的要写回去**时才校验 —— 只改个 SSID 却被密码拦住很烦人。
     * 从开放切到加密时哪怕密码没动过也必须校验,否则会写出一个没密码的加密网络。
     *
     * @return null 表示通过,否则是要提示的文案
     */
    private String validatePassword(String password) {
        if ("none".equals(encryption)) return null;
        boolean switchedToSecured = "none".equals(originalEnc);
        if (!switchedToSecured && password.equals(originalPassword)) return null;
        if (password.isEmpty()) return getString(R.string.wifi_add_need_password);
        if (password.length() < 8) return getString(R.string.wifi_add_password_short);
        return null;
    }

    /**
     * 只收集真正改过的字段。
     * 原来是把 ssid / encryption / key / disabled 全量写回 —— 而编辑页进来时
     * 除了 ssid 什么都没收到,于是一次「保存」就会用界面默认值
     * (WPA2 + 已启用)覆盖掉真实配置。
     */
    private JsonObject changedValues(String ssid, String password) {
        JsonObject values = new JsonObject();
        if (!ssid.equals(originalSsid)) {
            values.addProperty("ssid", ssid);
        }
        if (!encryption.equals(originalEnc)) {
            values.addProperty("encryption", encryption);
        }
        // 从开放切到加密时,即使密码框内容没变过也必须写进去
        boolean switchedToSecured = "none".equals(originalEnc) && !"none".equals(encryption);
        if (!"none".equals(encryption)
                && (switchedToSecured || !password.equals(originalPassword))) {
            values.addProperty("key", password);
        }
        boolean disabled = !switchEnabled.isChecked();
        if (disabled != originalDisabled) {
            values.addProperty("disabled", disabled ? "1" : "0");
        }
        return values;
    }

    private void finishOk() {
        new MaterialAlertDialogBuilder(this)
                .setMessage(R.string.wifi_saved)
                .setPositiveButton(R.string.ok, (d, w) -> finish())
                .show();
    }

    private void fail(ApiError error) {
        btnSave.setEnabled(true);
        new MaterialAlertDialogBuilder(this)
                .setMessage(getString(R.string.msg_action_failed, error.getMessage()))
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    private static int indexOf(String[] arr, String v) {
        for (int i = 0; i < arr.length; i++) if (arr[i].equals(v)) return i;
        return 0;
    }
}