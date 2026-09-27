package com.whykangkang.wrthub.ui.services.ssh;

import android.os.Bundle;
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
import com.whykangkang.wrthub.manager.RouterDeviceManager;
import com.whykangkang.wrthub.model.RouterDevice;

/**
 * SSH(dropbear)配置,对应 iOS SSHConfigViewController。
 * 连接信息卡(连接命令 / 登录密码 / 复制)、端口设置、安全设置,
 * 保存后 uci commit + 重启 dropbear。
 */
public class SshConfigActivity extends AppCompatActivity {

    private EditText inputPort;
    private SwitchMaterial switchPassword;
    private SwitchMaterial switchRoot;
    private MaterialButton btnSave;
    private TextView commandLabel;
    private String currentPort = "22";

    /** dropbear 实例的 section 名(读配置时确定) */
    private String section = "@dropbear[0]";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ssh);

        inputPort = findViewById(R.id.input_port);
        switchPassword = findViewById(R.id.switch_password);
        switchRoot = findViewById(R.id.switch_root);
        btnSave = findViewById(R.id.btn_save);
        commandLabel = findViewById(R.id.ssh_command);
        btnSave.setOnClickListener(v -> save());
        findViewById(R.id.btn_copy_conn).setOnClickListener(v -> copyConnectionInfo());

        // 登录密码直接取当前设备保存的那一份(iOS 同样这么显示)
        RouterDevice device = RouterDeviceManager.getInstance(this).getCurrentDevice();
        String password = device != null ? device.getPassword() : null;
        TextView passwordLabel = findViewById(R.id.ssh_password);
        passwordLabel.setText(password == null || password.isEmpty()
                ? getString(R.string.ssh_password_unsaved) : password);
        renderCommand();

        // 端口改动后连接命令要跟着变
        inputPort.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(android.text.Editable e) {
                currentPort = e.toString().trim();
                renderCommand();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        OpenWrtApi.getInstance().uciGetConfig("dropbear", new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                JsonObject values = result.has("values") && result.get("values").isJsonObject()
                        ? result.getAsJsonObject("values") : result;
                for (String sec : values.keySet()) {
                    if (!values.get(sec).isJsonObject()) continue;
                    JsonObject o = values.getAsJsonObject(sec);
                    if (o.has(".type") && !"dropbear".equals(o.get(".type").getAsString())) continue;
                    section = sec;
                    if (o.has("Port")) {
                        currentPort = o.get("Port").getAsString();
                        inputPort.setText(currentPort);
                    }
                    renderCommand();
                    switchPassword.setChecked(uciBool(o, "PasswordAuth", true));
                    switchRoot.setChecked(uciBool(o, "RootPasswordAuth", true));
                    break;
                }
            }

            @Override
            public void onFailure(ApiError error) {
                // 保持默认值
            }
        });
    }

    /** ssh user@host -p port */
    private void renderCommand() {
        RouterDevice device = RouterDeviceManager.getInstance(this).getCurrentDevice();
        String user = device != null ? device.getUsername() : "root";
        String host = OpenWrtApi.getInstance().getHost();
        if (host == null || host.isEmpty()) host = device != null ? device.getHost() : "192.168.1.1";
        String port = currentPort.isEmpty() ? "22" : currentPort;
        commandLabel.setText("ssh " + user + "@" + host + " -p " + port);
    }

    private void copyConnectionInfo() {
        android.content.ClipboardManager cm = (android.content.ClipboardManager)
                getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(android.content.ClipData.newPlainText(
                "ssh", commandLabel.getText()));
        new MaterialAlertDialogBuilder(this)
                .setMessage(R.string.ssh_copied)
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    private boolean uciBool(JsonObject o, String key, boolean def) {
        if (!o.has(key)) return def;
        String v = o.get(key).getAsString();
        return "1".equals(v) || "on".equalsIgnoreCase(v) || "true".equalsIgnoreCase(v)
                || "yes".equalsIgnoreCase(v);
    }

    private void save() {
        btnSave.setEnabled(false);
        JsonObject values = new JsonObject();
        values.addProperty("Port", inputPort.getText().toString().trim());
        values.addProperty("PasswordAuth", switchPassword.isChecked() ? "on" : "off");
        values.addProperty("RootPasswordAuth", switchRoot.isChecked() ? "on" : "off");

        OpenWrtApi api = OpenWrtApi.getInstance();
        api.uciSet("dropbear", section, values, new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                api.uciApplyOrCommit("dropbear", new ApiCallback<JsonObject>() {
                    @Override
                    public void onSuccess(JsonObject r) {
                        api.initdControl("dropbear", "restart", new ApiCallback<JsonObject>() {
                            @Override
                            public void onSuccess(JsonObject rr) {
                                done(null);
                            }

                            @Override
                            public void onFailure(ApiError error) {
                                done(null);   // 端口已改,重启失败不致命
                            }
                        });
                    }

                    @Override
                    public void onFailure(ApiError error) {
                        done(error);
                    }
                });
            }

            @Override
            public void onFailure(ApiError error) {
                done(error);
            }
        });
    }

    private void done(ApiError error) {
        btnSave.setEnabled(true);
        String msg = error == null ? getString(R.string.ssh_saved)
                : getString(R.string.msg_action_failed, error.getMessage());
        new MaterialAlertDialogBuilder(this)
                .setMessage(msg)
                .setPositiveButton(R.string.ok, null)
                .show();
    }
}