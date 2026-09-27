package com.whykangkang.wrthub.ui.dashboard;

import android.app.Dialog;
import android.content.Intent;
import android.net.Uri;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.whykangkang.wrthub.R;
import com.whykangkang.wrthub.api.ApiCallback;
import com.whykangkang.wrthub.api.ApiError;
import com.whykangkang.wrthub.api.BackupApi;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;

/**
 * 首页「备份升级」卡的三条流程,对应 iOS DashboardViewController 的
 * backup / restore / upgrade 一组方法(含逐级确认文案)。
 */
class BackupUpgradeController {

    private final DashboardFragment fragment;

    /** 备份数据先存这里,等用户选好保存位置再写出去 */
    private byte[] pendingBackup;

    private final ActivityResultLauncher<String> saveBackupLauncher;
    private final ActivityResultLauncher<String[]> pickBackupLauncher;
    private final ActivityResultLauncher<String[]> pickFirmwareLauncher;

    BackupUpgradeController(DashboardFragment fragment) {
        this.fragment = fragment;
        saveBackupLauncher = fragment.registerForActivityResult(
                new ActivityResultContracts.CreateDocument("application/gzip"),
                this::writeBackupTo);
        pickBackupLauncher = fragment.registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), this::onBackupPicked);
        pickFirmwareLauncher = fragment.registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), this::onFirmwarePicked);
    }

    // =====================================================================
    // 备份
    // =====================================================================

    void startBackup() {
        new MaterialAlertDialogBuilder(fragment.requireContext())
                .setTitle(R.string.backup_config)
                .setMessage(R.string.backup_confirm_msg)
                .setPositiveButton(R.string.ok, (d, w) -> performBackup())
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void performBackup() {
        Dialog loading = showLoading(fragment.getString(R.string.backup_generating), null);
        BackupApi.generateBackup(new ApiCallback<byte[]>() {
            @Override
            public void onSuccess(byte[] data) {
                loading.dismiss();
                pendingBackup = data;
                saveBackupLauncher.launch(defaultBackupName());
            }

            @Override
            public void onFailure(ApiError error) {
                loading.dismiss();
                fragment.alert("BACKUP_UNAVAILABLE".equals(error.getMessage())
                        ? fragment.getString(R.string.backup_unavailable)
                        : fragment.getString(R.string.msg_action_failed, error.getMessage()));
            }
        });
    }

    private String defaultBackupName() {
        return "backup-openwrt-"
                + new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US)
                .format(new java.util.Date()) + ".tar.gz";
    }

    private void writeBackupTo(Uri uri) {
        if (uri == null || pendingBackup == null) return;
        try (OutputStream out = fragment.requireContext()
                .getContentResolver().openOutputStream(uri)) {
            if (out == null) throw new IOException("openOutputStream 返回 null");
            out.write(pendingBackup);
            fragment.alert(fragment.getString(R.string.backup_saved,
                    formatSize(pendingBackup.length)));
        } catch (IOException e) {
            fragment.alert(fragment.getString(R.string.backup_save_failed, e.getMessage()));
        } finally {
            pendingBackup = null;
        }
    }

    // =====================================================================
    // 恢复
    // =====================================================================

    void pickBackupFile() {
        new MaterialAlertDialogBuilder(fragment.requireContext())
                .setTitle(R.string.restore_config)
                .setMessage(R.string.restore_confirm_msg)
                .setPositiveButton(R.string.restore_pick_file, (d, w) ->
                        pickBackupLauncher.launch(new String[]{"*/*"}))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void onBackupPicked(Uri uri) {
        if (uri == null) return;
        byte[] data = readAll(uri);
        if (data == null) {
            fragment.alert(fragment.getString(R.string.restore_read_failed));
            return;
        }
        Dialog loading = showLoading(fragment.getString(R.string.restore_uploading), null);
        BackupApi.uploadAndValidateBackup(data, new ApiCallback<List<String>>() {
            @Override
            public void onSuccess(List<String> files) {
                loading.dismiss();
                showBackupPreview(fileName(uri), formatSize(data.length), files);
            }

            @Override
            public void onFailure(ApiError error) {
                loading.dismiss();
                fragment.alert(fragment.getString(R.string.msg_action_failed,
                        error.getMessage()));
            }
        });
    }

    private void showBackupPreview(String fileName, String fileSize, List<String> files) {
        String message;
        if (files.isEmpty()) {
            message = fragment.getString(R.string.restore_preview_simple, fileName, fileSize);
        } else {
            StringBuilder list = new StringBuilder();
            int shown = Math.min(files.size(), 30);
            for (int i = 0; i < shown; i++) {
                if (i > 0) list.append('\n');
                list.append(files.get(i));
            }
            if (files.size() > 30) {
                list.append('\n').append(fragment.getString(R.string.restore_more_files,
                        files.size() - 30));
            }
            message = fragment.getString(R.string.restore_preview_files, list.toString());
        }
        new MaterialAlertDialogBuilder(fragment.requireContext())
                .setTitle(R.string.restore_preview_title)
                .setMessage(message)
                .setPositiveButton(R.string.restore_continue, (d, w) -> performRestore())
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void performRestore() {
        Dialog loading = showLoading(fragment.getString(R.string.restore_running),
                fragment.getString(R.string.restore_keep_app_open));
        BackupApi.restoreBackup(new ApiCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean ok) {
                loading.dismiss();
                // 回调可能在页面关闭后才到,不加这道判断弹窗会 BadTokenException
                if (!fragment.isAdded()) return;
                new MaterialAlertDialogBuilder(fragment.requireContext())
                        .setTitle(R.string.restore_success_title)
                        .setMessage(R.string.restore_success_msg)
                        .setPositiveButton(R.string.ok, null)
                        .show();
            }

            @Override
            public void onFailure(ApiError error) {
                loading.dismiss();
                fragment.alert(fragment.getString(R.string.restore_failed,
                        error.getMessage()));
            }
        });
    }

    // =====================================================================
    // 固件升级
    // =====================================================================

    void pickFirmwareFile() {
        new MaterialAlertDialogBuilder(fragment.requireContext())
                .setTitle(R.string.upgrade_firmware)
                .setMessage(R.string.upgrade_pick_msg)
                .setPositiveButton(R.string.upgrade_pick_file, (d, w) ->
                        pickFirmwareLauncher.launch(new String[]{"*/*"}))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void onFirmwarePicked(Uri uri) {
        if (uri == null) return;
        byte[] data = readAll(uri);
        if (data == null) {
            fragment.alert(fragment.getString(R.string.restore_read_failed));
            return;
        }
        Dialog loading = showLoading(fragment.getString(R.string.upgrade_uploading), null);
        BackupApi.uploadFirmware(data, new ApiCallback<Void>() {
            @Override
            public void onSuccess(Void unused) {
                loading.dismiss();
                showFirmwareConfirmation(data);
            }

            @Override
            public void onFailure(ApiError error) {
                loading.dismiss();
                fragment.alert(fragment.getString(R.string.msg_action_failed,
                        error.getMessage()));
            }
        });
    }

    /** 上传后列出校验和与体积,让用户与原始文件比对(与网页端刷机确认页一致) */
    private void showFirmwareConfirmation(byte[] data) {
        String message = fragment.getString(R.string.upgrade_confirm_msg,
                formatSize(data.length), digest(data, "MD5"), digest(data, "SHA-256"));
        new MaterialAlertDialogBuilder(fragment.requireContext())
                .setTitle(R.string.upgrade_confirm_title)
                .setMessage(message)
                .setPositiveButton(R.string.upgrade_keep_settings, (d, w) ->
                        showFirmwareWarning(true))
                .setNeutralButton(R.string.upgrade_no_keep, (d, w) ->
                        showFirmwareWarning(false))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void showFirmwareWarning(boolean keepSettings) {
        String message = fragment.getString(R.string.upgrade_warning_msg,
                fragment.getString(keepSettings
                        ? R.string.upgrade_option_keep : R.string.upgrade_option_reset));
        new MaterialAlertDialogBuilder(fragment.requireContext())
                .setTitle(R.string.upgrade_final_title)
                .setMessage(message)
                .setPositiveButton(R.string.upgrade_flash_action, (d, w) -> flash(keepSettings))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void flash(boolean keepSettings) {
        Dialog loading = showLoading(fragment.getString(R.string.upgrade_flashing),
                fragment.getString(R.string.upgrade_flashing_msg));
        BackupApi.flashFirmware(keepSettings, new ApiCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean ok) {
                loading.dismiss();
                // 回调可能在页面关闭后才到,不加这道判断弹窗会 BadTokenException
                if (!fragment.isAdded()) return;
                new MaterialAlertDialogBuilder(fragment.requireContext())
                        .setTitle(R.string.upgrade_started_title)
                        .setMessage(R.string.upgrade_started_msg)
                        .setPositiveButton(R.string.ok, null)
                        .show();
            }

            @Override
            public void onFailure(ApiError error) {
                loading.dismiss();
                fragment.alert(fragment.getString(R.string.upgrade_failed,
                        error.getMessage()));
            }
        });
    }

    // =====================================================================
    // 工具
    // =====================================================================

    private Dialog showLoading(String title, String message) {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(
                fragment.requireContext())
                .setTitle(title)
                .setCancelable(false)
                .setView(R.layout.view_loading);
        if (message != null) builder.setMessage(message);
        return builder.show();
    }

    private byte[] readAll(Uri uri) {
        try (InputStream in = fragment.requireContext()
                .getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) {
                out.write(chunk, 0, n);
            }
            return out.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    private String fileName(Uri uri) {
        String last = uri.getLastPathSegment();
        if (last == null) return "backup.tar.gz";
        int slash = last.lastIndexOf('/');
        return slash >= 0 ? last.substring(slash + 1) : last;
    }

    static String formatSize(long bytes) {
        double mb = bytes / 1048576.0;
        if (mb >= 1) return String.format(Locale.US, "%.2f MB", mb);
        return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
    }

    static String digest(byte[] data, String algorithm) {
        try {
            byte[] hash = MessageDigest.getInstance(algorithm).digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format(Locale.US, "%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "N/A";
        }
    }
}
