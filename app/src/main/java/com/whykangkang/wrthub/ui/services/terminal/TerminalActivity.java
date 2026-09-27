package com.whykangkang.wrthub.ui.services.terminal;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.webkit.HttpAuthHandler;
import android.webkit.JavascriptInterface;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.ColorRes;
import androidx.annotation.DrawableRes;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.whykangkang.wrthub.R;
import com.whykangkang.wrthub.api.AiAssistant;
import com.whykangkang.wrthub.api.ApiCallback;
import com.whykangkang.wrthub.api.ApiError;
import com.whykangkang.wrthub.api.OpenWrtApi;
import com.whykangkang.wrthub.api.TtydApi;
import com.whykangkang.wrthub.manager.RouterDeviceManager;
import com.whykangkang.wrthub.model.RouterDevice;
import com.whykangkang.wrthub.ui.settings.AiSettingsActivity;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * AI 终端(luci-app-ttyd),对应 iOS TerminalViewController。
 *
 * 与网页端 term.js 的做法一样:直接加载 ttyd 自带的 xterm.js 页面
 * {@code http://路由器:端口}(配了 url_override 就用它)。在此之上:
 *  - 自动登录:ttyd 启动的是 /bin/login,识别到登录/密码提示就用当前设备的账号密码填上;
 *  - 快捷键栏:补上手机键盘没有的 Esc / Tab / Ctrl / 方向键 / 粘贴;
 *  - 右上角 ✦ 打开 AI 对话面板,AI 给出的命令经用户确认后在这里逐条执行并抓回输出。
 *
 * 桥接脚本 assets/ttyd_bridge.js 与 iOS 同一份逻辑。
 */
public class TerminalActivity extends AppCompatActivity implements TerminalAiSheet.Runner {

    private static final String PREFS = "terminal";
    private static final String KEY_FONT = "terminal.fontSize";
    private static final String KEY_AUTO_LOGIN = "terminal.autoLogin";
    private static final String BG_HEX = "#121419";

    /** 单条 AI 命令最长等多久,超时自动 Ctrl+C */
    private static final long AI_COMMAND_TIMEOUT_MS = 180_000;

    private enum Status {
        CONNECTING(R.string.term_status_connecting, R.color.ios_yellow),
        WAITING_LOGIN(R.string.term_status_waiting_login, R.color.ios_orange),
        LOGGING_IN(R.string.term_status_logging_in, R.color.ios_yellow),
        READY(R.string.term_status_connected, R.color.ios_green),
        LOGIN_FAILED(R.string.term_status_login_failed, R.color.ios_red),
        FAILED(R.string.term_status_failed, R.color.ios_red);

        final int textRes;
        final int colorRes;

        Status(int textRes, int colorRes) {
            this.textRes = textRes;
            this.colorRes = colorRes;
        }
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Gson gson = new Gson();

    private WebView webView;
    private View statusDot;
    private TextView statusText;
    private View errorView;
    private TextView errorText;
    private View spinner;
    private TextView banner;
    private TextView ctrlKey;

    private TtydApi.Config config = new TtydApi.Config();
    private boolean ctrlOn;
    private int httpAuthAttempts;
    private String bridgeScript;
    private SharedPreferences prefs;

    private TerminalAiSheet aiSheet;
    private AiJob aiJob;

    // =====================================================================
    // 生命周期
    // =====================================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_terminal);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        bridgeScript = readAsset("ttyd_bridge.js");

        statusDot = findViewById(R.id.status_dot);
        statusText = findViewById(R.id.status_text);
        errorView = findViewById(R.id.error_view);
        errorText = findViewById(R.id.error_text);
        spinner = findViewById(R.id.spinner);
        banner = findViewById(R.id.banner);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_ai).setOnClickListener(v -> openAI());
        findViewById(R.id.btn_menu).setOnClickListener(this::showMenu);
        findViewById(R.id.btn_retry).setOnClickListener(v -> reconnect());

        setupWebView();
        setupKeyBar();
        load();
    }

    @Override
    protected void onDestroy() {
        if (aiJob != null) {
            handler.removeCallbacks(aiJob.poller);
            aiJob = null;
        }
        if (aiSheet != null) aiSheet.dismiss();
        if (webView != null) {
            webView.removeJavascriptInterface("WrtHubAndroid");
            webView.destroy();
        }
        super.onDestroy();
    }

    private int fontSize() {
        return prefs.getInt(KEY_FONT, 10);
    }

    private boolean autoLogin() {
        return prefs.getBoolean(KEY_AUTO_LOGIN, true);
    }

    // =====================================================================
    // 终端
    // =====================================================================

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void setupWebView() {
        webView = findViewById(R.id.web_view);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setSupportZoom(false);
        webView.setBackgroundColor(getColor(R.color.term_bg));
        // 终端自己处理滚动,外层再回弹会和 xterm 的滚动打架
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.addJavascriptInterface(new JsBridge(this), "WrtHubAndroid");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                // 与 iOS 在 atDocumentEnd 注入等价;脚本自己会等 window.term 出现
                view.evaluateJavascript(bridgeScript, null);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                if (!request.isForMainFrame()) return;
                // ttyd 只绑在 LAN 口(常见配置 interface=@lan),不在同一网络时连不上
                fail(getString(R.string.term_err_connect, String.valueOf(error.getDescription()))
                        + "\n\n" + getString(R.string.term_err_lan_hint));
            }

            /** 配了 credential(Basic 认证)就自动带上,和浏览器弹窗输入一个效果 */
            @Override
            public void onReceivedHttpAuthRequest(WebView view, HttpAuthHandler authHandler,
                                                  String host, String realm) {
                String credential = config.credential;
                if (credential != null && httpAuthAttempts == 0) {
                    httpAuthAttempts++;
                    int colon = credential.indexOf(':');
                    authHandler.proceed(credential.substring(0, colon),
                            credential.substring(colon + 1));
                } else {
                    authHandler.cancel();
                    fail(getString(R.string.term_err_auth));
                }
            }

            /**
             * 开了 SSL 的 ttyd 一般是自签证书。安卓上不放行证书错误(应用商店也不允许
             * 无条件放行),提示用户改用 HTTP。
             */
            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler sslHandler,
                                           SslError error) {
                sslHandler.cancel();
                fail(getString(R.string.term_err_ssl));
            }
        });
        // 旋转屏幕 / 弹出键盘后让 xterm 重新算行列,否则右侧和底部会被截掉
        webView.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) {
                webView.evaluateJavascript("window.__whFit && window.__whFit()", null);
            }
        });
    }

    /** ttyd 页面发来的消息(由 JsBridge 转到主线程) */
    private void onPageMessage(String body) {
        switch (body) {
            case "ready":
                pageReady();
                break;
            case "ctrlOff":
                setCtrl(false);
                break;
            case "loggingIn":
                setStatus(Status.LOGGING_IN, null);
                break;
            case "noLogin":
                setStatus(Status.READY, null);
                break;
            case "loginFailed":
                setStatus(Status.LOGIN_FAILED, null);
                showBanner(getString(R.string.term_login_failed_banner));
                break;
            case "loginTimeout":
                setStatus(Status.FAILED, null);
                showBanner(getString(R.string.term_login_timeout_banner));
                break;
            default:
                if (body.startsWith("loggedIn:")) {
                    // 状态栏直接显示 shell 提示符,如 root@ImmortalWrt:~#
                    String prompt = body.substring("loggedIn:".length()).trim();
                    setStatus(Status.READY, prompt.isEmpty() ? null : prompt);
                }
        }
    }

    /** 页面就绪后:套主题和字号;开着自动登录就把当前设备的账号交给页面里的状态机 */
    private void pageReady() {
        spinner.setVisibility(View.GONE);
        webView.evaluateJavascript("window.__whStyle && window.__whStyle("
                + js(BG_HEX) + ", " + fontSize() + ")", null);

        RouterDevice device = RouterDeviceManager.getInstance(this).getCurrentDevice();
        if (!autoLogin() || device == null || device.getUsername() == null
                || device.getUsername().isEmpty()) {
            setStatus(Status.WAITING_LOGIN, null);
            return;
        }
        // 密码只在这一次调用里经过页面,不写进注入脚本,也不持久化
        String password = device.getPassword() == null ? "" : device.getPassword();
        webView.evaluateJavascript("window.__whAutoLogin && window.__whAutoLogin("
                + js(device.getUsername()) + ", " + js(password) + ")", null);
    }

    // =====================================================================
    // 连接
    // =====================================================================

    private void load() {
        showError(null);
        setStatus(Status.CONNECTING, null);
        spinner.setVisibility(View.VISIBLE);
        httpAuthAttempts = 0;
        TtydApi.loadConfig(new ApiCallback<TtydApi.Config>() {
            @Override
            public void onSuccess(TtydApi.Config result) {
                config = result;
                checkAndOpen();
            }

            @Override
            public void onFailure(ApiError error) {
                // 读配置失败就按默认 7681 试,和网页端 `|| '7681'` 的兜底一致
                checkAndOpen();
            }
        });
    }

    private void checkAndOpen() {
        if (isFinishing()) return;
        if (config.unixSocket) {
            fail(getString(R.string.term_err_unix_socket));
            return;
        }
        if ("0".equals(config.port)) {
            fail(getString(R.string.term_err_random_port));
            return;
        }
        TtydApi.isRunning(new ApiCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean running) {
                if (isFinishing()) return;
                if (!running) {
                    fail(getString(R.string.term_err_not_running));
                    return;
                }
                String url = terminalUrl();
                if (url == null) {
                    fail(getString(R.string.term_err_no_address));
                    return;
                }
                webView.loadUrl(url);
            }

            @Override
            public void onFailure(ApiError error) {
                fail(getString(R.string.term_err_not_running));
            }
        });
    }

    /** 与网页端 term.js 一致:url_override 优先,否则 协议://路由器:端口 */
    private String terminalUrl() {
        if (config.urlOverride != null) return config.urlOverride;
        String host = OpenWrtApi.getInstance().getHost();
        if (host == null || host.isEmpty()) return null;
        String h = host.contains(":") ? "[" + host + "]" : host;   // IPv6 地址要加方括号
        return (config.ssl ? "https" : "http") + "://" + h + ":" + config.port + "/";
    }

    private void reconnect() {
        setCtrl(false);
        webView.loadUrl("about:blank");
        load();
    }

    private void fail(String message) {
        spinner.setVisibility(View.GONE);
        setStatus(Status.FAILED, null);
        showError(message);
    }

    private void showError(String text) {
        errorText.setText(text);
        errorView.setVisibility(text == null ? View.GONE : View.VISIBLE);
        webView.setVisibility(text == null ? View.VISIBLE : View.INVISIBLE);
    }

    private void setStatus(Status status, String prompt) {
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(getColor(status.colorRes));
        statusDot.setBackground(dot);
        statusText.setText(prompt != null ? prompt : getString(status.textRes));
    }

    private void showBanner(String text) {
        banner.setText(text);
        banner.animate().cancel();
        banner.animate().alpha(1f).setDuration(200).withEndAction(() ->
                banner.animate().alpha(0f).setStartDelay(2500).setDuration(300).start()).start();
    }

    // =====================================================================
    // 菜单:重新连接 / 字号 / 自动登录
    // =====================================================================

    private void showMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        Menu menu = popup.getMenu();
        menu.add(0, 1, 0, R.string.term_reconnect);
        MenuItem sizeHeader = menu.add(1, 0, 1, getString(R.string.term_font_size, fontSize()));
        sizeHeader.setEnabled(false);
        menu.add(1, 2, 2, R.string.term_font_bigger);
        menu.add(1, 3, 3, R.string.term_font_smaller);
        MenuItem auto = menu.add(2, 4, 4, R.string.term_auto_login);
        auto.setCheckable(true);
        auto.setChecked(autoLogin());
        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    reconnect();
                    return true;
                case 2:
                    changeFont(1);
                    return true;
                case 3:
                    changeFont(-1);
                    return true;
                case 4:
                    prefs.edit().putBoolean(KEY_AUTO_LOGIN, !autoLogin()).apply();
                    return true;
                default:
                    return false;
            }
        });
        popup.show();
    }

    private void changeFont(int delta) {
        int size = Math.min(22, Math.max(10, fontSize() + delta));
        prefs.edit().putInt(KEY_FONT, size).apply();
        webView.evaluateJavascript("window.__whFont && window.__whFont(" + size + ")", null);
    }

    // =====================================================================
    // 快捷键栏
    // =====================================================================

    private static final int ACT_SEND = 0, ACT_CTRL = 1, ACT_PASTE = 2, ACT_HIDE = 3;

    private static final class Key {
        final String title;
        @DrawableRes
        final int icon;
        final int action;
        final String data;
        final boolean groupEnd;

        Key(String title, int icon, int action, String data, boolean groupEnd) {
            this.title = title;
            this.icon = icon;
            this.action = action;
            this.data = data;
            this.groupEnd = groupEnd;
        }
    }

    private static final Key[] KEYS = {
            new Key(null, R.drawable.ic_keyboard_hide, ACT_HIDE, null, true),
            new Key("Esc", 0, ACT_SEND, "\u001b", false),
            new Key("Tab", 0, ACT_SEND, "\t", false),
            new Key("Ctrl", 0, ACT_CTRL, null, true),
            new Key(null, R.drawable.ic_arrow_up, ACT_SEND, "\u001b[A", false),
            new Key(null, R.drawable.ic_arrow_down, ACT_SEND, "\u001b[B", false),
            new Key(null, R.drawable.ic_arrow_left, ACT_SEND, "\u001b[D", false),
            new Key(null, R.drawable.ic_arrow_right, ACT_SEND, "\u001b[C", true),
            new Key("^C", 0, ACT_SEND, "\u0003", false),
            new Key("^D", 0, ACT_SEND, "\u0004", false),
            new Key("^L", 0, ACT_SEND, "\u000c", true),
            new Key(null, R.drawable.ic_paste, ACT_PASTE, null, true),
            new Key("|", 0, ACT_SEND, "|", false),
            new Key("/", 0, ACT_SEND, "/", false),
            new Key("-", 0, ACT_SEND, "-", false),
            new Key("~", 0, ACT_SEND, "~", false),
            new Key("Home", 0, ACT_SEND, "\u001b[H", false),
            new Key("End", 0, ACT_SEND, "\u001b[F", false)
    };

    private void setupKeyBar() {
        LinearLayout row = findViewById(R.id.key_row);
        for (Key key : KEYS) {
            View button = makeKeyButton(key);
            row.addView(button);
            if (key.action == ACT_CTRL) ctrlKey = (TextView) button;
            if (key.groupEnd) {
                View divider = new View(this);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(1), dp(22));
                lp.setMargins(dp(3), 0, dp(3), 0);
                divider.setLayoutParams(lp);
                divider.setBackgroundColor(getColor(R.color.term_hairline));
                row.addView(divider);
            }
        }
    }

    private View makeKeyButton(Key key) {
        View button;
        if (key.icon != 0) {
            ImageView iv = new ImageView(this);
            iv.setImageResource(key.icon);
            iv.setColorFilter(getColor(R.color.term_key_text));
            iv.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            iv.setPadding(dp(12), dp(9), dp(12), dp(9));
            iv.setContentDescription(key.title);
            button = iv;
        } else {
            TextView tv = new TextView(this);
            tv.setText(key.title);
            tv.setTextColor(getColor(R.color.term_key_text));
            tv.setTextSize(14);
            tv.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(dp(12), 0, dp(12), 0);
            button = tv;
        }
        button.setBackground(keyBackground(R.color.term_key));
        button.setMinimumWidth(dp(42));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(36));
        lp.setMargins(dp(3), 0, dp(3), 0);
        button.setLayoutParams(lp);
        button.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            tapped(key);
        });
        return button;
    }

    private GradientDrawable keyBackground(@ColorRes int color) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(8));
        bg.setColor(getColor(color));
        return bg;
    }

    private void tapped(Key key) {
        switch (key.action) {
            case ACT_SEND:
                send(key.data);
                break;
            case ACT_CTRL:
                // 粘滞键:按一下亮起,下一个字符(软键盘或快捷栏)变成 Ctrl+字符,然后自动熄灭
                setCtrl(!ctrlOn);
                webView.evaluateJavascript("window.__whCtrl = " + ctrlOn, null);
                break;
            case ACT_PASTE:
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                ClipData clip = cm != null ? cm.getPrimaryClip() : null;
                CharSequence text = clip != null && clip.getItemCount() > 0
                        ? clip.getItemAt(0).coerceToText(this) : null;
                if (text == null || text.length() == 0) {
                    showBanner(getString(R.string.term_clipboard_empty));
                    return;
                }
                // 走原始通道,不受 Ctrl 影响;换行统一成回车,和真终端里粘贴一致
                sendRaw(text.toString().replace("\r\n", "\r").replace("\n", "\r"));
                break;
            default:
                webView.evaluateJavascript(
                        "document.activeElement && document.activeElement.blur()", null);
                InputMethodManager imm = (InputMethodManager) getSystemService(
                        Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.hideSoftInputFromWindow(webView.getWindowToken(), 0);
        }
    }

    private void setCtrl(boolean on) {
        ctrlOn = on;
        if (ctrlKey == null) return;
        ctrlKey.setBackground(keyBackground(on ? R.color.ios_blue : R.color.term_key));
        ctrlKey.setTextColor(on ? 0xFFFFFFFF : getColor(R.color.term_key_text));
    }

    private void send(String data) {
        webView.evaluateJavascript("window.__whSend && window.__whSend(" + js(data) + ")", null);
    }

    private void sendRaw(String data) {
        webView.evaluateJavascript("window.__whRaw && window.__whRaw(" + js(data) + ")", null);
    }

    // =====================================================================
    // AI 助手
    // =====================================================================

    private void openAI() {
        if (!AiAssistant.isEnabled(this)) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.ai_off_title)
                    .setMessage(R.string.ai_off_message)
                    .setNegativeButton(R.string.action_cancel, null)
                    .setPositiveButton(R.string.ai_go_settings, (d, w) ->
                            startActivity(new Intent(this, AiSettingsActivity.class)))
                    .show();
            return;
        }
        // 同一个终端页里复用同一个 AI 面板:关掉再打开,之前的对话还在
        if (aiSheet != null) {
            aiSheet.show();
            return;
        }
        loadRouterInfo(info -> {
            if (isFinishing()) return;
            aiSheet = new TerminalAiSheet(this, info, this);
            aiSheet.show();
        });
    }

    /** 固件信息帮 AI 选对命令(opkg 还是 apk、ImmortalWrt 还是官方版)。取不到就留空。 */
    private void loadRouterInfo(ValueCallback<String> done) {
        OpenWrtApi.getInstance().getSystemBoard(new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject b) {
                StringBuilder sb = new StringBuilder();
                line(sb, "hostname", str(b, "hostname"));
                line(sb, "model", str(b, "model"));
                if (b.has("release") && b.get("release").isJsonObject()) {
                    line(sb, "release", str(b.getAsJsonObject("release"), "description"));
                }
                line(sb, "kernel", str(b, "kernel"));
                done.onReceiveValue(sb.toString().trim());
            }

            @Override
            public void onFailure(ApiError error) {
                done.onReceiveValue("");
            }
        });
    }

    private static void line(StringBuilder sb, String key, String value) {
        if (value != null && !value.isEmpty()) sb.append(key).append(": ").append(value).append('\n');
    }

    private static String str(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonPrimitive()
                ? o.get(key).getAsString() : null;
    }

    @Override
    public void aiScreen(ValueCallback<String> done) {
        webView.evaluateJavascript("window.__whScreen ? window.__whScreen(40) : ''",
                value -> {
                    String s = decodeJs(value);
                    done.onReceiveValue(s == null ? "" : s);
                });
    }

    /** 光标停在 shell 提示符上才能执行 —— 还在 login: 时敲进去,命令会被当成用户名 */
    @Override
    public void aiAtShell(ValueCallback<Boolean> done) {
        webView.evaluateJavascript("window.__whAtShell ? window.__whAtShell() : false",
                value -> done.onReceiveValue("true".equals(value)));
    }

    /** 正在跑的一条 AI 命令 */
    private final class AiJob {
        final String tag;
        final String command;
        final long started = System.currentTimeMillis();
        final ValueCallback<AiAssistant.ExecResult> completion;
        long interruptedAt;
        boolean timedOut;
        int missedPolls;
        final Runnable poller = new Runnable() {
            @Override
            public void run() {
                poll(AiJob.this);
            }
        };

        AiJob(String tag, String command, ValueCallback<AiAssistant.ExecResult> completion) {
            this.tag = tag;
            this.command = command;
            this.completion = completion;
        }
    }

    /**
     * 在终端里执行一条命令并抓回输出(与 iOS aiRun 相同)。
     *
     * 实际敲进去的是一行:
     * {@code printf '__WH%s_%s\n' B <tag>; eval '<命令>'; printf '\n__WH%s_%s_%s\n' E <tag> "$?"}
     * 输出里会出现 __WHB_<tag> 和 __WHE_<tag>_<退出码> 两行标记,中间就是命令的输出。
     * 回显的那行命令里是 __WH%s,不会被误认成标记。用 eval 包一层,
     * 命令末尾带分号、& 或注释都不会把后面的结束标记弄坏。
     */
    @Override
    public void aiRun(String command, ValueCallback<AiAssistant.ExecResult> completion) {
        String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String quoted = command.replace("'", "'\\''");
        String line = "printf '__WH%s_%s\\n' B " + tag + "; eval '" + quoted + "'; "
                + "printf '\\n__WH%s_%s_%s\\n' E " + tag + " \"$?\"\r";
        AiJob job = new AiJob(tag, command, completion);
        aiJob = job;
        setCtrl(false);
        webView.evaluateJavascript("window.__whRun && window.__whRun(" + js(line) + ")", null);
        handler.postDelayed(job.poller, 400);
    }

    /** Ctrl+C 打断当前命令 */
    @Override
    public void aiInterrupt() {
        AiJob job = aiJob;
        if (job == null || job.interruptedAt != 0) return;
        job.interruptedAt = System.currentTimeMillis();
        webView.evaluateJavascript("window.__whRun && window.__whRun('\\u0003')", null);
    }

    private void poll(AiJob job) {
        if (aiJob != job) return;
        webView.evaluateJavascript("window.__whCapture ? window.__whCapture(" + js(job.tag) + ") : ''",
                value -> {
                    if (aiJob != job) return;
                    JsonObject o = null;
                    try {
                        String text = decodeJs(value);
                        if (text != null && !text.isEmpty()) {
                            o = JsonParser.parseString(text).getAsJsonObject();
                        }
                    } catch (Exception ignored) {
                        // 页面没了或返回异常,按拿不到处理
                    }
                    if (o == null) {
                        // 页面没了(断线重连中):连续 15 次(约 6 秒)拿不到就放弃
                        if (++job.missedPolls > 15) {
                            finishJob(job, "", AiAssistant.ExecResult.Status.LOST, 0);
                        } else {
                            handler.postDelayed(job.poller, 400);
                        }
                        return;
                    }
                    job.missedPolls = 0;
                    String output = o.has("out") && o.get("out").isJsonPrimitive()
                            ? o.get("out").getAsString() : "";
                    boolean done = o.has("done") && o.get("done").getAsBoolean();
                    if (done) {
                        int code = o.has("code") && o.get("code").isJsonPrimitive()
                                ? o.get("code").getAsInt() : 0;
                        // Ctrl+C 打断后 shell 一般不会再打结束标记;万一打了,也按打断算
                        if (job.interruptedAt == 0) {
                            finishJob(job, output, AiAssistant.ExecResult.Status.EXITED, code);
                        } else {
                            finishJob(job, output, job.timedOut
                                    ? AiAssistant.ExecResult.Status.TIMED_OUT
                                    : AiAssistant.ExecResult.Status.INTERRUPTED, 0);
                        }
                        return;
                    }
                    if (job.interruptedAt != 0) {
                        // 打断后等回到提示符(最多 5 秒),把已有输出带回去
                        final String partial = output;
                        aiAtShell(atShell -> {
                            if (aiJob != job) return;
                            if (atShell || System.currentTimeMillis() - job.interruptedAt > 5000) {
                                finishJob(job, partial, job.timedOut
                                        ? AiAssistant.ExecResult.Status.TIMED_OUT
                                        : AiAssistant.ExecResult.Status.INTERRUPTED, 0);
                            } else {
                                handler.postDelayed(job.poller, 400);
                            }
                        });
                        return;
                    }
                    if (System.currentTimeMillis() - job.started > AI_COMMAND_TIMEOUT_MS) {
                        job.timedOut = true;
                        aiInterrupt();
                    }
                    handler.postDelayed(job.poller, 400);
                });
    }

    private void finishJob(AiJob job, String output, AiAssistant.ExecResult.Status status,
                           int code) {
        handler.removeCallbacks(job.poller);
        aiJob = null;
        if (output.length() > 20000) output = output.substring(output.length() - 20000);
        job.completion.onReceiveValue(new AiAssistant.ExecResult(job.command, output, status, code));
    }

    // =====================================================================
    // 工具
    // =====================================================================

    /** 任意字符串 → JS 字面量(Gson 会转义引号、换行和控制字符) */
    private String js(String s) {
        return gson.toJson(s == null ? "" : s);
    }

    /** evaluateJavascript 回来的是 JSON 编码的值:字符串要再解一层 */
    private static String decodeJs(String value) {
        if (value == null || "null".equals(value)) return null;
        try {
            JsonElement e = JsonParser.parseString(value);
            return e.isJsonPrimitive() ? e.getAsString() : value;
        } catch (Exception e) {
            return value;
        }
    }

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (IOException e) {
            return "";
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /**
     * JS → Java 的桥。addJavascriptInterface 会被 WebView 长期持有,
     * 用弱引用避免页面关掉后 Activity 释放不掉。回调在 JavaBridge 线程,转回主线程处理。
     */
    private static final class JsBridge {
        private final WeakReference<TerminalActivity> ref;

        JsBridge(TerminalActivity activity) {
            ref = new WeakReference<>(activity);
        }

        @JavascriptInterface
        public void post(String message) {
            TerminalActivity a = ref.get();
            if (a == null || message == null) return;
            a.handler.post(() -> {
                if (!a.isDestroyed()) a.onPageMessage(message);
            });
        }
    }
}
