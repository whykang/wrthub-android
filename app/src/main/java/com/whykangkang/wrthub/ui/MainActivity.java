package com.whykangkang.wrthub.ui;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.whykangkang.wrthub.R;
import com.whykangkang.wrthub.ui.common.UpdatePrompt;
import com.whykangkang.wrthub.ui.dashboard.DashboardFragment;
import com.whykangkang.wrthub.ui.devices.DevicesFragment;
import com.whykangkang.wrthub.ui.realtime.RealtimeFragment;
import com.whykangkang.wrthub.ui.services.ServicesFragment;
import com.whykangkang.wrthub.ui.settings.SettingsFragment;

/**
 * 底部导航容器,对应 iOS 的 TabBarController。
 * 各 Tab 的 Fragment 用 show/hide 切换以保留状态。
 */
public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        BottomNavigationView bottomNav = findViewById(R.id.bottom_nav);
        bottomNav.setOnItemSelectedListener(item -> {
            switchTab(item.getItemId());
            return true;
        });

        if (savedInstanceState == null) {
            switchTab(R.id.nav_dashboard);
        }

        // 每次启动检测一次新版本(UpdatePrompt 内部按进程去重),没有更新时静默
        UpdatePrompt.autoCheck(this);
    }

    private void switchTab(int itemId) {
        String tag = String.valueOf(itemId);
        FragmentManager fm = getSupportFragmentManager();
        FragmentTransaction tx = fm.beginTransaction();

        for (Fragment f : fm.getFragments()) {
            if (!f.isHidden() && !tag.equals(f.getTag())) {
                tx.hide(f);
            }
        }

        Fragment target = fm.findFragmentByTag(tag);
        if (target == null) {
            tx.add(R.id.fragment_container, createFragment(itemId), tag);
        } else {
            tx.show(target);
        }
        tx.commit();
    }

    private Fragment createFragment(int itemId) {
        if (itemId == R.id.nav_realtime) return new RealtimeFragment();
        if (itemId == R.id.nav_devices) return new DevicesFragment();
        if (itemId == R.id.nav_services) return new ServicesFragment();
        if (itemId == R.id.nav_settings) return new SettingsFragment();
        return new DashboardFragment();
    }
}