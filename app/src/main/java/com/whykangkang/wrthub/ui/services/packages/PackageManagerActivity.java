package com.whykangkang.wrthub.ui.services.packages;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.whykangkang.wrthub.R;
import com.whykangkang.wrthub.api.ApiCallback;
import com.whykangkang.wrthub.api.ApiError;
import com.whykangkang.wrthub.api.PackageApi;
import com.whykangkang.wrthub.model.PackageInfo;
import com.whykangkang.wrthub.model.PackageProtection;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 软件包管理,对应 iOS PackageManagerViewController。
 *
 *  - 「已安装」/「可安装」两个列表,搜索过滤后点击条目即可安装
 *  - 卸载需两步确认;系统核心组件一律拦下,引导到网页端(见 PackageProtection)
 *  - 被依赖挡下时列出依赖方,确认后级联卸载
 *  - 支持外部带入预填关键词(插件未安装提示里的「去安装」)
 */
public class PackageManagerActivity extends AppCompatActivity {

    /** 由服务页「去安装」带入的预填关键词:进页面后切到「可安装」并按该词过滤 */
    public static final String EXTRA_PREFILL = "prefill_search";

    private static final int TAB_INSTALLED = 0;
    private static final int TAB_AVAILABLE = 1;

    private int currentTab = TAB_INSTALLED;

    private final List<PackageInfo> installed = new ArrayList<>();
    private final List<PackageInfo> available = new ArrayList<>();
    private final Set<String> installedNames = new HashSet<>();
    private final List<PackageInfo> filtered = new ArrayList<>();
    private boolean availableLoaded;
    private String keyword = "";

    private final Handler main = new Handler(Looper.getMainLooper());
    private Runnable searchDebounce;

    private MaterialButtonToggleGroup tabs;
    private EditText searchInput;
    private RecyclerView listView;
    private TextView statusLabel;
    private TextView sectionHeader;
    private TextView sectionFooter;
    private ProgressBar spinner;
    private SwipeRefreshLayout refresh;
    private Adapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_packages);

        tabs = findViewById(R.id.tabs);
        searchInput = findViewById(R.id.search_input);
        listView = findViewById(R.id.package_list);
        statusLabel = findViewById(R.id.status_label);
        sectionHeader = findViewById(R.id.section_header);
        sectionFooter = findViewById(R.id.section_footer);
        spinner = findViewById(R.id.spinner);
        refresh = findViewById(R.id.refresh);

        adapter = new Adapter();
        listView.setLayoutManager(new LinearLayoutManager(this));
        listView.setAdapter(adapter);
        refresh.setOnRefreshListener(this::pullToRefresh);
        findViewById(R.id.btn_update_sources).setOnClickListener(v -> updateSourcesTapped());

        tabs.check(R.id.tab_installed);
        tabs.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            tabChanged(checkedId == R.id.tab_available ? TAB_AVAILABLE : TAB_INSTALLED);
        });

        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                keyword = s.toString();
                // 防抖:可安装列表上万条,输入过程中不必每次都全量过滤
                if (searchDebounce != null) main.removeCallbacks(searchDebounce);
                searchDebounce = PackageManagerActivity.this::applyFilter;
                main.postDelayed(searchDebounce, 250);
            }
        });

        loadInstalled();

        String prefill = getIntent().getStringExtra(EXTRA_PREFILL);
        if (prefill != null && !prefill.isEmpty()) {
            keyword = prefill;
            searchInput.setText(prefill);
            currentTab = TAB_AVAILABLE;
            tabs.check(R.id.tab_available);
            // 先拿现有索引搜 —— 绝大多数情况包就在里面,几秒出结果。
            // 不要一上来就 opkg update:它要联网,还会和 list-installed 抢 opkg 全局锁。
            loadAvailable(false, () -> handlePrefillResult(false));
        }
    }

    // =====================================================================
    // 预填搜索
    // =====================================================================

    /**
     * 自动搜索的结果处理:
     *   有匹配 → 提示条报数量
     *   没匹配 → 说明索引可能过期,自动更新软件源后重试一次
     *   更新后仍没有 → 弹窗讲清楚原因,给重试入口
     */
    private void handlePrefillResult(boolean retriedAfterUpdate) {
        if (keyword.isEmpty()) return;
        if (!filtered.isEmpty()) {
            toast(getString(R.string.pkg_prefill_found, keyword, filtered.size()));
            return;
        }
        if (!retriedAfterUpdate) {
            setStatus(getString(R.string.pkg_prefill_retry, keyword), true);
            updateSourcesThenRetry();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.pkg_not_found_title)
                .setMessage(getString(R.string.pkg_not_found_msg, keyword))
                .setPositiveButton(R.string.pkg_retry, (d, w) -> updateSourcesThenRetry())
                .setNegativeButton(R.string.ok, null)
                .show();
    }

    /** 更新软件源 → 丢掉缓存重拉 → 再判一次结果 */
    private void updateSourcesThenRetry() {
        PackageApi.updatePackageLists(new ApiCallback<String>() {
            @Override
            public void onSuccess(String result) {
                retry();
            }

            @Override
            public void onFailure(ApiError error) {
                // 更新失败不阻断:本地旧索引里也可能有,继续往下拉
                retry();
            }

            private void retry() {
                PackageApi.clearCache();
                available.clear();
                availableLoaded = false;
                loadAvailable(true, () -> handlePrefillResult(true));
            }
        });
    }

    // =====================================================================
    // 数据加载
    // =====================================================================

    private void setStatus(String text, boolean busy) {
        statusLabel.setText(text);
        statusLabel.setVisibility(text == null ? View.GONE : View.VISIBLE);
        spinner.setVisibility(busy ? View.VISIBLE : View.GONE);
    }

    private void loadInstalled() {
        if (installed.isEmpty()) {
            setStatus(getString(R.string.pkg_loading), true);
        }
        PackageApi.getInstalledPackages(new ApiCallback<List<PackageInfo>>() {
            @Override
            public void onSuccess(List<PackageInfo> list) {
                refresh.setRefreshing(false);
                installed.clear();
                installed.addAll(list);
                installedNames.clear();
                for (PackageInfo p : list) installedNames.add(p.name);
                setStatus(list.isEmpty() ? getString(R.string.pkg_installed_empty) : null, false);
                applyFilter();
            }

            @Override
            public void onFailure(ApiError error) {
                refresh.setRefreshing(false);
                setStatus(friendlyMessage(error), false);
                applyFilter();
            }
        });
    }

    private void loadAvailable(boolean force, Runnable then) {
        if (force) PackageApi.clearCache();
        if (available.isEmpty() || force) {
            setStatus(getString(R.string.pkg_loading_available), true);
        }
        PackageApi.getAvailablePackages(force, new ApiCallback<List<PackageInfo>>() {
            @Override
            public void onSuccess(List<PackageInfo> list) {
                refresh.setRefreshing(false);
                available.clear();
                available.addAll(list);
                availableLoaded = true;
                setStatus(list.isEmpty() ? getString(R.string.pkg_available_empty) : null, false);
                applyFilter();
                if (then != null) then.run();
            }

            @Override
            public void onFailure(ApiError error) {
                refresh.setRefreshing(false);
                setStatus(friendlyMessage(error), false);
                applyFilter();
                if (then != null) then.run();
            }
        });
    }

    private void pullToRefresh() {
        if (currentTab == TAB_INSTALLED) {
            loadInstalled();
        } else {
            loadAvailable(true, null);
        }
    }

    private void tabChanged(int tab) {
        currentTab = tab;
        setStatus(null, false);
        applyFilter();
        // 「可安装」体积大,首次切换时才拉取
        if (currentTab == TAB_AVAILABLE && !availableLoaded) {
            loadAvailable(false, null);
        }
    }

    // =====================================================================
    // 过滤
    // =====================================================================

    private void applyFilter() {
        List<PackageInfo> source = currentTab == TAB_INSTALLED ? installed : available;
        String key = keyword.trim().toLowerCase(Locale.ROOT);
        filtered.clear();
        if (key.isEmpty()) {
            filtered.addAll(source);
        } else {
            for (PackageInfo p : source) {
                if (p.searchKey.contains(key)) filtered.add(p);
            }
        }
        sectionHeader.setText(getString(currentTab == TAB_INSTALLED
                ? R.string.pkg_header_installed : R.string.pkg_header_available, filtered.size()));
        sectionFooter.setText(currentTab == TAB_INSTALLED
                ? R.string.pkg_footer_installed : R.string.pkg_footer_available);
        adapter.notifyDataSetChanged();
    }

    // =====================================================================
    // 操作
    // =====================================================================

    private void updateSourcesTapped() {
        setStatus(getString(R.string.pkg_updating_sources), true);
        PackageApi.updatePackageLists(new ApiCallback<String>() {
            @Override
            public void onSuccess(String result) {
                setStatus(null, false);
                toast(getString(R.string.pkg_sources_updated));
                // 索引已变,可用列表需要重新拉取
                availableLoaded = false;
                available.clear();
                PackageApi.clearCache();
                if (currentTab == TAB_AVAILABLE) loadAvailable(true, null);
            }

            @Override
            public void onFailure(ApiError error) {
                setStatus(null, false);
                alert(friendlyMessage(error));
            }
        });
    }

    private void confirmInstall(PackageInfo pkg) {
        String detail = detailText(pkg);
        new MaterialAlertDialogBuilder(this)
                .setTitle(pkg.name)
                .setMessage((detail.isEmpty() ? "" : detail + "\n\n")
                        + getString(R.string.pkg_confirm_install))
                .setPositiveButton(R.string.pkg_install, (d, w) -> performInstall(pkg.name))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void performInstall(String name) {
        setStatus(getString(R.string.pkg_installing, name), true);
        PackageApi.installPackage(name, new ApiCallback<String>() {
            @Override
            public void onSuccess(String result) {
                setStatus(null, false);
                toast(getString(R.string.pkg_installed_ok, name));
                loadInstalled();
            }

            @Override
            public void onFailure(ApiError error) {
                setStatus(null, false);
                alert(friendlyMessage(error));
            }
        });
    }

    /**
     * 弹窗正文:版本/大小/分类 + 描述。
     * 注:已安装列表读的是 /usr/lib/opkg/status,只有版本,没有描述/分类/体积,这些行会自动省略。
     */
    private String detailText(PackageInfo pkg) {
        StringBuilder sb = new StringBuilder();
        if (pkg.hasVersion()) {
            sb.append(getString(R.string.pkg_version, pkg.version));
        }
        String size = pkg.sizeText();
        if (size != null) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(getString(R.string.pkg_size, size));
        }
        if (pkg.section != null && !pkg.section.isEmpty()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(getString(R.string.pkg_section, pkg.section));
        }
        if (pkg.description != null && !pkg.description.isEmpty()) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(pkg.description);
        }
        return sb.toString();
    }

    // =====================================================================
    // 卸载
    // =====================================================================

    /** 点已安装的条目:受保护的讲清楚为什么不能卸,其余给卸载入口 */
    private void showInstalledOptions(PackageInfo pkg) {
        if (PackageProtection.isProtected(pkg.name)) {
            showProtectedNotice(pkg);
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(pkg.name)
                .setMessage(detailText(pkg))
                .setPositiveButton(R.string.pkg_uninstall, (d, w) -> confirmRemoveStep1(pkg))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** 系统组件:不从手机端卸载。理由要说透,并给出正确的去处。 */
    private void showProtectedNotice(PackageInfo pkg) {
        String detail = detailText(pkg);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.pkg_protected_title)
                .setMessage((detail.isEmpty() ? "" : detail + "\n\n")
                        + getString(R.string.pkg_protected_msg, pkg.name))
                .setPositiveButton(R.string.pkg_got_it, null)
                .show();
    }

    private void confirmRemoveStep1(PackageInfo pkg) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.pkg_uninstall_title)
                .setMessage(getString(R.string.pkg_confirm_uninstall, pkg.name))
                .setPositiveButton(R.string.pkg_uninstall, (d, w) -> confirmRemoveStep2(pkg))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** 二次确认:把后果讲明白再动手 */
    private void confirmRemoveStep2(PackageInfo pkg) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.pkg_confirm_again)
                .setMessage(getString(R.string.pkg_uninstall_warning, pkg.name))
                .setPositiveButton(R.string.pkg_confirm_uninstall_action,
                        (d, w) -> performRemove(pkg.name))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void performRemove(String name) {
        setStatus(getString(R.string.pkg_uninstalling, name), true);
        PackageApi.removePackage(name, new ApiCallback<String>() {
            @Override
            public void onSuccess(String result) {
                verifyRemoved(name, result);
            }

            @Override
            public void onFailure(ApiError error) {
                setStatus(null, false);
                // 被别的包依赖而拒绝:这不是故障,是 opkg 在保护你。
                // 把依赖方列出来,让用户决定要不要一并卸载。
                String text = error.getMessage() == null ? "" : error.getMessage();
                if (PackageApi.isDependencyBlocked(text)) {
                    offerCascadeRemove(name, PackageApi.parseBlockingDependents(text));
                } else {
                    alert(friendlyMessage(error));
                }
            }
        });
    }

    /**
     * 别急着报成功 —— 重新拉一次已安装列表,包真的不在了才算卸掉。
     * opkg 有不少「退出码 0 但其实没删掉」的情况,只信返回值会骗人。
     */
    private void verifyRemoved(String name, String output) {
        PackageApi.getInstalledPackages(new ApiCallback<List<PackageInfo>>() {
            @Override
            public void onSuccess(List<PackageInfo> list) {
                setStatus(null, false);
                installed.clear();
                installed.addAll(list);
                installedNames.clear();
                for (PackageInfo p : list) installedNames.add(p.name);
                applyFilter();

                boolean stillThere = installedNames.contains(name);
                if (stillThere) {
                    String detail = output == null ? "" : output.trim();
                    alert(getString(R.string.pkg_uninstall_not_applied, name)
                            + (detail.isEmpty() ? "" : "\n\n" + detail));
                } else {
                    toast(getString(R.string.pkg_uninstalled_ok, name));
                }
            }

            @Override
            public void onFailure(ApiError error) {
                // 列表没拉到就没法核实,照实说
                setStatus(null, false);
                alert(getString(R.string.pkg_uninstall_unverified, name));
                loadInstalled();
            }
        });
    }

    /**
     * 卸载被依赖挡下时的处理:列出会被一起删掉的包,让用户确认后级联卸载。
     * 若依赖方里混有系统组件,一律不做级联 —— 级联会把它们一起删掉。
     */
    private void offerCascadeRemove(String name, List<String> dependents) {
        if (dependents.isEmpty()) {
            alert(getString(R.string.pkg_dependency_blocked, name));
            return;
        }
        List<String> blocked = new ArrayList<>();
        for (String d : dependents) {
            if (PackageProtection.isProtected(d)) blocked.add(d);
        }
        if (!blocked.isEmpty()) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.pkg_cannot_uninstall)
                    .setMessage(getString(R.string.pkg_blocked_by_system,
                            name, join(blocked)))
                    .setPositiveButton(R.string.pkg_got_it, null)
                    .show();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.pkg_cascade_title)
                .setMessage(getString(R.string.pkg_cascade_msg, name, join(dependents),
                        dependents.size() + 1))
                .setPositiveButton(R.string.pkg_cascade_action,
                        (d, w) -> performCascadeRemove(name))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void performCascadeRemove(String name) {
        setStatus(getString(R.string.pkg_uninstalling_cascade, name), true);
        PackageApi.removePackageWithDependents(name, new ApiCallback<String>() {
            @Override
            public void onSuccess(String result) {
                verifyRemoved(name, result);
            }

            @Override
            public void onFailure(ApiError error) {
                setStatus(null, false);
                alert(friendlyMessage(error));
            }
        });
    }

    private static String join(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (String s : items) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(s);
        }
        return sb.toString();
    }

    /** 把底层错误转成用户能据以行动的提示(对应 iOS friendlyMessage) */
    private String friendlyMessage(ApiError error) {
        String desc = error.getMessage() == null ? "" : error.getMessage();
        if (desc.contains("NO_BACKEND")) {
            return getString(R.string.pkg_err_no_backend);
        }
        String lower = desc.toLowerCase(Locale.ROOT);
        if (desc.contains("权限被拒绝") || lower.contains("permission") || desc.contains("403")
                || error.getType() == ApiError.Type.PERMISSION_DENIED) {
            return getString(R.string.pkg_err_forbidden);
        }
        if (desc.contains("depends on") || desc.contains("depended upon by")
                || desc.contains("Cannot remove") || desc.contains("被依赖")) {
            return getString(R.string.pkg_err_dependency);
        }
        return getString(R.string.msg_action_failed, desc);
    }

    private void alert(String message) {
        // 异步回调回来时页面可能已经关了,再弹窗会 BadTokenException 崩掉
        if (isFinishing() || isDestroyed()) return;
        new MaterialAlertDialogBuilder(this)
                .setMessage(message)
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    /** 底部提示条,停留 4.5 秒(与 iOS showToast 一致 —— 提示里有"点击即可安装") */
    private void toast(String message) {
        // 异步回调回来时页面可能已经关了,再弹窗会 BadTokenException 崩掉
        if (isFinishing() || isDestroyed()) return;
        TextView view = findViewById(R.id.toast_label);
        view.setText(message);
        view.setVisibility(View.VISIBLE);
        view.animate().alpha(1f).setDuration(250).start();
        view.removeCallbacks(hideToast);
        view.postDelayed(hideToast, 4500);
    }

    private final Runnable hideToast = () -> {
        View view = findViewById(R.id.toast_label);
        view.animate().alpha(0f).setDuration(300)
                .withEndAction(() -> view.setVisibility(View.GONE)).start();
    };

    // =====================================================================
    // 列表
    // =====================================================================

    private class Adapter extends RecyclerView.Adapter<Adapter.Holder> {
        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_package, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            PackageInfo pkg = filtered.get(position);
            h.name.setText(pkg.name);
            h.version.setText(pkg.hasVersion() ? pkg.version : "");
            if (currentTab == TAB_AVAILABLE) {
                // 已安装的包在可安装列表里标出来,避免重复安装
                boolean isInstalled = installedNames.contains(pkg.name);
                h.mark.setVisibility(isInstalled ? View.VISIBLE : View.GONE);
                h.mark.setImageResource(R.drawable.ic_check);
                h.name.setTextColor(getColor(isInstalled
                        ? R.color.label_secondary : R.color.label_primary));
                h.itemView.setOnClickListener(isInstalled ? null : v -> confirmInstall(pkg));
            } else {
                // 已安装:系统组件挂锁标出来,让人一眼知道为什么卸不掉
                boolean locked = PackageProtection.isProtected(pkg.name);
                h.mark.setVisibility(locked ? View.VISIBLE : View.GONE);
                h.mark.setImageResource(R.drawable.ic_lock);
                h.name.setTextColor(getColor(locked
                        ? R.color.label_secondary : R.color.label_primary));
                h.itemView.setOnClickListener(v -> showInstalledOptions(pkg));
            }
        }

        @Override
        public int getItemCount() {
            return filtered.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final TextView name, version;
            final ImageView mark;

            Holder(@NonNull View v) {
                super(v);
                name = v.findViewById(R.id.pkg_name);
                version = v.findViewById(R.id.pkg_version);
                mark = v.findViewById(R.id.pkg_mark);
            }
        }
    }
}
