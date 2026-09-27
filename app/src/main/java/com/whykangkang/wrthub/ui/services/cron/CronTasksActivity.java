package com.whykangkang.wrthub.ui.services.cron;

import android.app.TimePickerDialog;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.gson.JsonObject;
import com.whykangkang.wrthub.R;
import com.whykangkang.wrthub.api.ApiCallback;
import com.whykangkang.wrthub.api.ApiError;
import com.whykangkang.wrthub.api.OpenWrtApi;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 定时任务,对应 iOS CronTasksViewController。
 *
 * 固定三张任务卡:重启网络 / 重启WiFi / 定时关闭WiFi(后者是一对 down+up 时间)。
 * 每张卡一个开关和时间按钮;启用会写入 /etc/crontabs/root 并**重启路由器**使其生效
 * (与 iOS 一致,启用前会明确告知),关闭则删掉对应行。
 */
public class CronTasksActivity extends AppCompatActivity {

    private static final String CRON_PATH = "/etc/crontabs/root";

    /** 任务类型,command 与 iOS CronTask.TaskType.command 完全一致 */
    private enum TaskType {
        NETWORK(R.string.cron_restart_network, "/etc/init.d/network restart"),
        WIFI(R.string.cron_restart_wifi, "wifi reload"),
        WIFI_SCHEDULE(R.string.cron_wifi_schedule, "wifi down");

        final int titleRes;
        final String command;

        TaskType(int titleRes, String command) {
            this.titleRes = titleRes;
            this.command = command;
        }

        boolean isSchedule() {
            return this == WIFI_SCHEDULE;
        }
    }

    private static class Task {
        final TaskType type;
        int hour;
        int minute;
        /** 仅定时关闭 WiFi 用:恢复开启的时间 */
        int endHour;
        int endMinute;
        boolean enabled;

        Task(TaskType type, int hour, int minute, int endHour, int endMinute) {
            this.type = type;
            this.hour = hour;
            this.minute = minute;
            this.endHour = endHour;
            this.endMinute = endMinute;
        }
    }

    /** 默认时间与 iOS 一致 */
    private final List<Task> tasks = new ArrayList<>();
    private LinearLayout container;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_cron);
        container = findViewById(R.id.cron_container);
        tasks.add(new Task(TaskType.NETWORK, 4, 0, 0, 0));
        tasks.add(new Task(TaskType.WIFI, 5, 0, 0, 0));
        tasks.add(new Task(TaskType.WIFI_SCHEDULE, 23, 0, 7, 0));
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    // =====================================================================
    // 读取
    // =====================================================================

    private void load() {
        OpenWrtApi.getInstance().fileRead(CRON_PATH, new ApiCallback<String>() {
            @Override
            public void onSuccess(String content) {
                parse(content);
                render();
            }

            @Override
            public void onFailure(ApiError error) {
                // 文件不存在就是「一条都没设」,按默认配置显示
                render();
            }
        });
    }

    /** 解析 crontab,把匹配到的行回填到对应任务卡(与 iOS parseCronTasks 一致) */
    private void parse(String content) {
        for (Task t : tasks) {
            t.enabled = false;
        }
        if (content == null) return;
        int[] wifiDown = null;
        int[] wifiUp = null;
        for (String raw : content.split("\n")) {
            CronParser.Entry entry = CronParser.parseLine(raw);
            if (entry == null) continue;

            if (CronParser.isWifiDown(entry.command)) {
                wifiDown = new int[]{entry.hour, entry.minute};
                continue;
            }
            if (CronParser.isWifiUp(entry.command)) {
                wifiUp = new int[]{entry.hour, entry.minute};
                continue;
            }
            for (Task t : tasks) {
                if (t.type.isSchedule()) continue;
                if (entry.command.contains(t.type.command)) {
                    t.hour = entry.hour;
                    t.minute = entry.minute;
                    t.enabled = true;
                    break;
                }
            }
        }
        if (wifiDown != null && wifiUp != null) {
            for (Task t : tasks) {
                if (!t.type.isSchedule()) continue;
                t.hour = wifiDown[0];
                t.minute = wifiDown[1];
                t.endHour = wifiUp[0];
                t.endMinute = wifiUp[1];
                t.enabled = true;
                break;
            }
        }
    }

    // =====================================================================
    // 渲染
    // =====================================================================

    private void render() {
        container.removeAllViews();
        for (Task task : tasks) {
            container.addView(taskCard(task));
        }
    }

    private View taskCard(Task task) {
        MaterialCardView card = new MaterialCardView(this, null,
                com.google.android.material.R.attr.materialCardViewStyle);
        card.setRadius(dp(16));
        card.setCardElevation(dp(1));
        card.setStrokeWidth(0);
        card.setCardBackgroundColor(getColor(R.color.bg_card));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.bottomMargin = dp(16);
        card.setLayoutParams(cardLp);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.addView(body);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText(task.type.titleRes);
        title.setTextSize(17);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setTextColor(getColor(R.color.label_primary));
        title.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        SwitchMaterial toggle = new SwitchMaterial(this);
        toggle.setChecked(task.enabled);
        toggle.setOnClickListener(v -> {
            boolean wantEnabled = toggle.isChecked();
            if (wantEnabled) {
                confirmEnable(task, toggle);
            } else {
                disableTask(task, toggle);
            }
        });
        header.addView(title);
        header.addView(toggle);
        body.addView(header);

        body.addView(timeRow(task, false));
        if (task.type.isSchedule()) {
            body.addView(timeRow(task, true));
        }
        // 启用后显示「下次执行」,与 iOS 一致(未启用时不显示)
        if (task.enabled) {
            body.addView(nextExecutionRow(task));
        }
        return card;
    }

    /** 下次执行:定时关闭 WiFi 取「关闭时间」那一次 */
    private View nextExecutionRow(Task task) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        row.setLayoutParams(lp);

        TextView label = new TextView(this);
        label.setText(R.string.cron_next_run);
        label.setTextSize(15);
        label.setTextColor(getColor(R.color.label_secondary));
        label.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView value = new TextView(this);
        boolean zh = Locale.getDefault().getLanguage().startsWith("zh");
        long next = CronParser.nextExecution(task.hour, task.minute, System.currentTimeMillis());
        value.setText(new java.text.SimpleDateFormat(
                zh ? "MM月dd日 HH:mm" : "MMM d, HH:mm", Locale.getDefault())
                .format(new java.util.Date(next)));
        value.setTextSize(15);
        value.setTextColor(getColor(R.color.label_primary));

        row.addView(label);
        row.addView(value);
        return row;
    }

    private View timeRow(Task task, boolean isEnd) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        row.setLayoutParams(lp);

        TextView label = new TextView(this);
        label.setText(task.type.isSchedule()
                ? (isEnd ? R.string.cron_wifi_on_time : R.string.cron_wifi_off_time)
                : R.string.cron_time);
        label.setTextSize(15);
        label.setTextColor(getColor(R.color.label_secondary));
        label.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        MaterialButton button = new MaterialButton(this, null,
                com.google.android.material.R.attr.borderlessButtonStyle);
        button.setText(String.format(Locale.US, "%02d:%02d",
                isEnd ? task.endHour : task.hour, isEnd ? task.endMinute : task.minute));
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setTextColor(getColor(R.color.ios_blue));
        button.setOnClickListener(v -> pickTime(task, isEnd));

        row.addView(label);
        row.addView(button);
        return row;
    }

    private void pickTime(Task task, boolean isEnd) {
        int hour = isEnd ? task.endHour : task.hour;
        int minute = isEnd ? task.endMinute : task.minute;
        new TimePickerDialog(this, (view, h, m) -> {
            if (isEnd) {
                task.endHour = h;
                task.endMinute = m;
            } else {
                task.hour = h;
                task.minute = m;
            }
            render();
            // 已启用的任务改时间要立即写回,否则显示与路由器不一致
            if (task.enabled) {
                confirmTimeUpdate(task);
            }
        }, hour, minute, true).show();
    }

    private void confirmTimeUpdate(Task task) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.cron_confirm_title)
                .setMessage(describe(task) + "\n\n" + getString(R.string.cron_reboot_warning))
                .setPositiveButton(R.string.cron_confirm_reboot, (d, w) -> writeTask(task, true))
                .setNegativeButton(R.string.action_cancel, (d, w) -> load())
                .show();
    }

    // =====================================================================
    // 启用 / 停用
    // =====================================================================

    private String describe(Task task) {
        if (task.type.isSchedule()) {
            return getString(R.string.cron_schedule_desc,
                    String.format(Locale.US, "%02d:%02d", task.hour, task.minute),
                    String.format(Locale.US, "%02d:%02d", task.endHour, task.endMinute));
        }
        return getString(R.string.cron_task_desc, getString(task.type.titleRes),
                String.format(Locale.US, "%02d:%02d", task.hour, task.minute));
    }

    private void confirmEnable(Task task, SwitchMaterial toggle) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.cron_confirm_title)
                .setMessage(describe(task) + "\n\n" + getString(R.string.cron_reboot_warning))
                .setPositiveButton(R.string.cron_confirm_reboot, (d, w) -> writeTask(task, true))
                .setNegativeButton(R.string.action_cancel, (d, w) -> {
                    toggle.setChecked(false);
                })
                .setCancelable(false)
                .show();
    }

    /** 写 crontab:先删掉本任务的旧行,再按当前时间追加 */
    private void writeTask(Task task, boolean enable) {
        AlertDialog progress = new MaterialAlertDialogBuilder(this)
                .setMessage(enable ? R.string.cron_enabling : R.string.cron_disabling)
                .setCancelable(false)
                .show();
        OpenWrtApi api = OpenWrtApi.getInstance();
        api.fileRead(CRON_PATH, new ApiCallback<String>() {
            @Override
            public void onSuccess(String content) {
                write(content);
            }

            @Override
            public void onFailure(ApiError error) {
                write("");
            }

            private void write(String content) {
                StringBuilder sb = new StringBuilder();
                for (String raw : content == null ? new String[0] : content.split("\n")) {
                    String line = raw.trim();
                    if (line.isEmpty()) continue;
                    if (matchesTask(line, task)) continue;   // 去掉本任务的旧行
                    sb.append(line).append('\n');
                }
                if (enable) {
                    if (task.type.isSchedule()) {
                        sb.append(task.minute).append(' ').append(task.hour)
                                .append(" * * * wifi down\n");
                        sb.append(task.endMinute).append(' ').append(task.endHour)
                                .append(" * * * wifi up\n");
                    } else {
                        sb.append(task.minute).append(' ').append(task.hour)
                                .append(" * * * ").append(task.type.command).append('\n');
                    }
                }
                api.fileWrite(CRON_PATH, sb.toString(), false,
                        new ApiCallback<JsonObject>() {
                            @Override
                            public void onSuccess(JsonObject result) {
                                progress.dismiss();
                                task.enabled = enable;
                                render();
                                if (enable) {
                                    rebootRouter();
                                } else {
                                    // 停用不需要重启,重载 cron 即可
                                    api.initdControl("cron", "restart",
                                            new ApiCallback<JsonObject>() {
                                                @Override
                                                public void onSuccess(JsonObject r) {
                                                    alert(getString(R.string.cron_deleted));
                                                }

                                                @Override
                                                public void onFailure(ApiError e) {
                                                    alert(getString(R.string.cron_deleted));
                                                }
                                            });
                                }
                            }

                            @Override
                            public void onFailure(ApiError error) {
                                progress.dismiss();
                                task.enabled = !enable;
                                render();
                                alert(getString(R.string.msg_action_failed,
                                        error.getMessage()));
                            }
                        });
            }
        });
    }

    /** 该 cron 行是否属于这个任务 */
    private static boolean matchesTask(String line, Task task) {
        if (task.type.isSchedule()) {
            return line.contains("wifi down") || line.contains("wifi up");
        }
        return line.contains(task.type.command);
    }

    private void disableTask(Task task, SwitchMaterial toggle) {
        writeTask(task, false);
    }

    /** 定时任务要重启路由器才生效(与 iOS 的 performReboot 一致) */
    private void rebootRouter() {
        OpenWrtApi.getInstance().reboot(new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                showRebootSuccess();
            }

            @Override
            public void onFailure(ApiError error) {
                // 重启命令常在响应返回前就断连,这不算失败
                showRebootSuccess();
            }
        });
    }

    private void showRebootSuccess() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.cron_saved)
                .setMessage(R.string.cron_reboot_started)
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    private void alert(String message) {
        // 异步回调回来时页面可能已经关了,再弹窗会 BadTokenException 崩掉
        if (isFinishing() || isDestroyed()) return;
        new MaterialAlertDialogBuilder(this)
                .setMessage(message)
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
