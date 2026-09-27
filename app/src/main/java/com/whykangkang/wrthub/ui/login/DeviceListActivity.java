package com.whykangkang.wrthub.ui.login;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.whykangkang.wrthub.R;
import com.whykangkang.wrthub.api.ApiCallback;
import com.whykangkang.wrthub.api.ApiError;
import com.whykangkang.wrthub.api.OpenWrtApi;
import com.whykangkang.wrthub.manager.RouterDeviceManager;
import com.whykangkang.wrthub.manager.SettingsManager;
import com.whykangkang.wrthub.model.RouterDevice;
import com.whykangkang.wrthub.ui.MainActivity;
import com.whykangkang.wrthub.ui.common.UpdatePrompt;

import java.util.ArrayList;
import java.util.List;

/**
 * 设备列表,对应 iOS DeviceListViewController。
 *
 * 也是启动入口:已登录时直接放行到 {@link MainActivity},不停在这一页
 * (对应 iOS 根 ViewController 里按 isLoggedIn 决定展示标签栏还是设备列表)。
 *
 * 设备卡片(图标 / 名称 / 地址 / 用户名 / 「当前」徽章),点击连接,
 * 长按弹出「编辑 / 删除」;右上角 + 添加、ⓘ 关于;无设备时显示品牌空状态页。
 */
public class DeviceListActivity extends AppCompatActivity {

    /**
     * 明确要求停在设备列表(退出登录、首页连接失败后点「切换设备」),
     * 不走「已登录就直接进首页」的放行逻辑,否则会原地弹回去。
     */
    public static final String EXTRA_PICK_DEVICE = "pick_device";

    private RouterDeviceManager manager;
    private DeviceAdapter adapter;
    private View emptyView;
    private View listContainer;
    private RecyclerView listView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        manager = RouterDeviceManager.getInstance(this);
        if (savedInstanceState == null && resumeLastSession()) return;

        setContentView(R.layout.activity_device_list);

        listView = findViewById(R.id.device_list);
        listContainer = findViewById(R.id.list_container);
        emptyView = findViewById(R.id.empty_view);

        adapter = new DeviceAdapter();
        listView.setLayoutManager(new LinearLayoutManager(this));
        listView.setAdapter(adapter);

        View.OnClickListener add = v ->
                startActivity(new Intent(this, AddEditDeviceActivity.class));
        findViewById(R.id.btn_add).setOnClickListener(add);
        findViewById(R.id.btn_empty_add).setOnClickListener(add);
        View.OnClickListener about = v -> showAbout();
        findViewById(R.id.btn_info).setOnClickListener(about);
        findViewById(R.id.btn_empty_info).setOnClickListener(about);

        // 未登录时停在这一页,更新检测也要走到(已登录的分支由 MainActivity 负责)
        UpdatePrompt.autoCheck(this);
    }

    /**
     * 冷启动时若上次是登录状态且设备还在,直接进首页。
     * 连接配置已由 {@link com.whykangkang.wrthub.WrtHubApp} 恢复,
     * 首个请求会自动登录,登录失败由首页的错误卡片处理。
     */
    private boolean resumeLastSession() {
        if (getIntent().getBooleanExtra(EXTRA_PICK_DEVICE, false)) return false;
        SettingsManager settings = SettingsManager.getInstance(this);
        if (!settings.isLoggedIn()) return false;
        if (manager.getCurrentDevice() == null || !OpenWrtApi.getInstance().isConfigured()) {
            settings.setLoggedIn(false);
            return false;
        }
        startActivity(new Intent(this, MainActivity.class));
        // 不留返回栈:从首页按返回应当退出 App,而不是回到设备列表
        finish();
        overridePendingTransition(0, 0);
        return true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        List<RouterDevice> devices = manager.getDevices();
        adapter.submit(devices);
        boolean empty = devices.isEmpty();
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        listContainer.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    // =====================================================================
    // 操作
    // =====================================================================

    private void showAbout() {
        String version;
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            version = "1.0";
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.about_title)
                .setMessage(getString(R.string.about_message, version))
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    private void connect(RouterDevice device) {
        AlertDialog progress = new MaterialAlertDialogBuilder(this)
                .setMessage(R.string.msg_connecting)
                .setCancelable(false)
                .show();

        OpenWrtApi api = OpenWrtApi.getInstance();
        api.configure(device.getHost(), device.getPort(), device.isUseHttps(),
                device.getUsername(), device.getPassword());
        // 换设备必须丢掉上一台的插件/软件包检测缓存
        com.whykangkang.wrthub.api.PluginChecker.clearCache();
        com.whykangkang.wrthub.api.PackageApi.clearAllCaches();
        api.login(new ApiCallback<Void>() {
            @Override
            public void onSuccess(Void result) {
                progress.dismiss();
                manager.setLastDeviceId(device.getId());
                SettingsManager settings = SettingsManager.getInstance(DeviceListActivity.this);
                settings.setLoginTimestamp(System.currentTimeMillis());
                settings.setLoggedIn(true);
                Intent intent = new Intent(DeviceListActivity.this, MainActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
            }

            @Override
            public void onFailure(ApiError error) {
                progress.dismiss();
                // 回调可能在页面关闭后才到,不加这道判断弹窗会 BadTokenException
                if (DeviceListActivity.this.isFinishing() || DeviceListActivity.this.isDestroyed()) return;
                new MaterialAlertDialogBuilder(DeviceListActivity.this)
                        .setMessage(getString(R.string.msg_login_failed, error.getMessage()))
                        .setPositiveButton(R.string.ok, null)
                        .show();
            }
        });
    }

    /** 长按设备卡片弹出的操作表 */
    private void showActions(RouterDevice device) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(device.getDisplayName())
                .setItems(new CharSequence[]{
                        getString(R.string.action_edit),
                        getString(R.string.action_delete)
                }, (d, which) -> {
                    if (which == 0) {
                        edit(device);
                    } else {
                        confirmDelete(device);
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void edit(RouterDevice device) {
        Intent intent = new Intent(this, AddEditDeviceActivity.class);
        intent.putExtra(AddEditDeviceActivity.EXTRA_DEVICE_ID, device.getId());
        startActivity(intent);
    }

    private void confirmDelete(RouterDevice device) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.action_delete)
                .setMessage(getString(R.string.confirm_delete_device, device.getDisplayName()))
                .setPositiveButton(R.string.action_delete, (d, w) -> {
                    manager.removeDevice(device.getId());
                    reload();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    // =====================================================================
    // 列表
    // =====================================================================

    private class DeviceAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private static final int TYPE_DEVICE = 0;
        /** 最后一项是操作说明,对应 iOS insetGrouped 的 section footer */
        private static final int TYPE_HINT = 1;

        private final List<RouterDevice> items = new ArrayList<>();

        void submit(List<RouterDevice> devices) {
            items.clear();
            items.addAll(devices);
            notifyDataSetChanged();
        }

        @Override
        public int getItemViewType(int position) {
            return position == items.size() ? TYPE_HINT : TYPE_DEVICE;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            if (viewType == TYPE_HINT) {
                return new HintHolder(
                        inflater.inflate(R.layout.item_device_list_hint, parent, false));
            }
            return new Holder(inflater.inflate(R.layout.item_device, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (!(holder instanceof Holder)) return;   // 说明行没有数据要绑
            Holder h = (Holder) holder;
            RouterDevice device = items.get(position);
            h.name.setText(device.getDisplayName());
            h.address.setText(device.getDisplayAddress());
            h.username.setText(getString(R.string.device_username_line, device.getUsername()));
            boolean isCurrent = device.getId().equals(manager.getLastDeviceId());
            h.currentBadge.setVisibility(isCurrent ? View.VISIBLE : View.GONE);

            h.card.setOnClickListener(v -> connect(device));
            h.card.setOnLongClickListener(v -> {
                showActions(device);
                return true;
            });
        }

        @Override
        public int getItemCount() {
            // 空列表时不显示说明(那会儿走的是品牌空状态页)
            return items.isEmpty() ? 0 : items.size() + 1;
        }

        class HintHolder extends RecyclerView.ViewHolder {
            HintHolder(@NonNull View v) {
                super(v);
            }
        }

        class Holder extends RecyclerView.ViewHolder {
            final MaterialCardView card;
            final TextView name, address, username, currentBadge;

            Holder(@NonNull View v) {
                super(v);
                card = (MaterialCardView) v;
                name = v.findViewById(R.id.device_name);
                address = v.findViewById(R.id.device_address);
                username = v.findViewById(R.id.device_username);
                currentBadge = v.findViewById(R.id.device_current_badge);
            }
        }
    }
}
