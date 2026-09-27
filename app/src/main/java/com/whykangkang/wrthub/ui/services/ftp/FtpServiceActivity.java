package com.whykangkang.wrthub.ui.services.ftp;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.whykangkang.wrthub.R;
import com.whykangkang.wrthub.api.ApiCallback;
import com.whykangkang.wrthub.api.ApiError;
import com.whykangkang.wrthub.api.FtpApi;
import com.whykangkang.wrthub.api.OpenWrtApi;
import com.whykangkang.wrthub.manager.RouterDeviceManager;
import com.whykangkang.wrthub.model.RouterDevice;

import java.util.ArrayList;
import java.util.List;

/**
 * FTP(vsftpd)服务页,对应 iOS FTPServiceViewController。
 * 状态卡(开关 + 地址 + 浏览文件)、端口设置卡、虚拟用户卡(可删除)。
 */
public class FtpServiceActivity extends AppCompatActivity {

    private TextView statusLabel;
    private TextView addressLabel;
    private SwitchMaterial enableSwitch;
    private TextView portValue;
    private LinearLayout usersContainer;
    private com.google.android.material.button.MaterialButton browseButton;

    private String ftpPort = "21";
    private boolean serviceRunning;
    /** 还没拿到配置时状态是「未知」,不能一上来就显示「已停止」(iOS 同款) */
    private boolean configLoaded;
    private final List<FtpApi.FtpUser> users = new ArrayList<>();
    /** 开关是代码写入还是用户点的 —— 避免 load 回填时触发确认弹窗 */
    private boolean suppressSwitchCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ftp);

        statusLabel = findViewById(R.id.ftp_status);
        addressLabel = findViewById(R.id.ftp_address);
        enableSwitch = findViewById(R.id.ftp_switch);
        portValue = findViewById(R.id.ftp_port_value);
        usersContainer = findViewById(R.id.users_container);
        browseButton = findViewById(R.id.btn_browse);

        enableSwitch.setOnCheckedChangeListener((btn, checked) -> {
            if (suppressSwitchCallback) return;
            confirmToggle(checked);
        });
        findViewById(R.id.port_row).setOnClickListener(v -> editPort());
        browseButton.setOnClickListener(v -> browseFiles());
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        FtpApi.getConfig(new ApiCallback<FtpApi.FtpConfig>() {
            @Override
            public void onSuccess(FtpApi.FtpConfig config) {
                ftpPort = config.port;
                serviceRunning = config.enable4;
                configLoaded = true;
                renderStatus();
            }

            @Override
            public void onFailure(ApiError error) {
                configLoaded = false;
                renderStatus();
            }
        });
        FtpApi.getUsers(new ApiCallback<List<FtpApi.FtpUser>>() {
            @Override
            public void onSuccess(List<FtpApi.FtpUser> result) {
                users.clear();
                users.addAll(result);
                renderUsers();
            }

            @Override
            public void onFailure(ApiError error) {
                users.clear();
                renderUsers();
            }
        });
    }

    private void renderStatus() {
        String host = OpenWrtApi.getInstance().getHost();
        portValue.setText(ftpPort);
        addressLabel.setText(getString(R.string.ftp_address, "ftp://" + host + ":" + ftpPort));
        if (!configLoaded) {
            statusLabel.setText(R.string.ftp_unknown);
            statusLabel.setTextColor(getColor(R.color.label_secondary));
        } else {
            statusLabel.setText(serviceRunning ? R.string.ftp_running : R.string.ftp_stopped);
            statusLabel.setTextColor(getColor(serviceRunning ? R.color.ios_green : R.color.ios_red));
        }
        suppressSwitchCallback = true;
        enableSwitch.setChecked(serviceRunning);
        suppressSwitchCallback = false;
    }

    private void renderUsers() {
        usersContainer.removeAllViews();
        if (users.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.ftp_no_users);
            empty.setTextColor(getColor(R.color.label_secondary));
            empty.setTextSize(14);
            empty.setGravity(android.view.Gravity.CENTER);
            int pad = dp(16);
            empty.setPadding(pad, dp(24), pad, dp(24));
            usersContainer.addView(empty);
            return;
        }
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = 0; i < users.size(); i++) {
            FtpApi.FtpUser user = users.get(i);
            View row = inflater.inflate(R.layout.item_ftp_user, usersContainer, false);
            ((TextView) row.findViewById(R.id.ftp_user_name))
                    .setText(getString(R.string.ftp_user_line, user.username));
            ((TextView) row.findViewById(R.id.ftp_user_home))
                    .setText(getString(R.string.ftp_home_line, user.home));
            row.findViewById(R.id.ftp_user_delete)
                    .setOnClickListener(v -> confirmDeleteUser(user));
            usersContainer.addView(row);
            if (i < users.size() - 1) {
                usersContainer.addView(separator());
            }
        }
    }

    private void confirmDeleteUser(FtpApi.FtpUser user) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.ftp_delete_user)
                .setMessage(getString(R.string.ftp_confirm_delete_user, user.username))
                .setPositiveButton(R.string.action_delete, (d, w) ->
                        FtpApi.deleteUser(user.section, new ApiCallback<Boolean>() {
                            @Override
                            public void onSuccess(Boolean ok) {
                                toast(getString(R.string.ftp_user_deleted));
                                load();
                            }

                            @Override
                            public void onFailure(ApiError error) {
                                toast(getString(R.string.msg_action_failed, error.getMessage()));
                            }
                        }))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void confirmToggle(boolean enable) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(enable ? R.string.ftp_enable_title : R.string.ftp_disable_title)
                .setMessage(enable ? R.string.ftp_confirm_enable : R.string.ftp_confirm_disable)
                .setPositiveButton(R.string.ok, (d, w) -> performToggle(enable))
                .setNegativeButton(R.string.action_cancel, (d, w) -> {
                    suppressSwitchCallback = true;
                    enableSwitch.setChecked(!enable);
                    suppressSwitchCallback = false;
                })
                .setCancelable(false)
                .show();
    }

    private void performToggle(boolean enable) {
        FtpApi.updateConfig(ftpPort, enable, new ApiCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean ok) {
                serviceRunning = enable;
                renderStatus();
                toast(getString(enable ? R.string.ftp_started : R.string.ftp_stopped_msg));
            }

            @Override
            public void onFailure(ApiError error) {
                renderStatus();
                toast(getString(R.string.msg_action_failed, error.getMessage()));
            }
        });
    }

    private void editPort() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(ftpPort);
        int pad = dp(20);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(pad, pad / 2, pad, 0);
        wrap.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.ftp_port_edit_title)
                .setMessage(R.string.ftp_port_prompt)
                .setView(wrap)
                .setPositiveButton(R.string.btn_save, (d, w) -> {
                    String value = input.getText().toString().trim();
                    int port;
                    try {
                        port = Integer.parseInt(value);
                    } catch (NumberFormatException e) {
                        port = -1;
                    }
                    if (port < 1 || port > 65535) {
                        toast(getString(R.string.ftp_port_invalid));
                        return;
                    }
                    updatePort(value);
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void updatePort(String port) {
        FtpApi.updateConfig(port, serviceRunning, new ApiCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean ok) {
                ftpPort = port;
                renderStatus();
                toast(getString(R.string.ftp_port_updated));
            }

            @Override
            public void onFailure(ApiError error) {
                toast(getString(R.string.msg_action_failed, error.getMessage()));
            }
        });
    }

    /** 用路由器登录凭据连 FTP,与 iOS 一致 */
    private void browseFiles() {
        if (!serviceRunning) {
            toast(getString(R.string.ftp_not_running));
            return;
        }
        RouterDevice device = RouterDeviceManager.getInstance(this).getCurrentDevice();
        String username = device != null ? device.getUsername() : "root";
        String password = device != null ? device.getPassword() : "";
        Intent intent = new Intent(this, FtpBrowserActivity.class);
        intent.putExtra(FtpBrowserActivity.EXTRA_HOST, OpenWrtApi.getInstance().getHost());
        intent.putExtra(FtpBrowserActivity.EXTRA_PORT, parsePort(ftpPort));
        intent.putExtra(FtpBrowserActivity.EXTRA_USERNAME, username);
        intent.putExtra(FtpBrowserActivity.EXTRA_PASSWORD, password);
        intent.putExtra(FtpBrowserActivity.EXTRA_PATH, "/");
        intent.putExtra(FtpBrowserActivity.EXTRA_TITLE, getString(R.string.ftp_root));
        startActivity(intent);
    }

    private static int parsePort(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 21;
        }
    }

    private void toast(String message) {
        // 异步回调回来时页面可能已经关了,再弹窗会 BadTokenException 崩掉
        if (isFinishing() || isDestroyed()) return;
        new MaterialAlertDialogBuilder(this)
                .setMessage(message)
                .setPositiveButton(R.string.ok, null)
                .show();
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
