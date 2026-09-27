package com.whykangkang.wrthub.ui.services.led;

import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.whykangkang.wrthub.R;
import com.whykangkang.wrthub.api.ApiCallback;
import com.whykangkang.wrthub.api.ApiError;
import com.whykangkang.wrthub.api.OpenWrtApi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 灯光配置,对应 iOS LEDConfigViewController + AddLEDConfigViewController。
 *
 * 列出 uci system 里的 led 段,点条目改触发器(5 种);选 timer 时再问闪烁间隔。
 * 右上角可新增:从 /sys/class/leds/ 里挑一个设备(符号链接与目录两种类型都要认,
 * 否则部分固件会一个都列不出来 —— iOS 修过的坑)。长按删除。
 */
public class LedConfigActivity extends AppCompatActivity {

    /** 常用触发器,与 iOS getAvailableLEDTriggers 一致 */
    private static final String[] TRIGGERS = {
            "none", "default-on", "heartbeat", "netdev", "timer"};

    static class Led {
        String section;
        String name;
        String sysfs;
        String trigger;
        String delayon;
        String delayoff;
    }

    private final List<Led> leds = new ArrayList<>();
    private LedAdapter adapter;
    private RecyclerView listView;
    private TextView emptyView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_led);
        listView = findViewById(R.id.led_list);
        emptyView = findViewById(R.id.empty_view);
        adapter = new LedAdapter();
        listView.setLayoutManager(new LinearLayoutManager(this));
        listView.setAdapter(adapter);
        findViewById(R.id.btn_add).setOnClickListener(v -> startAdd());
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    // =====================================================================
    // 读取
    // =====================================================================

    private void reload() {
        OpenWrtApi.getInstance().uciGetConfig("system", new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject config) {
                leds.clear();
                JsonObject values = config.has("values") && config.get("values").isJsonObject()
                        ? config.getAsJsonObject("values") : config;
                for (String sec : values.keySet()) {
                    JsonElement el = values.get(sec);
                    if (!el.isJsonObject()) continue;
                    JsonObject o = el.getAsJsonObject();
                    if (!"led".equals(str(o, ".type"))) continue;
                    Led led = new Led();
                    led.section = sec;
                    led.sysfs = str(o, "sysfs");
                    led.name = str(o, "name");
                    if (led.name == null) led.name = led.sysfs != null ? led.sysfs : sec;
                    led.trigger = str(o, "trigger");
                    if (led.trigger == null) led.trigger = "none";
                    led.delayon = str(o, "delayon");
                    led.delayoff = str(o, "delayoff");
                    leds.add(led);
                }
                showList();
            }

            @Override
            public void onFailure(ApiError error) {
                leds.clear();
                showList();
            }
        });
    }

    private void showList() {
        adapter.notifyDataSetChanged();
        boolean empty = leds.isEmpty();
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        listView.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    /** 触发器的中文/英文显示名,与 iOS triggerDisplayName 一致 */
    private String triggerDisplayName(String trigger) {
        switch (trigger) {
            case "none":
                return getString(R.string.led_trigger_none);
            case "default-on":
                return getString(R.string.led_trigger_default_on);
            case "heartbeat":
                return getString(R.string.led_trigger_heartbeat);
            case "netdev":
                return getString(R.string.led_trigger_netdev);
            case "timer":
                return getString(R.string.led_trigger_timer);
            default:
                return trigger + " (kernel: " + trigger + ")";
        }
    }

    // =====================================================================
    // 改触发器
    // =====================================================================

    private void pickTrigger(Led led) {
        CharSequence[] items = new CharSequence[TRIGGERS.length];
        int current = 0;
        for (int i = 0; i < TRIGGERS.length; i++) {
            items[i] = triggerDisplayName(TRIGGERS[i]);
            if (TRIGGERS[i].equals(led.trigger)) current = i;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.led_trigger)
                .setSingleChoiceItems(items, current, (d, which) -> {
                    d.dismiss();
                    String trigger = TRIGGERS[which];
                    if ("timer".equals(trigger)) {
                        askTimerDelays(led);
                    } else {
                        applyTrigger(led, trigger, null, null);
                    }
                })
                .setNeutralButton(R.string.action_delete, (d, w) -> confirmDelete(led))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** timer 触发器要填通电/熄灭毫秒数 */
    private void askTimerDelays(Led led) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        form.setPadding(pad, dp(8), pad, 0);
        EditText on = numberInput(getString(R.string.led_delay_on),
                led.delayon != null ? led.delayon : "1000");
        EditText off = numberInput(getString(R.string.led_delay_off),
                led.delayoff != null ? led.delayoff : "1000");
        form.addView(on);
        form.addView(off);

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.led_trigger_timer)
                .setMessage(R.string.led_timer_hint)
                .setView(form)
                .setPositiveButton(R.string.ok, (d, w) -> applyTrigger(led, "timer",
                        on.getText().toString().trim(), off.getText().toString().trim()))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void applyTrigger(Led led, String trigger, String delayon, String delayoff) {
        OpenWrtApi api = OpenWrtApi.getInstance();
        JsonObject values = new JsonObject();
        values.addProperty("trigger", trigger);
        if ("timer".equals(trigger)) {
            if (delayon != null && !delayon.isEmpty()) values.addProperty("delayon", delayon);
            if (delayoff != null && !delayoff.isEmpty()) values.addProperty("delayoff", delayoff);
        }
        api.uciSet("system", led.section, values, new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                commitAndRestart();
            }

            @Override
            public void onFailure(ApiError error) {
                fail(error);
            }
        });
    }

    // =====================================================================
    // 新增 / 删除
    // =====================================================================

    /** 列出 /sys/class/leds/ 下的 LED 设备;符号链接与目录都要接受 */
    private void startAdd() {
        JsonObject params = new JsonObject();
        params.addProperty("path", "/sys/class/leds/");
        OpenWrtApi.getInstance().makeUbusCall("file", "list", params, null,
                new ApiCallback<JsonObject>() {
                    @Override
                    public void onSuccess(JsonObject result) {
                        List<String> devices = new ArrayList<>();
                        JsonElement entries = result.get("entries");
                        if (entries != null && entries.isJsonArray()) {
                            for (JsonElement el : entries.getAsJsonArray()) {
                                if (!el.isJsonObject()) continue;
                                JsonObject entry = el.getAsJsonObject();
                                String name = str(entry, "name");
                                String type = str(entry, "type");
                                if (name == null || ".".equals(name) || "..".equals(name)) continue;
                                if (!"symlink".equals(type) && !"directory".equals(type)) continue;
                                devices.add(name);
                            }
                        }
                        Collections.sort(devices);
                        if (devices.isEmpty()) {
                            alert(getString(R.string.led_no_devices));
                        } else {
                            pickDevice(devices);
                        }
                    }

                    @Override
                    public void onFailure(ApiError error) {
                        fail(error);
                    }
                });
    }

    private void pickDevice(List<String> devices) {
        CharSequence[] items = devices.toArray(new CharSequence[0]);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.led_pick_device)
                .setItems(items, (d, which) -> askName(devices.get(which)))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void askName(String sysfs) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(sysfs);
        LinearLayout box = new LinearLayout(this);
        int pad = dp(20);
        box.setPadding(pad, dp(8), pad, 0);
        box.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.led_add)
                .setMessage(R.string.led_name_hint)
                .setView(box)
                .setPositiveButton(R.string.ok, (d, w) ->
                        createLed(input.getText().toString().trim(), sysfs))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void createLed(String name, String sysfs) {
        OpenWrtApi api = OpenWrtApi.getInstance();
        JsonObject values = new JsonObject();
        values.addProperty("name", name.isEmpty() ? sysfs : name);
        values.addProperty("sysfs", sysfs);
        values.addProperty("trigger", "none");
        api.uciAdd("system", "led", null, values, new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                commitAndRestart();
            }

            @Override
            public void onFailure(ApiError error) {
                fail(error);
            }
        });
    }

    private void confirmDelete(Led led) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.confirm_delete_title)
                .setMessage(getString(R.string.led_confirm_delete, led.name))
                .setPositiveButton(R.string.action_delete, (d, w) ->
                        OpenWrtApi.getInstance().uciDelete("system", led.section, null,
                                new ApiCallback<JsonObject>() {
                                    @Override
                                    public void onSuccess(JsonObject result) {
                                        commitAndRestart();
                                    }

                                    @Override
                                    public void onFailure(ApiError error) {
                                        fail(error);
                                    }
                                }))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** 提交 system 配置并重启 led 服务(部分固件没有该服务,失败不算错) */
    private void commitAndRestart() {
        OpenWrtApi api = OpenWrtApi.getInstance();
        api.uciApplyOrCommit("system", new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                api.bestEffortInitAction("led", "restart", () -> {
                    alert(getString(R.string.led_saved));
                    reload();
                });
            }

            @Override
            public void onFailure(ApiError error) {
                fail(error);
            }
        });
    }

    // =====================================================================
    // 工具
    // =====================================================================

    private EditText numberInput(String hint, String value) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        return e;
    }

    private void fail(ApiError error) {
        alert(getString(R.string.msg_action_failed, error.getMessage()));
    }

    private void alert(String message) {
        // 异步回调回来时页面可能已经关了,再弹窗会 BadTokenException 崩掉
        if (isFinishing() || isDestroyed()) return;
        new MaterialAlertDialogBuilder(this)
                .setMessage(message)
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && !e.isJsonNull() && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private class LedAdapter extends RecyclerView.Adapter<LedAdapter.Holder> {
        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_led, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            Led led = leds.get(position);
            h.name.setText(led.name);
            h.sysfs.setText(led.sysfs != null ? led.sysfs : "");
            String trigger = triggerDisplayName(led.trigger);
            if ("timer".equals(led.trigger) && led.delayon != null && led.delayoff != null) {
                trigger = trigger + "  " + led.delayon + "/" + led.delayoff + " ms";
            }
            h.trigger.setText(trigger);
            h.itemView.setOnClickListener(v -> pickTrigger(led));
            h.itemView.setOnLongClickListener(v -> {
                confirmDelete(led);
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return leds.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final TextView name, sysfs, trigger;

            Holder(@NonNull View v) {
                super(v);
                name = v.findViewById(R.id.led_name);
                sysfs = v.findViewById(R.id.led_sysfs);
                trigger = v.findViewById(R.id.led_trigger);
            }
        }
    }
}
