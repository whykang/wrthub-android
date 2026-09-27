package com.whykangkang.wrthub.api;

import android.util.Base64;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 备份 / 恢复 / 固件升级,对应 iOS OpenWrtAPI 的「Backup &amp; Upgrade Management」分区。
 *
 * 备份下载走 LuCI 的 cgi-backup(POST sessionid,响应是 tar.gz);
 * 该端点不可用时依次回退:sysupgrade --list-backup | tar | base64 → 读 /tmp 下已有备份。
 * 恢复与刷机都靠 cgi-upload 上传到 /tmp 后用 file.exec 执行 sysupgrade。
 */
public final class BackupApi {

    public static final String BACKUP_PATH = "/tmp/backup.tar.gz";
    public static final String FIRMWARE_PATH = "/tmp/firmware.bin";

    private static final MediaType FORM =
            MediaType.get("application/x-www-form-urlencoded");
    private static final MediaType GZIP = MediaType.get("application/x-gzip");
    private static final MediaType OCTET = MediaType.get("application/octet-stream");

    private BackupApi() {
    }

    private static OpenWrtApi api() {
        return OpenWrtApi.getInstance();
    }

    // =====================================================================
    // 备份
    // =====================================================================

    /** 生成并下载备份归档(tar.gz 原始字节) */
    public static void generateBackup(ApiCallback<byte[]> cb) {
        OpenWrtApi api = api();
        api.withSession(cb, () -> {
            byte[] data = downloadViaCgi(api);
            if (data != null) {
                api.postSuccess(cb, data);
            } else {
                // CGI 端点不通,退回 exec 打包 + base64
                downloadViaExec(cb);
            }
        });
    }

    /** POST /cgi-bin/cgi-backup(GET 会 403);校验 gzip 魔数 0x1f8b 且体积合理 */
    private static byte[] downloadViaCgi(OpenWrtApi api) {
        String token = api.cgiSysauthToken();
        Request request = new Request.Builder()
                .url(api.baseUrl() + "/cgi-bin/cgi-backup")
                .post(RequestBody.create("sessionid=" + token, FORM))
                .header("Cookie", "sysauth_http=" + token)
                .header("Referer", api.baseUrl() + "/cgi-bin/luci/admin/system/flash")
                .build();
        try (Response response = api.clientWithTimeout(180).newCall(request).execute()) {
            if (response.code() != 200 || response.body() == null) return null;
            byte[] bytes = response.body().bytes();
            if (isGzip(bytes) && bytes.length > 1000) {
                return bytes;
            }
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    static boolean isGzip(byte[] bytes) {
        return bytes != null && bytes.length >= 2
                && (bytes[0] & 0xFF) == 0x1f && (bytes[1] & 0xFF) == 0x8b;
    }

    /** sysupgrade --list-backup | tar -czf | base64 */
    private static void downloadViaExec(ApiCallback<byte[]> cb) {
        String script = "/sbin/sysupgrade --list-backup | tar -czf " + BACKUP_PATH
                + " -T - 2>/dev/null && cat " + BACKUP_PATH + " | base64 && rm -f " + BACKUP_PATH;
        api().fileExec("sh", new String[]{"-c", script}, 180, new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                byte[] data = decodeStdout(result);
                if (data != null && data.length > 100) {
                    cb.onSuccess(data);
                } else {
                    tryReadExisting(0, cb);
                }
            }

            @Override
            public void onFailure(ApiError error) {
                tryReadExisting(0, cb);
            }
        });
    }

    private static byte[] decodeStdout(JsonObject result) {
        JsonElement stdout = result.get("stdout");
        if (stdout == null || !stdout.isJsonPrimitive()) return null;
        String cleaned = stdout.getAsString().replaceAll("\\s", "");
        if (cleaned.isEmpty()) return null;
        try {
            return Base64.decode(cleaned, Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static final List<String> CANDIDATE_FILES = Arrays.asList(
            "/tmp/backup.tar.gz", "/tmp/sysupgrade.tgz", "/tmp/backup-config.tar.gz");

    /** 最后的兜底:先尝试生成,再 base64 读出来 */
    private static void tryReadExisting(int index, ApiCallback<byte[]> cb) {
        if (index >= CANDIDATE_FILES.size()) {
            cb.onFailure(new ApiError(ApiError.Type.UBUS_ERROR, "BACKUP_UNAVAILABLE"));
            return;
        }
        String path = CANDIDATE_FILES.get(index);
        String script = "/sbin/sysupgrade --list-backup | tar -czf " + path + " -T - 2>/dev/null";
        api().fileExec("sh", new String[]{"-c", script}, 120, new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject r) {
                read();
            }

            @Override
            public void onFailure(ApiError error) {
                read();
            }

            private void read() {
                JsonObject p = new JsonObject();
                p.addProperty("path", path);
                p.addProperty("base64", true);
                api().makeUbusCall("file", "read", p, 120, new ApiCallback<JsonObject>() {
                    @Override
                    public void onSuccess(JsonObject result) {
                        JsonElement data = result.get("data");
                        if (data != null && data.isJsonPrimitive()) {
                            try {
                                byte[] bytes = Base64.decode(data.getAsString(), Base64.DEFAULT);
                                if (bytes.length > 100) {
                                    cb.onSuccess(bytes);
                                    return;
                                }
                            } catch (IllegalArgumentException ignored) {
                                // 继续试下一个
                            }
                        }
                        tryReadExisting(index + 1, cb);
                    }

                    @Override
                    public void onFailure(ApiError error) {
                        tryReadExisting(index + 1, cb);
                    }
                });
            }
        });
    }

    // =====================================================================
    // 恢复
    // =====================================================================

    /** 上传备份到 /tmp 并用 tar -tzf 列出内容,供恢复前预览 */
    public static void uploadAndValidateBackup(byte[] data, ApiCallback<List<String>> cb) {
        upload(data, BACKUP_PATH, "backup.tar.gz", GZIP, new ApiCallback<Void>() {
            @Override
            public void onSuccess(Void unused) {
                api().fileExec("tar", new String[]{"-tzf", BACKUP_PATH}, 60,
                        new ApiCallback<JsonObject>() {
                            @Override
                            public void onSuccess(JsonObject result) {
                                cb.onSuccess(parseFileList(result));
                            }

                            @Override
                            public void onFailure(ApiError error) {
                                // 校验被权限拦下不算失败,仍允许恢复(与 iOS 一致)
                                cb.onSuccess(new ArrayList<>());
                            }
                        });
            }

            @Override
            public void onFailure(ApiError error) {
                cb.onFailure(error);
            }
        });
    }

    static List<String> parseFileList(JsonObject result) {
        List<String> files = new ArrayList<>();
        JsonElement stdout = result.get("stdout");
        if (stdout == null || !stdout.isJsonPrimitive()) return files;
        for (String line : stdout.getAsString().split("\n")) {
            String f = line.trim();
            if (f.isEmpty() || f.endsWith("/")) continue;   // 过滤目录
            files.add(f);
        }
        return files;
    }

    /** sysupgrade --restore-backup,成功后重启路由器 */
    public static void restoreBackup(ApiCallback<Boolean> cb) {
        api().fileExec("/sbin/sysupgrade", new String[]{"--restore-backup", BACKUP_PATH}, 120,
                new ApiCallback<JsonObject>() {
                    @Override
                    public void onSuccess(JsonObject result) {
                        int code = result.has("code") ? result.get("code").getAsInt() : 0;
                        if (code != 0) {
                            cb.onFailure(new ApiError(ApiError.Type.UBUS_ERROR,
                                    "RESTORE_FAILED_" + code));
                            return;
                        }
                        api().reboot(new ApiCallback<JsonObject>() {
                            @Override
                            public void onSuccess(JsonObject r) {
                                cb.onSuccess(true);
                            }

                            @Override
                            public void onFailure(ApiError error) {
                                // 重启时连接必然中断,不算失败
                                cb.onSuccess(true);
                            }
                        });
                    }

                    @Override
                    public void onFailure(ApiError error) {
                        cb.onFailure(error);
                    }
                });
    }

    // =====================================================================
    // 固件升级
    // =====================================================================

    /** 上传固件到 /tmp/firmware.bin */
    public static void uploadFirmware(byte[] data, ApiCallback<Void> cb) {
        upload(data, FIRMWARE_PATH, "firmware.bin", OCTET, cb);
    }

    /** sysupgrade 刷写。keepSettings=false 时加 -n */
    public static void flashFirmware(boolean keepSettings, ApiCallback<Boolean> cb) {
        String[] args = keepSettings
                ? new String[]{FIRMWARE_PATH}
                : new String[]{"-n", FIRMWARE_PATH};
        api().fileExec("/sbin/sysupgrade", args, 120, new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                cb.onSuccess(true);
            }

            @Override
            public void onFailure(ApiError error) {
                // 刷机开始后连接必然中断,网络错误视为已开始
                if (error.getType() == ApiError.Type.NETWORK) {
                    cb.onSuccess(true);
                } else {
                    cb.onFailure(error);
                }
            }
        });
    }

    // =====================================================================
    // cgi-upload
    // =====================================================================

    /** multipart 上传到 cgi-io,字段与网页端一致:sessionid / filename / filedata */
    private static void upload(byte[] data, String targetPath, String fileName,
                               MediaType type, ApiCallback<Void> cb) {
        OpenWrtApi api = api();
        api.withSession(cb, () -> {
            String token = api.cgiSysauthToken();
            String boundary = "----WebKitFormBoundary"
                    + UUID.randomUUID().toString().replace("-", "");
            MultipartBody body = new MultipartBody.Builder(boundary)
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("sessionid", api.sessionToken() != null
                            ? api.sessionToken() : token)
                    .addFormDataPart("filename", targetPath)
                    .addFormDataPart("filedata", fileName, RequestBody.create(data, type))
                    .build();
            Request request = new Request.Builder()
                    .url(api.baseUrl() + "/cgi-bin/cgi-upload?" + System.currentTimeMillis())
                    .post(body)
                    .header("Cookie", "sysauth_http=" + token)
                    .header("Referer", api.baseUrl() + "/cgi-bin/luci/admin/system/flash")
                    .build();
            try (Response response = api.clientWithTimeout(300).newCall(request).execute()) {
                if (response.code() == 401) {
                    api.postFailure(cb, new ApiError(ApiError.Type.AUTH_FAILED, "上传未授权"));
                } else if (response.code() >= 200 && response.code() < 300) {
                    api.postSuccess(cb, null);
                } else {
                    api.postFailure(cb, new ApiError(ApiError.Type.NETWORK,
                            "HTTP " + response.code()));
                }
            } catch (IOException e) {
                api.postFailure(cb, new ApiError(ApiError.Type.NETWORK,
                        "网络错误: " + e.getMessage()));
            }
        });
    }
}
