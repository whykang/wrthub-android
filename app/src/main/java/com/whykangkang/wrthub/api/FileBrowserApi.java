package com.whykangkang.wrthub.api;

import android.content.Context;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.whykangkang.wrthub.manager.FileBrowserAccount;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSink;

/**
 * FileBrowser(filebrowser/filebrowser)HTTP API 客户端。
 *
 * 接口按真机实测确定(v2.28.0,AuthMethod=json、NoAuth=false):
 * <pre>
 *  登录      POST   /api/login                {"username","password","recaptcha":""} → 正文即 JWT
 *  列目录    GET    /api/resources/&lt;path&gt;
 *  新建目录  POST   /api/resources/&lt;path&gt;/?override=false   (路径以 / 结尾表示目录)
 *  新建文件  POST   /api/resources/&lt;path&gt;?override=false
 *  写入      PUT    /api/resources/&lt;path&gt;?override=true     正文即内容
 *  重命名    PATCH  /api/resources/&lt;src&gt;?action=rename&amp;destination=&lt;dst&gt;&amp;override=false
 *  删除      DELETE /api/resources/&lt;path&gt;
 *  下载      GET    /api/raw/&lt;path&gt;           (目录加 ?algo=zip)
 *  分享      POST   /api/share/&lt;path&gt;         {"password","expires","unit"} → {hash,path,userID,expire}
 * </pre>
 *
 * 认证用 {@code X-Auth: &lt;jwt&gt;} 请求头。它与路由器后台是**两套独立账号**,
 * 所以凭据单独存(见 {@link FileBrowserAccount}),不能拿 LuCI 的用户名密码顶上。
 */
public final class FileBrowserApi {

    /** 目录项 */
    public static final class Entry {
        public final String path;
        public final String name;
        public final long size;
        public final boolean isDir;
        public final String modified;
        public final String extension;
        /**
         * 来自搜索结果。
         * 搜索接口只回 {@code {dir, path}},**没有体积和时间** ——
         * 界面得知道这一点,否则会把缺省的 0 和空字符串当成真数据显示出来。
         */
        public final boolean fromSearch;

        Entry(String path, String name, long size, boolean isDir,
              String modified, String extension) {
            this(path, name, size, isDir, modified, extension, false);
        }

        Entry(String path, String name, long size, boolean isDir,
              String modified, String extension, boolean fromSearch) {
            this.path = path;
            this.name = name;
            this.size = size;
            this.isDir = isDir;
            this.modified = modified;
            this.extension = extension;
            this.fromSearch = fromSearch;
        }
    }

    /** 某个路径所在文件系统的容量,来自 GET /api/usage/&lt;path&gt; */
    public static final class Usage {
        public final long total;
        public final long used;

        public Usage(long total, long used) {
            this.total = total;
            this.used = used;
        }

        /** 已用百分比,0-100;总量未知时返回 0 */
        public int percent() {
            if (total <= 0) return 0;
            long pct = used * 100 / total;
            return (int) Math.max(0, Math.min(100, pct));
        }

        public long free() {
            return Math.max(0, total - used);
        }
    }

    public interface Callback<T> {
        void onSuccess(T result);

        void onFailure(String message);
    }

    /** 上传/下载用的流写出口,由调用方提供(走 SAF,不落地到应用私有目录) */
    public interface StreamSource {
        InputStream open() throws IOException;

        long length();

        String contentType();
    }

    public interface StreamSink {
        OutputStream open() throws IOException;
    }

    /**
     * 认证失败的哨兵消息。调用方拿它和 {@code equals} 比,
     * 不要去匹配中文文案 —— 文案会随语言变,匹配会悄悄失效。
     */
    public static final String ERR_AUTH = "__filebrowser_auth_failed__";

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)   // 上传大文件
            .build();

    private final String baseUrl;
    private final String username;
    private final String password;
    /** 登录拿到的 JWT;过期(401)时自动重登一次 */
    private volatile String token;

    public FileBrowserApi(String baseUrl, String username, String password) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.username = username;
        this.password = password;
    }

    /** FileBrowser 首次部署时的默认账号,两者都是 admin */
    public static final String DEFAULT_USER = "admin";
    public static final String DEFAULT_PASSWORD = "admin";

    /** 试一次登录,成功返回 null,失败返回原因(认证失败为 {@link #ERR_AUTH}) */
    public void tryLogin(Callback<Void> cb) {
        EXECUTOR.execute(() -> {
            String err = loginSync();
            if (err == null) {
                post(() -> cb.onSuccess(null));
            } else {
                post(() -> cb.onFailure(err));
            }
        });
    }

    public static FileBrowserApi from(Context context, String host, String port) {
        FileBrowserAccount.Credentials c = FileBrowserAccount.load(context, host);
        return new FileBrowserApi("http://" + host + ":" + port, c.username, c.password);
    }

    // =====================================================================
    // 登录
    // =====================================================================

    /** 同步登录。成功返回 null,失败返回原因。 */
    private String loginSync() {
        JsonObject body = new JsonObject();
        body.addProperty("username", username == null ? "" : username);
        body.addProperty("password", password == null ? "" : password);
        body.addProperty("recaptcha", "");
        Request request = new Request.Builder()
                .url(baseUrl + "/api/login")
                .post(RequestBody.create(body.toString(), JSON))
                .build();
        try (Response response = CLIENT.newCall(request).execute()) {
            if (response.code() == 403 || response.code() == 401) {
                return ERR_AUTH;
            }
            if (!response.isSuccessful()) {
                return "HTTP " + response.code();
            }
            String jwt = response.body() != null ? response.body().string().trim() : "";
            if (jwt.isEmpty()) return "登录响应为空";
            token = jwt;
            return null;
        } catch (IOException e) {
            return "网络错误: " + e.getMessage();
        }
    }

    /** 确保有 token;已有就直接用 */
    private String ensureToken() {
        return token != null ? null : loginSync();
    }

    // =====================================================================
    // 请求执行
    // =====================================================================

    private interface Work<T> {
        T run() throws Exception;
    }

    /** 业务失败(HTTP 非 2xx)用它跳出,和 IO 异常区分开 */
    private static final class ApiFailure extends Exception {
        ApiFailure(String message) {
            super(message);
        }
    }

    private <T> void async(Callback<T> cb, Work<T> work) {
        EXECUTOR.execute(() -> {
            String err = ensureToken();
            if (err != null) {
                post(() -> cb.onFailure(err));
                return;
            }
            try {
                T result = work.run();
                post(() -> cb.onSuccess(result));
            } catch (TokenExpired e) {
                // token 过期:重登一次再试
                token = null;
                String retryErr = ensureToken();
                if (retryErr != null) {
                    post(() -> cb.onFailure(retryErr));
                    return;
                }
                try {
                    T result = work.run();
                    post(() -> cb.onSuccess(result));
                } catch (Exception e2) {
                    post(() -> cb.onFailure(describe(e2)));
                }
            } catch (Exception e) {
                post(() -> cb.onFailure(describe(e)));
            }
        });
    }

    private static final class TokenExpired extends Exception {
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
    }

    private static void post(Runnable action) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(action);
    }

    private Request.Builder authed(String url) {
        return new Request.Builder().url(url).header("X-Auth", token);
    }

    /** 401 抛 TokenExpired 触发重登,其余非 2xx 抛 ApiFailure */
    private static void check(Response response) throws TokenExpired, ApiFailure, IOException {
        if (response.code() == 401) throw new TokenExpired();
        if (response.isSuccessful()) return;
        String detail = response.body() != null ? response.body().string().trim() : "";
        if (detail.length() > 120) detail = detail.substring(0, 120);
        throw new ApiFailure(detail.isEmpty()
                ? "HTTP " + response.code() : "HTTP " + response.code() + ": " + detail);
    }

    // =====================================================================
    // 资源路径编码
    // =====================================================================

    /**
     * 路径按段编码:斜杠要保留成分隔符,其余字符(空格、中文、#、? 等)必须转义,
     * 否则带空格或 # 的文件名会把 URL 截断。
     */
    static String encodePath(String path) {
        if (path == null || path.isEmpty()) return "/";
        StringBuilder sb = new StringBuilder();
        for (String segment : path.split("/", -1)) {
            if (sb.length() > 0) sb.append('/');
            sb.append(encodeSegment(segment));
        }
        String encoded = sb.toString();
        return encoded.startsWith("/") ? encoded : "/" + encoded;
    }

    private static String encodeSegment(String segment) {
        try {
            // URLEncoder 是表单编码:空格成 +、~ 被转义,路径里要还原
            return URLEncoder.encode(segment, "UTF-8")
                    .replace("+", "%20")
                    .replace("%2F", "/");
        } catch (Exception e) {
            return segment;
        }
    }

    /** 把父目录和文件名拼成绝对路径,避免出现双斜杠 */
    public static String join(String parent, String name) {
        if (parent == null || parent.isEmpty()) parent = "/";
        if (!parent.endsWith("/")) parent = parent + "/";
        return parent + name;
    }

    /** 上一级目录;已在根目录返回 null */
    public static String parentOf(String path) {
        if (path == null || path.equals("/") || path.isEmpty()) return null;
        String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        int idx = trimmed.lastIndexOf('/');
        if (idx <= 0) return "/";
        return trimmed.substring(0, idx);
    }

    // =====================================================================
    // 接口
    // =====================================================================

    public void list(String path, Callback<List<Entry>> cb) {
        async(cb, () -> {
            String url = baseUrl + "/api/resources" + encodePath(path);
            if (!url.endsWith("/")) url = url + "/";
            try (Response response = CLIENT.newCall(authed(url).build()).execute()) {
                check(response);
                String text = response.body() != null ? response.body().string() : "{}";
                return parseListing(text);
            }
        });
    }

    /** 可单测:目录列表 JSON → Entry 列表(目录在前,再按名称排) */
    public static List<Entry> parseListing(String text) {
        List<Entry> result = new ArrayList<>();
        JsonObject root;
        try {
            root = JsonParser.parseString(text).getAsJsonObject();
        } catch (Exception e) {
            return result;
        }
        if (!root.has("items") || !root.get("items").isJsonArray()) return result;
        JsonArray items = root.getAsJsonArray("items");
        for (JsonElement el : items) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            result.add(new Entry(
                    str(o, "path"), str(o, "name"), longOf(o, "size"),
                    o.has("isDir") && o.get("isDir").getAsBoolean(),
                    str(o, "modified"), str(o, "extension")));
        }
        result.sort((a, b) -> {
            if (a.isDir != b.isDir) return a.isDir ? -1 : 1;
            return a.name.compareToIgnoreCase(b.name);
        });
        return result;
    }

    /**
     * 搜索。接口 {@code GET /api/search/<path>/?query=<q>},
     * 返回 {@code [{"dir":bool,"path":"相对路径"}]} —— **path 是相对搜索起点的**,
     * 这里直接拼成绝对路径,省得调用方再记一遍起点。
     */
    public void search(String root, String query, Callback<List<Entry>> cb) {
        async(cb, () -> {
            String url = baseUrl + "/api/search" + encodePath(root);
            if (!url.endsWith("/")) url = url + "/";
            url = url + "?query=" + encodeQuery(query);
            try (Response response = CLIENT.newCall(authed(url).build()).execute()) {
                check(response);
                String text = response.body() != null ? response.body().string() : "[]";
                return parseSearch(text, root);
            }
        });
    }

    /** 可单测:搜索结果 JSON → Entry 列表(相对路径拼成绝对路径) */
    public static List<Entry> parseSearch(String text, String root) {
        List<Entry> result = new ArrayList<>();
        JsonArray arr;
        try {
            arr = JsonParser.parseString(text).getAsJsonArray();
        } catch (Exception e) {
            return result;
        }
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            String rel = str(o, "path");
            if (rel.isEmpty()) continue;
            boolean isDir = o.has("dir") && o.get("dir").getAsBoolean();
            String abs = join(root, rel.startsWith("/") ? rel.substring(1) : rel);
            String name = abs.endsWith("/") ? abs.substring(0, abs.length() - 1) : abs;
            int idx = name.lastIndexOf('/');
            if (idx >= 0) name = name.substring(idx + 1);
            result.add(new Entry(abs, name, 0, isDir, "", extensionOf(name), true));
        }
        return result;
    }

    static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot) : "";
    }

    /**
     * 读取文件内容到内存,供预览用。超过 maxBytes 就只读前面那段,
     * 不把几百兆的文件整个吞进内存。
     */
    public void readBytes(String path, int maxBytes, Callback<byte[]> cb) {
        async(cb, () -> {
            String url = baseUrl + "/api/raw" + encodePath(path);
            try (Response response = CLIENT.newCall(authed(url).build()).execute()) {
                check(response);
                ResponseBody body = response.body();
                if (body == null) throw new ApiFailure("响应为空");
                try (InputStream in = body.byteStream()) {
                    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int read;
                    while (out.size() < maxBytes && (read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, Math.min(read, maxBytes - out.size()));
                    }
                    return out.toByteArray();
                }
            }
        });
    }

    /**
     * 当前目录所在文件系统的容量。
     * 接口 {@code GET /api/usage/<path>} → {@code {"total":2358194176,"used":244330496}}
     * (真机实测)。注意是**按路径**问的 —— 不同挂载点容量不一样,
     * 所以每次进目录都要重新取,不能只在根目录取一次。
     */
    public void usage(String path, Callback<Usage> cb) {
        async(cb, () -> {
            String url = baseUrl + "/api/usage" + encodePath(path);
            if (!url.endsWith("/")) url = url + "/";
            try (Response response = CLIENT.newCall(authed(url).build()).execute()) {
                check(response);
                String text = response.body() != null ? response.body().string() : "{}";
                return parseUsage(text);
            }
        });
    }

    /** 可单测:usage 响应 → Usage */
    public static Usage parseUsage(String text) {
        try {
            JsonObject o = JsonParser.parseString(text).getAsJsonObject();
            return new Usage(longOf(o, "total"), longOf(o, "used"));
        } catch (Exception e) {
            return new Usage(0, 0);
        }
    }

    public void createFolder(String path, Callback<Void> cb) {
        async(cb, () -> {
            String url = baseUrl + "/api/resources" + encodePath(path) + "/?override=false";
            try (Response response = CLIENT.newCall(
                    authed(url).post(RequestBody.create(new byte[0], null)).build()).execute()) {
                check(response);
                return null;
            }
        });
    }

    public void createFile(String path, Callback<Void> cb) {
        async(cb, () -> {
            String url = baseUrl + "/api/resources" + encodePath(path) + "?override=false";
            try (Response response = CLIENT.newCall(
                    authed(url).post(RequestBody.create(new byte[0], null)).build()).execute()) {
                check(response);
                return null;
            }
        });
    }

    public void rename(String from, String to, Callback<Void> cb) {
        async(cb, () -> {
            String url = baseUrl + "/api/resources" + encodePath(from)
                    + "?action=rename&destination=" + encodeQuery(encodePath(to))
                    + "&override=false";
            try (Response response = CLIENT.newCall(
                    authed(url).patch(RequestBody.create(new byte[0], null)).build()).execute()) {
                check(response);
                return null;
            }
        });
    }

    public void delete(String path, Callback<Void> cb) {
        async(cb, () -> {
            String url = baseUrl + "/api/resources" + encodePath(path);
            try (Response response = CLIENT.newCall(authed(url).delete().build()).execute()) {
                check(response);
                return null;
            }
        });
    }

    /**
     * 上传:**POST** 原始字节,不能用 PUT。
     *
     * FileBrowser 里 POST = 创建并写入(带 override=true 时也负责覆盖),
     * 而 PUT 只更新已存在的文件 —— 目标不存在直接返回 **404**。
     * 上传新文件走 PUT 就是这么失败的(真机实测:PUT 新文件 404,POST 新文件 200)。
     */
    public void upload(String path, StreamSource source, Callback<Void> cb) {
        async(cb, () -> {
            String url = baseUrl + "/api/resources" + encodePath(path) + "?override=true";
            RequestBody body = new RequestBody() {
                @Override
                public MediaType contentType() {
                    String type = source.contentType();
                    return type == null ? null : MediaType.parse(type);
                }

                @Override
                public long contentLength() {
                    return source.length();
                }

                @Override
                public void writeTo(BufferedSink sink) throws IOException {
                    try (InputStream in = source.open()) {
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            sink.write(buffer, 0, read);
                        }
                    }
                }
            };
            try (Response response = CLIENT.newCall(authed(url).post(body).build()).execute()) {
                check(response);
                return null;
            }
        });
    }

    /** 下载:目录自动打包成 zip */
    public void download(String path, boolean isDir, StreamSink sink, Callback<Void> cb) {
        async(cb, () -> {
            String url = baseUrl + "/api/raw" + encodePath(path) + (isDir ? "?algo=zip" : "");
            try (Response response = CLIENT.newCall(authed(url).build()).execute()) {
                check(response);
                ResponseBody body = response.body();
                if (body == null) throw new ApiFailure("响应为空");
                try (InputStream in = body.byteStream(); OutputStream out = sink.open()) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                    out.flush();
                }
                return null;
            }
        });
    }

    /** 创建分享链接,返回可直接打开的完整地址 */
    public void share(String path, Callback<String> cb) {
        async(cb, () -> {
            String url = baseUrl + "/api/share" + encodePath(path);
            JsonObject body = new JsonObject();
            body.addProperty("password", "");
            body.addProperty("expires", "");
            body.addProperty("unit", "hours");
            try (Response response = CLIENT.newCall(
                    authed(url).post(RequestBody.create(body.toString(), JSON)).build())
                    .execute()) {
                check(response);
                String text = response.body() != null ? response.body().string() : "{}";
                String hash = shareHash(text);
                if (hash == null) throw new ApiFailure("分享响应缺少 hash");
                return baseUrl + "/share/" + hash;
            }
        });
    }

    /** 可单测:分享响应 → hash */
    public static String shareHash(String text) {
        try {
            JsonObject o = JsonParser.parseString(text).getAsJsonObject();
            return o.has("hash") ? o.get("hash").getAsString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String encodeQuery(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    private static long longOf(JsonObject o, String key) {
        try {
            return o.has(key) ? o.get(key).getAsLong() : 0;
        } catch (Exception e) {
            return 0;
        }
    }
}
