package com.whykangkang.wrthub.ui.services.firewall;

import android.content.Context;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.whykangkang.wrthub.R;

/** 卡片内表单行构造(iOS 表单风格:左标签 + 右输入/选择) */
class FwForm {

    private final Context context;
    private final LinearLayout container;

    FwForm(Context context, LinearLayout container) {
        this.context = context;
        this.container = container;
    }

    EditText addTextRow(String label, String hint, int inputType) {
        LinearLayout row = newRow();
        row.addView(makeLabel(label));
        EditText edit = new EditText(context);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        edit.setLayoutParams(lp);
        edit.setBackground(null);
        edit.setHint(hint);
        edit.setInputType(inputType);
        edit.setTextSize(16f);
        edit.setGravity(Gravity.END);
        edit.setTextColor(context.getColor(R.color.label_primary));
        edit.setHintTextColor(context.getColor(R.color.label_tertiary));
        edit.setMinHeight(dp(48));
        row.addView(edit);
        addWithSeparator(row);
        return edit;
    }

    /** 选择行:返回值 TextView,由调用方设文本并挂点击 */
    TextView addPickerRow(String label, View.OnClickListener onClick) {
        LinearLayout row = newRow();
        row.setClickable(true);
        row.setBackgroundResource(selectableBg());
        row.setOnClickListener(onClick);
        row.addView(makeLabel(label));
        TextView value = new TextView(context);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        value.setLayoutParams(lp);
        value.setGravity(Gravity.END);
        value.setTextSize(16f);
        value.setTextColor(context.getColor(R.color.label_secondary));
        row.addView(value);
        addWithSeparator(row);
        return value;
    }

    private LinearLayout newRow() {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(48));
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private TextView makeLabel(String label) {
        TextView tv = new TextView(context);
        tv.setText(label);
        tv.setWidth(dp(112));
        tv.setTextSize(16f);
        tv.setTextColor(context.getColor(R.color.label_primary));
        return tv;
    }

    private void addWithSeparator(View row) {
        if (container.getChildCount() > 0) {
            View sep = new View(context);
            sep.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1));
            sep.setBackgroundColor(context.getColor(R.color.separator));
            container.addView(sep);
        }
        container.addView(row);
    }

    private int selectableBg() {
        android.util.TypedValue tv = new android.util.TypedValue();
        context.getTheme().resolveAttribute(
                android.R.attr.selectableItemBackground, tv, true);
        return tv.resourceId;
    }

    static final int NUMBER = InputType.TYPE_CLASS_NUMBER;
    static final int TEXT = InputType.TYPE_CLASS_TEXT;

    private int dp(int v) {
        return Math.round(v * context.getResources().getDisplayMetrics().density);
    }
}