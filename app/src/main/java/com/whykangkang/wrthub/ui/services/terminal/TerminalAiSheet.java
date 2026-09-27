package com.whykangkang.wrthub.ui.services.terminal;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.ValueCallback;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.annotation.DrawableRes;
import androidx.appcompat.widget.SwitchCompat;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.whykangkang.wrthub.R;
import com.whykangkang.wrthub.api.AiAssistant;
import com.whykangkang.wrthub.api.AiAssistant.ExecResult;
import com.whykangkang.wrthub.api.AiAssistant.Plan;
import com.whykangkang.wrthub.api.AiAssistant.Turn;

import java.util.ArrayList;
import java.util.List;

/**
 * 终端里的 AI 对话面板,对应 iOS TerminalAIViewController。
 *
 * 流程:用户描述需求 → AI 给出命令 → 用户确认 → 在终端里逐条执行并抓回输出
 * → 结果自动交回 AI 总结,回答用户的问题;需要的话 AI 会再给下一步命令(同样要确认)。
 * 执行时面板不关闭,整个过程都在这里完成,也可以接着追问。
 * 命令列表和执行结果默认折叠,只突出 AI 的说明和总结。
 *
 * 面板对象由终端页持有,关掉再打开对话还在。
 */
public class TerminalAiSheet {

    /** AI 面板借终端页来看屏幕、执行命令 */
    public interface Runner {
        /** 终端最近几十行 */
        void aiScreen(ValueCallback<String> done);

        /** 是否停在 shell 提示符上 */
        void aiAtShell(ValueCallback<Boolean> done);

        /** 执行一条命令并抓回输出和退出码 */
        void aiRun(String command, ValueCallback<ExecResult> completion);

        /** Ctrl+C 打断正在跑的命令 */
        void aiInterrupt();
    }

    private final Activity activity;
    private final Runner runner;
    private final String routerInfo;
    private final List<Turn> conversation = new ArrayList<>();

    private final BottomSheetDialog dialog;
    private ScrollView scroll;
    private LinearLayout messages;
    private TextView intro;
    private EditText input;
    private ImageButton sendButton;
    private TextView providerTag;

    /** 最新一份方案的「复制 / 执行」按钮行;执行后换成状态文字 */
    private LinearLayout pendingActions;
    /** 思考中 / 执行中的状态行 */
    private View statusRow;
    private MaterialButton stopButton;

    private boolean busy;
    private boolean stopRequested;

    /**
     * 自动模式:AI 给出的命令不经确认直接执行(高风险的除外)。
     * 默认关;不持久化 —— 面板跟着终端页,离开 AI 终端就恢复关闭,免得哪天忘了它开着。
     */
    private boolean autoMode;
    private SwitchCompat autoSwitch;
    /** 用户每提一次问,最多连续自动执行几轮;到了就停下来等人确认,防止 AI 自己绕圈 */
    private static final int MAX_AUTO_ROUNDS = 5;
    private int autoRounds;

    public TerminalAiSheet(Activity activity, String routerInfo, Runner runner) {
        this.activity = activity;
        this.routerInfo = routerInfo;
        this.runner = runner;
        dialog = new BottomSheetDialog(activity);
        dialog.setContentView(buildLayout());
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
        dialog.setOnShowListener(d -> {
            // 撑满全高、直接展开,和 iOS 的 large sheet 一样
            FrameLayout sheet = dialog.findViewById(
                    com.google.android.material.R.id.design_bottom_sheet);
            if (sheet == null) return;
            sheet.setBackgroundColor(Color.TRANSPARENT);
            ViewGroup.LayoutParams lp = sheet.getLayoutParams();
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
            sheet.setLayoutParams(lp);
            BottomSheetBehavior<FrameLayout> behavior = BottomSheetBehavior.from(sheet);
            behavior.setSkipCollapsed(true);
            behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        });
    }

    public void show() {
        // 设置里可能换了模型
        providerTag.setText(AiAssistant.PROVIDER_NAME + " · " + AiAssistant.model(activity).shortName);
        dialog.show();
        if (!busy) input.requestFocus();
    }

    public void dismiss() {
        dialog.dismiss();
    }

    // =====================================================================
    // 布局
    // =====================================================================

    private View buildLayout() {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color(R.color.ai_bg));
        float r = dp(16);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        root.setBackground(bg);
        root.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // 拖动条
        View grabber = new View(activity);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x66FFFFFF);
        g.setCornerRadius(dp(3));
        grabber.setBackground(g);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(dp(36), dp(5));
        glp.gravity = Gravity.CENTER_HORIZONTAL;
        glp.topMargin = dp(6);
        root.addView(grabber, glp);

        scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        messages = vertical(dp(14));
        messages.setPadding(dp(16), dp(18), dp(16), dp(16));
        scroll.addView(messages);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 标题行:✦ AI 助手 [DeepSeek · V4 Pro]      [新对话]
        LinearLayout titleRow = horizontal();
        TextView title = text(activity.getString(R.string.ai_panel_title), 20, Color.WHITE, true);
        titleRow.addView(title);
        providerTag = pill("", 0xBFFFFFFF, 0x1AFFFFFF, 11);
        LinearLayout.LayoutParams tagLp = wrap();
        tagLp.leftMargin = dp(8);
        titleRow.addView(providerTag, tagLp);
        titleRow.addView(new View(activity), new LinearLayout.LayoutParams(0, 1, 1f));
        MaterialButton newChat = textButton(activity.getString(R.string.ai_new_chat),
                R.drawable.ic_new_chat, color(R.color.ios_blue));
        newChat.setOnClickListener(v -> resetConversation());
        titleRow.addView(newChat);
        messages.addView(titleRow);
        LinearLayout.LayoutParams autoLp = matchWrap();
        autoLp.topMargin = -dp(6);
        messages.addView(autoModeRow(), autoLp);

        intro = text(activity.getString(R.string.ai_intro), 13, color(R.color.ai_secondary), false);
        messages.addView(intro, matchWrap());

        root.addView(buildComposer());
        return root;
    }

    /** 底部输入栏:圆角输入框 + 圆形发送按钮 */
    private View buildComposer() {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        View divider = new View(activity);
        divider.setBackgroundColor(0x14FFFFFF);
        box.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));

        LinearLayout row = horizontal();
        row.setGravity(Gravity.BOTTOM);
        row.setPadding(dp(12), dp(8), dp(12), dp(8));

        input = new EditText(activity);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(0xFF737373);
        input.setHint(R.string.ai_placeholder);
        input.setTextSize(16);
        input.setMinHeight(dp(38));
        input.setMaxLines(5);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setPadding(dp(14), dp(8), dp(14), dp(8));
        input.setBackground(rounded(color(R.color.ai_input_bg), dp(19)));
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                updateComposer();
            }
        });
        row.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        sendButton = new ImageButton(activity);
        sendButton.setImageResource(R.drawable.ic_arrow_up);
        sendButton.setColorFilter(Color.WHITE);
        sendButton.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        sendButton.setPadding(dp(8), dp(8), dp(8), dp(8));
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(color(R.color.ios_blue));
        sendButton.setBackground(circle);
        sendButton.setContentDescription(activity.getString(R.string.ai_send));
        sendButton.setOnClickListener(v -> submit());
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(38), dp(38));
        slp.leftMargin = dp(8);
        row.addView(sendButton, slp);

        box.addView(row);
        updateComposer();
        return box;
    }

    private void updateComposer() {
        boolean hasText = input.getText().toString().trim().length() > 0;
        boolean enabled = !busy && hasText;
        sendButton.setEnabled(enabled);
        sendButton.setAlpha(enabled ? 1f : 0.4f);
    }

    /** 执行 / 思考过程中不允许下滑或返回键关掉面板,免得命令跑一半没人看结果 */
    private void setBusy(boolean value) {
        busy = value;
        dialog.setCancelable(!value);
        FrameLayout sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (sheet != null) BottomSheetBehavior.from(sheet).setDraggable(!value);
        updateComposer();
    }

    private void resetConversation() {
        if (busy) return;
        conversation.clear();
        pendingActions = null;
        // 保留标题行和说明
        // 保留标题行、自动执行开关和说明
        while (messages.getChildCount() > 3) messages.removeViewAt(3);
        intro.setVisibility(View.VISIBLE);
        input.setHint(R.string.ai_placeholder);
        input.requestFocus();
    }

    // =====================================================================
    // 提问
    // =====================================================================

    private void submit() {
        String request = input.getText().toString().trim();
        if (request.isEmpty() || busy) return;
        input.setText("");
        intro.setVisibility(View.GONE);
        input.setHint(R.string.ai_placeholder_followup);
        appendView(userBubble(request));
        // 新的提问:自动执行轮数重新计,之前按过的「中断」也不再算数
        autoRounds = 0;
        stopRequested = false;

        // 旧方案没执行就接着问,就当放弃了,按钮收起来
        if (pendingActions != null) {
            ((ViewGroup) pendingActions.getParent()).removeView(pendingActions);
            pendingActions = null;
        }

        if (conversation.isEmpty()) {
            // 第一轮带上终端屏幕和固件信息,AI 才知道当前状态
            setBusy(true);
            showStatus(activity.getString(R.string.ai_thinking), null, false);
            runner.aiScreen(screen -> {
                appendUserTurn(AiAssistant.firstRequestText(request, screen, routerInfo));
                askAI(activity.getString(R.string.ai_thinking));
            });
        } else {
            appendUserTurn(AiAssistant.followUpText(request));
            askAI(activity.getString(R.string.ai_thinking));
        }
    }

    /** 连续两轮都是 user(比如上一次请求失败后又换了个说法)就合并,保持一问一答交替 */
    private void appendUserTurn(String text) {
        if (!conversation.isEmpty() && "user".equals(conversation.get(conversation.size() - 1).role)) {
            Turn last = conversation.get(conversation.size() - 1);
            last.text = last.text + "\n\n" + text;
        } else {
            conversation.add(new Turn("user", text));
        }
    }

    private void askAI(String status) {
        setBusy(true);
        showStatus(status, null, false);
        AiAssistant.respond(activity, conversation, autoMode, new AiAssistant.Callback<Plan>() {
            @Override
            public void onSuccess(Plan plan) {
                hideStatus();
                setBusy(false);
                conversation.add(new Turn("assistant", plan.json()));
                appendView(planCard(plan));
                autoRunIfNeeded(plan);
            }

            @Override
            public void onFailure(AiAssistant.AiError error) {
                hideStatus();
                setBusy(false);
                appendView(errorRow(error.message(activity)));
            }
        });
    }

    // =====================================================================
    // 执行
    // =====================================================================

    /** 最后一道确认:把要执行的命令原样再列一遍。高风险的用红色按钮。 */
    private void confirmRun(Plan plan, LinearLayout actions) {
        if (busy) return;
        androidx.appcompat.app.AlertDialog alert = new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.ai_confirm_run_title)
                .setMessage(plan.commandsText())
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.ai_run, (d, w) -> startRun(plan, actions))
                .show();
        if ("high".equals(plan.risk)) {
            alert.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                    .setTextColor(color(R.color.ios_red));
        }
    }

    /** 自动模式下直接执行刚给出的方案。高风险、连续轮数到顶、刚按过中断时停下来等人确认。 */
    private void autoRunIfNeeded(Plan plan) {
        if (!autoMode || plan.steps.isEmpty() || pendingActions == null) return;
        if (stopRequested) return;   // 用户刚中断过,别又自己跑起来
        if ("high".equals(plan.risk)) {
            appendView(noteRow(activity.getString(R.string.ai_auto_high_risk)));
            return;
        }
        if (autoRounds >= MAX_AUTO_ROUNDS) {
            appendView(noteRow(activity.getString(R.string.ai_auto_round_limit, MAX_AUTO_ROUNDS)));
            return;
        }
        autoRounds++;
        startRun(plan, pendingActions, true);
    }

    private void startRun(Plan plan, LinearLayout actions) {
        startRun(plan, actions, false);
    }

    private void startRun(Plan plan, LinearLayout actions, boolean auto) {
        setBusy(true);
        runner.aiAtShell(ready -> {
            if (!ready) {
                setBusy(false);
                new MaterialAlertDialogBuilder(activity)
                        .setTitle(R.string.ai_not_ready_title)
                        .setMessage(R.string.ai_not_ready_message)
                        .setPositiveButton(R.string.ok, null)
                        .show();
                return;
            }
            replaceActions(actions, activity.getString(auto ? R.string.ai_ran_auto : R.string.ai_ran));
            stopRequested = false;
            List<String> commands = new ArrayList<>();
            for (Plan.Step s : plan.steps) commands.add(s.command);
            runSteps(commands, 0, new ArrayList<>());
        });
    }

    /** 逐条执行;某条失败或被打断,后面的都跳过 */
    private void runSteps(List<String> commands, int index, List<ExecResult> results) {
        if (index >= commands.size()) {
            finishRun(results);
            return;
        }
        String command = commands.get(index);
        showStatus(activity.getString(R.string.ai_running, index + 1, commands.size()),
                "$ " + command, true);
        runner.aiRun(command, result -> {
            results.add(result);
            if (!result.succeeded() || stopRequested) {
                for (int i = index + 1; i < commands.size(); i++) {
                    results.add(new ExecResult(commands.get(i), "", ExecResult.Status.SKIPPED, 0));
                }
                finishRun(results);
            } else {
                runSteps(commands, index + 1, results);
            }
        });
    }

    private void finishRun(List<ExecResult> results) {
        hideStatus();
        appendView(resultCard(results));
        appendUserTurn(AiAssistant.resultsText(results));
        askAI(activity.getString(R.string.ai_summarizing));
    }

    private void stopTapped() {
        stopRequested = true;
        if (stopButton != null) stopButton.setEnabled(false);
        runner.aiInterrupt();
    }

    // =====================================================================
    // 消息视图
    // =====================================================================

    private void appendView(View v) {
        // 状态行始终在最底下
        if (statusRow != null && statusRow.getParent() == messages) {
            messages.addView(v, messages.getChildCount() - 1);
        } else {
            messages.addView(v);
        }
        scrollToBottom();
    }

    private void scrollToBottom() {
        // 不用 fullScroll:它会把焦点从输入框抢走,键盘跟着收起
        scroll.post(() -> scroll.smoothScrollTo(0, messages.getHeight()));
    }

    private void showStatus(String text, String detail, boolean stoppable) {
        hideStatus();
        LinearLayout row = horizontal();
        ProgressBar spinner = new ProgressBar(activity);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(0xFFB3B3B3));
        row.addView(spinner, new LinearLayout.LayoutParams(dp(22), dp(22)));

        LinearLayout texts = vertical(dp(3));
        texts.addView(text(text, 14, 0xFFCCCCCC, true));
        if (detail != null) {
            TextView d = mono(detail, 12, color(R.color.ai_command));
            d.setMaxLines(2);
            d.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(d);
        }
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(10);
        row.addView(texts, tlp);

        if (stoppable) {
            MaterialButton stop = textButton(activity.getString(R.string.ai_stop),
                    R.drawable.ic_stop, color(R.color.ios_red));
            stop.setBackgroundTintList(ColorStateList.valueOf(0x1FFFFFFF));
            stop.setCornerRadius(dp(16));
            stop.setEnabled(!stopRequested);
            stop.setOnClickListener(v -> stopTapped());
            row.addView(stop);
            stopButton = stop;
        }
        statusRow = row;
        messages.addView(row);
        scrollToBottom();
    }

    private void hideStatus() {
        if (statusRow != null && statusRow.getParent() != null) {
            ((ViewGroup) statusRow.getParent()).removeView(statusRow);
        }
        statusRow = null;
        stopButton = null;
    }

    private View userBubble(String text) {
        TextView label = text(text, 15, Color.WHITE, false);
        label.setPadding(dp(13), dp(9), dp(13), dp(9));
        label.setBackground(rounded(color(R.color.ai_bubble), dp(16)));
        label.setTextIsSelectable(true);
        // 靠右,最宽 80%
        label.setMaxWidth((int) (activity.getResources().getDisplayMetrics().widthPixels * 0.8f));
        LinearLayout row = horizontal();
        row.setGravity(Gravity.END);
        row.addView(label);
        return row;
    }

    private View planCard(Plan plan) {
        LinearLayout stack = vertical(dp(10));

        if (!plan.steps.isEmpty()) {
            LinearLayout header = horizontal();
            header.addView(riskPill(plan.risk));
            stack.addView(header);
        }

        TextView explanation = text(plan.explanation, 15, Color.WHITE, false);
        explanation.setTextIsSelectable(true);
        explanation.setLineSpacing(0, 1.1f);
        stack.addView(explanation);

        // AI 只回答了问题、在追问细节或者总结完了:没有命令,也就没有执行按钮
        if (plan.steps.isEmpty()) return stack;

        // 命令默认折叠,点开才看;执行前的确认弹窗里也会完整列出
        List<View> steps = new ArrayList<>();
        for (int i = 0; i < plan.steps.size(); i++) steps.add(stepCard(i + 1, plan.steps.get(i)));
        stack.addView(collapsible(activity.getString(R.string.ai_commands_count, plan.steps.size()),
                R.drawable.ic_code, color(R.color.ai_command), steps));

        if (!plan.notes.isEmpty()) {
            stack.addView(text("⚠️ " + plan.notes, 13, 0xE6FFCC00, false));
        }

        LinearLayout actions = horizontal();
        MaterialButton copy = filledButton(activity.getString(R.string.ai_copy_commands),
                0x33FFFFFF, color(R.color.ios_blue));
        copy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("commands", plan.commandsText()));
            flash(copy, activity.getString(R.string.ai_copied));
        });
        boolean high = "high".equals(plan.risk);
        MaterialButton run = filledButton(activity.getString(high ? R.string.ai_run_high : R.string.ai_run),
                color(high ? R.color.ios_red : R.color.ios_green), Color.WHITE);
        run.setOnClickListener(v -> confirmRun(plan, actions));
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0, dp(46), 1f);
        LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(0, dp(46), 1f);
        l2.leftMargin = dp(10);
        actions.addView(copy, l1);
        actions.addView(run, l2);
        stack.addView(actions);
        pendingActions = actions;
        return stack;
    }

    /** 执行过的方案:按钮换成一行状态文字,免得重复执行 */
    private void replaceActions(LinearLayout actions, String status) {
        if (!(actions.getParent() instanceof ViewGroup)) return;
        ViewGroup parent = (ViewGroup) actions.getParent();
        int index = parent.indexOfChild(actions);
        parent.removeView(actions);
        parent.addView(text(status, 13, color(R.color.ai_secondary), true), index);
        if (pendingActions == actions) pendingActions = null;
    }

    /** 执行结果默认折叠成一行摘要(几条成功 / 哪条失败),点开看每条命令的输出 */
    private View resultCard(List<ExecResult> results) {
        List<View> cards = new ArrayList<>();
        for (ExecResult r : results) {
            StatusInfo info = statusInfo(r);
            LinearLayout block = vertical(dp(6));

            LinearLayout head = horizontal();
            head.setGravity(Gravity.TOP);
            TextView command = mono("$ " + r.command, 12, color(R.color.ai_command));
            command.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            head.addView(command, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView badge = text(info.icon + " " + info.text, 11, info.color, true);
            LinearLayout.LayoutParams blp = wrap();
            blp.leftMargin = dp(8);
            head.addView(badge, blp);
            block.addView(head);

            if (r.status != ExecResult.Status.SKIPPED) {
                boolean empty = r.output.isEmpty();
                TextView output = mono(empty ? activity.getString(R.string.ai_no_output) : r.output,
                        11.5f, empty ? color(R.color.ai_secondary) : 0xFFD9D9D9);
                output.setMaxLines(8);
                output.setEllipsize(TextUtils.TruncateAt.END);
                // 点一下展开 / 收起
                output.setOnClickListener(v -> {
                    boolean collapsed = output.getMaxLines() != Integer.MAX_VALUE;
                    output.setMaxLines(collapsed ? Integer.MAX_VALUE : 8);
                });
                block.addView(output);
            }
            block.setPadding(dp(12), dp(10), dp(12), dp(10));
            block.setBackground(rounded(color(R.color.ai_card), dp(10)));
            cards.add(block);
        }

        int failed = -1;
        for (int i = 0; i < results.size(); i++) {
            if (!results.get(i).succeeded()) {
                failed = i;
                break;
            }
        }
        String title;
        int tint;
        int icon;
        if (failed >= 0) {
            ExecResult r = results.get(failed);
            StatusInfo info = statusInfo(r);
            title = r.status == ExecResult.Status.EXITED
                    ? activity.getString(R.string.ai_result_failed_code, failed + 1, r.exitCode)
                    : activity.getString(R.string.ai_result_failed_status, failed + 1, info.text);
            tint = info.color;
            icon = R.drawable.ic_error_circle;
        } else {
            title = activity.getString(R.string.ai_result_all_ok, results.size());
            tint = color(R.color.ios_green);
            icon = R.drawable.ic_check_circle;
        }
        return collapsible(title, icon, tint, cards);
    }

    private static final class StatusInfo {
        final String icon;
        final String text;
        final int color;

        StatusInfo(String icon, String text, int color) {
            this.icon = icon;
            this.text = text;
            this.color = color;
        }
    }

    private StatusInfo statusInfo(ExecResult r) {
        switch (r.status) {
            case EXITED:
                return r.exitCode == 0
                        ? new StatusInfo("✓", activity.getString(R.string.ai_status_ok), color(R.color.ios_green))
                        : new StatusInfo("✗", activity.getString(R.string.ai_status_exit, r.exitCode),
                        color(R.color.ios_red));
            case INTERRUPTED:
                return new StatusInfo("■", activity.getString(R.string.ai_status_interrupted),
                        color(R.color.ios_orange));
            case TIMED_OUT:
                return new StatusInfo("■", activity.getString(R.string.ai_status_timed_out),
                        color(R.color.ios_orange));
            case SKIPPED:
                return new StatusInfo("–", activity.getString(R.string.ai_status_skipped),
                        color(R.color.ai_secondary));
            default:
                return new StatusInfo("?", activity.getString(R.string.ai_status_lost),
                        color(R.color.ios_orange));
        }
    }

    /** 折叠区:一行标题(图标 + 文字 + 箭头),点一下展开 / 收起下面的内容 */
    private View collapsible(String title, @DrawableRes int icon, @ColorInt int tint, List<View> body) {
        LinearLayout box = vertical(0);
        box.setBackground(rounded(color(R.color.ai_box_bg), dp(10)));

        LinearLayout header = horizontal();
        header.setPadding(dp(12), dp(10), dp(12), dp(10));
        ImageView iv = new ImageView(activity);
        iv.setImageResource(icon);
        iv.setColorFilter(tint);
        header.addView(iv, new LinearLayout.LayoutParams(dp(16), dp(16)));
        TextView label = text(title, 13, tint, true);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        llp.leftMargin = dp(6);
        header.addView(label, llp);
        ImageView chevron = new ImageView(activity);
        chevron.setImageResource(R.drawable.ic_expand_more);
        chevron.setColorFilter(color(R.color.ai_secondary));
        header.addView(chevron, new LinearLayout.LayoutParams(dp(18), dp(18)));
        box.addView(header);

        LinearLayout content = vertical(dp(8));
        content.setPadding(dp(8), 0, dp(8), dp(8));
        for (View v : body) content.addView(v);
        content.setVisibility(View.GONE);
        box.addView(content);

        header.setOnClickListener(v -> {
            boolean expand = content.getVisibility() != View.VISIBLE;
            content.setVisibility(expand ? View.VISIBLE : View.GONE);
            chevron.animate().rotation(expand ? 180 : 0).setDuration(200).start();
        });
        return box;
    }

    /** 标题下面的「自动执行」开关行 */
    private View autoModeRow() {
        LinearLayout row = horizontal();
        row.setPadding(dp(12), dp(8), dp(8), dp(8));
        row.setBackground(rounded(color(R.color.ai_box_bg), dp(10)));

        ImageView icon = new ImageView(activity);
        icon.setImageResource(R.drawable.ic_bolt);
        icon.setColorFilter(color(R.color.ios_orange));
        row.addView(icon, new LinearLayout.LayoutParams(dp(18), dp(18)));

        LinearLayout texts = vertical(0);
        texts.addView(text(activity.getString(R.string.ai_auto_mode), 14, Color.WHITE, true));
        texts.addView(text(activity.getString(R.string.ai_auto_mode_hint), 12,
                color(R.color.ai_secondary), false));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(8);
        row.addView(texts, tlp);

        autoSwitch = new SwitchCompat(activity);
        autoSwitch.setChecked(autoMode);
        autoSwitch.setThumbTintList(new ColorStateList(
                new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{color(R.color.ios_orange), 0xFFBDBDBD}));
        autoSwitch.setTrackTintList(new ColorStateList(
                new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{(color(R.color.ios_orange) & 0x00FFFFFF) | 0x80000000, 0x40FFFFFF}));
        autoSwitch.setOnCheckedChangeListener((b, checked) -> onAutoSwitch(checked));
        row.addView(autoSwitch);
        return row;
    }

    /** 打开前先讲清楚风险,用户明确同意才开;关闭不用确认 */
    private void onAutoSwitch(boolean checked) {
        if (!checked) {
            autoMode = false;
            return;
        }
        if (autoMode) return;   // 同意后程序里 setChecked(true) 触发的回调
        autoSwitch.setChecked(false);
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.ai_auto_confirm_title)
                .setMessage(R.string.ai_auto_confirm_message)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.ai_auto_confirm_ok, (d, w) -> {
                    autoMode = true;
                    autoSwitch.setChecked(true);
                })
                .show();
    }

    /** 对话里的一行提示(自动执行停下来的原因等) */
    private View noteRow(String message) {
        return text(message, 13, color(R.color.ios_orange), false);
    }

    private View errorRow(String message) {
        LinearLayout row = vertical(dp(8));
        row.addView(text(message, 14, color(R.color.ios_red), false));
        MaterialButton retry = filledButton(activity.getString(R.string.term_retry), 0x33FFFFFF,
                Color.WHITE);
        retry.setCornerRadius(dp(16));
        retry.setOnClickListener(v -> {
            if (busy || conversation.isEmpty()
                    || !"user".equals(conversation.get(conversation.size() - 1).role)) return;
            messages.removeView(row);
            askAI(activity.getString(R.string.ai_thinking));
        });
        row.addView(retry, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)));
        return row;
    }

    private TextView riskPill(String risk) {
        int textRes;
        int c;
        switch (risk) {
            case "low":
                textRes = R.string.ai_risk_low;
                c = color(R.color.ios_green);
                break;
            case "high":
                textRes = R.string.ai_risk_high;
                c = color(R.color.ios_red);
                break;
            default:
                textRes = R.string.ai_risk_medium;
                c = color(R.color.ios_orange);
        }
        return pill(activity.getString(textRes), c, (c & 0x00FFFFFF) | 0x26000000, 12);
    }

    private View stepCard(int index, Plan.Step step) {
        LinearLayout stack = vertical(dp(6));
        if (!step.description.isEmpty()) {
            stack.addView(text(index + ". " + step.description, 12, color(R.color.ai_secondary), false));
        }
        TextView command = mono("$ " + step.command, 13, color(R.color.ai_command));
        command.setTextIsSelectable(true);
        stack.addView(command);
        stack.setPadding(dp(12), dp(10), dp(12), dp(10));
        stack.setBackground(rounded(color(R.color.ai_card), dp(10)));
        return stack;
    }

    private void flash(MaterialButton button, String text) {
        CharSequence old = button.getText();
        button.setText(text);
        button.postDelayed(() -> button.setText(old), 1200);
    }

    // =====================================================================
    // 视图工具
    // =====================================================================

    private LinearLayout vertical(int spacing) {
        LinearLayout l = new LinearLayout(activity);
        l.setOrientation(LinearLayout.VERTICAL);
        if (spacing > 0) {
            GradientDrawable gap = new GradientDrawable();
            gap.setSize(0, spacing);
            l.setDividerDrawable(gap);
            l.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
        }
        return l;
    }

    private LinearLayout horizontal() {
        LinearLayout l = new LinearLayout(activity);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private TextView text(String s, float size, @ColorInt int color, boolean bold) {
        TextView t = new TextView(activity);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private TextView mono(String s, float size, @ColorInt int color) {
        TextView t = text(s, size, color, false);
        t.setTypeface(Typeface.MONOSPACE);
        return t;
    }

    private TextView pill(String s, @ColorInt int textColor, @ColorInt int bgColor, float size) {
        TextView t = text(s, size, textColor, true);
        t.setPadding(dp(8), dp(3), dp(8), dp(3));
        t.setBackground(rounded(bgColor, dp(10)));
        return t;
    }

    private MaterialButton textButton(String s, @DrawableRes int icon, @ColorInt int color) {
        MaterialButton b = new MaterialButton(activity, null,
                com.google.android.material.R.attr.borderlessButtonStyle);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(color);
        b.setIconResource(icon);
        b.setIconTint(ColorStateList.valueOf(color));
        b.setIconSize(dp(16));
        b.setIconPadding(dp(4));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setInsetTop(0);
        b.setInsetBottom(0);
        b.setPadding(dp(10), 0, dp(10), 0);
        return b;
    }

    private MaterialButton filledButton(String s, @ColorInt int bg, @ColorInt int fg) {
        MaterialButton b = new MaterialButton(activity);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(fg);
        b.setBackgroundTintList(ColorStateList.valueOf(bg));
        b.setCornerRadius(dp(12));
        b.setInsetTop(0);
        b.setInsetBottom(0);
        return b;
    }

    private GradientDrawable rounded(@ColorInt int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int color(int res) {
        return activity.getColor(res);
    }

    private int dp(float v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }
}
