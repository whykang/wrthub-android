package com.whykangkang.wrthub.api;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import androidx.annotation.StringRes;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.whykangkang.wrthub.R;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 终端 AI 助手,对应 iOS Services/AIAssistant.swift。
 *
 * 把「帮我改主机名」这类自然语言转成 OpenWrt 命令,交给用户确认后再执行。
 * 使用 DeepSeek 的 OpenAI 兼容接口 {@code /chat/completions}:
 *  - 输出用 JSON 模式(response_format: json_object)。它**不按 schema 强约束**,
 *    官方要求提示词里出现 "json" 并给出样例,所以解析时要自己兜底校验;
 *  - Key 校验用只读的 {@code GET /models},不生成 token,不花钱。
 *
 * 系统提示词放在 assets/ai_system_prompt.txt,与 iOS 完全一致。
 *
 * **AI 只负责出主意,绝不直接执行。** 命令一律先展示给用户确认。
 */
public final class AiAssistant {

    public static final String PROVIDER_NAME = "DeepSeek";
    private static final String ENDPOINT = "https://api.deepseek.com";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private static final String PREFS = "wrthub_ai";
    private static final String PREFS_SECURE = "wrthub_ai_secure";
    private static final String KEY_ENABLED = "ai.enabled";
    private static final String KEY_MODEL = "ai.deepseek.model";
    private static final String KEY_API_KEY = "deepseek-api-key";

    /** DeepSeek 可选模型:V4 Pro 推理更强,Flash 更快更便宜 */
    public enum Model {
        PRO("deepseek-v4-pro", R.string.ai_model_pro, "V4 Pro"),
        FLASH("deepseek-flash", R.string.ai_model_flash, "Flash");

        public final String id;
        @StringRes
        public final int labelRes;
        /** AI 面板标题旁的短名 */
        public final String shortName;

        Model(String id, int labelRes, String shortName) {
            this.id = id;
            this.labelRes = labelRes;
            this.shortName = shortName;
        }
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            // V4 Pro 会先推理再回答,可能要几十秒,给足超时
            .readTimeout(180, TimeUnit.SECONDS)
            .build();

    private static String systemPrompt;

    private AiAssistant() {
    }

    // =====================================================================
    // 开关与 Key
    // =====================================================================

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Key 走 EncryptedSharedPreferences,和设备密码一样;加密库不可用时退回普通存储 */
    private static SharedPreferences securePrefs(Context context) {
        Context app = context.getApplicationContext();
        try {
            MasterKey key = new MasterKey.Builder(app)
                    .setKeyGenParameterSpec(new KeyGenParameterSpec.Builder(
                            MasterKey.DEFAULT_MASTER_KEY_ALIAS,
                            KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .setKeySize(256)
                            .build())
                    .build();
            return EncryptedSharedPreferences.create(app, PREFS_SECURE, key,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (Exception e) {
            return app.getSharedPreferences(PREFS_SECURE + "_plain", Context.MODE_PRIVATE);
        }
    }

    /** 只有 Key 校验通过后才会被置为 true */
    public static boolean isEnabled(Context c) {
        return prefs(c).getBoolean(KEY_ENABLED, false) && apiKey(c) != null;
    }

    /** 校验通过后调用 */
    public static void enable(Context c, String key) {
        securePrefs(c).edit().putString(KEY_API_KEY, key).apply();
        prefs(c).edit().putBoolean(KEY_ENABLED, true).apply();
    }

    /** 关闭时删掉 Key,不在手机上留着能花钱的钥匙 */
    public static void disable(Context c) {
        securePrefs(c).edit().remove(KEY_API_KEY).apply();
        prefs(c).edit().putBoolean(KEY_ENABLED, false).apply();
    }

    public static String apiKey(Context c) {
        String key = securePrefs(c).getString(KEY_API_KEY, null);
        return key == null || key.isEmpty() ? null : key;
    }

    /** 设置页里显示用:sk-abcd…WXYZ */
    public static String maskedKey(Context c) {
        String key = apiKey(c);
        if (key == null || key.length() <= 12) return null;
        return key.substring(0, 7) + "…" + key.substring(key.length() - 4);
    }

    public static Model model(Context c) {
        String id = prefs(c).getString(KEY_MODEL, Model.FLASH.id);
        for (Model m : Model.values()) if (m.id.equals(id)) return m;
        return Model.FLASH;
    }

    public static void setModel(Context c, Model model) {
        prefs(c).edit().putString(KEY_MODEL, model.id).apply();
    }

    // =====================================================================
    // 错误
    // =====================================================================

    public static final class AiError {
        public enum Kind {
            INVALID_KEY, PERMISSION_DENIED, RATE_LIMITED, INSUFFICIENT_BALANCE, OVERLOADED,
            REFUSED, TRUNCATED, BAD_RESPONSE, SERVER, NETWORK
        }

        public final Kind kind;
        /** BAD_RESPONSE 的原因 / SERVER 的服务端说法 / NETWORK 的异常信息 */
        public final String detail;
        public final int code;

        AiError(Kind kind, String detail, int code) {
            this.kind = kind;
            this.detail = detail;
            this.code = code;
        }

        static AiError of(Kind kind) {
            return new AiError(kind, null, 0);
        }

        /** BAD_RESPONSE 的原因码 → 文案 */
        private String badResponseReason(Context c) {
            if ("empty".equals(detail)) return c.getString(R.string.ai_bad_empty);
            if ("invalid JSON".equals(detail)) return c.getString(R.string.ai_bad_invalid_json);
            if ("missing result".equals(detail)) return c.getString(R.string.ai_bad_missing);
            return c.getString(R.string.ai_bad_not_json);
        }

        public String message(Context c) {
            switch (kind) {
                case INVALID_KEY:
                    return c.getString(R.string.ai_err_invalid_key);
                case PERMISSION_DENIED:
                    return c.getString(R.string.ai_err_permission);
                case RATE_LIMITED:
                    return c.getString(R.string.ai_err_rate_limited);
                case INSUFFICIENT_BALANCE:
                    return c.getString(R.string.ai_err_balance);
                case OVERLOADED:
                    return c.getString(R.string.ai_err_overloaded);
                case REFUSED:
                    return c.getString(R.string.ai_err_refused);
                case TRUNCATED:
                    return c.getString(R.string.ai_err_truncated);
                case BAD_RESPONSE:
                    return c.getString(R.string.ai_err_bad_response, badResponseReason(c));
                case SERVER:
                    return c.getString(R.string.ai_err_server, code, detail);
                default:
                    return c.getString(R.string.ai_err_network, detail);
            }
        }
    }

    public interface Callback<T> {
        void onSuccess(T result);

        void onFailure(AiError error);
    }

    // =====================================================================
    // 校验 Key
    // =====================================================================

    /** 用「列出模型」接口验证 Key。只读,不产生费用。 */
    public static void validate(String key, Callback<Void> cb) {
        Request request = new Request.Builder()
                .url(ENDPOINT + "/models")
                .header("Authorization", "Bearer " + key)
                .get()
                .build();
        CLIENT.newBuilder().readTimeout(20, TimeUnit.SECONDS).build()
                .newCall(request).enqueue(new okhttp3.Callback() {
                    @Override
                    public void onFailure(Call call, IOException e) {
                        post(cb, null, new AiError(AiError.Kind.NETWORK, e.getMessage(), 0));
                    }

                    @Override
                    public void onResponse(Call call, Response response) throws IOException {
                        String body = response.body() != null ? response.body().string() : "";
                        if (response.code() == 200) {
                            post(cb, null, null);
                        } else {
                            post(cb, null, mapStatus(response.code(), body));
                        }
                    }
                });
    }

    // =====================================================================
    // 对话
    // =====================================================================

    /** 命令方案(也用于回答 / 总结:没有命令时只有 explanation) */
    public static final class Plan {
        public static final class Step {
            public final String command;
            public final String description;

            Step(String command, String description) {
                this.command = command;
                this.description = description;
            }
        }

        public final String explanation;
        public final List<Step> steps;
        /** low / medium / high */
        public final String risk;
        public final String notes;

        Plan(String explanation, List<Step> steps, String risk, String notes) {
            this.explanation = explanation;
            this.steps = Collections.unmodifiableList(steps);
            this.risk = risk;
            this.notes = notes;
        }

        public String commandsText() {
            StringBuilder sb = new StringBuilder();
            for (Step s : steps) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(s.command);
            }
            return sb.toString();
        }

        /** 作为对话历史里 assistant 那一轮回传给模型 */
        public String json() {
            JsonObject o = new JsonObject();
            o.addProperty("explanation", explanation);
            JsonArray commands = new JsonArray();
            for (Step s : steps) {
                JsonObject c = new JsonObject();
                c.addProperty("command", s.command);
                c.addProperty("description", s.description);
                commands.add(c);
            }
            o.add("commands", commands);
            o.addProperty("risk", risk);
            o.addProperty("notes", notes);
            return o.toString();
        }
    }

    /** 对话里的一轮 */
    public static final class Turn {
        public final String role;   // user / assistant
        public String text;

        public Turn(String role, String text) {
            this.role = role;
            this.text = text;
        }
    }

    /** 一条命令在终端里的执行结果 */
    public static final class ExecResult {
        public enum Status { EXITED, INTERRUPTED, TIMED_OUT, SKIPPED, LOST }

        public final String command;
        public final String output;
        public final Status status;
        /** status == EXITED 时的退出码 */
        public final int exitCode;

        public ExecResult(String command, String output, Status status, int exitCode) {
            this.command = command;
            this.output = output == null ? "" : output;
            this.status = status;
            this.exitCode = exitCode;
        }

        public boolean succeeded() {
            return status == Status.EXITED && exitCode == 0;
        }
    }

    /** 第一轮提问:带上路由器信息和终端屏幕 */
    public static String firstRequestText(String request, String screen, String routerInfo) {
        return "<router>\n" + routerInfo + "\n</router>\n\n"
                + "<terminal_screen>\n" + screen + "\n</terminal_screen>\n\n"
                + "<request>\n" + request + "\n</request>";
    }

    /** 之后的追问 */
    public static String followUpText(String request) {
        return "<request>\n" + request + "\n</request>";
    }

    /** 执行结果回传给模型,让它总结。输出只留末尾一段,免得上下文太长。 */
    public static String resultsText(List<ExecResult> results) {
        StringBuilder sb = new StringBuilder("<execution_results>\n");
        for (int i = 0; i < results.size(); i++) {
            ExecResult r = results.get(i);
            String status;
            switch (r.status) {
                case EXITED:
                    status = "exit " + r.exitCode;
                    break;
                case INTERRUPTED:
                    status = "interrupted by user (Ctrl+C)";
                    break;
                case TIMED_OUT:
                    status = "timed out, interrupted with Ctrl+C";
                    break;
                case SKIPPED:
                    status = "skipped because an earlier command failed or was interrupted";
                    break;
                default:
                    status = "unknown - the terminal connection was lost";
            }
            String output = r.output;
            if (output.length() > 4000) {
                output = "[...earlier output truncated]\n" + output.substring(output.length() - 4000);
            }
            sb.append("<command index=\"").append(i + 1).append("\" status=\"").append(status)
                    .append("\">\n$ ").append(r.command).append("\n<output>\n").append(output)
                    .append("\n</output>\n</command>\n");
        }
        return sb.append("</execution_results>").toString();
    }

    /** 把整段对话发给 DeepSeek,拿回下一步:回答 / 命令方案 / 执行结果总结。 */
    /**
     * @param autoRun 自动模式开着 —— 命令会不经确认直接执行(高风险的除外),
     *                提示词里要让 AI 知道,出方案时更保守、风险等级标得更严
     */
    public static void respond(Context context, List<Turn> conversation, boolean autoRun,
                               Callback<Plan> cb) {
        String key = apiKey(context);
        if (key == null) {
            MAIN.post(() -> cb.onFailure(AiError.of(AiError.Kind.INVALID_KEY)));
            return;
        }
        JsonArray messages = new JsonArray();
        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content",
                systemPrompt(context).replace("{{LANGUAGE}}", languageInstructions(context)
                        + (autoRun ? "\n\n" + AUTO_RUN_INSTRUCTIONS : "")));
        messages.add(system);
        for (Turn t : conversation) {
            JsonObject m = new JsonObject();
            m.addProperty("role", t.role);
            m.addProperty("content", t.text);
            messages.add(m);
        }
        JsonObject body = new JsonObject();
        body.addProperty("model", model(context).id);
        body.addProperty("max_tokens", 8000);
        JsonObject format = new JsonObject();
        format.addProperty("type", "json_object");
        body.add("response_format", format);
        body.add("messages", messages);

        Request request = new Request.Builder()
                .url(ENDPOINT + "/chat/completions")
                .header("Authorization", "Bearer " + key)
                .post(RequestBody.create(body.toString(), JSON))
                .build();
        CLIENT.newCall(request).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                post(cb, null, new AiError(AiError.Kind.NETWORK, e.getMessage(), 0));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String text = response.body() != null ? response.body().string() : "";
                Object[] out = parseResponse(response.code(), text);
                post(cb, (Plan) out[0], (AiError) out[1]);
            }
        });
    }

    /** 可单测:返回 {Plan, AiError},二者恰有一个非空 */
    static Object[] parseResponse(int code, String text) {
        if (code != 200) return new Object[]{null, mapStatus(code, text)};
        JsonObject choice;
        try {
            JsonObject json = JsonParser.parseString(text).getAsJsonObject();
            choice = json.getAsJsonArray("choices").get(0).getAsJsonObject();
        } catch (Exception e) {
            return new Object[]{null, new AiError(AiError.Kind.BAD_RESPONSE, "not JSON", 0)};
        }
        String finish = str(choice, "finish_reason");
        if ("length".equals(finish)) return new Object[]{null, AiError.of(AiError.Kind.TRUNCATED)};
        if ("content_filter".equals(finish)) {
            return new Object[]{null, AiError.of(AiError.Kind.REFUSED)};
        }
        String content = null;
        if (choice.has("message") && choice.get("message").isJsonObject()) {
            content = str(choice.getAsJsonObject("message"), "content");
        }
        // 官方文档写明 JSON 模式偶尔会返回空内容
        if (content == null || content.trim().isEmpty()) {
            return new Object[]{null, new AiError(AiError.Kind.BAD_RESPONSE, "empty", 0)};
        }
        JsonObject o;
        try {
            o = JsonParser.parseString(content.trim()).getAsJsonObject();
        } catch (Exception e) {
            return new Object[]{null, new AiError(AiError.Kind.BAD_RESPONSE, "invalid JSON", 0)};
        }
        Plan plan = makePlan(o);
        return plan == null
                ? new Object[]{null, new AiError(AiError.Kind.BAD_RESPONSE, "missing result", 0)}
                : new Object[]{plan, null};
    }

    /**
     * 可单测:JSON 对象 → Plan。
     * JSON 模式不按 schema 强约束,字段可能缺、风险等级可能写成别的词,
     * 这里统一兜底:没有说明也没有命令就算解析失败(返回 null);未知的风险等级按「中」处理。
     */
    static Plan makePlan(JsonObject o) {
        List<Plan.Step> steps = new ArrayList<>();
        if (o.has("commands") && o.get("commands").isJsonArray()) {
            for (JsonElement item : o.getAsJsonArray("commands")) {
                // 偶尔会直接给字符串数组,也接受
                if (item.isJsonPrimitive()) {
                    String c = item.getAsString().trim();
                    if (!c.isEmpty()) steps.add(new Plan.Step(c, ""));
                } else if (item.isJsonObject()) {
                    String c = str(item.getAsJsonObject(), "command");
                    if (c == null || c.trim().isEmpty()) continue;
                    String d = str(item.getAsJsonObject(), "description");
                    steps.add(new Plan.Step(c.trim(), d == null ? "" : d));
                }
            }
        }
        String explanation = str(o, "explanation");
        if (explanation == null) explanation = "";
        if (explanation.isEmpty() && steps.isEmpty()) return null;
        String risk = str(o, "risk");
        risk = risk == null ? "" : risk.toLowerCase();
        if (!risk.equals("low") && !risk.equals("medium") && !risk.equals("high")) risk = "medium";
        String notes = str(o, "notes");
        return new Plan(explanation, steps, risk, notes == null ? "" : notes);
    }

    // =====================================================================
    // 私有
    // =====================================================================

    /** 按状态码归类(DeepSeek 文档):401 Key 错、402 余额不足、429 限流、500/503 繁忙 */
    private static AiError mapStatus(int code, String body) {
        switch (code) {
            case 401:
                return AiError.of(AiError.Kind.INVALID_KEY);
            case 402:
                return AiError.of(AiError.Kind.INSUFFICIENT_BALANCE);
            case 403:
                return AiError.of(AiError.Kind.PERMISSION_DENIED);
            case 429:
                return AiError.of(AiError.Kind.RATE_LIMITED);
            case 500:
            case 503:
                return AiError.of(AiError.Kind.OVERLOADED);
            default:
                String message = "HTTP " + code;
                try {
                    JsonObject err = JsonParser.parseString(body).getAsJsonObject()
                            .getAsJsonObject("error");
                    String m = str(err, "message");
                    if (m != null) message = m;
                } catch (Exception ignored) {
                    // 不是 JSON 就只报状态码
                }
                return new AiError(AiError.Kind.SERVER, message, code);
        }
    }

    /**
     * 回答语言跟 App 当前语言走,不跟提问语言走:执行结果那一轮没有提问,
     * 终端输出、主机名里又常有中文,只说「跟提问一致」时英文模式下也会冒出中文。
     * 按界面实际生效的语言判断(values-zh 覆盖所有 zh 语言,其余是英文)。
     */
    private static String languageInstructions(Context context) {
        String lang = context.getResources().getConfiguration().getLocales().get(0).getLanguage();
        String language = "zh".equals(lang) ? "Simplified Chinese" : "English";
        return "Reply language: " + language + ". The app's interface is in " + language
                + ", so write `explanation`, every `description`, and `notes` in " + language
                + ", even when the request, the terminal output, or earlier turns are in another "
                + "language. Commands stay exactly as they must be typed.";
    }

    /** 自动模式下 App 只会把 high 风险的方案停下来等确认,所以风险等级就是唯一的闸门(与 iOS 一致) */
    private static final String AUTO_RUN_INSTRUCTIONS = "Auto-run mode is on: the user chose to let "
            + "the app run your commands immediately, without reviewing them. Only a plan whose "
            + "`risk` is `high` still waits for their approval. So be conservative: check the "
            + "current state with read-only commands before changing anything, never guess missing "
            + "values, and set `risk` to `high` for anything that could lock the user out, drop the "
            + "network or Wi-Fi, reboot, change passwords or firewall rules, touch firmware or "
            + "packages, or delete data.";

    private static synchronized String systemPrompt(Context context) {
        if (systemPrompt == null) {
            try (InputStream in = context.getAssets().open("ai_system_prompt.txt")) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                systemPrompt = out.toString(StandardCharsets.UTF_8.name()).trim();
            } catch (IOException e) {
                systemPrompt = "Reply with a single JSON object with fields explanation, commands, risk, notes.";
            }
        }
        return systemPrompt;
    }

    private static String str(JsonObject o, String key) {
        if (o == null || !o.has(key) || !o.get(key).isJsonPrimitive()) return null;
        return o.get(key).getAsString();
    }

    private static <T> void post(Callback<T> cb, T value, AiError error) {
        MAIN.post(() -> {
            if (error != null) cb.onFailure(error);
            else cb.onSuccess(value);
        });
    }
}
