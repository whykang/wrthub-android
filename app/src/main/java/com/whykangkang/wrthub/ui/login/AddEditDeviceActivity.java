package com.whykangkang.wrthub.ui.login;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.whykangkang.wrthub.R;
import com.whykangkang.wrthub.api.ApiCallback;
import com.whykangkang.wrthub.api.ApiError;
import com.whykangkang.wrthub.api.OpenWrtApi;
import com.whykangkang.wrthub.manager.RouterDeviceManager;
import com.whykangkang.wrthub.model.RouterDevice;
import com.whykangkang.wrthub.util.AddressParser;

/**
 * 添加/编辑设备,对应 iOS AddEditDeviceViewController。
 * 地址解析(http(s)://host:port 自动拆分)、HTTPS 开关与端口联动、测试连接。
 */
public class AddEditDeviceActivity extends AppCompatActivity {

    public static final String EXTRA_DEVICE_ID = "device_id";

    private EditText inputName;
    private EditText inputAddress;
    private EditText inputPort;
    private SwitchMaterial switchHttps;
    private EditText inputUsername;
    private EditText inputPassword;

    private RouterDeviceManager manager;
    private RouterDevice editingDevice;
    /** 联动改端口时避免 TextWatcher 递归 */
    private boolean updatingFields;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_add_edit_device);

        manager = RouterDeviceManager.getInstance(this);
        inputName = findViewById(R.id.input_name);
        inputAddress = findViewById(R.id.input_address);
        inputPort = findViewById(R.id.input_port);
        switchHttps = findViewById(R.id.switch_https);
        inputUsername = findViewById(R.id.input_username);
        inputPassword = findViewById(R.id.input_password);

        String deviceId = getIntent().getStringExtra(EXTRA_DEVICE_ID);
        if (deviceId != null) {
            editingDevice = manager.getDevice(deviceId);
        }
        if (editingDevice != null) {
            ((TextView) findViewById(R.id.page_title)).setText(R.string.title_edit_device);
            inputName.setText(editingDevice.getName());
            inputAddress.setText(editingDevice.getHost());
            inputPort.setText(String.valueOf(editingDevice.getPort()));
            switchHttps.setChecked(editingDevice.isUseHttps());
            inputUsername.setText(editingDevice.getUsername());
            inputPassword.setText(editingDevice.getPassword());
        }

        setupAddressParsing();

        ActivityResultLauncher<Intent> scanLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        String host = result.getData().getStringExtra(RouterScanActivity.EXTRA_HOST);
                        if (host != null) {
                            inputAddress.setText(host);
                        }
                    }
                });
        findViewById(R.id.btn_scan).setOnClickListener(v ->
                scanLauncher.launch(new Intent(this, RouterScanActivity.class)));
        findViewById(R.id.btn_test).setOnClickListener(v -> testConnection());
        findViewById(R.id.btn_save).setOnClickListener(v -> save());
        findViewById(R.id.btn_cancel).setOnClickListener(v -> finish());
    }

    /** 地址输入含 scheme/端口时自动拆分到 HTTPS 开关与端口框;开关切换联动默认端口 */
    private void setupAddressParsing() {
        inputAddress.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (updatingFields) return;
                String text = s.toString();
                boolean hasScheme = text.contains("://");
                AddressParser.ParsedAddress parsed = AddressParser.parse(text);
                if (parsed == null || (!hasScheme && !parsed.explicitPort)) {
                    return;
                }
                updatingFields = true;
                if (hasScheme) {
                    switchHttps.setChecked(parsed.useHttps);
                }
                inputPort.setText(String.valueOf(parsed.port));
                updatingFields = false;
            }
        });

        switchHttps.setOnCheckedChangeListener((button, checked) -> {
            if (updatingFields) return;
            // 端口仍是默认值时跟随切换(80 <-> 443)
            String port = inputPort.getText().toString().trim();
            if (checked && "80".equals(port)) {
                inputPort.setText("443");
            } else if (!checked && "443".equals(port)) {
                inputPort.setText("80");
            }
        });
    }

    /** 校验输入,返回填充好的设备对象(编辑模式复用原对象);非法时返回 null 并提示 */
    private RouterDevice validateAndBuild() {
        if (inputName.getText().toString().trim().isEmpty()) {
            alert(getString(R.string.error_name_required));
            return null;
        }
        if (inputUsername.getText().toString().trim().isEmpty()) {
            alert(getString(R.string.error_username_required));
            return null;
        }
        String address = inputAddress.getText().toString();
        AddressParser.ParsedAddress parsed = AddressParser.parse(address);
        if (parsed == null) {
            new MaterialAlertDialogBuilder(this)
                    .setMessage(address.trim().isEmpty()
                            ? R.string.error_address_required : R.string.error_invalid_address)
                    .setPositiveButton(R.string.ok, null)
                    .show();
            return null;
        }

        int port;
        try {
            port = Integer.parseInt(inputPort.getText().toString().trim());
        } catch (NumberFormatException e) {
            port = switchHttps.isChecked() ? 443 : 80;
        }
        // 地址栏里显式带的端口优先
        if (parsed.explicitPort) {
            port = parsed.port;
        }

        RouterDevice device = editingDevice != null ? editingDevice : new RouterDevice();
        device.setName(inputName.getText().toString().trim());
        device.setHost(parsed.host);
        device.setPort(port);
        device.setUseHttps(switchHttps.isChecked());
        device.setUsername(inputUsername.getText().toString().trim());
        device.setPassword(inputPassword.getText().toString());
        return device;
    }

    private void testConnection() {
        RouterDevice device = validateAndBuild();
        if (device == null) return;

        setTesting(true);
        OpenWrtApi.testConnection(device.getHost(), device.getPort(), device.isUseHttps(),
                device.getUsername(), device.getPassword(), new ApiCallback<Void>() {
                    @Override
                    public void onSuccess(Void result) {
                        setTesting(false);
                        // 连接成功时直接给「保存并登录」,省掉一次返回再点(与 iOS 一致)
                        // 回调可能在页面关闭后才到,不加这道判断弹窗会 BadTokenException
                        if (AddEditDeviceActivity.this.isFinishing() || AddEditDeviceActivity.this.isDestroyed()) return;
                        new MaterialAlertDialogBuilder(AddEditDeviceActivity.this)
                                .setTitle(R.string.msg_test_ok)
                                .setMessage(R.string.msg_test_ok_detail)
                                .setPositiveButton(R.string.btn_save_and_login,
                                        (d, w) -> save())
                                .setNegativeButton(R.string.ok, null)
                                .show();
                    }

                    @Override
                    public void onFailure(ApiError error) {
                        setTesting(false);
                        // 回调可能在页面关闭后才到,不加这道判断弹窗会 BadTokenException
                        if (AddEditDeviceActivity.this.isFinishing() || AddEditDeviceActivity.this.isDestroyed()) return;
                        new MaterialAlertDialogBuilder(AddEditDeviceActivity.this)
                                .setMessage(getString(R.string.msg_login_failed, error.getMessage()))
                                .setPositiveButton(R.string.ok, null)
                                .show();
                    }
                });
    }

    /** 测试中:按钮文字换成转圈(对应 iOS TestConnectionCell 的 isTesting) */
    private void setTesting(boolean testing) {
        findViewById(R.id.test_spinner).setVisibility(testing ? View.VISIBLE : View.GONE);
        View testButton = findViewById(R.id.btn_test);
        testButton.setVisibility(testing ? View.INVISIBLE : View.VISIBLE);
        testButton.setEnabled(!testing);
    }

    /**
     * 保存。
     *
     * **编辑已有设备时只存不连**,直接回设备列表 —— 改个备注或密码而已,
     * 没必要等一次连接,更不该把人甩到首页去(被编辑的可能根本不是当前设备)。
     * 新增设备仍然保存后登录进首页(与 iOS saveAndLogin 一致)。
     */
    private void save() {
        RouterDevice device = validateAndBuild();
        if (device == null) return;
        if (editingDevice != null) {
            manager.updateDevice(device);
            applyToLiveSessionIfCurrent(device);
            finish();
            return;
        }
        manager.addDevice(device);

        AlertDialog progress = new MaterialAlertDialogBuilder(this)
                .setMessage(R.string.msg_connecting)
                .setCancelable(false)
                .show();
        OpenWrtApi api = OpenWrtApi.getInstance();
        api.configure(device.getHost(), device.getPort(), device.isUseHttps(),
                device.getUsername(), device.getPassword());
        // 换设备必须丢掉上一台的插件/软件包检测缓存
        com.whykangkang.wrthub.api.PluginChecker.clearCache();
        com.whykangkang.wrthub.api.PackageApi.clearAllCaches();
        api.login(new ApiCallback<Void>() {
            @Override
            public void onSuccess(Void result) {
                progress.dismiss();
                manager.setLastDeviceId(device.getId());
                com.whykangkang.wrthub.manager.SettingsManager
                        .getInstance(AddEditDeviceActivity.this)
                        .setLoginTimestamp(System.currentTimeMillis());
                Intent intent = new Intent(AddEditDeviceActivity.this,
                        com.whykangkang.wrthub.ui.MainActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
            }

            @Override
            public void onFailure(ApiError error) {
                progress.dismiss();
                // 设备已保存,登录失败只提示,返回列表后仍可重试
                // 回调可能在页面关闭后才到,不加这道判断弹窗会 BadTokenException
                if (AddEditDeviceActivity.this.isFinishing() || AddEditDeviceActivity.this.isDestroyed()) return;
                new MaterialAlertDialogBuilder(AddEditDeviceActivity.this)
                        .setMessage(getString(R.string.msg_login_failed, error.getMessage()))
                        .setPositiveButton(R.string.ok, (d, w) -> finish())
                        .show();
            }
        });
    }

    /**
     * 改的要是**当前正连着**的那台,得把新地址/账号灌回 OpenWrtApi,
     * 否则后续请求还在用旧凭据,一直失败到重启 App。
     * 这里不主动登录 —— 下一次请求发现 token 不新鲜会自己重登(withSession)。
     */
    private void applyToLiveSessionIfCurrent(RouterDevice device) {
        String currentId = manager.getLastDeviceId();
        if (currentId == null || !currentId.equals(device.getId())) return;
        OpenWrtApi.getInstance().configure(device.getHost(), device.getPort(),
                device.isUseHttps(), device.getUsername(), device.getPassword());
        // 地址或账号变了,上一台的检测缓存就不能用了
        com.whykangkang.wrthub.api.PluginChecker.clearCache();
        com.whykangkang.wrthub.api.PackageApi.clearAllCaches();
    }

    private void alert(String message) {
        // 异步回调回来时页面可能已经关了,再弹窗会 BadTokenException 崩掉
        if (isFinishing() || isDestroyed()) return;
        new MaterialAlertDialogBuilder(this)
                .setMessage(message)
                .setPositiveButton(R.string.ok, null)
                .show();
    }
}