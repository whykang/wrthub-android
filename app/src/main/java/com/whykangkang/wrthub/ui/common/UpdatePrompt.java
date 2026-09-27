package com.whykangkang.wrthub.ui.common;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.whykangkang.wrthub.R;
import com.whykangkang.wrthub.manager.UpdateChecker;

/**
 * 新版本提示弹窗与下载跳转,设置页的手动「检测更新」和启动时的自动检测共用。
 */
public final class UpdatePrompt {

    /** 每个进程只自动检测一次,避免来回切 Activity 反复弹 */
    private static boolean checkedThisLaunch;

    private UpdatePrompt() {
    }

    /**
     * 启动时自动检测:只有真的有新版本才弹窗,已是最新或网络失败一律静默
     * (手动检测才需要给「已是最新版本」这种反馈)。
     */
    public static void autoCheck(Activity activity) {
        if (checkedThisLaunch) return;
        checkedThisLaunch = true;
        UpdateChecker.check(activity, new UpdateChecker.Callback() {
            @Override
            public void onResult(UpdateChecker.UpdateInfo info) {
                if (info == null || activity.isFinishing() || activity.isDestroyed()) return;
                show(activity, info);
            }

            @Override
            public void onFailure(String message) {
                // 静默:启动时连不上更新服务器不该打扰用户
            }
        });
    }

    /**
     * 显示新版本弹窗。forceUpdate 为 true 时不可取消,且只有「立即更新」。
     */
    public static void show(Activity activity, UpdateChecker.UpdateInfo info) {
        String body = info.updateMessage == null ? "" : info.updateMessage;
        if (info.forceUpdate) {
            String note = activity.getString(R.string.update_force_note);
            body = body.isEmpty() ? note : body + "\n\n" + note;
        }
        String name = info.versionName == null || info.versionName.isEmpty()
                ? String.valueOf(info.versionCode) : info.versionName;

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity)
                .setTitle(activity.getString(R.string.update_available_title, name))
                .setMessage(body)
                .setCancelable(!info.forceUpdate)
                .setPositiveButton(R.string.update_now,
                        (d, w) -> openDownload(activity, info.downloadUrl));
        if (!info.forceUpdate) {
            builder.setNegativeButton(R.string.update_later, null);
        }
        AlertDialog dialog = builder.create();
        if (info.forceUpdate) {
            dialog.setCanceledOnTouchOutside(false);
        }
        dialog.show();
    }

    /** 跳外部浏览器下载安装包 */
    public static void openDownload(Activity activity, String url) {
        if (url == null || url.isEmpty()) return;
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            activity.startActivity(intent);
        } catch (Exception e) {
            new MaterialAlertDialogBuilder(activity)
                    .setMessage(R.string.update_no_browser)
                    .setPositiveButton(R.string.ok, null)
                    .show();
        }
    }
}
