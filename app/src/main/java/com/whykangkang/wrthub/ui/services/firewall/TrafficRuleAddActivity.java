package com.whykangkang.wrthub.ui.services.firewall;

import android.os.Bundle;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.gson.JsonObject;
import com.whykangkang.wrthub.R;
import com.whykangkang.wrthub.api.ApiCallback;
import com.whykangkang.wrthub.api.ApiError;
import com.whykangkang.wrthub.api.OpenWrtApi;

/**
 * 添加流量规则,对应 iOS TrafficRulesAddViewController。
 * 新增 firewall rule(wan→设备,指定目标端口与动作)。
 */
public class TrafficRuleAddActivity extends AppCompatActivity {

    private static final String[] PROTO_KEYS = {"tcp udp", "tcp", "udp"};
    private static final int[] PROTO_LABELS = {
            R.string.proto_tcpudp, R.string.proto_tcp, R.string.proto_udp};

    private static final String[] TARGET_KEYS = {"ACCEPT", "REJECT", "DROP"};
    private static final int[] TARGET_LABELS = {
            R.string.tr_target_accept, R.string.tr_target_reject, R.string.tr_target_drop};

    private EditText name, destPort;
    private TextView protoValue, targetValue;
    private String proto = "tcp udp";
    private String target = "ACCEPT";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_fw_add);
        ((TextView) findViewById(R.id.page_title)).setText(R.string.tr_add);

        FwForm form = new FwForm(this, findViewById(R.id.form_container));
        name = form.addTextRow(getString(R.string.pf_name), getString(R.string.field_name_hint), FwForm.TEXT);
        protoValue = form.addPickerRow(getString(R.string.pf_proto), v -> pickProto());
        destPort = form.addTextRow(getString(R.string.tr_dest_port), getString(R.string.pf_dest_port_hint), FwForm.NUMBER);
        targetValue = form.addPickerRow(getString(R.string.tr_target), v -> pickTarget());
        protoValue.setText(PROTO_LABELS[0]);
        targetValue.setText(TARGET_LABELS[0]);

        MaterialButton save = findViewById(R.id.btn_save);
        save.setOnClickListener(v -> save());
    }

    private void pickProto() {
        CharSequence[] items = labels(PROTO_LABELS);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.pf_proto)
                .setSingleChoiceItems(items, indexOf(PROTO_KEYS, proto), (d, which) -> {
                    proto = PROTO_KEYS[which];
                    protoValue.setText(PROTO_LABELS[which]);
                    d.dismiss();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void pickTarget() {
        CharSequence[] items = labels(TARGET_LABELS);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.tr_target)
                .setSingleChoiceItems(items, indexOf(TARGET_KEYS, target), (d, which) -> {
                    target = TARGET_KEYS[which];
                    targetValue.setText(TARGET_LABELS[which]);
                    d.dismiss();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void save() {
        String dPort = destPort.getText().toString().trim();
        if (dPort.isEmpty()) {
            new MaterialAlertDialogBuilder(this)
                    .setMessage(R.string.error_address_required)
                    .setPositiveButton(R.string.ok, null)
                    .show();
            return;
        }
        JsonObject values = new JsonObject();
        String n = name.getText().toString().trim();
        values.addProperty("name", n.isEmpty() ? "Rule-" + dPort : n);
        values.addProperty("src", "wan");
        values.addProperty("proto", proto);
        values.addProperty("dest_port", dPort);
        values.addProperty("target", target);

        findViewById(R.id.btn_save).setEnabled(false);
        OpenWrtApi.getInstance().addFirewallSection("rule", values, new ApiCallback<Void>() {
            @Override
            public void onSuccess(Void result) {
                finish();
            }

            @Override
            public void onFailure(ApiError error) {
                findViewById(R.id.btn_save).setEnabled(true);
                // 回调可能在页面关闭后才到,不加这道判断弹窗会 BadTokenException
                if (TrafficRuleAddActivity.this.isFinishing() || TrafficRuleAddActivity.this.isDestroyed()) return;
                new MaterialAlertDialogBuilder(TrafficRuleAddActivity.this)
                        .setMessage(getString(R.string.msg_action_failed, error.getMessage()))
                        .setPositiveButton(R.string.ok, null)
                        .show();
            }
        });
    }

    private CharSequence[] labels(int[] resArr) {
        CharSequence[] items = new CharSequence[resArr.length];
        for (int i = 0; i < resArr.length; i++) items[i] = getString(resArr[i]);
        return items;
    }

    private int indexOf(String[] arr, String v) {
        for (int i = 0; i < arr.length; i++) if (arr[i].equals(v)) return i;
        return 0;
    }
}