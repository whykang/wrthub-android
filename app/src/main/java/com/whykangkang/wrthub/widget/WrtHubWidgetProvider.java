package com.whykangkang.wrthub.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.RemoteViews;

import com.whykangkang.wrthub.R;
import com.whykangkang.wrthub.ui.login.DeviceListActivity;

import java.util.List;
import java.util.Locale;

/**
 * 桌面小组件,对应 iOS 的 WrtHubWidget。
 * 顶部路由器名 + WAN IP,中间主参数大字,底部最多 4 个次要指标。
 * 展示哪些指标由「设置 → 小组件设置」决定(见 WidgetSnapshot)。
 * 数据有两个来源,都写进同一份快照:
 * <ul>
 *   <li>主 App 刷新首页时写入</li>
 *   <li>小组件自己取(自动周期 + 点刷新按钮),见 {@link WidgetRefreshTask}</li>
 * </ul>
 * 点击组件打开 App。
 *
 * <p><b>关于「实时」:</b>Android 的小组件做不到秒级。
 * {@code updatePeriodMillis} 系统下限 30 分钟,WorkManager 周期任务下限 15 分钟,
 * 唯一能做到秒级的是常驻前台服务(常驻通知 + 耗电,国产 ROM 还照杀)。
 * 所以这里是 30 分钟自动 + 手动点刷新。
 */
public class WrtHubWidgetProvider extends AppWidgetProvider {

    private static final String ACTION_REFRESH = "com.whykangkang.wrthub.widget.REFRESH";

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        renderAll(context, manager, appWidgetIds);
        fetchInBackground(context, manager, appWidgetIds);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (!ACTION_REFRESH.equals(intent.getAction())) return;
        Context app = context.getApplicationContext();
        AppWidgetManager manager = AppWidgetManager.getInstance(app);
        int[] ids = manager.getAppWidgetIds(
                new android.content.ComponentName(app, WrtHubWidgetProvider.class));
        if (ids.length > 0) fetchInBackground(app, manager, ids);
    }

    /**
     * 后台取一轮数据。用 goAsync 保住进程,
     * {@link WidgetRefreshTask} 内部有 8 秒硬超时兜底。
     */
    private void fetchInBackground(Context context, AppWidgetManager manager, int[] ids) {
        Context app = context.getApplicationContext();
        PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                // write() 会顺带通知组件重绘,这里只负责兜底刷新一次
                if (!WidgetRefreshTask.run(app)) {
                    renderAll(app, manager, ids);
                }
            } finally {
                pending.finish();
            }
        }, "widget-refresh").start();
    }

    static void renderAll(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        for (int id : appWidgetIds) {
            manager.updateAppWidget(id, buildViews(context));
        }
    }

    private static RemoteViews buildViews(Context context) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_wrthub);
        views.setTextViewText(R.id.widget_name, WidgetSnapshot.routerName(context));
        String wanIp = WidgetSnapshot.wanIp(context);
        views.setTextViewText(R.id.widget_wan, wanIp);

        WidgetSnapshot.Metric primary = WidgetSnapshot.primary(context);
        views.setTextViewText(R.id.widget_primary_label, context.getString(labelRes(primary)));
        views.setTextViewText(R.id.widget_primary_value, value(context, primary));

        int[] labelIds = {R.id.metric1_label, R.id.metric2_label,
                R.id.metric3_label, R.id.metric4_label};
        int[] valueIds = {R.id.metric1_value, R.id.metric2_value,
                R.id.metric3_value, R.id.metric4_value};
        int[] boxIds = {R.id.metric1, R.id.metric2, R.id.metric3, R.id.metric4};

        List<WidgetSnapshot.Metric> items = WidgetSnapshot.items(context);
        for (int i = 0; i < boxIds.length; i++) {
            if (i < items.size()) {
                WidgetSnapshot.Metric metric = items.get(i);
                views.setViewVisibility(boxIds[i], View.VISIBLE);
                views.setTextViewText(labelIds[i], context.getString(labelRes(metric)));
                views.setTextViewText(valueIds[i], value(context, metric));
            } else {
                views.setViewVisibility(boxIds[i], View.GONE);
            }
        }

        // 数据过期时整体变淡,提示用户刷新。
        // 必须用 setFloat:View.setAlpha 的参数是 float,用 setInt 找的是
        // setAlpha(int),LinearLayout 上没有这个方法,整个 RemoteViews 会
        // 直接 inflate 失败 —— 桌面上显示「载入窗口小部件出现问题」。
        views.setFloat(R.id.widget_root, "setAlpha",
                WidgetSnapshot.isStale(context) ? 0.55f : 1f);

        Intent open = new Intent(context, DeviceListActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(
                context, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));

        Intent refresh = new Intent(context, WrtHubWidgetProvider.class);
        refresh.setAction(ACTION_REFRESH);
        views.setOnClickPendingIntent(R.id.widget_refresh, PendingIntent.getBroadcast(
                context, 1, refresh,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        return views;
    }

    static int labelRes(WidgetSnapshot.Metric metric) {
        switch (metric) {
            case LATENCY:
                return R.string.metric_latency;
            case DEVICES:
                return R.string.metric_devices;
            case CPU:
                return R.string.metric_cpu;
            case MEMORY:
                return R.string.metric_memory;
            case SPEED:
            default:
                return R.string.metric_speed;
        }
    }

    private static String value(Context context, WidgetSnapshot.Metric metric) {
        switch (metric) {
            case LATENCY: {
                Float ms = WidgetSnapshot.latencyMs(context);
                return ms == null ? "--" : String.format(Locale.US, "%.0f ms", ms);
            }
            case DEVICES: {
                Integer n = WidgetSnapshot.devices(context);
                return n == null ? "--" : String.valueOf(n);
            }
            case CPU: {
                Float p = WidgetSnapshot.cpuPercent(context);
                return p == null ? "--" : String.format(Locale.US, "%.0f%%", p);
            }
            case MEMORY: {
                Float p = WidgetSnapshot.memPercent(context);
                return p == null ? "--" : String.format(Locale.US, "%.0f%%", p);
            }
            case SPEED:
            default:
                return speedText(WidgetSnapshot.downBps(context));
        }
    }

    static String speedText(double bps) {
        if (bps >= 1048576) return String.format(Locale.US, "%.1f MB/s", bps / 1048576);
        if (bps >= 1024) return String.format(Locale.US, "%.0f KB/s", bps / 1024);
        return String.format(Locale.US, "%.0f B/s", bps);
    }
}
