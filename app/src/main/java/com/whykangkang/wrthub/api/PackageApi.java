package com.whykangkang.wrthub.api;


import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.whykangkang.wrthub.model.PackageInfo;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 软件包管理 API,对应 iOS OpenWrtAPI 的「软件包管理」分区。
 *
 * 走 cgi-io 的 exec 端点(/cgi-bin/cgi-exec),与 LuCI 网页端完全一致:
 *   POST body: sessionid=&lt;ubus token&gt;&amp;command=&lt;完整命令&gt;
 *   响应体:Base64 编码的命令输出
 * 不能用 ubus file.exec —— 多数固件的 rpcd ACL 不授权直接执行 /bin/opkg。
 *
 * 重要:cgi-io 的 exec 仍按「完整命令行」匹配 rpcd 的 file ACL,并非粗粒度放行。
 * luci-app-opkg 的 ACL 形如 "/usr/libexec/opkg-call update *"(带通配符),
 * 因此 update 必须带参数,否则匹配失败返回 403。命令形式需与网页端保持一致。
 */
public final class PackageApi {

    /**
     * 包管理封装脚本。不同 LuCI 版本脚本名不同,需探测。
     *
     * 24.10+ 的 package-manager-call **同时支持 opkg 和 apk** —— 脚本自己看
     * /usr/bin/apk 在不在来决定调哪个。所以判断用不用 apk 不能靠脚本名,
     * 只能看输出长什么样(见 {@link #parsePackageList})。
     */
    public enum Backend {
        /** luci-app-opkg(OpenWrt 23.05 及更早)。ACL 是 "update *",必须带参数 */
        OPKG_CALL("/usr/libexec/opkg-call", " --force-removal-of-dependent-packages"),
        /** luci-app-package-manager(24.10+)。ACL 是精确的 "update",**不能带参数** */
        PACKAGE_MANAGER_CALL("/usr/libexec/package-manager-call", "");

        public final String script;
        /** 刷新软件源时附加的参数,为空表示不能带 */
        public final String updateSuffix;

        Backend(String script, String updateSuffix) {
            this.script = script;
            this.updateSuffix = updateSuffix;
        }
    }

    private static final MediaType FORM =
            MediaType.get("application/x-www-form-urlencoded");

    /** baseUrl -> 探测到的后端脚本 */
    private static final Map<String, Backend> backendCache = new HashMap<>();
    /** baseUrl -> 可用包列表(输出上万条 / 数 MB,按设备缓存) */
    private static final Map<String, List<PackageInfo>> availableCache = new HashMap<>();

    private PackageApi() {
    }

    private static OpenWrtApi api() {
        return OpenWrtApi.getInstance();
    }

    /** 切换设备或刷新软件源后清缓存 */
    public static synchronized void clearCache() {
        availableCache.remove(api().baseUrl());
    }

    public static synchronized void clearAllCaches() {
        availableCache.clear();
        backendCache.clear();
    }

    // =====================================================================
    // 命令
    // =====================================================================

    public static void getInstalledPackages(ApiCallback<List<PackageInfo>> cb) {
        exec(backend -> backend.script + " list-installed", 60, Retry.ON_LOCK,
                new ApiCallback<String>() {
            @Override
            public void onSuccess(String text) {
                cb.onSuccess(parsePackageList(text));
            }

            @Override
            public void onFailure(ApiError error) {
                cb.onFailure(error);
            }
        });
    }

    public static void getAvailablePackages(boolean forceReload,
                                            ApiCallback<List<PackageInfo>> cb) {
        if (!forceReload) {
            List<PackageInfo> cached;
            synchronized (PackageApi.class) {
                cached = availableCache.get(api().baseUrl());
            }
            if (cached != null && !cached.isEmpty()) {
                cb.onSuccess(cached);
                return;
            }
        }
        exec(backend -> backend.script + " list-available", 120, Retry.ON_LOCK,
                new ApiCallback<String>() {
            @Override
            public void onSuccess(String text) {
                List<PackageInfo> list = parsePackageList(text);
                synchronized (PackageApi.class) {
                    availableCache.put(api().baseUrl(), list);
                }
                cb.onSuccess(list);
            }

            @Override
            public void onFailure(ApiError error) {
                cb.onFailure(error);
            }
        });
    }

    /**
     * 刷新软件源。两个后端的 ACL 写法相反,必须分别对待:
     *   opkg-call              → "update *"  不带参数会 403
     *   package-manager-call   → "update"    精确匹配,**带了参数**反而 403
     */
    public static void updatePackageLists(ApiCallback<String> cb) {
        exec(backend -> backend.script + " update" + backend.updateSuffix, 120, cb);
    }

    public static void installPackage(String name, ApiCallback<String> cb) {
        exec(backend -> backend.script + " install " + name, 180, cb);
    }

    public static void removePackage(String name, ApiCallback<String> cb) {
        exec(backend -> backend.script + " remove " + name, 120, checkRemoveOutput(cb));
    }

    /**
     * 级联卸载:连同依赖它的包一起删。调用方必须已把影响明确告知用户。
     *
     * 参数顺序照抄网页端实际发出的命令(抓包为证):
     *   opkg-call remove --force-removal-of-dependent-packages &lt;name&gt;
     * 网页端还会带 --autoremove(顺带清理变成孤儿的依赖),这里**不带** ——
     * 级联弹窗已经把要删的包逐个列给用户了,再悄悄多删几个就对不上号。
     */
    public static void removePackageWithDependents(String name, ApiCallback<String> cb) {
        exec(backend -> backend.script
                + " remove --force-removal-of-dependent-packages " + name,
                180, checkRemoveOutput(cb));
    }

    /**
     * opkg 卸载失败时**不一定**返回非零退出码 —— 被依赖、找不到包这些情况,
     * 它照样 exit 0,只在输出里写「Collected errors: ... Cannot remove package」。
     * 只看 code 就会报「卸载成功」而包还在。这里按输出再判一次。
     *
     * 只对 remove 生效:update / install 的输出里出现 Collected errors
     * 往往只是软件源签名之类的告警,不代表整条命令失败。
     */
    private static ApiCallback<String> checkRemoveOutput(ApiCallback<String> cb) {
        return new ApiCallback<String>() {
            @Override
            public void onSuccess(String output) {
                if (removeFailed(output)) {
                    cb.onFailure(new ApiError(ApiError.Type.UBUS_ERROR, output.trim()));
                } else {
                    cb.onSuccess(output);
                }
            }

            @Override
            public void onFailure(ApiError error) {
                cb.onFailure(error);
            }
        };
    }

    /** 退出码为 0 但输出表明卸载其实没做成 */
    public static boolean removeFailed(String output) {
        if (output == null || output.isEmpty()) return false;
        return output.contains("Cannot remove package")
                || output.contains("opkg_remove_pkg")
                || output.contains("print_dependents_warning")
                || output.contains("No packages removed")
                || output.contains("Collected errors:")
                // apk 的说法
                || output.contains("unable to select packages")
                || output.contains("World updated, but the following")
                || output.contains("ERROR: ");
    }

    // =====================================================================
    // 解析
    // =====================================================================

    /**
     * 解析包列表。兼容三种输出:
     *  1) **apk 的 JSON 数组**(24.10+ 且装了 apk):
     *       apk query --fields all --format json ... → [{"name":"busybox","version":"1.37.0-r2",...}]
     *  2) Debian control 段落格式(opkg 的 list-installed):
     *       Package: kmod-nft-tproxy
     *       Version: 5.15.167-1
     *       (空行分隔)
     *  3) 简单格式:"busybox - 1.36.1-1"
     *
     * 判别方式和 LuCI 网页端一致 —— 看首字符是不是 '[',不靠脚本名猜。
     */
    public static List<PackageInfo> parsePackageList(String text) {
        List<PackageInfo> result = new ArrayList<>();
        if (text == null) return result;
        String head = text.trim();
        if (head.startsWith("[")) {
            return parseApkJson(head);
        }
        if (text.contains("Package:")) {
            String[] holder = {"", "", "", ""};   // name, version, desc, section
            int[] size = {0};
            boolean[] inDescription = {false};
            Runnable flush = () -> {
                if (!holder[0].isEmpty()) {
                    result.add(new PackageInfo(holder[0], holder[1], holder[2],
                            holder[3], size[0]));
                }
                holder[0] = "";
                holder[1] = "";
                holder[2] = "";
                holder[3] = "";
                size[0] = 0;
                inDescription[0] = false;
            };
            for (String rawLine : text.split("\n", -1)) {
                String line = rawLine.trim();
                if (line.isEmpty()) {
                    flush.run();
                    continue;
                }
                // 描述续行:原始行以空白开头,且当前正处在 Description 字段里
                if (inDescription[0] && (rawLine.startsWith(" ") || rawLine.startsWith("\t"))) {
                    if (!line.equals(".")) {
                        holder[2] = holder[2].isEmpty() ? line : holder[2] + " " + line;
                    }
                    continue;
                }
                inDescription[0] = false;
                if (line.startsWith("Package:")) {
                    if (!holder[0].isEmpty()) flush.run();   // 上一段没有空行分隔时也要收尾
                    holder[0] = value(line, "Package:");
                } else if (line.startsWith("Version:")) {
                    holder[1] = value(line, "Version:");
                } else if (line.startsWith("Description:")) {
                    holder[2] = value(line, "Description:");
                    inDescription[0] = true;
                } else if (line.startsWith("Section:")) {
                    holder[3] = value(line, "Section:");
                } else if (line.startsWith("Installed-Size:")) {
                    try {
                        size[0] = Integer.parseInt(value(line, "Installed-Size:"));
                    } catch (NumberFormatException ignored) {
                        size[0] = 0;
                    }
                }
            }
            flush.run();
        } else {
            for (String rawLine : text.split("\n", -1)) {
                String line = rawLine.trim();
                if (line.isEmpty()) continue;
                int idx = line.indexOf(" - ");
                if (idx > 0) {
                    String name = line.substring(0, idx);
                    String rest = line.substring(idx + 3);
                    String version = rest.split(" ")[0];
                    result.add(new PackageInfo(name, version));
                } else {
                    result.add(new PackageInfo(line.split(" ")[0], ""));
                }
            }
        }
        Collections.sort(result, (a, b) -> a.searchKey.compareTo(b.searchKey));
        return result;
    }

    /**
     * apk 的 {@code query --fields all --format json} 输出。
     * 字段名按 LuCI 网页端的用法取:name / version / description,
     * 体积优先 installed-size(装机占用),没有再退到 file-size(下载包大小)。
     */
    static List<PackageInfo> parseApkJson(String text) {
        List<PackageInfo> result = new ArrayList<>();
        JsonArray arr;
        try {
            arr = JsonParser.parseString(text).getAsJsonArray();
        } catch (Exception e) {
            return result;
        }
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            String name = jsonStr(o, "name");
            if (name.isEmpty()) continue;
            int size = jsonInt(o, "installed-size");
            if (size == 0) size = jsonInt(o, "file-size");
            if (size == 0) size = jsonInt(o, "size");
            result.add(new PackageInfo(name, jsonStr(o, "version"),
                    jsonStr(o, "description"), jsonStr(o, "section"), size));
        }
        Collections.sort(result, (a, b) -> a.searchKey.compareTo(b.searchKey));
        return result;
    }

    private static String jsonStr(JsonObject o, String key) {
        try {
            return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static int jsonInt(JsonObject o, String key) {
        try {
            return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsInt() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static String value(String line, String field) {
        return line.substring(field.length()).trim();
    }

    private static final Pattern PACKAGE_NAME =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._+-]*$");

    /**
     * 从包管理器的拒绝输出里解析出「依赖它的软件包」。
     *
     * opkg 典型输出:
     *   * print_dependents_warning: Package luci-app-eqos is depended upon by packages:
     *   * print_dependents_warning:     luci-i18n-eqos-zh-cn
     * 说明性句子都含空格,包名不含 —— 以此把两者分开。
     *
     * apk 的输出格式完全不同("required by: pkg-1.2.3-r0"),单独走一支。
     */
    public static List<String> parseBlockingDependents(String output) {
        List<String> names = new ArrayList<>();
        if (output == null) return names;
        if (output.contains("are not removed due to")) {
            return parseApkNotRemoved(output);
        }
        if (!output.contains("print_dependents_warning") && output.contains("required by:")) {
            return parseApkDependents(output);
        }
        String marker = "print_dependents_warning:";
        for (String line : output.split("\n", -1)) {
            int idx = line.lastIndexOf(marker);
            if (idx < 0) continue;
            String token = line.substring(idx + marker.length()).trim();
            if (token.isEmpty() || token.contains(" ")) continue;
            if (!PACKAGE_NAME.matcher(token).matches()) continue;
            if (!names.contains(token)) names.add(token);
        }
        return names;
    }

    /**
     * apk 卸载被拦下时的输出(真机实测):
     * <pre>
     * World updated, but the following packages are not removed due to:
     *   luci-app-filebrowser: luci-i18n-filebrowser-zh-cn
     *
     * OK: 60.3 MiB in 224 packages
     * </pre>
     * 冒号左边是想删的包,右边是拦着它的包(可能多个,空格分隔)。
     */
    static List<String> parseApkNotRemoved(String output) {
        List<String> names = new ArrayList<>();
        boolean inBlock = false;
        for (String line : output.split("\n", -1)) {
            if (line.contains("are not removed due to")) {
                inBlock = true;
                continue;
            }
            if (!inBlock) continue;
            String trimmed = line.trim();
            // 空行或收尾的 "OK: ..." 统计行表示这一段结束
            if (trimmed.isEmpty() || trimmed.startsWith("OK:")) {
                if (!names.isEmpty()) break;
                continue;
            }
            int colon = trimmed.indexOf(':');
            if (colon < 0) continue;
            for (String token : trimmed.substring(colon + 1).trim().split("[\\s,]+")) {
                String name = token.trim();
                if (name.isEmpty() || names.contains(name)) continue;
                if (!PACKAGE_NAME.matcher(name).matches()) continue;
                names.add(name);
            }
        }
        return names;
    }

    /**
     * apk 的依赖拒绝输出,形如:
     *   ERROR: unable to select packages:
     *     luci-app-eqos-1.0-r1:
     *       required by: luci-i18n-eqos-zh-cn-1.0-r1[luci-app-eqos]
     * 取 required by: 后面的包名,并把 apk 带的 "-版本-r版本" 后缀剥掉。
     */
    static List<String> parseApkDependents(String output) {
        List<String> names = new ArrayList<>();
        for (String line : output.split("\n", -1)) {
            int idx = line.indexOf("required by:");
            if (idx < 0) continue;
            String rest = line.substring(idx + "required by:".length()).trim();
            for (String token : rest.split("[\\s,]+")) {
                // apk 会在后面用 [依赖名] 标注,先去掉
                int bracket = token.indexOf('[');
                if (bracket > 0) token = token.substring(0, bracket);
                String name = stripApkVersion(token);
                if (name.isEmpty() || names.contains(name)) continue;
                if (!PACKAGE_NAME.matcher(name).matches()) continue;
                names.add(name);
            }
        }
        return names;
    }

    /** "luci-app-eqos-1.0-r1" -> "luci-app-eqos";没有版本后缀就原样返回 */
    static String stripApkVersion(String token) {
        if (token == null) return "";
        // 版本段以 -<数字开头> 起,末尾是 -r<数字>
        java.util.regex.Matcher m =
                Pattern.compile("^(.*?)-\\d[^-]*(?:-r\\d+)?$").matcher(token.trim());
        return m.matches() ? m.group(1) : token.trim();
    }

    /** 这次失败是不是「被别的包依赖」导致的 */
    public static boolean isDependencyBlocked(String text) {
        if (text == null) return false;
        return text.contains("depended upon by")
                || text.contains("print_dependents_warning")
                || text.contains("force-removal-of-dependent-packages")
                // apk 卸载被依赖拦下时的原话(真机实测):
                //   World updated, but the following packages are not removed due to:
                //     luci-app-filebrowser: luci-i18n-filebrowser-zh-cn
                || text.contains("are not removed due to")
                // apk 安装期的冲突提示
                || text.contains("required by:");
    }

    // =====================================================================
    // cgi-io exec 通道
    // =====================================================================

    private interface CommandBuilder {
        String build(Backend backend);
    }

    /**
     * 撞锁后能不能自动重试。
     * **只有只读的列表能重试** —— install/remove/update 重试一次就是再执行一次,
     * 半装完的包再装一遍、或者重复触发软件源更新,都比直接报错糟糕。
     */
    private enum Retry { ON_LOCK, NEVER }

    /**
     * 包管理命令必须**串行**执行。
     *
     * apk 对数据库上独占锁,而 package-manager-call 的 flock 只包住
     * install/update/remove,**不包 list-installed / list-available**。
     * 两条命令撞在一起时后来的那条直接报:
     *   ERROR: Unable to lock database: Resource temporarily unavailable
     * 界面上同时拉「已安装」和「可安装」正好会踩到。opkg 也有全局锁,同理。
     */
    private static final java.util.concurrent.ExecutorService PACKAGE_QUEUE =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    /** 撞锁时重试几次 —— 前一条命令通常一两秒就放锁了 */
    private static final int LOCK_RETRIES = 3;

    private static void exec(CommandBuilder builder, int timeoutSec, ApiCallback<String> cb) {
        exec(builder, timeoutSec, Retry.NEVER, cb);
    }

    private static void exec(CommandBuilder builder, int timeoutSec, Retry retry,
                             ApiCallback<String> cb) {
        PACKAGE_QUEUE.execute(() -> {
            // 排到队了才真正发请求;用闩把异步回调拉回同步,保证队列真的是串行的
            java.util.concurrent.CountDownLatch latch =
                    new java.util.concurrent.CountDownLatch(1);
            execSerial(builder, timeoutSec, retry, 0, new ApiCallback<String>() {
                @Override
                public void onSuccess(String result) {
                    cb.onSuccess(result);
                    latch.countDown();
                }

                @Override
                public void onFailure(ApiError error) {
                    cb.onFailure(error);
                    latch.countDown();
                }
            });
            try {
                latch.await(timeoutSec + 30L, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private static void execSerial(CommandBuilder builder, int timeoutSec, Retry retry,
                                   int attempt, ApiCallback<String> cb) {
        detectBackend(new ApiCallback<Backend>() {
            @Override
            public void onSuccess(Backend backend) {
                cgiExec(builder.build(backend), timeoutSec, new ApiCallback<String>() {
                    @Override
                    public void onSuccess(String result) {
                        if (retryIfLocked(result)) return;
                        cb.onSuccess(result);
                    }

                    @Override
                    public void onFailure(ApiError error) {
                        if (retryIfLocked(error.getMessage())) return;
                        cb.onFailure(error);
                    }

                    /** 别的进程(网页端、定时任务)也可能占着锁,退避后再试 */
                    private boolean retryIfLocked(String text) {
                        if (retry != Retry.ON_LOCK) return false;
                        if (!isDatabaseLocked(text) || attempt >= LOCK_RETRIES) return false;
                        try {
                            Thread.sleep((attempt + 1) * 1200L);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return false;
                        }
                        execSerial(builder, timeoutSec, retry, attempt + 1, cb);
                        return true;
                    }
                });
            }

            @Override
            public void onFailure(ApiError error) {
                cb.onFailure(error);
            }
        });
    }

    /** 这次失败是不是「数据库被占着」。apk 和 opkg 的说法不一样。 */
    public static boolean isDatabaseLocked(String text) {
        if (text == null || text.isEmpty()) return false;
        return text.contains("Unable to lock database")
                || text.contains("Failed to open apk database")
                || text.contains("Could not lock")
                || text.contains("Resource temporarily unavailable");
    }

    /** 探测这台设备用哪个包管理脚本 */
    private static void detectBackend(ApiCallback<Backend> cb) {
        Backend cached;
        synchronized (PackageApi.class) {
            cached = backendCache.get(api().baseUrl());
        }
        if (cached != null) {
            cb.onSuccess(cached);
            return;
        }
        probeBackend(0, cb);
    }

    private static void probeBackend(int index, ApiCallback<Backend> cb) {
        Backend[] candidates = Backend.values();
        if (index >= candidates.length) {
            cb.onFailure(new ApiError(ApiError.Type.UBUS_ERROR, "NO_BACKEND"));
            return;
        }
        Backend backend = candidates[index];
        api().fileStat(backend.script, new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                if (result.has("type")) {
                    synchronized (PackageApi.class) {
                        backendCache.put(api().baseUrl(), backend);
                    }
                    cb.onSuccess(backend);
                } else {
                    probeBackend(index + 1, cb);
                }
            }

            @Override
            public void onFailure(ApiError error) {
                probeBackend(index + 1, cb);
            }
        });
    }

    /** 通过 cgi-io exec 执行命令,返回解码后的输出文本 */
    private static void cgiExec(String command, int timeoutSec, ApiCallback<String> cb) {
        OpenWrtApi api = api();
        api.withSession(cb, () -> {
            String token = api.sessionToken();
            long stamp = System.currentTimeMillis();
            // command 需整体百分号编码(与网页端一致:空格 -> %20,斜杠 -> %2F)
            String encoded = percentEncodeAll(command);
            Request request = new Request.Builder()
                    .url(api.baseUrl() + "/cgi-bin/cgi-exec?" + stamp)
                    .post(RequestBody.create("sessionid=" + token + "&command=" + encoded, FORM))
                    .build();
            String body;
            try (Response response = api.clientWithTimeout(timeoutSec).newCall(request).execute()) {
                if (response.code() != 200) {
                    api.postFailure(cb, new ApiError(ApiError.Type.NETWORK,
                            "HTTP " + response.code()));
                    return;
                }
                body = response.body() != null ? response.body().string() : "";
            } catch (IOException e) {
                api.postFailure(cb, new ApiError(ApiError.Type.NETWORK,
                        "网络错误: " + e.getMessage()));
                return;
            }
            if (body.isEmpty()) {
                api.postSuccess(cb, "");
                return;
            }
            // 响应体为 Base64;个别固件可能直接返回明文,两种都兼容
            String text = decodeBase64OrRaw(body);
            // 响应有两种形态:
            //   list-*                  → 纯文本(control 格式)
            //   update/install/remove   → JSON {"code":0,"stdout":"...","stderr":"..."}
            try {
                JsonObject json = JsonParser.parseString(text).getAsJsonObject();
                if (json.has("code")) {
                    int code = json.get("code").getAsInt();
                    String stdout = json.has("stdout") ? json.get("stdout").getAsString() : "";
                    String stderr = json.has("stderr") ? json.get("stderr").getAsString() : "";
                    if (code == 0) {
                        api.postSuccess(cb, stdout);
                    } else {
                        String message = stderr.isEmpty() ? stdout : stderr;
                        api.postFailure(cb, new ApiError(ApiError.Type.UBUS_ERROR,
                                message.trim(), code));
                    }
                    return;
                }
            } catch (Exception ignored) {
                // 不是 JSON,按纯文本处理
            }
            api.postSuccess(cb, text);
        });
    }

    /**
     * cgi-exec 的响应体多数固件是 Base64,少数直接回明文,两种都要兼容。
     *
     * 关键是**别乱解**:把一段明文当 Base64 解出来照样"成功",只是变成一堆乱码,
     * 然后 JSON 解析失败、错误信息全是方块 —— 卸载失败的提示就这么变成天书的。
     * 所以这里三道关:开头像 JSON/文本就直接按原文;字符集不合法不解;
     * 解出来不像文本(UTF-8 替换符或控制字符太多)就退回原文。
     */
    static String decodeBase64OrRaw(String body) {
        if (body == null) return "";
        String trimmed = body.trim();
        if (trimmed.isEmpty()) return body;

        // JSON 包裹或 control 段落格式,本来就是明文
        char first = trimmed.charAt(0);
        if (first == '{' || first == '[') return body;
        if (!looksLikeBase64(trimmed)) return body;

        byte[] decoded;
        try {
            decoded = java.util.Base64.getDecoder().decode(stripWhitespace(trimmed));
        } catch (IllegalArgumentException e) {
            return body;
        }
        if (decoded.length == 0) return body;

        String text = new String(decoded, StandardCharsets.UTF_8);
        return looksLikeText(text) ? text : body;
    }

    /** 只含 Base64 字符集(允许换行),且去掉空白后长度是 4 的倍数 */
    static boolean looksLikeBase64(String s) {
        int count = 0;
        boolean padding = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r' || c == ' ' || c == '\t') continue;
            if (c == '=') {
                padding = true;
                count++;
                continue;
            }
            if (padding) return false;          // '=' 之后不该再有数据
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '+' || c == '/';
            if (!ok) return false;
            count++;
        }
        return count >= 4 && count % 4 == 0;
    }

    private static String stripWhitespace(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\n' && c != '\r' && c != ' ' && c != '\t') sb.append(c);
        }
        return sb.toString();
    }

    /** 解码结果像不像人能读的文本:替换符和控制字符都不该多 */
    static boolean looksLikeText(String text) {
        if (text.isEmpty()) return false;
        int bad = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\uFFFD') {
                bad++;
            } else if (c < 0x20 && c != '\n' && c != '\r' && c != '\t') {
                bad++;
            }
        }
        return bad * 20 <= text.length();       // 坏字符超过 5% 就认定不是文本
    }

    /** 与 iOS 的 addingPercentEncoding(withAllowedCharacters: .alphanumerics) 等价 */
    static String percentEncodeAll(String s) {
        StringBuilder sb = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) {
                sb.append((char) c);
            } else {
                sb.append('%').append(String.format(Locale.US, "%02X", c));
            }
        }
        return sb.toString();
    }
}
