package com.whykangkang.wrthub.manager;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

/**
 * 语言切换,对应 iOS AppRoot.reloadForLanguageChange。
 * 用 AppCompat 1.6+ 的 per-app locales,无需手动 recreate 全部界面。
 */
public final class LocaleManager {

    private LocaleManager() {
    }

    /** 应用语言设置:system / zh / en */
    public static void apply(String language) {
        LocaleListCompat locales;
        switch (language) {
            case "zh":
                locales = LocaleListCompat.forLanguageTags("zh-CN");
                break;
            case "en":
                locales = LocaleListCompat.forLanguageTags("en");
                break;
            default:
                locales = LocaleListCompat.getEmptyLocaleList();  // 跟随系统
                break;
        }
        AppCompatDelegate.setApplicationLocales(locales);
    }

    /** 应用外观设置:system / light / dark */
    public static void applyAppearance(String appearance) {
        int mode;
        switch (appearance) {
            case "light":
                mode = AppCompatDelegate.MODE_NIGHT_NO;
                break;
            case "dark":
                mode = AppCompatDelegate.MODE_NIGHT_YES;
                break;
            default:
                mode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
                break;
        }
        AppCompatDelegate.setDefaultNightMode(mode);
    }
}