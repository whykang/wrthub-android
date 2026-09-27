package com.whykangkang.wrthub.ui.services.ftp;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.whykangkang.wrthub.R;
import com.whykangkang.wrthub.api.FtpClient;
import com.whykangkang.wrthub.model.FileItem;

import java.util.ArrayList;
import java.util.List;

/**
 * FTP 文件浏览器,对应 iOS FTPFileBrowserViewController。
 *
 * iOS 受 URLSession 限制无法进子目录(只能弹提示);Android 用原生 Socket 客户端,
 * 点目录直接进入下一级,点文件弹同款信息弹窗。
 */
public class FtpBrowserActivity extends AppCompatActivity {

    public static final String EXTRA_HOST = "host";
    public static final String EXTRA_PORT = "port";
    public static final String EXTRA_USERNAME = "username";
    public static final String EXTRA_PASSWORD = "password";
    public static final String EXTRA_PATH = "path";
    public static final String EXTRA_TITLE = "title";

    private final List<FileItem> files = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private FtpClient client;
    private String path = "/";
    private Adapter adapter;
    private RecyclerView listView;
    private TextView emptyView;
    private SwipeRefreshLayout refresh;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ftp_browser);

        Intent i = getIntent();
        path = i.getStringExtra(EXTRA_PATH) != null ? i.getStringExtra(EXTRA_PATH) : "/";
        String title = i.getStringExtra(EXTRA_TITLE);
        ((TextView) findViewById(R.id.page_title))
                .setText(title != null ? title : getString(R.string.ftp_root));

        client = new FtpClient(i.getStringExtra(EXTRA_HOST), i.getIntExtra(EXTRA_PORT, 21),
                i.getStringExtra(EXTRA_USERNAME), i.getStringExtra(EXTRA_PASSWORD));

        emptyView = findViewById(R.id.empty_view);
        emptyView.setText(R.string.ftp_folder_empty);
        listView = findViewById(R.id.file_list);
        refresh = findViewById(R.id.refresh);
        adapter = new Adapter();
        listView.setLayoutManager(new LinearLayoutManager(this));
        listView.setAdapter(adapter);
        refresh.setOnRefreshListener(this::load);
        load();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        client.shutdown();
    }

    private void load() {
        refresh.setRefreshing(true);
        client.listDirectory(path, new FtpClient.Callback() {
            @Override
            public void onSuccess(List<FileItem> items) {
                main.post(() -> {
                    refresh.setRefreshing(false);
                    files.clear();
                    files.addAll(items);
                    adapter.notifyDataSetChanged();
                    boolean empty = files.isEmpty();
                    emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
                    listView.setVisibility(empty ? View.GONE : View.VISIBLE);
                });
            }

            @Override
            public void onFailure(String message) {
                main.post(() -> {
                    refresh.setRefreshing(false);
                    // 回调可能在页面关闭后才到,不加这道判断弹窗会 BadTokenException
                    if (FtpBrowserActivity.this.isFinishing() || FtpBrowserActivity.this.isDestroyed()) return;
                    new MaterialAlertDialogBuilder(FtpBrowserActivity.this)
                            .setMessage(getString(R.string.ftp_connect_failed, message))
                            .setPositiveButton(R.string.ok, null)
                            .show();
                });
            }
        });
    }

    private void open(FileItem file) {
        if (file.isDirectory) {
            Intent intent = new Intent(this, FtpBrowserActivity.class);
            intent.putExtras(getIntent());
            intent.putExtra(EXTRA_PATH, file.path);
            intent.putExtra(EXTRA_TITLE, file.name);
            startActivity(intent);
            return;
        }
        String ext = file.extension();
        new MaterialAlertDialogBuilder(this)
                .setTitle(file.name)
                .setMessage(getString(R.string.ftp_file_info, file.path, file.sizeString(),
                        ext != null ? ext : getString(R.string.ftp_unknown_type)))
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    private class Adapter extends RecyclerView.Adapter<Adapter.Holder> {
        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_file, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            FileItem file = files.get(position);
            h.name.setText(file.name);
            h.detail.setText(file.isDirectory
                    ? getString(R.string.ftp_folder) : file.sizeString());
            h.icon.setImageResource(file.isDirectory
                    ? R.drawable.ic_folder : R.drawable.ic_file);
            h.chevron.setVisibility(file.isDirectory ? View.VISIBLE : View.INVISIBLE);
            h.itemView.setOnClickListener(v -> open(file));
        }

        @Override
        public int getItemCount() {
            return files.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final TextView name, detail;
            final ImageView icon, chevron;

            Holder(@NonNull View v) {
                super(v);
                name = v.findViewById(R.id.file_name);
                detail = v.findViewById(R.id.file_detail);
                icon = v.findViewById(R.id.file_icon);
                chevron = v.findViewById(R.id.file_chevron);
            }
        }
    }
}
