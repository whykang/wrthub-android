package com.whykangkang.wrthub.ui.services.filebrowser;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.whykangkang.wrthub.R;
import com.whykangkang.wrthub.api.FileBrowserApi;
import com.whykangkang.wrthub.manager.FileBrowserAccount;
import com.whykangkang.wrthub.util.Formatters;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * FileBrowser 文件管理:浏览 / 新建文件 / 新建文件夹 / 重命名 / 删除 / 上传 / 下载 / 分享。
 *
 * 走 FileBrowser 自己的 HTTP API(见 {@link FileBrowserApi}),不是 LuCI 通道。
 * 上传下载用 SAF(系统文件选择器),不申请存储权限、也不往应用私有目录堆文件。
 */
public class FileBrowserBrowseActivity extends AppCompatActivity {

    public static final String EXTRA_HOST = "host";
    public static final String EXTRA_PORT = "port";
    public static final String EXTRA_ROOT = "root";

    private String host;
    private String port;
    private String path = "/";

    private FileBrowserApi api;
    private EntryAdapter adapter;
    private SwipeRefreshLayout swipeRefresh;
    private TextView pathLabel;
    private TextView statusLabel;
    private View usageBox;
    private TextView usageText;
    private ProgressBar usageBar;

    private final List<FileBrowserApi.Entry> entries = new ArrayList<>();

    /** 搜索模式:非 null 表示当前列表是某次搜索的结果,不是目录内容 */
    private String searchQuery;
    /** 发起搜索时所在的目录,退出搜索要回到它 */
    private String searchRoot;

    /** 等待写入的下载目标;SAF 回调时用 */
    private FileBrowserApi.Entry pendingDownload;
    private ActivityResultLauncher<String> saveLauncher;
    private ActivityResultLauncher<String[]> pickLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_fb_browse);

        host = getIntent().getStringExtra(EXTRA_HOST);
        port = getIntent().getStringExtra(EXTRA_PORT);
        String root = getIntent().getStringExtra(EXTRA_ROOT);
        // FileBrowser 的路径是相对它自己的根目录的,始终从 / 开始
        path = "/";

        pathLabel = findViewById(R.id.fb_path);
        statusLabel = findViewById(R.id.fb_status);
        usageBox = findViewById(R.id.fb_usage_box);
        usageText = findViewById(R.id.fb_usage_text);
        usageBar = findViewById(R.id.fb_usage_bar);
        swipeRefresh = findViewById(R.id.swipe_refresh);
        RecyclerView list = findViewById(R.id.fb_list);
        adapter = new EntryAdapter();
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        swipeRefresh.setOnRefreshListener(this::reload);
        findViewById(R.id.btn_up).setOnClickListener(v -> goUp());
        findViewById(R.id.btn_new).setOnClickListener(v -> showCreateMenu());
        findViewById(R.id.btn_search).setOnClickListener(v -> promptSearch());
        findViewById(R.id.btn_upload).setOnClickListener(v -> pickLauncher.launch(new String[]{"*/*"}));

        saveLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("*/*"), this::onSaveLocationPicked);
        pickLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), this::onUploadFilePicked);

        ensureCredentials();
    }

    // =====================================================================
    // 凭据
    // =====================================================================

    /**
     * FileBrowser 有自己的用户表,和路由器后台账号无关。
     *
     * 没存过凭据时先拿默认账号(admin/admin)静默试一次 —— 刚装好的实例基本都是它,
     * 能省掉一次输入。试不通再让用户手动登录,并说明默认密码可能已被改过。
     */
    private void ensureCredentials() {
        FileBrowserAccount.Credentials c = FileBrowserAccount.load(this, host);
        if (!c.isEmpty()) {
            api = FileBrowserApi.from(this, host, port);
            reload();
            return;
        }
        tryDefaultLogin();
    }

    private void tryDefaultLogin() {
        AlertDialog progress = progress(getString(R.string.fb_signing_in));
        FileBrowserApi probe = new FileBrowserApi("http://" + host + ":" + port,
                FileBrowserApi.DEFAULT_USER, FileBrowserApi.DEFAULT_PASSWORD);
        probe.tryLogin(new FileBrowserApi.Callback<Void>() {
            @Override
            public void onSuccess(Void unused) {
                if (isFinishing() || isDestroyed()) return;
                progress.dismiss();
                // 通了才存,免得把错的凭据写进去
                FileBrowserAccount.save(FileBrowserBrowseActivity.this, host,
                        FileBrowserApi.DEFAULT_USER, FileBrowserApi.DEFAULT_PASSWORD);
                api = probe;
                reload();
            }

            @Override
            public void onFailure(String message) {
                if (isFinishing() || isDestroyed()) return;
                progress.dismiss();
                if (FileBrowserApi.ERR_AUTH.equals(message)) {
                    // 默认密码被改过,让用户自己填
                    promptCredentials(getString(R.string.fb_default_login_failed));
                } else {
                    // 连不上就不是凭据问题,别误导成密码错
                    showStatus(getString(R.string.fb_load_failed, message));
                }
            }
        });
    }

    private void promptCredentials(String note) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        wrap.setPadding(pad, dp(8), pad, 0);

        EditText user = new EditText(this);
        user.setHint(R.string.fb_username);
        user.setSingleLine(true);
        EditText pass = new EditText(this);
        pass.setHint(R.string.fb_password);
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        FileBrowserAccount.Credentials existing = FileBrowserAccount.load(this, host);
        user.setText(existing.username);
        wrap.addView(user);
        wrap.addView(pass);

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.fb_login_title)
                .setMessage(note)
                .setView(wrap)
                .setPositiveButton(R.string.ok, (d, w) -> {
                    FileBrowserAccount.save(this, host,
                            user.getText().toString().trim(), pass.getText().toString());
                    api = FileBrowserApi.from(this, host, port);
                    reload();
                })
                .setNegativeButton(R.string.action_cancel, (d, w) -> finish())
                .setOnCancelListener(d -> finish())
                .show();
    }

    // =====================================================================
    // 列表
    // =====================================================================

    private void reload() {
        if (api == null) return;
        searchQuery = null;
        searchRoot = null;
        swipeRefresh.setRefreshing(true);
        pathLabel.setText(path);
        loadUsage(path);
        api.list(path, new FileBrowserApi.Callback<List<FileBrowserApi.Entry>>() {
            @Override
            public void onSuccess(List<FileBrowserApi.Entry> result) {
                if (isFinishing() || isDestroyed()) return;
                swipeRefresh.setRefreshing(false);
                entries.clear();
                entries.addAll(result);
                adapter.notifyDataSetChanged();
                showStatus(result.isEmpty() ? getString(R.string.fb_empty_dir) : null);
            }

            @Override
            public void onFailure(String message) {
                if (isFinishing() || isDestroyed()) return;
                swipeRefresh.setRefreshing(false);
                entries.clear();
                adapter.notifyDataSetChanged();
                if (FileBrowserApi.ERR_AUTH.equals(message)) {
                    // 凭据不对,重新问一次
                    FileBrowserAccount.clear(FileBrowserBrowseActivity.this, host);
                    promptCredentials(getString(R.string.fb_login_retry));
                    return;
                }
                showStatus(getString(R.string.fb_load_failed, message));
            }
        });
    }

    /** 在当前目录下搜索(服务端递归找,不是本地过滤) */
    private void promptSearch() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(R.string.fb_search_hint);
        input.setText(searchQuery == null ? "" : searchQuery);
        input.setSelection(input.getText().length());
        int pad = dp(20);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(pad, dp(8), pad, 0);
        wrap.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.fb_search)
                .setMessage(getString(R.string.fb_search_scope, path))
                .setView(wrap)
                .setPositiveButton(R.string.fb_search, (d, w) -> {
                    String q = input.getText().toString().trim();
                    if (q.isEmpty()) {
                        alert(getString(R.string.fb_search_empty));
                        return;
                    }
                    runSearch(path, q);
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void runSearch(String root, String query) {
        searchRoot = root;
        searchQuery = query;
        swipeRefresh.setRefreshing(true);
        pathLabel.setText(getString(R.string.fb_search_result_path, query, root));
        api.search(root, query, new FileBrowserApi.Callback<List<FileBrowserApi.Entry>>() {
            @Override
            public void onSuccess(List<FileBrowserApi.Entry> result) {
                if (isFinishing() || isDestroyed()) return;
                swipeRefresh.setRefreshing(false);
                entries.clear();
                entries.addAll(result);
                adapter.notifyDataSetChanged();
                showStatus(result.isEmpty() ? getString(R.string.fb_search_none, query) : null);
            }

            @Override
            public void onFailure(String message) {
                if (isFinishing() || isDestroyed()) return;
                swipeRefresh.setRefreshing(false);
                showStatus(getString(R.string.fb_load_failed, message));
            }
        });
    }

    /** 退出搜索,回到发起搜索时的目录 */
    private void exitSearch() {
        String back = searchRoot == null ? path : searchRoot;
        searchQuery = null;
        searchRoot = null;
        path = back;
        reload();
    }

    /**
     * 容量按**当前路径**问 —— 不同挂载点容量不一样,
     * 只在根目录取一次的话进到 U 盘里显示的还是根分区的数字。
     */
    private void loadUsage(String forPath) {
        api.usage(forPath, new FileBrowserApi.Callback<FileBrowserApi.Usage>() {
            @Override
            public void onSuccess(FileBrowserApi.Usage usage) {
                if (isFinishing() || isDestroyed()) return;
                // 路径在请求飞行期间变了就丢弃,免得显示上一个目录的容量
                if (!forPath.equals(path)) return;
                if (usage.total <= 0) {
                    usageBox.setVisibility(View.GONE);
                    return;
                }
                usageBox.setVisibility(View.VISIBLE);
                usageBar.setProgress(usage.percent());
                usageText.setText(getString(R.string.fb_usage,
                        Formatters.bytes(usage.used), Formatters.bytes(usage.total),
                        usage.percent(), Formatters.bytes(usage.free())));
            }

            @Override
            public void onFailure(String message) {
                if (isFinishing() || isDestroyed()) return;
                // 容量拿不到不影响浏览,安静地藏起来
                usageBox.setVisibility(View.GONE);
            }
        });
    }

    private void showStatus(String message) {
        statusLabel.setText(message);
        statusLabel.setVisibility(message == null ? View.GONE : View.VISIBLE);
    }

    private void goUp() {
        if (searchQuery != null) {
            exitSearch();
            return;
        }
        String parent = FileBrowserApi.parentOf(path);
        if (parent == null) {
            finish();
            return;
        }
        path = parent;
        reload();
    }

    @Override
    public void onBackPressed() {
        if (searchQuery != null) {
            exitSearch();
        } else if (!"/".equals(path)) {
            goUp();
        } else {
            super.onBackPressed();
        }
    }

    // =====================================================================
    // 新建
    // =====================================================================

    private void showCreateMenu() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.fb_new)
                .setItems(new CharSequence[]{
                        getString(R.string.fb_new_folder),
                        getString(R.string.fb_new_file)
                }, (d, which) -> promptName(
                        which == 0 ? R.string.fb_new_folder : R.string.fb_new_file, "",
                        name -> {
                            String target = FileBrowserApi.join(path, name);
                            FileBrowserApi.Callback<Void> cb = simpleCallback(
                                    getString(R.string.fb_created));
                            if (which == 0) {
                                api.createFolder(target, cb);
                            } else {
                                api.createFile(target, cb);
                            }
                        }))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private interface NamePicked {
        void onName(String name);
    }

    private void promptName(int titleRes, String initial, NamePicked picked) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(initial);
        input.setSelection(input.getText().length());
        int pad = dp(20);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(pad, dp(8), pad, 0);
        wrap.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new MaterialAlertDialogBuilder(this)
                .setTitle(titleRes)
                .setView(wrap)
                .setPositiveButton(R.string.ok, (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        alert(getString(R.string.fb_name_required));
                        return;
                    }
                    if (name.contains("/")) {
                        alert(getString(R.string.fb_name_no_slash));
                        return;
                    }
                    picked.onName(name);
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    // =====================================================================
    // 单项操作
    // =====================================================================

    /** 点文件先看内容,操作留给长按 —— 大多数时候用户只是想看一眼 */
    private void openPreview(FileBrowserApi.Entry entry) {
        Intent intent = new Intent(this, FilePreviewActivity.class);
        intent.putExtra(FilePreviewActivity.EXTRA_HOST, host);
        intent.putExtra(FilePreviewActivity.EXTRA_PORT, port);
        intent.putExtra(FilePreviewActivity.EXTRA_PATH, entry.path);
        intent.putExtra(FilePreviewActivity.EXTRA_NAME, entry.name);
        startActivity(intent);
    }

    private void showActions(FileBrowserApi.Entry entry) {
        List<CharSequence> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();

        labels.add(getString(R.string.fb_download));
        actions.add(() -> startDownload(entry));
        labels.add(getString(R.string.fb_rename));
        // 搜索结果里的条目可能不在当前目录,重命名目标要按它自己的父目录算
        actions.add(() -> promptName(R.string.fb_rename, entry.name, name -> {
            String parent = FileBrowserApi.parentOf(entry.path);
            api.rename(entry.path, FileBrowserApi.join(parent == null ? "/" : parent, name),
                    simpleCallback(getString(R.string.fb_renamed)));
        }));
        labels.add(getString(R.string.fb_share));
        actions.add(() -> createShare(entry));
        labels.add(getString(R.string.fb_delete));
        actions.add(() -> confirmDelete(entry));

        new MaterialAlertDialogBuilder(this)
                .setTitle(entry.name)
                .setItems(labels.toArray(new CharSequence[0]),
                        (d, which) -> actions.get(which).run())
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void confirmDelete(FileBrowserApi.Entry entry) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.fb_delete)
                .setMessage(getString(entry.isDir
                        ? R.string.fb_confirm_delete_dir : R.string.fb_confirm_delete, entry.name))
                .setPositiveButton(R.string.fb_delete, (d, w) ->
                        api.delete(entry.path, simpleCallback(getString(R.string.fb_deleted))))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void createShare(FileBrowserApi.Entry entry) {
        AlertDialog progress = progress(getString(R.string.fb_sharing));
        api.share(entry.path, new FileBrowserApi.Callback<String>() {
            @Override
            public void onSuccess(String url) {
                if (isFinishing() || isDestroyed()) return;
                progress.dismiss();
                showShareLink(url);
            }

            @Override
            public void onFailure(String message) {
                if (isFinishing() || isDestroyed()) return;
                progress.dismiss();
                alert(getString(R.string.fb_action_failed, message));
            }
        });
    }

    /** 分享链接是局域网地址,外网打不开 —— 提示里说清楚,免得发出去对方打不开 */
    private void showShareLink(String url) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.fb_share_created)
                .setMessage(url + "\n\n" + getString(R.string.fb_share_lan_note))
                .setPositiveButton(R.string.fb_copy_link, (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("share", url));
                    alert(getString(R.string.fb_link_copied));
                })
                .setNeutralButton(R.string.fb_send_link, (d, w) -> {
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("text/plain");
                    send.putExtra(Intent.EXTRA_TEXT, url);
                    startActivity(Intent.createChooser(send, getString(R.string.fb_send_link)));
                })
                .setNegativeButton(R.string.ok, null)
                .show();
    }

    // =====================================================================
    // 上传 / 下载(走系统文件选择器)
    // =====================================================================

    private void startDownload(FileBrowserApi.Entry entry) {
        pendingDownload = entry;
        // 目录会被打包成 zip
        saveLauncher.launch(entry.isDir ? entry.name + ".zip" : entry.name);
    }

    private void onSaveLocationPicked(Uri uri) {
        FileBrowserApi.Entry entry = pendingDownload;
        pendingDownload = null;
        if (uri == null || entry == null) return;

        AlertDialog progress = progress(getString(R.string.fb_downloading, entry.name));
        api.download(entry.path, entry.isDir, () -> {
            OutputStream out = getContentResolver().openOutputStream(uri);
            if (out == null) throw new IOException("无法写入所选位置");
            return out;
        }, new FileBrowserApi.Callback<Void>() {
            @Override
            public void onSuccess(Void unused) {
                if (isFinishing() || isDestroyed()) return;
                progress.dismiss();
                alert(getString(R.string.fb_downloaded, entry.name));
            }

            @Override
            public void onFailure(String message) {
                if (isFinishing() || isDestroyed()) return;
                progress.dismiss();
                alert(getString(R.string.fb_action_failed, message));
            }
        });
    }

    private void onUploadFilePicked(Uri uri) {
        if (uri == null) return;
        String name = displayName(uri);
        String target = FileBrowserApi.join(path, name);
        AlertDialog progress = progress(getString(R.string.fb_uploading, name));
        api.upload(target, new FileBrowserApi.StreamSource() {
            @Override
            public InputStream open() throws IOException {
                InputStream in = getContentResolver().openInputStream(uri);
                if (in == null) throw new IOException("无法读取所选文件");
                return in;
            }

            @Override
            public long length() {
                try (android.os.ParcelFileDescriptor fd =
                             getContentResolver().openFileDescriptor(uri, "r")) {
                    return fd != null ? fd.getStatSize() : -1;
                } catch (Exception e) {
                    return -1;   // 长度未知时 OkHttp 走分块传输
                }
            }

            @Override
            public String contentType() {
                return getContentResolver().getType(uri);
            }
        }, new FileBrowserApi.Callback<Void>() {
            @Override
            public void onSuccess(Void unused) {
                if (isFinishing() || isDestroyed()) return;
                progress.dismiss();
                alert(getString(R.string.fb_uploaded, name));
                reload();
            }

            @Override
            public void onFailure(String message) {
                if (isFinishing() || isDestroyed()) return;
                progress.dismiss();
                alert(getString(R.string.fb_action_failed, message));
            }
        });
    }

    /** 从 SAF 的 Uri 取文件名,取不到就用时间戳兜底 */
    private String displayName(Uri uri) {
        try (android.database.Cursor c = getContentResolver()
                .query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String name = c.getString(idx);
                    if (name != null && !name.isEmpty()) return name;
                }
            }
        } catch (Exception ignored) {
            // 查不到就往下走
        }
        return "upload_" + System.currentTimeMillis();
    }

    // =====================================================================
    // 工具
    // =====================================================================

    private FileBrowserApi.Callback<Void> simpleCallback(String okMessage) {
        return new FileBrowserApi.Callback<Void>() {
            @Override
            public void onSuccess(Void unused) {
                if (isFinishing() || isDestroyed()) return;
                alert(okMessage);
                refreshCurrentView();
            }

            @Override
            public void onFailure(String message) {
                if (isFinishing() || isDestroyed()) return;
                alert(getString(R.string.fb_action_failed, message));
            }
        };
    }

    /** 搜索模式下刷新要重搜,不能跳回目录列表 */
    private void refreshCurrentView() {
        if (searchQuery != null) {
            runSearch(searchRoot, searchQuery);
        } else {
            reload();
        }
    }

    private AlertDialog progress(String message) {
        return new MaterialAlertDialogBuilder(this)
                .setMessage(message)
                .setCancelable(false)
                .show();
    }

    private void alert(String message) {
        if (isFinishing() || isDestroyed()) return;
        new MaterialAlertDialogBuilder(this)
                .setMessage(message)
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // =====================================================================
    // 适配器
    // =====================================================================

    private class EntryAdapter extends RecyclerView.Adapter<EntryAdapter.Holder> {

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_fb_entry, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            FileBrowserApi.Entry entry = entries.get(position);
            h.name.setText(entry.name);
            h.icon.setImageResource(entry.isDir ? R.drawable.ic_folder : R.drawable.ic_file);
            h.icon.setColorFilter(getColor(entry.isDir ? R.color.ios_blue : R.color.label_tertiary));
            h.chevron.setVisibility(entry.isDir ? View.VISIBLE : View.INVISIBLE);
            // 搜索结果没有体积和时间,显示它在哪个目录 —— 找东西时这才是想知道的
            if (entry.fromSearch) {
                String parent = FileBrowserApi.parentOf(entry.path);
                h.meta.setText(parent == null ? "/" : parent);
            } else {
                h.meta.setText(entry.isDir
                        ? shortDate(entry.modified)
                        : Formatters.bytes(entry.size) + "  ·  " + shortDate(entry.modified));
            }

            h.card.setOnClickListener(v -> {
                if (entry.isDir) {
                    path = entry.path;
                    reload();
                } else {
                    openPreview(entry);
                }
            });
            h.card.setOnLongClickListener(v -> {
                showActions(entry);
                return true;
            });
        }

        /** 2026-09-20T05:31:12.123Z → 2026-09-20 05:31 */
        private String shortDate(String iso) {
            if (iso == null || iso.length() < 16) return "";
            return iso.substring(0, 10) + " " + iso.substring(11, 16);
        }

        @Override
        public int getItemCount() {
            return entries.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final MaterialCardView card;
            final ImageView icon, chevron;
            final TextView name, meta;

            Holder(@NonNull View v) {
                super(v);
                card = (MaterialCardView) v;
                icon = v.findViewById(R.id.fb_icon);
                chevron = v.findViewById(R.id.fb_chevron);
                name = v.findViewById(R.id.fb_name);
                meta = v.findViewById(R.id.fb_meta);
            }
        }
    }
}
