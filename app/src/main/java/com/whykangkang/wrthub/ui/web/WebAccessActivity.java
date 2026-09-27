package com.whykangkang.wrthub.ui.web;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.whykangkang.wrthub.R;
import com.whykangkang.wrthub.manager.RouterDeviceManager;
import com.whykangkang.wrthub.model.RouterDevice;

/**
 * 内置浏览器,对应 iOS WebViewController / SFSafariViewController。
 *
 * 默认打开当前路由器后台,页面加载完成后注入 JS 自动填表登录
 * (逻辑与 iOS autoLogin 一致)。带 {@link #EXTRA_URL} 启动时改为打开指定地址
 * (如 Clash 控制面板),并且**不**注入登录脚本 —— 那是 LuCI 登录页专用的,
 * 往别的页面里塞路由器密码没有意义也不安全。
 */
public class WebAccessActivity extends AppCompatActivity {

    /** 要打开的地址;不传则打开当前设备的路由器后台 */
    public static final String EXTRA_URL = "url";
    /** 顶栏标题;不传则用「网页访问」 */
    public static final String EXTRA_TITLE = "title";

    private WebView webView;
    private ProgressBar progress;
    private String url;
    private String username;
    private String password;
    private boolean hasAttemptedAutoLogin;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_web_access);

        String requested = getIntent().getStringExtra(EXTRA_URL);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        if (requested != null && !requested.isEmpty()) {
            url = requested;
            hasAttemptedAutoLogin = true;   // 外部地址不注入登录脚本
        } else {
            RouterDevice device = RouterDeviceManager.getInstance(this).getCurrentDevice();
            if (device == null) {
                new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.web_error_title)
                        .setMessage(R.string.web_no_address)
                        .setPositiveButton(R.string.ok, (d, w) -> finish())
                        .setOnDismissListener(d -> finish())
                        .show();
                return;
            }
            url = device.getDisplayAddress();
            username = device.getUsername();
            password = device.getPassword();
        }

        ((TextView) findViewById(R.id.page_title)).setText(
                title != null && !title.isEmpty() ? title : getString(R.string.web_title));
        progress = findViewById(R.id.web_progress);
        webView = findViewById(R.id.web_view);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String finishedUrl) {
                progress.setVisibility(ProgressBar.GONE);
                // 密码可为空(部分路由器 root 未设密码),只要有用户名就尝试自动登录
                if (!hasAttemptedAutoLogin && username != null && !username.isEmpty()) {
                    hasAttemptedAutoLogin = true;
                    view.postDelayed(WebAccessActivity.this::autoLogin, 500);
                }
            }

            @Override
            public void onPageStarted(WebView view, String startedUrl, android.graphics.Bitmap f) {
                progress.setVisibility(ProgressBar.VISIBLE);
            }
        });
        findViewById(R.id.btn_refresh).setOnClickListener(v -> webView.reload());
        findViewById(R.id.btn_external).setOnClickListener(v ->
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))));
        findViewById(R.id.btn_close).setOnClickListener(v -> finish());
        webView.loadUrl(url);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    /** 自动填表并提交(兼容 OpenWrt / ImmortalWrt 的 LuCI 登录页) */
    private void autoLogin() {
        String user = escape(username);
        String pass = escape(password == null ? "" : password);
        String js = "(function() {"
                + "var u = document.querySelector('input[name=\"luci_username\"]')"
                + " || document.querySelector('input[name=\"username\"]')"
                + " || document.querySelector('input[type=\"text\"]');"
                + "var p = document.querySelector('input[name=\"luci_password\"]')"
                + " || document.querySelector('input[name=\"password\"]')"
                + " || document.querySelector('input[type=\"password\"]');"
                + "if (u && p) {"
                + "  u.value = '" + user + "';"
                + "  p.value = '" + pass + "';"
                + "  var form = u.closest('form');"
                + "  if (form) {"
                + "    u.dispatchEvent(new Event('input', { bubbles: true }));"
                + "    p.dispatchEvent(new Event('input', { bubbles: true }));"
                + "    setTimeout(function() { form.submit(); }, 100);"
                + "    return 'Login form submitted';"
                + "  }"
                + "}"
                + "return 'Login form not found';"
                + "})();";
        webView.evaluateJavascript(js, null);
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("'", "\\'");
    }
}
