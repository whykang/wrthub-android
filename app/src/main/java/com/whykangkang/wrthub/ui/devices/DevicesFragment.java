package com.whykangkang.wrthub.ui.devices;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.whykangkang.wrthub.R;
import com.whykangkang.wrthub.api.ApiCallback;
import com.whykangkang.wrthub.api.ApiError;
import com.whykangkang.wrthub.api.ArpScanApi;
import com.whykangkang.wrthub.api.OpenWrtApi;
import com.whykangkang.wrthub.manager.BlacklistDateStore;
import com.whykangkang.wrthub.manager.DeviceNameStore;
import com.whykangkang.wrthub.model.ConnectedDevice;
import com.whykangkang.wrthub.ui.common.SignalBarsView;
import com.whykangkang.wrthub.ui.services.packages.PackageManagerActivity;
import com.whykangkang.wrthub.util.Formatters;
import com.whykangkang.wrthub.util.NetworkUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 设备页,对应 iOS DevicesViewController。
 *
 * 设备卡片(主机名/IP/MAC/信号格/SSID/本机徽章/流量)、点击详情(重命名 / 加入或
 * 移出黑名单)、右上角黑名单入口与 arp-scan 深度扫描。
 */
public class DevicesFragment extends Fragment {

    private SwipeRefreshLayout swipeRefresh;
    private DeviceAdapter adapter;
    private ConnectedDeviceLoader loader;
    private boolean loading;

    private final List<ConnectedDevice> devices = new ArrayList<>();
    /** 已封禁的 MAC(大写),来自防火墙规则 */
    private final Set<String> blacklistedMacs = new HashSet<>();
    /** MAC(大写) -> 封禁规则的 uci section,解除时用 */
    private final Map<String, String> blockSections = new HashMap<>();
    /** 深度扫描结果,刷新后要并回列表 */
    private final List<ArpScanApi.Entry> scanEntries = new ArrayList<>();
    /** 只有扫描发现、租约里没有的 MAC */
    private final Set<String> scanOnlyMacs = new HashSet<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_devices, container, false);
        swipeRefresh = root.findViewById(R.id.swipe_refresh);
        RecyclerView list = root.findViewById(R.id.devices_list);
        adapter = new DeviceAdapter();
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        list.setAdapter(adapter);

        swipeRefresh.setOnRefreshListener(this::reload);
        root.findViewById(R.id.btn_blacklist).setOnClickListener(v ->
                startActivity(new Intent(requireContext(), BlacklistActivity.class)));
        root.findViewById(R.id.btn_deep_scan).setOnClickListener(v -> startDeepScan());
        return root;
    }

    @Override
    public void onResume() {
        super.onResume();
        OpenWrtApi.getInstance().resetSessionIfStale();
        String localIp = NetworkUtils.getLocalIpv4(requireContext());
        loader = new ConnectedDeviceLoader(OpenWrtApi.getInstance(), localIp);
        reload();
    }

    // =====================================================================
    // 数据
    // =====================================================================

    private void reload() {
        if (loading || !OpenWrtApi.getInstance().isConfigured()) {
            swipeRefresh.setRefreshing(false);
            return;
        }
        loading = true;
        swipeRefresh.setRefreshing(true);
        loadBlacklist(() -> loader.load(result -> {
            loading = false;
            if (!isAdded()) return;
            swipeRefresh.setRefreshing(false);
            devices.clear();
            devices.addAll(result);
            // 刷新会用租约列表覆盖 devices,把之前扫到的额外设备再并回去
            mergeScanResults();
            adapter.notifyDataSetChanged();
        }));
    }

    /** 读防火墙里由本 App 添加的封禁规则,拿到 MAC 集合与 section 映射 */
    private void loadBlacklist(Runnable then) {
        OpenWrtApi.getInstance().uciGetConfig("firewall", new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                blacklistedMacs.clear();
                blockSections.clear();
                JsonObject values = result.has("values") && result.get("values").isJsonObject()
                        ? result.getAsJsonObject("values") : result;
                for (String section : values.keySet()) {
                    JsonElement el = values.get(section);
                    if (!el.isJsonObject()) continue;
                    JsonObject rule = el.getAsJsonObject();
                    String name = rule.has("name") ? rule.get("name").getAsString() : "";
                    if (!name.startsWith(OpenWrtApi.BLOCK_RULE_PREFIX)) continue;
                    String srcMac = OpenWrtApi.firstSrcMac(rule);
                    if (srcMac == null) continue;
                    String mac = srcMac.toUpperCase(Locale.ROOT);
                    blacklistedMacs.add(mac);
                    blockSections.put(mac, section);
                }
                then.run();
            }

            @Override
            public void onFailure(ApiError error) {
                // 黑名单读失败不影响设备列表显示
                blacklistedMacs.clear();
                blockSections.clear();
                then.run();
            }
        });
    }

    // =====================================================================
    // arp-scan 深度扫描
    // =====================================================================

    private void startDeepScan() {
        AlertDialog progress = new MaterialAlertDialogBuilder(requireContext())
                .setMessage(R.string.scan_deep_running)
                .setCancelable(false)
                .show();
        ArpScanApi.run(new ArpScanApi.Callback() {
            @Override
            public void onSuccess(List<ArpScanApi.Entry> entries) {
                progress.dismiss();
                if (!isAdded()) return;
                applyScanResults(entries);
            }

            @Override
            public void onFailure(ArpScanApi.Failure failure, String message) {
                progress.dismiss();
                if (!isAdded()) return;
                switch (failure) {
                    case NOT_INSTALLED:
                        showArpScanNotInstalled();
                        break;
                    case PERMISSION_DENIED:
                        showArpScanPermissionDenied();
                        break;
                    default:
                        alert(getString(R.string.scan_deep_failed, message));
                }
            }
        });
    }

    private void applyScanResults(List<ArpScanApi.Entry> entries) {
        scanEntries.clear();
        scanEntries.addAll(entries);
        int before = devices.size();
        mergeScanResults();
        adapter.notifyDataSetChanged();
        int added = devices.size() - before;
        alert(added > 0
                ? getString(R.string.scan_deep_found, entries.size(), added)
                : getString(R.string.scan_deep_found_none, entries.size()));
    }

    /**
     * 把扫描到、但租约列表里没有的设备并入列表。
     * 幂等:重复调用(每次刷新后都会调)不会产生重复行。
     */
    private void mergeScanResults() {
        scanOnlyMacs.clear();
        if (scanEntries.isEmpty()) return;
        Set<String> known = new HashSet<>();
        for (ConnectedDevice d : devices) {
            if (d.macAddress != null) known.add(d.macAddress.toUpperCase(Locale.ROOT));
        }
        List<ConnectedDevice> extras = new ArrayList<>();
        for (ArpScanApi.Entry entry : scanEntries) {
            if (!known.add(entry.mac)) continue;
            ConnectedDevice d = new ConnectedDevice();
            d.ipAddress = entry.ip;
            d.macAddress = entry.mac;
            d.hostname = entry.vendor.isEmpty() ? null : entry.vendor;
            extras.add(d);
            scanOnlyMacs.add(entry.mac);
        }
        devices.addAll(extras);
    }

    private void showArpScanNotInstalled() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.scan_arp_missing_title)
                .setMessage(R.string.scan_arp_missing_msg)
                .setPositiveButton(R.string.svc_go_install, (d, w) -> {
                    Intent intent = new Intent(requireContext(), PackageManagerActivity.class);
                    intent.putExtra(PackageManagerActivity.EXTRA_PREFILL, "arp-scan");
                    startActivity(intent);
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void showArpScanPermissionDenied() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.scan_arp_denied_title)
                .setMessage(R.string.scan_arp_denied_msg)
                .setPositiveButton(R.string.scan_copy_command, (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) requireContext()
                            .getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("arp-scan ACL",
                            ArpScanApi.ACL_COMMAND));
                    alert(getString(R.string.scan_copied));
                })
                .setNegativeButton(R.string.ok, null)
                .show();
    }

    // =====================================================================
    // 详情 / 重命名 / 黑名单
    // =====================================================================

    private String displayName(ConnectedDevice d) {
        String custom = DeviceNameStore.get(requireContext(), d.macAddress);
        return custom != null ? custom : d.getPrimaryTitle();
    }

    private void showDetail(ConnectedDevice d) {
        StringBuilder sb = new StringBuilder();
        appendRow(sb, getString(R.string.detail_ip), d.ipAddress);
        appendRow(sb, getString(R.string.detail_mac), d.macAddress);
        if (d.ssid != null) {
            appendRow(sb, getString(R.string.detail_ssid), d.ssid);
        }
        if (d.signalDbm != null) {
            appendRow(sb, getString(R.string.detail_signal), d.signalDbm + " dBm");
        }
        if (d.leaseRemainingSec > 0) {
            appendRow(sb, getString(R.string.detail_lease), leaseRemaining(d.leaseRemainingSec));
        }
        if (d.rxBytes > 0 || d.txBytes > 0) {
            appendRow(sb, getString(R.string.detail_traffic),
                    Formatters.bytes(d.rxBytes) + " / " + Formatters.bytes(d.txBytes));
        }

        boolean blocked = d.macAddress != null
                && blacklistedMacs.contains(d.macAddress.toUpperCase(Locale.ROOT));
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(displayName(d))
                .setMessage(sb.toString().trim())
                .setNeutralButton(R.string.device_rename, (dialog, w) -> renameDevice(d))
                .setNegativeButton(R.string.ok, null);
        // 本机设备不允许操作黑名单
        if (!d.isLocal && d.macAddress != null) {
            builder.setPositiveButton(blocked ? R.string.action_unblock : R.string.action_block,
                    (dialog, w) -> {
                        if (blocked) {
                            confirmUnblock(d);
                        } else {
                            confirmBlock(d);
                        }
                    });
        }
        builder.show();
    }

    /** 重命名:自定义名按 MAC 存本地,留空恢复默认(主机名/IP) */
    private void renameDevice(ConnectedDevice d) {
        EditText input = new EditText(requireContext());
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint(d.getPrimaryTitle());
        String current = DeviceNameStore.get(requireContext(), d.macAddress);
        if (current != null) input.setText(current);
        LinearLayout box = new LinearLayout(requireContext());
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.device_rename)
                .setMessage(R.string.device_rename_hint)
                .setView(box)
                .setPositiveButton(R.string.ok, (dialog, w) -> {
                    DeviceNameStore.set(requireContext(), d.macAddress,
                            input.getText().toString());
                    adapter.notifyDataSetChanged();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void confirmBlock(ConnectedDevice d) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.action_block)
                .setMessage(getString(R.string.confirm_block, displayName(d)))
                .setPositiveButton(R.string.action_block, (dialog, w) -> doBlock(d))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void doBlock(ConnectedDevice d) {
        OpenWrtApi.getInstance().blockDevice(d.macAddress, displayName(d),
                new ApiCallback<Void>() {
                    @Override
                    public void onSuccess(Void result) {
                        if (!isAdded()) return;
                        // 记一笔加入时间,黑名单页要显示(iOS 的 addedDate)
                        BlacklistDateStore.markAdded(requireContext(), d.macAddress);
                        alert(getString(R.string.msg_block_ok));
                        reload();
                    }

                    @Override
                    public void onFailure(ApiError error) {
                        if (!isAdded()) return;
                        alert(getString(R.string.msg_action_failed, error.getMessage()));
                    }
                });
    }

    private void confirmUnblock(ConnectedDevice d) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.action_unblock)
                .setMessage(getString(R.string.confirm_unblock, displayName(d)))
                .setPositiveButton(R.string.action_unblock, (dialog, w) -> doUnblock(d))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void doUnblock(ConnectedDevice d) {
        String section = blockSections.get(d.macAddress.toUpperCase(Locale.ROOT));
        if (section == null) return;
        OpenWrtApi.getInstance().unblockDevice(section, new ApiCallback<Void>() {
            @Override
            public void onSuccess(Void result) {
                if (!isAdded()) return;
                BlacklistDateStore.clear(requireContext(), d.macAddress);
                alert(getString(R.string.msg_unblock_ok));
                reload();
            }

            @Override
            public void onFailure(ApiError error) {
                if (!isAdded()) return;
                alert(getString(R.string.msg_action_failed, error.getMessage()));
            }
        });
    }

    // =====================================================================
    // 工具
    // =====================================================================

    private void alert(String message) {
        if (!isAdded()) return;
        new MaterialAlertDialogBuilder(requireContext())
                .setMessage(message)
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    private void appendRow(StringBuilder sb, String label, String value) {
        if (value == null) return;
        sb.append(label).append(": ").append(value).append('\n');
    }

    /** 列表里的租约行:租约 N小时M分 / 租约 M分钟(与 iOS connectedTimeString 一致) */
    private String leaseLine(long remainingSec) {
        int hours = (int) (remainingSec / 3600);
        int minutes = (int) ((remainingSec % 3600) / 60);
        return hours > 0
                ? getString(R.string.lease_line_hm, hours, minutes)
                : getString(R.string.lease_line_m, minutes);
    }

    /** 按主机名关键字猜设备类型图标,与 iOS NetworkDeviceCell.configure 同一套规则 */
    private static int iconFor(String hostname) {
        String name = hostname == null ? "" : hostname.toLowerCase(Locale.ROOT);
        if (name.contains("iphone") || name.contains("ipad")) return R.drawable.ic_dev_phone;
        if (name.contains("macbook") || name.contains("mac")) return R.drawable.ic_dev_laptop;
        if (name.contains("watch")) return R.drawable.ic_dev_watch;
        if (name.contains("tv")) return R.drawable.ic_dev_tv;
        if (name.contains("oppo") || name.contains("android")) return R.drawable.ic_dev_phone;
        if (name.contains("epson") || name.contains("printer")) return R.drawable.ic_dev_printer;
        return R.drawable.ic_dev_network;
    }

    /** remaining 已经是剩余秒数,不要再和当前时间相减 */
    private String leaseRemaining(long remainingSec) {
        if (remainingSec <= 0) return getString(R.string.lease_expired);
        int hours = (int) (remainingSec / 3600);
        int minutes = (int) ((remainingSec % 3600) / 60);
        return getString(R.string.lease_hm, hours, minutes);
    }

    private class DeviceAdapter extends RecyclerView.Adapter<DeviceAdapter.Holder> {

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_connected_device, parent, false);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            ConnectedDevice d = devices.get(position);
            String mac = d.macAddress != null ? d.macAddress.toUpperCase(Locale.ROOT) : "";
            boolean blocked = blacklistedMacs.contains(mac);
            boolean scanOnly = scanOnlyMacs.contains(mac);

            // 图标按主机名猜设备类型,颜色:黑名单红 / 本机绿 / 其余蓝
            h.icon.setImageResource(iconFor(d.hostname));
            h.icon.setColorFilter(requireContext().getColor(
                    blocked ? R.color.ios_red : d.isLocal ? R.color.ios_green : R.color.ios_blue));

            // 标题:自定义名 > 主机名 > IP;标题已经是 IP 时不再重复一行
            h.title.setText(displayName(d));
            String secondary = d.getSecondaryTitle();
            boolean customNamed = DeviceNameStore.get(requireContext(), d.macAddress) != null;
            if (secondary == null && customNamed) secondary = d.ipAddress;
            h.ip.setVisibility(secondary != null ? View.VISIBLE : View.GONE);
            h.ip.setText(secondary);
            h.mac.setText(d.macAddress);

            // 扫描发现的设备显示来源;静态 IP / 无租约的干脆不占这一行,
            // 不然半屏都是「租约 0分钟」
            if (scanOnly) {
                h.lease.setVisibility(View.VISIBLE);
                h.lease.setText(R.string.lease_from_scan);
            } else if (d.leaseRemainingSec >= 60) {
                h.lease.setVisibility(View.VISIBLE);
                h.lease.setText(leaseLine(d.leaseRemainingSec));
            } else {
                h.lease.setVisibility(View.GONE);
            }

            h.localBadge.setVisibility(d.isLocal ? View.VISIBLE : View.GONE);
            h.blockedBadge.setVisibility(blocked ? View.VISIBLE : View.GONE);

            // 黑名单徽章与流量互斥,免得右侧挤成一团(iOS 同款规则)
            long total = d.rxBytes + d.txBytes;
            boolean showTraffic = !blocked && total > 0;
            h.traffic.setVisibility(showTraffic ? View.VISIBLE : View.GONE);
            if (showTraffic) h.traffic.setText(Formatters.bytes(total));

            // 有线设备整行隐藏,不显示空信号格
            boolean wifi = d.ssid != null && !d.ssid.isEmpty();
            h.wifiRow.setVisibility(wifi ? View.VISIBLE : View.GONE);
            if (wifi) {
                h.ssid.setText(d.ssid);
                int bars = d.signalBars();
                h.signalBars.setVisibility(bars >= 0 ? View.VISIBLE : View.GONE);
                h.signalBars.setBars(bars);
            }

            h.itemView.setOnClickListener(v -> showDetail(d));
        }

        @Override
        public int getItemCount() {
            return devices.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final android.widget.ImageView icon;
            final TextView title, ip, mac, lease, traffic, ssid, localBadge, blockedBadge;
            final View wifiRow;
            final SignalBarsView signalBars;

            Holder(@NonNull View v) {
                super(v);
                icon = v.findViewById(R.id.device_icon);
                title = v.findViewById(R.id.device_title);
                ip = v.findViewById(R.id.device_ip);
                mac = v.findViewById(R.id.device_mac);
                lease = v.findViewById(R.id.device_lease);
                traffic = v.findViewById(R.id.device_traffic);
                ssid = v.findViewById(R.id.device_ssid);
                localBadge = v.findViewById(R.id.device_local_badge);
                blockedBadge = v.findViewById(R.id.device_blocked_badge);
                wifiRow = v.findViewById(R.id.wifi_row);
                signalBars = v.findViewById(R.id.signal_bars);
            }
        }
    }
}
