package com.mbox.music;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;
import android.widget.Toast;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 无 root 获取登录凭据：
 * 让 WebView 打开网易云网页版，用户在其中正常登录（手机号/邮箱/扫码），
 * 登录成功后 cookie 落进本 App 自己的 WebView cookie store，
 * 用 CookieManager.getCookie() 即可读出 MUSIC_U —— 全程不需要 root。
 *
 * MUSIC_U 是账号级凭据，网页端与 App 端通用，之后所有接口调用都靠它。
 */
public class LoginActivity extends Activity {

    // 直达登录页；直接开首页的话移动版没有明显的登录入口
    private static final String MOBILE_HOME = "https://music.163.com/m/login";
    private static final String DESKTOP_HOME = "https://music.163.com/";
    private static final String UA_MOBILE =
            "Mozilla/5.0 (Linux; Android 13; M2012K11AC) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
    private static final String UA_DESKTOP =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private static final Pattern MUSIC_U = Pattern.compile("MUSIC_U=([^;\\s]+)");

    private final Handler ui = new Handler(Looper.getMainLooper());
    private WebView web;
    private TextView tvState;
    private SharedPreferences sp;
    private boolean desktopMode = false;
    private boolean done = false;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (done) return;
            try {
                String ck = CookieManager.getInstance().getCookie("https://music.163.com");
                if (ck != null) {
                    Matcher m = MUSIC_U.matcher(ck);
                    if (m.find()) {
                        onGotCredential(m.group(1));
                        return;
                    }
                }
            } catch (Throwable ignored) {
            }
            ui.postDelayed(this, 1200);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_login);
        Ui.immersivePlain(this);
        sp = getSharedPreferences(MainActivity.SP, MODE_PRIVATE);
        web = findViewById(R.id.web);
        tvState = findViewById(R.id.tvState);

        findViewById(R.id.btnClose).setOnClickListener(v -> finish());
        findViewById(R.id.btnDesktop).setOnClickListener(v -> {
            desktopMode = !desktopMode;
            ((TextView) v).setText(desktopMode ? "手机版" : "桌面版");
            setupWeb();
            web.loadUrl(desktopMode ? DESKTOP_HOME : MOBILE_HOME);
        });

        setupWeb();
        web.loadUrl(MOBILE_HOME);
        ui.postDelayed(poll, 1500);
    }

    @SuppressWarnings("SetJavaScriptEnabled")
    private void setupWeb() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setUserAgentString(desktopMode ? UA_DESKTOP : UA_MOBILE);
        // 只允许图片等被动资源混合，禁止脚本类混合内容
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        // 登录页不需要读本地文件/内容提供者，关掉减少攻击面
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setGeolocationEnabled(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                // 只让网易系域名在 WebView 里跳转；其它地址（含 intent:/file: 等
                // 自定义 scheme）一律交给系统处理，避免被重定向页钓鱼
                try {
                    android.net.Uri u = android.net.Uri.parse(url);
                    String scheme = u.getScheme();
                    String host = u.getHost();
                    boolean http = "https".equals(scheme) || "http".equals(scheme);
                    boolean trusted = host != null && (host.equals("163.com")
                            || host.endsWith(".163.com")
                            || host.endsWith(".126.net")
                            || host.endsWith(".127.net")
                            || host.endsWith(".netease.com"));
                    if (http && trusted) return false;
                    if (http) {
                        startActivity(new Intent(Intent.ACTION_VIEW, u));
                    }
                    return true;
                } catch (Throwable e) {
                    return true;
                }
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                // 页面每次加载完立刻查一次，比等轮询更快
                String ck = CookieManager.getInstance().getCookie("https://music.163.com");
                if (ck != null) {
                    // 只打印键名，不泄露 cookie 值
                    android.util.Log.d("MBoxLogin", "cookie keys: " + ck.replaceAll("=([^;]*)", "=***"));
                    Matcher m = MUSIC_U.matcher(ck);
                    if (m.find()) onGotCredential(m.group(1));
                } else {
                }
            }
        });
        web.setWebChromeClient(new WebChromeClient());
    }

    private void onGotCredential(String musicU) {
        if (done) return;
        done = true;
        ui.removeCallbacks(poll);
        try {
            CookieManager.getInstance().flush();  // 确保 cookie 落盘
        } catch (Throwable ignored) {
        }
        sp.edit()
                .putString(MainActivity.KEY_MUSIC_U, musicU)
                .remove(MainActivity.KEY_NICK)
                .apply();

        tvState.setTextColor(Color.parseColor("#4ADE80"));
        tvState.setText("登录成功，正在校验…");

        // 立刻校验一次，避免存下一个无效/游客态的凭据
        new Thread(() -> {
            String msg;
            boolean ok;
            try {
                NeteaseApi.Profile p = new NeteaseApi(musicU).account();
                ok = p != null;
                if (ok) {
                    sp.edit().putLong(MainActivity.KEY_UID, p.uid)
                            .putString(MainActivity.KEY_NICK, p.nickname).apply();
                    msg = "已登录：" + p.nickname;
                } else {
                    msg = "凭据未生效，请重新登录";
                    sp.edit().remove(MainActivity.KEY_MUSIC_U).apply();
                }
            } catch (Exception e) {
                ok = false;
                msg = "校验失败：" + NeteaseApi.friendlyError(e);
                sp.edit().remove(MainActivity.KEY_MUSIC_U).apply();
            }
            final boolean fok = ok;
            final String fmsg = msg;
            ui.post(() -> {
                tvState.setTextColor(Color.parseColor(fok ? "#4ADE80" : "#E5484D"));
                tvState.setText(fmsg);
                Toast.makeText(this, fmsg, Toast.LENGTH_SHORT).show();
                if (fok) ui.postDelayed(this::finish, 600);
                else done = false;
            });
        }).start();
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(poll);
        if (web != null) {
            try {
                web.stopLoading();
                web.destroy();
            } catch (Throwable ignored) {
            }
        }
        super.onDestroy();
    }
}
