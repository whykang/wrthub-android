package com.whykangkang.wrthub.manager;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

import com.google.android.play.core.review.ReviewInfo;
import com.google.android.play.core.review.ReviewManager;
import com.google.android.play.core.review.ReviewManagerFactory;

/**
 * 引导评分,对应 iOS RatingManager。
 * 节流规则(与 iOS 同款):
 *  - 会话去重:每次 App 启动最多请求一次;
 *  - engagement 里程碑:累计 5 / 20 / 60 次成功交互时触发;
 *  - 同版本只弹一次;
 *  - 两次弹窗至少间隔 30 天。
 */
public class RatingManager {

    private static final String PREFS = "wrthub_rating";
    private static final String KEY_ENGAGEMENT = "engagement_count";
    private static final String KEY_LAST_PROMPT_MS = "last_prompt_ms";
    private static final String KEY_LAST_PROMPT_VERSION = "last_prompt_version";

    private static final int[] MILESTONES = {5, 20, 60};
    private static final long MIN_INTERVAL_MS = 30L * 24 * 60 * 60 * 1000;

    private static RatingManager instance;

    public static synchronized RatingManager getInstance(Context context) {
        if (instance == null) {
            instance = new RatingManager(context.getApplicationContext());
        }
        return instance;
    }

    private final Context appContext;
    private final SharedPreferences prefs;
    /** 本次进程是否已请求过(会话去重) */
    private boolean promptedThisSession;

    private RatingManager(Context context) {
        this.appContext = context;
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 记一次成功交互(如首页成功加载),达到里程碑时尝试引导评分 */
    public void recordEngagement(Activity activity) {
        int count = prefs.getInt(KEY_ENGAGEMENT, 0) + 1;
        prefs.edit().putInt(KEY_ENGAGEMENT, count).apply();
        if (shouldPrompt(count)) {
            requestReview(activity);
        }
    }

    boolean shouldPrompt(int engagementCount) {
        if (promptedThisSession) return false;
        if (!isMilestone(engagementCount)) return false;
        long last = prefs.getLong(KEY_LAST_PROMPT_MS, 0);
        if (last > 0 && System.currentTimeMillis() - last < MIN_INTERVAL_MS) return false;
        int currentVersion = currentVersionCode();
        int lastVersion = prefs.getInt(KEY_LAST_PROMPT_VERSION, -1);
        return currentVersion != lastVersion;
    }

    private static boolean isMilestone(int count) {
        for (int m : MILESTONES) {
            if (count == m) return true;
        }
        return false;
    }

    private void requestReview(Activity activity) {
        promptedThisSession = true;
        markPrompted();
        ReviewManager manager = ReviewManagerFactory.create(appContext);
        manager.requestReviewFlow().addOnCompleteListener(task -> {
            if (task.isSuccessful() && !activity.isFinishing()) {
                ReviewInfo info = task.getResult();
                manager.launchReviewFlow(activity, info);
            }
        });
    }

    private void markPrompted() {
        prefs.edit()
                .putLong(KEY_LAST_PROMPT_MS, System.currentTimeMillis())
                .putInt(KEY_LAST_PROMPT_VERSION, currentVersionCode())
                .apply();
    }

    private int currentVersionCode() {
        try {
            return (int) appContext.getPackageManager()
                    .getPackageInfo(appContext.getPackageName(), 0).getLongVersionCode();
        } catch (Exception e) {
            return 1;
        }
    }
}