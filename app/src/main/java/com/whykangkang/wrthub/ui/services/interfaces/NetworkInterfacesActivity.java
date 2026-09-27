package com.whykangkang.wrthub.ui.services.interfaces;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.whykangkang.wrthub.R;
import com.whykangkang.wrthub.api.ApiCallback;
import com.whykangkang.wrthub.api.ApiError;
import com.whykangkang.wrthub.api.OpenWrtApi;
import com.whykangkang.wrthub.util.Formatters;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 网络接口,对应 iOS NetworkInterfacesViewController。
 * 接口卡片:名称徽章(绿/灰)、协议/运行时间/MAC/接收/发送/IPv4/IPv6,下拉刷新。
 * 数据:network.interface.dump(协议/状态/IP)+ luci-rpc.getNetworkDevices(MAC/字节)。
 */
public class NetworkInterfacesActivity extends AppCompatActivity {

    static class Iface {
        String name;
        String proto;
        boolean up;
        long uptime;
        String device;
        String mac;
        long rxBytes, txBytes, rxPackets, txPackets;
        String ipv4, ipv6;
    }

    private SwipeRefreshLayout swipeRefresh;
    private IfaceAdapter adapter;
    private TextView statusLabel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_interfaces);

        swipeRefresh = findViewById(R.id.swipe_refresh);
        statusLabel = findViewById(R.id.status_label);
        RecyclerView list = findViewById(R.id.iface_list);
        adapter = new IfaceAdapter();
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);
        swipeRefresh.setOnRefreshListener(this::reload);
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        swipeRefresh.setRefreshing(true);
        OpenWrtApi.getInstance().getNetworkInterfaceDump(new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject dump) {
                List<Iface> ifaces = parseDump(dump);
                loadDevices(ifaces);
            }

            @Override
            public void onFailure(ApiError error) {
                swipeRefresh.setRefreshing(false);
                showStatus(getString(R.string.iface_load_failed, error.getMessage()));
            }
        });
    }

    /** 空列表 / 加载失败时用文字说明,别只留一片空白 */
    private void showStatus(String message) {
        statusLabel.setText(message);
        statusLabel.setVisibility(message == null ? View.GONE : View.VISIBLE);
        swipeRefresh.setVisibility(message == null ? View.VISIBLE : View.GONE);
    }

    private void loadDevices(List<Iface> ifaces) {
        OpenWrtApi.getInstance().getNetworkDevices(new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject devices) {
                mergeDevices(ifaces, devices);
                swipeRefresh.setRefreshing(false);
                adapter.submit(ifaces);
                showStatus(ifaces.isEmpty() ? getString(R.string.iface_empty) : null);
            }

            @Override
            public void onFailure(ApiError error) {
                swipeRefresh.setRefreshing(false);
                adapter.submit(ifaces);
                showStatus(ifaces.isEmpty() ? getString(R.string.iface_empty) : null);
            }
        });
    }

    /** uci proto 值 → 展示名(与 iOS InterfaceStatus.protoDisplay 一致) */
    private String protoDisplay(String proto) {
        if (proto == null || proto.isEmpty()) return getString(R.string.iface_proto_none);
        switch (proto.toLowerCase(Locale.ROOT)) {
            case "static":
                return getString(R.string.iface_proto_static);
            case "dhcp":
                return getString(R.string.iface_proto_dhcp);
            case "dhcpv6":
                return getString(R.string.iface_proto_dhcpv6);
            case "pppoe":
                return "PPPoE";
            case "none":
                return getString(R.string.iface_proto_none);
            default:
                return proto.toUpperCase(Locale.ROOT);
        }
    }

    private List<Iface> parseDump(JsonObject dump) {
        List<Iface> list = new ArrayList<>();
        if (!dump.has("interface") || !dump.get("interface").isJsonArray()) return list;
        for (JsonElement e : dump.getAsJsonArray("interface")) {
            JsonObject o = e.getAsJsonObject();
            Iface f = new Iface();
            f.name = optStr(o, "interface");
            f.proto = optStr(o, "proto");
            f.up = o.has("up") && o.get("up").getAsBoolean();
            f.uptime = optLong(o, "uptime");
            f.device = optStr(o, "device");
            f.ipv4 = firstAddress(o, "ipv4-address");
            f.ipv6 = firstAddress(o, "ipv6-address");
            list.add(f);
        }
        return list;
    }

    private void mergeDevices(List<Iface> ifaces, JsonObject devices) {
        for (Iface f : ifaces) {
            if (f.device == null || !devices.has(f.device)) continue;
            if (!devices.get(f.device).isJsonObject()) continue;
            JsonObject dev = devices.getAsJsonObject(f.device);
            f.mac = optStr(dev, "mac");
            if (dev.has("stats") && dev.get("stats").isJsonObject()) {
                JsonObject s = dev.getAsJsonObject("stats");
                f.rxBytes = optLong(s, "rx_bytes");
                f.txBytes = optLong(s, "tx_bytes");
                f.rxPackets = optLong(s, "rx_packets");
                f.txPackets = optLong(s, "tx_packets");
            }
        }
    }

    private String firstAddress(JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonArray()) return null;
        JsonArray arr = o.getAsJsonArray(key);
        if (arr.size() == 0) return null;
        JsonObject first = arr.get(0).getAsJsonObject();
        String addr = optStr(first, "address");
        if (addr == null) return null;
        return first.has("mask") ? addr + "/" + first.get("mask").getAsInt() : addr;
    }

    private static String optStr(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static long optLong(JsonObject o, String k) {
        try {
            return o.has(k) ? o.get(k).getAsLong() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private class IfaceAdapter extends RecyclerView.Adapter<IfaceAdapter.Holder> {

        private final List<Iface> items = new ArrayList<>();

        void submit(List<Iface> ifaces) {
            items.clear();
            items.addAll(ifaces);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_interface, parent, false);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            Iface f = items.get(position);
            boolean zh = Locale.getDefault().getLanguage().startsWith("zh");

            // 名称徽章本身按链路状态着色(绿/灰),这是 iOS 的做法
            h.name.setText(f.name != null ? f.name.toUpperCase(Locale.ROOT) : "?");
            h.name.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    getColor(f.up ? R.color.ios_green : R.color.ios_gray)));
            h.device.setText(f.device != null ? f.device : "");

            // 行序与 iOS 一致:协议 / 链路状态 / 运行时间 / MAC / 接收 / 发送 / IPv4 / IPv6
            h.rows.removeAllViews();
            addRow(h.rows, getString(R.string.iface_proto), protoDisplay(f.proto),
                    R.color.label_primary);
            addRow(h.rows, getString(R.string.iface_link),
                    getString(f.up ? R.string.iface_connected : R.string.iface_disconnected),
                    f.up ? R.color.ios_green : R.color.label_secondary);
            if (f.up) {
                addRow(h.rows, getString(R.string.iface_uptime),
                        Formatters.uptime(f.uptime, zh), R.color.label_primary);
            }
            addRow(h.rows, getString(R.string.iface_mac), f.mac, R.color.label_primary);
            addRow(h.rows, getString(R.string.iface_rx),
                    getString(R.string.iface_traffic, Formatters.bytes(f.rxBytes), f.rxPackets),
                    R.color.label_primary);
            addRow(h.rows, getString(R.string.iface_tx),
                    getString(R.string.iface_traffic, Formatters.bytes(f.txBytes), f.txPackets),
                    R.color.label_primary);
            addRow(h.rows, "IPv4", f.ipv4, R.color.label_primary);
            addRow(h.rows, "IPv6", f.ipv6, R.color.label_primary);
        }

        /** 一行键值:键固定最小宽度靠左,值右对齐可换行 */
        private void addRow(LinearLayout host, String label, String value, int valueColorRes) {
            if (value == null || value.isEmpty()) return;
            LinearLayout row = new LinearLayout(NetworkInterfacesActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (host.getChildCount() > 0) rowLp.topMargin = dp(6);
            row.setLayoutParams(rowLp);

            TextView key = new TextView(NetworkInterfacesActivity.this);
            key.setText(label);
            key.setTextSize(14);
            key.setTextColor(getColor(R.color.label_secondary));
            key.setTypeface(key.getTypeface(), android.graphics.Typeface.BOLD);
            key.setMinWidth(dp(78));

            TextView val = new TextView(NetworkInterfacesActivity.this);
            val.setText(value);
            val.setTextSize(14);
            val.setTextColor(getColor(valueColorRes));
            val.setGravity(android.view.Gravity.END);
            val.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            row.addView(key);
            row.addView(val);
            host.addView(row);
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final TextView name, device;
            final LinearLayout rows;

            Holder(@NonNull View v) {
                super(v);
                name = v.findViewById(R.id.iface_name);
                device = v.findViewById(R.id.iface_device);
                rows = v.findViewById(R.id.iface_rows);
            }
        }
    }
}