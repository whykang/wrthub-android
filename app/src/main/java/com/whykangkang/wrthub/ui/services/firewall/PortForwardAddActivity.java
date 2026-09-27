package com.whykangkang.wrthub.ui.services.firewall;

import android.os.Bundle;
import android.widget.EditText;
import android.widget.LinearLayout;
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
 * 添加端口转发,对应 iOS PortForwardingAddViewController。
 * 新增 firewall redirect(DNAT,wan→lan)。
 */
public class PortForwardAddActivity extends AppCompatActivity {

    private static final String[] PROTO_KEYS = {"tcp udp", "tcp", "udp"};
    private static final int[] PROTO_LABELS = {
            R.string.proto_tcpudp, R.string.proto_tcp, R.string.proto_udp};

    private EditText name, srcPort, destIp, destPort;
    private TextView protoValue;
    private String proto = "tcp udp";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_fw_add);
        ((TextView) findViewById(R.id.page_title)).setText(R.string.pf_add);

        FwForm form = new FwForm(this, findViewById(R.id.form_container));
        name = form.addTextRow(getString(R.string.pf_name), getString(R.string.field_name_hint), FwForm.TEXT);
        protoValue = form.addPickerRow(getString(R.string.pf_proto), v -> pickProto());
        srcPort = form.addTextRow(getString(R.string.pf_src_port), getString(R.string.pf_src_port_hint), FwForm.NUMBER);
        destIp = form.addTextRow(getString(R.string.pf_dest_ip), getString(R.string.pf_dest_ip_hint), FwForm.TEXT);
        destPort = form.addTextRow(getString(R.string.pf_dest_port), getString(R.string.pf_dest_port_hint), FwForm.NUMBER);
        protoValue.setText(PROTO_LABELS[0]);

        MaterialButton save = findViewById(R.id.btn_save);
        save.setOnClickListener(v -> save());
    }

    private void pickProto() {
        CharSequence[] items = new CharSequence[PROTO_LABELS.length];
        for (int i = 0; i < items.length; i++) items[i] = getString(PROTO_LABELS[i]);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.pf_proto)
                .setSingleChoiceItems(items, indexOf(proto), (d, which) -> {
                    proto = PROTO_KEYS[which];
                    protoValue.setText(PROTO_LABELS[which]);
                    d.dismiss();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void save() {
        String sPort = srcPort.getText().toString().trim();
        String dIp = destIp.getText().toString().trim();
        String dPort = destPort.getText().toString().trim();
        if (sPort.isEmpty() || dIp.isEmpty() || dPort.isEmpty()) {
            new MaterialAlertDialogBuilder(this)
                    .setMessage(R.string.error_address_required)
                    .setPositiveButton(R.string.ok, null)
                    .show();
            return;
        }
        JsonObject values = new JsonObject();
        String n = name.getText().toString().trim();
        values.addProperty("name", n.isEmpty() ? "PF-" + sPort : n);
        values.addProperty("target", "DNAT");
        values.addProperty("src", "wan");
        values.addProperty("dest", "lan");
        values.addProperty("proto", proto);
        values.addProperty("src_dport", sPort);
        values.addProperty("dest_ip", dIp);
        values.addProperty("dest_port", dPort);

        findViewById(R.id.btn_save).setEnabled(false);
        OpenWrtApi.getInstance().addFirewallSection("redirect", values, new ApiCallback<Void>() {
            @Override
            public void onSuccess(Void result) {
                finish();
            }

            @Override
            public void onFailure(ApiError error) {
                findViewById(R.id.btn_save).setEnabled(true);
                // 回调可能在页面关闭后才到,不加这道判断弹窗会 BadTokenException
                if (PortForwardAddActivity.this.isFinishing() || PortForwardAddActivity.this.isDestroyed()) return;
                new MaterialAlertDialogBuilder(PortForwardAddActivity.this)
                        .setMessage(getString(R.string.msg_action_failed, error.getMessage()))
                        .setPositiveButton(R.string.ok, null)
                        .show();
            }
        });
    }

    private int indexOf(String v) {
        for (int i = 0; i < PROTO_KEYS.length; i++) if (PROTO_KEYS[i].equals(v)) return i;
        return 0;
    }
}