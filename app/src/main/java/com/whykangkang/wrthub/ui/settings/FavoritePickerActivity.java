package com.whykangkang.wrthub.ui.settings;

import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.checkbox.MaterialCheckBox;
import com.whykangkang.wrthub.R;
import com.whykangkang.wrthub.manager.FavoriteServicesManager;
import com.whykangkang.wrthub.manager.ServiceCatalog;
import com.whykangkang.wrthub.manager.ServiceCatalog.Descriptor;

/**
 * 首页「常用功能」收藏选择,对应 iOS FavoriteServicesPickerViewController。
 * 勾选目录里的功能加到首页,取消勾选移除;顺序按勾选先后(见 FavoriteServicesManager)。
 */
public class FavoritePickerActivity extends AppCompatActivity {

    private FavoriteServicesManager favorites;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_favorite_picker);
        favorites = FavoriteServicesManager.getInstance(this);
        LinearLayout host = findViewById(R.id.picker_container);
        LayoutInflater inflater = LayoutInflater.from(this);

        for (int i = 0; i < ServiceCatalog.all().size(); i++) {
            Descriptor d = ServiceCatalog.all().get(i);
            View row = inflater.inflate(R.layout.item_favorite_pick, host, false);
            ImageView icon = row.findViewById(R.id.pick_icon);
            icon.setImageResource(d.iconRes);
            int color = getColor(d.iconColorRes);
            icon.setColorFilter(color, PorterDuff.Mode.SRC_IN);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(16 * getResources().getDisplayMetrics().density);
            bg.setColor((color & 0x00FFFFFF) | 0x1A000000);
            row.findViewById(R.id.pick_icon_bg).setBackground(bg);
            ((TextView) row.findViewById(R.id.pick_title)).setText(d.titleRes);
            ((TextView) row.findViewById(R.id.pick_subtitle)).setText(d.subtitleRes);
            MaterialCheckBox check = row.findViewById(R.id.pick_check);
            check.setChecked(favorites.contains(d.id));
            View.OnClickListener toggle = v -> {
                favorites.toggle(d.id);
                check.setChecked(favorites.contains(d.id));
            };
            row.setOnClickListener(toggle);
            check.setOnClickListener(toggle);
            host.addView(row);
            if (i < ServiceCatalog.all().size() - 1) {
                host.addView(separator());
            }
        }
        findViewById(R.id.btn_done).setOnClickListener(v -> finish());
    }

    private View separator() {
        View line = new View(this);
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.setMarginStart(Math.round(68 * getResources().getDisplayMetrics().density));
        line.setLayoutParams(lp);
        line.setBackgroundColor(getColor(R.color.separator));
        return line;
    }
}
