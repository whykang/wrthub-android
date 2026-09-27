package com.whykangkang.wrthub.ui.services.samba;

import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.whykangkang.wrthub.R;
import com.whykangkang.wrthub.api.ApiCallback;
import com.whykangkang.wrthub.api.ApiError;
import com.whykangkang.wrthub.api.SambaApi;

/**
 * 添加 / 编辑 Samba 共享,对应 iOS AddSambaShareViewController +
 * EditSambaShareViewController(带 section 参数即编辑模式,多一个删除按钮)。
 */
public class SambaShareEditActivity extends AppCompatActivity {

    public static final String EXTRA_SECTION = "section";
    public static final String EXTRA_NAME = "name";
    public static final String EXTRA_PATH = "path";
    public static final String EXTRA_READ_ONLY = "read_only";
    public static final String EXTRA_GUEST_OK = "guest_ok";

    private String section;
    private EditText inputName;
    private EditText inputPath;
    private SwitchMaterial switchReadOnly;
    private SwitchMaterial switchGuest;
    private MaterialButton btnSave;
    private MaterialButton btnDelete;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_samba_share);

        section = getIntent().getStringExtra(EXTRA_SECTION);
        boolean editing = section != null;

        inputName = findViewById(R.id.input_name);
        inputPath = findViewById(R.id.input_path);
        switchReadOnly = findViewById(R.id.switch_read_only);
        switchGuest = findViewById(R.id.switch_guest);
        btnSave = findViewById(R.id.btn_save);
        btnDelete = findViewById(R.id.btn_delete);

        ((TextView) findViewById(R.id.page_title))
                .setText(editing ? R.string.samba_edit_share : R.string.samba_add_share);

        if (editing) {
            inputName.setText(getIntent().getStringExtra(EXTRA_NAME));
            inputPath.setText(getIntent().getStringExtra(EXTRA_PATH));
            switchReadOnly.setChecked(getIntent().getBooleanExtra(EXTRA_READ_ONLY, false));
            switchGuest.setChecked(getIntent().getBooleanExtra(EXTRA_GUEST_OK, false));
            btnDelete.setVisibility(View.VISIBLE);
            btnDelete.setOnClickListener(v -> confirmDelete());
        } else {
            btnDelete.setVisibility(View.GONE);
        }
        btnSave.setOnClickListener(v -> save());
    }

    private void save() {
        String name = inputName.getText().toString().trim();
        String path = inputPath.getText().toString().trim();
        if (name.isEmpty()) {
            error(getString(R.string.samba_name_required));
            return;
        }
        if (path.isEmpty()) {
            error(getString(R.string.samba_path_required));
            return;
        }
        btnSave.setEnabled(false);
        ApiCallback<Boolean> cb = new ApiCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean ok) {
                btnSave.setEnabled(true);
                // 回调可能在页面关闭后才到,不加这道判断弹窗会 BadTokenException
                if (SambaShareEditActivity.this.isFinishing() || SambaShareEditActivity.this.isDestroyed()) return;
                new MaterialAlertDialogBuilder(SambaShareEditActivity.this)
                        .setMessage(R.string.samba_saved)
                        .setPositiveButton(R.string.ok, (d, w) -> finish())
                        .show();
            }

            @Override
            public void onFailure(ApiError err) {
                btnSave.setEnabled(true);
                error(getString(R.string.msg_action_failed, err.getMessage()));
            }
        };
        if (section == null) {
            SambaApi.addShare(name, path, switchReadOnly.isChecked(),
                    switchGuest.isChecked(), cb);
        } else {
            SambaApi.updateShare(section, name, path, switchReadOnly.isChecked(),
                    switchGuest.isChecked(), cb);
        }
    }

    private void confirmDelete() {
        String name = inputName.getText().toString().trim();
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.confirm_delete_title)
                .setMessage(getString(R.string.samba_confirm_delete, name))
                .setPositiveButton(R.string.action_delete, (d, w) ->
                        SambaApi.deleteShare(section, new ApiCallback<Boolean>() {
                            @Override
                            public void onSuccess(Boolean ok) {
                                finish();
                            }

                            @Override
                            public void onFailure(ApiError err) {
                                error(getString(R.string.msg_action_failed, err.getMessage()));
                            }
                        }))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void error(String message) {
        new MaterialAlertDialogBuilder(this)
                .setMessage(message)
                .setPositiveButton(R.string.ok, null)
                .show();
    }
}
