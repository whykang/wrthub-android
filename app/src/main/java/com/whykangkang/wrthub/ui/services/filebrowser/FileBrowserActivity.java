package com.whykangkang.wrthub.ui.services.filebrowser;

import android.content.Intent;
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
import com.whykangkang.wrthub.ui.web.WebAccessActivity;

/**
 * FileBrowser(luci-app-filebrowser)。
 *
 * 字段与网页端完全对应,取自 /luci-static/resources/view/filebrowser.js:
 *  - uci 配置 `filebrowser`,段名固定为 `config`(NamedSection),类型 `filebrowser`
 *  - enabled / listen_port(默认 8989)/ root_path(默认 /mnt)/ disable_exec(默认开)
 *  - 运行状态查 ubus service.list {"name":"filebrowser"},看 instances.*.running
 *  - Web 界面地址 http://&lt;路由器地址&gt;:&lt;listen_port&gt;
 */
public class FileBrowserActivity extends AppCompatActivity {

    /** 网页端的默认值,读不到配置时用同一套 */
    private static final String DEFAULT_PORT = "8989";
    private static final String DEFAULT_ROOT = "/mnt";
    private static final String CONFIG = "filebrowser";
    private static final String SECTION = "config";

    private TextView statusLabel;
    private TextView addressLabel;
    private SwitchMaterial switchEnabled;
    private SwitchMaterial switchDisableExec;
    private EditText inputPort;
    private EditText inputRoot;
    private MaterialButton btnSave;

    /** 还没拿到状态前不说「未运行」,先显示未知 */
    private boolean statusKnown;
    private boolean running;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_filebrowser);

        statusLabel = findViewById(R.id.fb_status);
        addressLabel = findViewById(R.id.fb_address);
        switchEnabled = findViewById(R.id.fb_switch_enabled);
        switchDisableExec = findViewById(R.id.fb_switch_disable_exec);
        inputPort = findViewById(R.id.fb_input_port);
        inputRoot = findViewById(R.id.fb_input_root);
        btnSave = findViewById(R.id.btn_save);

        btnSave.setOnClickListener(v -> save());
        findViewById(R.id.btn_browse).setOnClickListener(v -> browseFiles());
        findViewById(R.id.btn_open_web).setOnClickListener(v -> openWeb());
        renderAddress();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadConfig();
        loadStatus();
    }

    // =====================================================================
    // 读取
    // =====================================================================

    private void loadConfig() {
        OpenWrtApi.getInstance().uciGetConfig(CONFIG, new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                JsonObject values = result.has("values") && result.get("values").isJsonObject()
                        ? result.getAsJsonObject("values") : result;
                JsonObject cfg = values.has(SECTION) && values.get(SECTION).isJsonObject()
                        ? values.getAsJsonObject(SECTION) : null;
                if (cfg == null) {
                    // 段名不是 config 的情况(理论上不会,网页端写死了),退回找第一个 filebrowser 段
                    for (String key : values.keySet()) {
                        if (!values.get(key).isJsonObject()) continue;
                        JsonObject o = values.getAsJsonObject(key);
                        if (o.has(".type") && CONFIG.equals(o.get(".type").getAsString())) {
                            cfg = o;
                            break;
                        }
                    }
                }
                if (cfg == null) return;
                switchEnabled.setChecked(uciBool(cfg, "enabled", false));
                switchDisableExec.setChecked(uciBool(cfg, "disable_exec", true));
                inputPort.setText(str(cfg, "listen_port", DEFAULT_PORT));
                inputRoot.setText(str(cfg, "root_path", DEFAULT_ROOT));
                renderAddress();
            }

            @Override
            public void onFailure(ApiError error) {
                if (error.getType() == ApiError.Type.CONFIG_NOT_FOUND) {
                    notInstalled();
                    return;
                }
                // 其余失败保留默认值,别把页面搞空
                inputPort.setText(DEFAULT_PORT);
                inputRoot.setText(DEFAULT_ROOT);
                renderAddress();
            }
        });
    }

    private void loadStatus() {
        OpenWrtApi.getInstance().serviceList(CONFIG, new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                statusKnown = true;
                running = OpenWrtApi.serviceIsRunning(result, CONFIG);
                renderStatus();
            }

            @Override
            public void onFailure(ApiError error) {
                statusKnown = false;
                renderStatus();
            }
        });
    }

    // =====================================================================
    // 渲染
    // =====================================================================

    private void renderStatus() {
        if (!statusKnown) {
            statusLabel.setText(R.string.fb_status_unknown);
            statusLabel.setTextColor(getColor(R.color.label_secondary));
        } else {
            statusLabel.setText(running ? R.string.fb_running : R.string.fb_not_running);
            statusLabel.setTextColor(getColor(running ? R.color.ios_green : R.color.ios_red));
        }
    }

    private void renderAddress() {
        addressLabel.setText(getString(R.string.fb_address, webUrl()));
    }

    /** http://路由器地址:端口 —— 与网页端「打开 Web 界面」同一个地址 */
    private String webUrl() {
        String host = OpenWrtApi.getInstance().getHost();
        if (host == null || host.isEmpty()) host = "192.168.1.1";
        return "http://" + host + ":" + port();
    }

    private String port() {
        String value = inputPort.getText().toString().trim();
        return value.isEmpty() ? DEFAULT_PORT : value;
    }

    // =====================================================================
    // 操作
    // =====================================================================

    /** 在 App 里浏览文件(走 FileBrowser 的 HTTP API,不是内嵌网页) */
    private void browseFiles() {
        if (statusKnown && !running) {
            alert(getString(R.string.fb_not_running_hint));
            return;
        }
        Intent intent = new Intent(this, FileBrowserBrowseActivity.class);
        intent.putExtra(FileBrowserBrowseActivity.EXTRA_HOST, OpenWrtApi.getInstance().getHost());
        intent.putExtra(FileBrowserBrowseActivity.EXTRA_PORT, port());
        intent.putExtra(FileBrowserBrowseActivity.EXTRA_ROOT,
                inputRoot.getText().toString().trim());
        startActivity(intent);
    }

    /** 用内置浏览器打开,和 Clash 面板、网页访问一致 */
    private void openWeb() {
        if (statusKnown && !running) {
            alert(getString(R.string.fb_not_running_hint));
            return;
        }
        Intent intent = new Intent(this, WebAccessActivity.class);
        intent.putExtra(WebAccessActivity.EXTRA_URL, webUrl());
        intent.putExtra(WebAccessActivity.EXTRA_TITLE, getString(R.string.svc_filebrowser));
        startActivity(intent);
    }

    private void save() {
        String portValue = port();
        int portNumber;
        try {
            portNumber = Integer.parseInt(portValue);
        } catch (NumberFormatException e) {
            portNumber = -1;
        }
        if (portNumber < 1 || portNumber > 65535) {
            alert(getString(R.string.fb_port_invalid));
            return;
        }
        String root = inputRoot.getText().toString().trim();
        if (root.isEmpty()) {
            alert(getString(R.string.fb_root_required));
            return;
        }

        btnSave.setEnabled(false);
        JsonObject values = new JsonObject();
        values.addProperty("enabled", switchEnabled.isChecked() ? "1" : "0");
        values.addProperty("listen_port", portValue);
        values.addProperty("root_path", root);
        values.addProperty("disable_exec", switchDisableExec.isChecked() ? "1" : "0");

        OpenWrtApi api = OpenWrtApi.getInstance();
        api.uciSet(CONFIG, SECTION, values, new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                api.uciApplyOrCommit(CONFIG, new ApiCallback<JsonObject>() {
                    @Override
                    public void onSuccess(JsonObject r) {
                        // 开关变了要让服务跟着起停,apply 不一定会带上
                        api.bestEffortInitAction(CONFIG,
                                switchEnabled.isChecked() ? "restart" : "stop", () -> {
                                    btnSave.setEnabled(true);
                                    renderAddress();
                                    alert(getString(R.string.fb_saved));
                                    loadStatus();
                                });
                    }

                    @Override
                    public void onFailure(ApiError error) {
                        btnSave.setEnabled(true);
                        failed("uci apply " + CONFIG, error);
                    }
                });
            }

            @Override
            public void onFailure(ApiError error) {
                btnSave.setEnabled(true);
                failed("uci set " + CONFIG + "." + SECTION, error);
            }
        });
    }

    private void notInstalled() {
        if (isFinishing() || isDestroyed()) return;
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.svc_not_installed)
                .setMessage(getString(R.string.svc_not_installed_msg,
                        getString(R.string.svc_filebrowser)))
                .setPositiveButton(R.string.ok, (d, w) -> finish())
                .setOnDismissListener(d -> finish())
                .show();
    }

    // =====================================================================
    // 工具
    // =====================================================================

    private void failed(String step, ApiError error) {
        alert(getString(R.string.msg_action_failed, step + ": " + error.getMessage()));
    }

    private void alert(String message) {
        // 异步回调回来时页面可能已经关了,再弹窗会 BadTokenException 崩掉
        if (isFinishing() || isDestroyed()) return;
        new MaterialAlertDialogBuilder(this)
                .setMessage(message)
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    private static boolean uciBool(JsonObject o, String key, boolean def) {
        if (!o.has(key) || o.get(key).isJsonNull()) return def;
        String v = o.get(key).getAsString();
        return "1".equals(v) || "on".equalsIgnoreCase(v) || "true".equalsIgnoreCase(v)
                || "yes".equalsIgnoreCase(v);
    }

    private static String str(JsonObject o, String key, String def) {
        return o.has(key) && !o.get(key).isJsonNull() && !o.get(key).getAsString().isEmpty()
                ? o.get(key).getAsString() : def;
    }
}
