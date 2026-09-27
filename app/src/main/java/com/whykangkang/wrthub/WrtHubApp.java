package com.whykangkang.wrthub;

import android.app.Application;

import com.whykangkang.wrthub.api.OpenWrtApi;
import com.whykangkang.wrthub.manager.LocaleManager;
import com.whykangkang.wrthub.manager.RouterDeviceManager;
import com.whykangkang.wrthub.manager.SettingsManager;
import com.whykangkang.wrthub.model.RouterDevice;

/** 应用入口:启动时应用已保存的外观与语言设置,并恢复上次的连接配置 */
public class WrtHubApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        SettingsManager settings = SettingsManager.getInstance(this);
        LocaleManager.applyAppearance(settings.getAppearance());
        LocaleManager.apply(settings.getLanguage());
        restoreSession(settings);
    }

    /**
     * 冷启动恢复上次连接。
     *
     * iOS 在根 ViewController 里按 routerURL + authToken 判断「已登录」,直接进标签栏;
     * Android 这边 token 只活在内存里,所以这里只把设备信息灌回 OpenWrtApi ——
     * 首个请求发现 token 不新鲜会自动重登(withSession),效果一致。
     */
    private void restoreSession(SettingsManager settings) {
        if (!settings.isLoggedIn()) return;
        RouterDevice current = RouterDeviceManager.getInstance(this).getCurrentDevice();
        if (current == null) {
            settings.setLoggedIn(false);   // 设备被删了,回到设备列表
            return;
        }
        OpenWrtApi.getInstance().configure(current.getHost(), current.getPort(),
                current.isUseHttps(), current.getUsername(), current.getPassword());
    }
}