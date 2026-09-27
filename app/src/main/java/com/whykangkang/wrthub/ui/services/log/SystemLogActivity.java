package com.whykangkang.wrthub.ui.services.log;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.whykangkang.wrthub.R;
import com.whykangkang.wrthub.api.ApiCallback;
import com.whykangkang.wrthub.api.ApiError;
import com.whykangkang.wrthub.api.OpenWrtApi;

/**
 * 系统日志,对应 iOS SystemLogViewController。
 * 系统/内核分段切换、等宽显示、加载后自动滚底、复制/分享/刷新,
 * 权限被拒时给出可操作的双语提示(需要 rpcd 的 file exec 权限才能跑 logread/dmesg)。
 */
public class SystemLogActivity extends AppCompatActivity {

    private TextView logText;
    private ScrollView scroll;
    private MaterialButtonToggleGroup typeGroup;
    private androidx.swiperefreshlayout.widget.SwipeRefreshLayout swipeRefresh;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_system_log);

        logText = findViewById(R.id.log_text);
        scroll = findViewById(R.id.log_scroll);
        typeGroup = findViewById(R.id.log_type_group);
        swipeRefresh = findViewById(R.id.swipe_refresh);
        swipeRefresh.setOnRefreshListener(() -> loadLogs(true));

        typeGroup.check(R.id.log_system);
        typeGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) loadLogs(true);
        });
        findViewById(R.id.btn_copy).setOnClickListener(v -> copyLogs());
        findViewById(R.id.btn_share).setOnClickListener(v -> shareLogs());
        findViewById(R.id.btn_refresh).setOnClickListener(v -> loadLogs(true));
        loadLogs(true);
    }

    /**
     * allowRetry:会话失效(切设备再切回来)时,等后台静默重登刷新 token 后重试一次。
     * 与 iOS handleLogResult 的处理一致。
     */
    private void loadLogs(boolean allowRetry) {
        logText.setText(R.string.log_loading);
        boolean kernel = typeGroup.getCheckedButtonId() == R.id.log_kernel;
        ApiCallback<String> cb = new ApiCallback<String>() {
            @Override
            public void onSuccess(String result) {
                swipeRefresh.setRefreshing(false);
                logText.setText(result.trim().isEmpty() ? getString(R.string.log_empty) : result);
                scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
            }

            @Override
            public void onFailure(ApiError error) {
                swipeRefresh.setRefreshing(false);
                if (allowRetry && (error.getType() == ApiError.Type.SESSION_EXPIRED
                        || error.getType() == ApiError.Type.ACCESS_DENIED)) {
                    logText.setText("");
                    new Handler(Looper.getMainLooper())
                            .postDelayed(() -> loadLogs(false), 1500);
                    return;
                }
                logText.setText(errorMessage(error));
            }
        };
        if (kernel) {
            OpenWrtApi.getInstance().getKernelLogs(cb);
        } else {
            OpenWrtApi.getInstance().getSystemLogs(cb);
        }
    }

    /**
     * 权限被拒说明路由器 rpcd 的 ACL 没给当前用户 file exec 权限
     * (读日志要跑 logread / dmesg),这是路由器端配置问题,提示要说清怎么修。
     */
    private String errorMessage(ApiError error) {
        String desc = error.getMessage() == null ? "" : error.getMessage();
        if (error.getType() == ApiError.Type.PERMISSION_DENIED
                || error.getType() == ApiError.Type.ACCESS_DENIED
                || desc.contains("权限被拒绝") || desc.toLowerCase().contains("permission")) {
            return getString(R.string.log_permission_denied);
        }
        return getString(R.string.log_failed, desc);
    }

    private void copyLogs() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("logs", logText.getText()));
        new MaterialAlertDialogBuilder(this)
                .setMessage(R.string.log_copied)
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    private void shareLogs() {
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT, logText.getText().toString());
        startActivity(Intent.createChooser(share, getString(R.string.log_share)));
    }
}
