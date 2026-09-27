package com.whykangkang.wrthub.ui.services.samba;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.whykangkang.wrthub.R;
import com.whykangkang.wrthub.api.ApiCallback;
import com.whykangkang.wrthub.api.ApiError;
import com.whykangkang.wrthub.api.SambaApi;
import com.whykangkang.wrthub.model.SambaShare;

import java.util.ArrayList;
import java.util.List;

/**
 * NAS(Samba)共享服务页,对应 iOS SambaServiceViewController。
 * 两节:基本信息(工作组/描述)、共享目录列表;右上角添加,点条目进编辑页。
 */
public class SambaServiceActivity extends AppCompatActivity {

    private SwipeRefreshLayout refresh;
    private LinearLayout infoContainer;
    private LinearLayout sharesContainer;
    private MaterialCardView sharesCard;
    private TextView sharesHeader;
    private TextView sharesFooter;

    private final List<SambaShare> shares = new ArrayList<>();
    private String workgroup = "WORKGROUP";
    private String description = "Samba on OpenWRT";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_samba);
        refresh = findViewById(R.id.refresh);
        infoContainer = findViewById(R.id.info_container);
        sharesContainer = findViewById(R.id.shares_container);
        sharesCard = findViewById(R.id.shares_card);
        sharesHeader = findViewById(R.id.shares_header);
        sharesFooter = findViewById(R.id.shares_footer);
        refresh.setOnRefreshListener(this::load);
        findViewById(R.id.btn_add).setOnClickListener(v ->
                startActivity(new Intent(this, SambaShareEditActivity.class)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        SambaApi.getShares(new ApiCallback<SambaApi.SambaConfig>() {
            @Override
            public void onSuccess(SambaApi.SambaConfig config) {
                refresh.setRefreshing(false);
                workgroup = config.workgroup;
                description = config.description;
                shares.clear();
                shares.addAll(config.shares);
                render();
            }

            @Override
            public void onFailure(ApiError error) {
                refresh.setRefreshing(false);
                shares.clear();
                render();
                // 回调可能在页面关闭后才到,不加这道判断弹窗会 BadTokenException
                if (SambaServiceActivity.this.isFinishing() || SambaServiceActivity.this.isDestroyed()) return;
                new MaterialAlertDialogBuilder(SambaServiceActivity.this)
                        .setMessage(getString(R.string.msg_action_failed, error.getMessage()))
                        .setPositiveButton(R.string.ok, null)
                        .show();
            }
        });
    }

    private void render() {
        infoContainer.removeAllViews();
        addInfoRow(R.string.samba_workgroup, workgroup);
        infoContainer.addView(separator());
        addInfoRow(R.string.samba_description, description);

        sharesContainer.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = 0; i < shares.size(); i++) {
            SambaShare share = shares.get(i);
            View row = inflater.inflate(R.layout.item_samba_share, sharesContainer, false);
            ((TextView) row.findViewById(R.id.share_name)).setText(share.displayName);
            ((TextView) row.findViewById(R.id.share_path)).setText(share.describe(this));
            row.setOnClickListener(v -> openEdit(share));
            sharesContainer.addView(row);
            if (i < shares.size() - 1) {
                sharesContainer.addView(separator());
            }
        }
        boolean empty = shares.isEmpty();
        sharesCard.setVisibility(empty ? View.GONE : View.VISIBLE);
        sharesHeader.setVisibility(empty ? View.GONE : View.VISIBLE);
        sharesFooter.setText(empty ? R.string.samba_empty_footer : R.string.samba_tap_to_edit);
    }

    private void openEdit(SambaShare share) {
        Intent intent = new Intent(this, SambaShareEditActivity.class);
        intent.putExtra(SambaShareEditActivity.EXTRA_SECTION, share.section);
        intent.putExtra(SambaShareEditActivity.EXTRA_NAME, share.displayName);
        intent.putExtra(SambaShareEditActivity.EXTRA_PATH, share.path);
        intent.putExtra(SambaShareEditActivity.EXTRA_READ_ONLY, share.readOnly);
        intent.putExtra(SambaShareEditActivity.EXTRA_GUEST_OK, share.guestOk);
        startActivity(intent);
    }

    private void addInfoRow(@StringRes int labelRes, String value) {
        View row = LayoutInflater.from(this)
                .inflate(R.layout.view_info_row, infoContainer, false);
        ((TextView) row.findViewById(R.id.row_label)).setText(labelRes);
        ((TextView) row.findViewById(R.id.row_value)).setText(value);
        infoContainer.addView(row);
    }

    private View separator() {
        View line = new View(this);
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.setMarginStart(Math.round(16 * getResources().getDisplayMetrics().density));
        line.setLayoutParams(lp);
        line.setBackgroundColor(getColor(R.color.separator));
        return line;
    }
}
