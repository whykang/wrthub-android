package com.whykangkang.wrthub.ui.services.diagnostics;

import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.whykangkang.wrthub.R;
import com.whykangkang.wrthub.api.ApiCallback;
import com.whykangkang.wrthub.api.ApiError;
import com.whykangkang.wrthub.api.OpenWrtApi;

/**
 * 网络诊断,对应 iOS NetworkDiagnosticsViewController(即 LuCI 的「网络 / 诊断」页)。
 *
 * 三组「输入框 + 按钮」:Ping / Traceroute(长按按钮切 IPv4/IPv6)/ Nslookup,
 * 结果显示在等宽字体框里。命令与参数与 LuCI 完全一致(见 OpenWrtApi.runDiagnostic)。
 */
public class NetworkDiagnosticsActivity extends AppCompatActivity {

    private EditText inputPing;
    private EditText inputTrace;
    private EditText inputNs;
    private MaterialButton btnPing;
    private MaterialButton btnTrace;
    private MaterialButton btnNs;
    private TextView resultText;
    private ProgressBar spinner;

    private boolean pingIpv6;
    private boolean traceIpv6;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_diagnostics);

        inputPing = findViewById(R.id.input_ping);
        inputTrace = findViewById(R.id.input_trace);
        inputNs = findViewById(R.id.input_ns);
        btnPing = findViewById(R.id.btn_ping);
        btnTrace = findViewById(R.id.btn_trace);
        btnNs = findViewById(R.id.btn_ns);
        resultText = findViewById(R.id.result_text);
        spinner = findViewById(R.id.spinner);

        // 默认主机,方便直接点(与 iOS 一致)
        inputPing.setText("immortalwrt.org");
        inputTrace.setText("baidu.com");
        inputNs.setText("immortalwrt.org");

        // iOS 点按钮弹 IPv4/IPv6 菜单,选中即执行。
        // 原来这里把切换做成长按 —— 没人找得到,改成同样的下拉菜单。
        btnPing.setOnClickListener(v -> pickVersion(v, R.string.diag_ping_v4,
                R.string.diag_ping_v6, ipv6 -> {
                    pingIpv6 = ipv6;
                    btnPing.setText(ipv6 ? R.string.diag_ping_v6 : R.string.diag_ping_v4);
                    run(OpenWrtApi.DiagnosticType.PING, inputPing, ipv6);
                }));
        btnTrace.setOnClickListener(v -> pickVersion(v, R.string.diag_trace_v4,
                R.string.diag_trace_v6, ipv6 -> {
                    traceIpv6 = ipv6;
                    btnTrace.setText(ipv6 ? R.string.diag_trace_v6 : R.string.diag_trace_v4);
                    run(OpenWrtApi.DiagnosticType.TRACEROUTE, inputTrace, ipv6);
                }));
        btnNs.setOnClickListener(v -> run(OpenWrtApi.DiagnosticType.NSLOOKUP, inputNs, false));
    }

    private interface VersionPicked {
        void onPick(boolean ipv6);
    }

    /** IPv4 / IPv6 下拉菜单,选中立即执行 */
    private void pickVersion(View anchor, int v4Label, int v6Label, VersionPicked picked) {
        android.widget.PopupMenu menu = new android.widget.PopupMenu(this, anchor);
        menu.getMenu().add(0, 4, 0, getString(v4Label));
        menu.getMenu().add(0, 6, 1, getString(v6Label));
        menu.setOnMenuItemClickListener(item -> {
            picked.onPick(item.getItemId() == 6);
            return true;
        });
        menu.show();
    }

    private void run(OpenWrtApi.DiagnosticType type, EditText field, boolean ipv6) {
        String target = field.getText().toString().trim();
        if (target.isEmpty()) {
            field.setError(getString(R.string.diag_target_hint));
            return;
        }
        setBusy(true);
        resultText.setText(R.string.diag_running);
        OpenWrtApi.getInstance().runDiagnostic(type, target, ipv6, new ApiCallback<String>() {
            @Override
            public void onSuccess(String result) {
                setBusy(false);
                resultText.setText(result.isEmpty()
                        ? getString(R.string.diag_no_output) : result);
            }

            @Override
            public void onFailure(ApiError error) {
                setBusy(false);
                resultText.setText(error.getMessage());
            }
        });
    }

    private void setBusy(boolean busy) {
        spinner.setVisibility(busy ? View.VISIBLE : View.GONE);
        btnPing.setEnabled(!busy);
        btnTrace.setEnabled(!busy);
        btnNs.setEnabled(!busy);
    }
}
