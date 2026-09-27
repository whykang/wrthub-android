package com.whykangkang.wrthub.ui.devices;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.gson.JsonObject;
import com.whykangkang.wrthub.R;
import com.whykangkang.wrthub.api.ApiCallback;
import com.whykangkang.wrthub.api.ApiError;
import com.whykangkang.wrthub.api.OpenWrtApi;
import com.whykangkang.wrthub.manager.BlacklistDateStore;

import com.google.gson.JsonElement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 黑名单,对应 iOS BlacklistViewController。
 * 列出防火墙里由本 App 添加的封禁规则(名称前缀识别),支持解除。
 */
public class BlacklistActivity extends AppCompatActivity {

    /** 一条封禁规则 */
    static class BlockedRule {
        String section;   // uci section 名(删除用)
        String name;      // 去前缀后的展示名
        String mac;
        /** 由 DHCP 租约 / hostHints 补出来的当前 IP 与主机名 */
        String ipAddress;
        String hostname;
    }

    private BlockAdapter adapter;
    private RecyclerView listView;
    private View emptyView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_blacklist);

        listView = findViewById(R.id.blacklist_list);
        emptyView = findViewById(R.id.empty_view);
        adapter = new BlockAdapter();
        listView.setLayoutManager(new LinearLayoutManager(this));
        listView.setAdapter(adapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        OpenWrtApi.getInstance().uciGetConfig("firewall", new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                enrich(parseRules(result));
            }

            @Override
            public void onFailure(ApiError error) {
                showList(new ArrayList<>());
            }
        });
    }

    /**
     * 防火墙规则里只有 MAC,列表上光看 MAC 认不出是哪台设备。
     * 用 DHCP 租约 + hostHints 补出主机名与当前 IP(与 iOS enrichBlacklistWithDetails 一致)。
     */
    private void enrich(List<BlockedRule> rules) {
        if (rules.isEmpty()) {
            showList(rules);
            return;
        }
        OpenWrtApi api = OpenWrtApi.getInstance();
        Map<String, String[]> byMac = new HashMap<>();
        api.getDHCPLeases(new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                JsonElement leases = result.get("dhcp_leases");
                if (leases != null && leases.isJsonArray()) {
                    for (JsonElement el : leases.getAsJsonArray()) {
                        if (!el.isJsonObject()) continue;
                        JsonObject lease = el.getAsJsonObject();
                        String mac = optStr(lease, "macaddr");
                        if (mac == null) continue;
                        byMac.put(mac.toUpperCase(Locale.ROOT), new String[]{
                                optStr(lease, "hostname"), optStr(lease, "ipaddr")});
                    }
                }
                loadHints();
            }

            @Override
            public void onFailure(ApiError error) {
                loadHints();
            }

            private void loadHints() {
                api.getHostHints(new ApiCallback<JsonObject>() {
                    @Override
                    public void onSuccess(JsonObject hints) {
                        for (String mac : hints.keySet()) {
                            JsonElement el = hints.get(mac);
                            if (!el.isJsonObject()) continue;
                            JsonObject hint = el.getAsJsonObject();
                            String key = mac.toUpperCase(Locale.ROOT);
                            if (byMac.containsKey(key)) continue;   // 租约优先
                            String ip = null;
                            JsonElement addrs = hint.get("ipaddrs");
                            if (addrs != null && addrs.isJsonArray()
                                    && addrs.getAsJsonArray().size() > 0) {
                                ip = addrs.getAsJsonArray().get(0).getAsString();
                            }
                            byMac.put(key, new String[]{optStr(hint, "name"), ip});
                        }
                        apply();
                    }

                    @Override
                    public void onFailure(ApiError error) {
                        apply();
                    }

                    private void apply() {
                        for (BlockedRule rule : rules) {
                            if (rule.mac == null) continue;
                            String[] info = byMac.get(rule.mac.toUpperCase(Locale.ROOT));
                            if (info == null) continue;
                            rule.hostname = info[0];
                            rule.ipAddress = info[1];
                        }
                        showList(rules);
                    }
                });
            }
        });
    }

    private List<BlockedRule> parseRules(JsonObject result) {
        List<BlockedRule> rules = new ArrayList<>();
        JsonObject values = result.has("values") && result.get("values").isJsonObject()
                ? result.getAsJsonObject("values") : result;
        for (String section : values.keySet()) {
            if (!values.get(section).isJsonObject()) continue;
            JsonObject rule = values.getAsJsonObject(section);
            String name = rule.has("name") ? rule.get("name").getAsString() : "";
            if (!name.startsWith(OpenWrtApi.BLOCK_RULE_PREFIX)) continue;
            BlockedRule r = new BlockedRule();
            r.section = section;
            r.name = name.substring(OpenWrtApi.BLOCK_RULE_PREFIX.length());
            String srcMac = OpenWrtApi.firstSrcMac(rule);
            r.mac = srcMac != null ? srcMac : "";
            rules.add(r);
        }
        return rules;
    }

    private void showList(List<BlockedRule> rules) {
        adapter.submit(rules);
        boolean empty = rules.isEmpty();
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        listView.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private void confirmUnblock(BlockedRule rule) {
        String name = rule.hostname != null && !rule.hostname.isEmpty() ? rule.hostname : rule.name;
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.action_unblock)
                .setMessage(getString(R.string.confirm_unblock, name))
                .setPositiveButton(R.string.action_unblock, (d, w) -> unblock(rule))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void unblock(BlockedRule rule) {
        OpenWrtApi.getInstance().unblockDevice(rule.section, new ApiCallback<Void>() {
            @Override
            public void onSuccess(Void result) {
                BlacklistDateStore.clear(BlacklistActivity.this, rule.mac);
                // 回调可能在页面关闭后才到,不加这道判断弹窗会 BadTokenException
                if (BlacklistActivity.this.isFinishing() || BlacklistActivity.this.isDestroyed()) return;
                new MaterialAlertDialogBuilder(BlacklistActivity.this)
                        .setMessage(R.string.msg_unblock_ok)
                        .setPositiveButton(R.string.ok, null)
                        .show();
                reload();
            }

            @Override
            public void onFailure(ApiError error) {
                // 回调可能在页面关闭后才到,不加这道判断弹窗会 BadTokenException
                if (BlacklistActivity.this.isFinishing() || BlacklistActivity.this.isDestroyed()) return;
                new MaterialAlertDialogBuilder(BlacklistActivity.this)
                        .setMessage(getString(R.string.msg_action_failed, error.getMessage()))
                        .setPositiveButton(R.string.ok, null)
                        .show();
            }
        });
    }

    private static String optStr(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && !e.isJsonNull() && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private class BlockAdapter extends RecyclerView.Adapter<BlockAdapter.Holder> {

        private final List<BlockedRule> items = new ArrayList<>();

        void submit(List<BlockedRule> rules) {
            items.clear();
            items.addAll(rules);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_blocked_device, parent, false);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            BlockedRule r = items.get(position);
            h.name.setText(r.hostname != null && !r.hostname.isEmpty() ? r.hostname : r.name);
            StringBuilder sub = new StringBuilder(r.mac != null ? r.mac : "");
            if (r.ipAddress != null && !r.ipAddress.isEmpty()) {
                sub.append("  ·  ").append(r.ipAddress);
            }
            h.mac.setText(sub.toString());
            h.unblock.setOnClickListener(v -> confirmUnblock(r));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final TextView name, ip, mac, added;
            final View unblock;

            Holder(@NonNull View v) {
                super(v);
                name = v.findViewById(R.id.block_name);
                ip = v.findViewById(R.id.block_ip);
                mac = v.findViewById(R.id.block_mac);
                added = v.findViewById(R.id.block_added);
                unblock = v.findViewById(R.id.btn_unblock);
            }
        }
    }
}