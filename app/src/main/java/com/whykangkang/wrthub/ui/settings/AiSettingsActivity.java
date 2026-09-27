package com.whykangkang.wrthub.ui.settings;

import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.whykangkang.wrthub.R;
import com.whykangkang.wrthub.api.AiAssistant;

/**
 * 设置 → AI 助手,对应 iOS AISettingsViewController。
 *
 * 使用 DeepSeek。打开开关时要求填 API Key,**校验通过才真正开启**;
 * 校验失败开关弹回关闭。关闭时删除保存的 Key。
 */
public class AiSettingsActivity extends AppCompatActivity {

    private LinearLayout rows;
    private boolean validating;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ai_settings);
        rows = findViewById(R.id.ai_rows);
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    // =====================================================================
    // 表格
    // =====================================================================

    private void render() {
        rows.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        boolean enabled = AiAssistant.isEnabled(this);

        // 启用开关;校验中换成转圈 + 「正在验证…」
        View toggleRow = inflater.inflate(R.layout.view_switch_row, rows, false);
        ((TextView) toggleRow.findViewById(R.id.row_label)).setText(R.string.ai_enable);
        SwitchMaterial toggle = toggleRow.findViewById(R.id.row_switch);
        if (validating) {
            toggle.setVisibility(View.GONE);
            TextView verifying = new TextView(this);
            verifying.setText(R.string.ai_verifying);
            verifying.setTextColor(getColor(R.color.label_secondary));
            verifying.setTextSize(16);
            ((LinearLayout) toggleRow).addView(verifying);
            ProgressBar spinner = new ProgressBar(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(20), dp(20));
            lp.leftMargin = dp(8);
            ((LinearLayout) toggleRow).addView(spinner, lp);
        } else {
            toggle.setChecked(enabled);
            toggle.setOnCheckedChangeListener((b, checked) -> {
                if (checked) {
                    // 先弹回去,等 Key 校验通过再真正打开
                    b.setChecked(false);
                    promptKey(null, null);
                } else {
                    confirmDisable();
                }
            });
        }
        rows.addView(toggleRow);
        if (!enabled) return;

        rows.addView(separator());
        rows.addView(valueRow(getString(R.string.ai_model),
                getString(AiAssistant.model(this).labelRes), true, this::chooseModel));
        rows.addView(separator());
        View keyRow = valueRow(getString(R.string.ai_api_key), AiAssistant.maskedKey(this), false, null);
        ((TextView) keyRow.findViewById(R.id.row_value)).setTypeface(Typeface.MONOSPACE);
        rows.addView(keyRow);
        rows.addView(separator());
        View change = valueRow(getString(R.string.ai_change_key), "", false,
                // 更换 Key 同样要先校验;失败时保留原来的 Key 不动
                () -> promptKey(null, null));
        ((TextView) change.findViewById(R.id.row_label)).setTextColor(getColor(R.color.ios_blue));
        rows.addView(change);
    }

    private View valueRow(String label, String value, boolean chevron, Runnable onClick) {
        View row = LayoutInflater.from(this).inflate(R.layout.view_value_row, rows, false);
        ((TextView) row.findViewById(R.id.row_label)).setText(label);
        ((TextView) row.findViewById(R.id.row_value)).setText(value == null ? "" : value);
        // view_value_row 最后一个子视图是箭头
        ViewGroup group = (ViewGroup) row;
        group.getChildAt(group.getChildCount() - 1).setVisibility(chevron ? View.VISIBLE : View.GONE);
        if (onClick != null) {
            row.setOnClickListener(v -> {
                if (!validating) onClick.run();
            });
        } else {
            row.setBackground(null);
        }
        return row;
    }

    private View separator() {
        View line = new View(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.setMarginStart(dp(16));
        line.setLayoutParams(lp);
        line.setBackgroundColor(getColor(R.color.separator));
        return line;
    }

    // =====================================================================
    // Key
    // =====================================================================

    private void promptKey(String prefill, String error) {
        EditText field = new EditText(this);
        field.setHint("sk-...");
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        field.setSingleLine(true);
        if (prefill != null) field.setText(prefill);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(20), dp(4), dp(20), 0);
        box.addView(field);

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.ai_enter_key_title)
                .setMessage(error != null ? error : getString(R.string.ai_key_hint))
                .setView(box)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.ai_verify_enable, (d, w) -> {
                    String key = field.getText().toString().trim();
                    if (key.isEmpty()) {
                        promptKey(null, getString(R.string.ai_key_empty));
                        return;
                    }
                    validate(key);
                })
                .show();
        field.requestFocus();
    }

    private void validate(String key) {
        boolean wasEnabled = AiAssistant.isEnabled(this);
        validating = true;
        render();
        AiAssistant.validate(key, new AiAssistant.Callback<Void>() {
            @Override
            public void onSuccess(Void result) {
                validating = false;
                AiAssistant.enable(AiSettingsActivity.this, key);
                if (isFinishing()) return;
                render();
                alert(getString(wasEnabled ? R.string.ai_key_changed : R.string.ai_enabled_done));
            }

            @Override
            public void onFailure(AiAssistant.AiError error) {
                validating = false;
                if (isFinishing()) return;
                // 失败时不动原来的设置
                render();
                // 带着原来填的内容重新弹,免得要重新粘贴
                promptKey(key, getString(R.string.ai_verify_failed,
                        error.message(AiSettingsActivity.this)));
            }
        });
    }

    private void chooseModel() {
        AiAssistant.Model[] models = AiAssistant.Model.values();
        CharSequence[] labels = new CharSequence[models.length];
        int current = 0;
        for (int i = 0; i < models.length; i++) {
            labels[i] = getString(models[i].labelRes);
            if (models[i] == AiAssistant.model(this)) current = i;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.ai_choose_model)
                .setSingleChoiceItems(labels, current, (d, which) -> {
                    d.dismiss();
                    AiAssistant.setModel(this, models[which]);
                    render();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void confirmDisable() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.ai_disable_title)
                .setMessage(R.string.ai_disable_message)
                .setNegativeButton(R.string.action_cancel, (d, w) -> render())
                .setOnCancelListener(d -> render())
                .setPositiveButton(R.string.ai_disable, (d, w) -> {
                    AiAssistant.disable(this);
                    render();
                })
                .show();
    }

    private void alert(String message) {
        new MaterialAlertDialogBuilder(this)
                .setMessage(message)
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
