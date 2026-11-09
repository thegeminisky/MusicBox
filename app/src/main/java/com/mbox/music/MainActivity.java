package com.mbox.music;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

/** 主容器：顶部三个 tab 是同一 Activity 内的同级面板，切换只改 visibility，不开新页面。 */
public class MainActivity extends Activity {

    static final String SP = "mbox";
    static final String KEY_MUSIC_U = "music_u";
    static final String KEY_UID = "uid";
    static final String KEY_NICK = "nickname";
    static final String KEY_AVATAR = "avatar";
    static final String KEY_LEVEL = "level";
    static final String KEY_LIKE_PID = "like_playlist_id";
    static final String KEY_AUTOPLAY_ALL = "autoplay_all";
    static final String KEY_AUTOPLAY_SCOPE = "autoplay_scope";
    static final String KEY_PRELOAD_NEXT = "preload_next";
    static final String KEY_CACHE_MAX_GB = "cache_max_gb";
    static final String KEY_CACHE_MAX_SONGS = "cache_max_songs";
    static final String KEY_RECOMMEND_CACHE = "recommend_cache";
    static final String KEY_NOTIFY_CONTROLS = "notify_controls";
    /** 各歌单最后一次进入播放的时间戳（JSON: {id: millis}），用于首页「我的歌单」排序 */
    static final String KEY_PLAYLIST_TS = "playlist_play_ts";
    /** 是否已经走过首次启动引导 */
    static final String KEY_LAUNCHED = "launched_once";
    /** 缓存是否放外部存储（应用专属目录） */
    static final String KEY_CACHE_EXTERNAL = "cache_external";
    /** 拔出耳机时是否暂停 */
    static final String KEY_PAUSE_ON_UNPLUG = "pause_on_unplug";

    private static final String EXTRA_TAB = "tab";

    private TopTabs tabs;
    private View[] panels;
    private View miniBar;
    private HomePanel homePanel;
    private SettingsPanel settingsPanel;
    private int currentTab;
    private String lastMusicU;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        Ui.immersiveStatusBar(this);

        tabs = findViewById(R.id.topTabs);
        miniBar = findViewById(R.id.miniBar);
        panels = new View[]{
                findViewById(R.id.panelHome),
                findViewById(R.id.panelSearch),
                findViewById(R.id.panelSettings)
        };

        homePanel = new HomePanel(this, panels[0]);
        new SearchPanel(this, panels[1]);
        settingsPanel = new SettingsPanel(this, panels[2]);

        android.content.SharedPreferences sp =
                getSharedPreferences(SP, MODE_PRIVATE);
        lastMusicU = sp.getString(KEY_MUSIC_U, null);
        // 首次安装且没登录：直接进登录流程，别让用户对着空白首页
        if (!sp.getBoolean(KEY_LAUNCHED, false) && lastMusicU == null) {
            sp.edit().putBoolean(KEY_LAUNCHED, true).apply();
            startActivity(new Intent(this, LoginActivity.class));
        }

        tabs.setOnTabSelected(this::showTab);
        showTab(getIntent() == null ? 0 : getIntent().getIntExtra(EXTRA_TAB, 0));

        // Android 13+ 通知要运行时授权
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 100);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null) showTab(intent.getIntExtra(EXTRA_TAB, currentTab));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 登录/退出登录后回来，首页数据要重新拉一遍（原来一直显示空白或旧内容）
        android.content.SharedPreferences sp = getSharedPreferences(SP, MODE_PRIVATE);
        String cur = sp.getString(KEY_MUSIC_U, null);
        boolean changed = (cur == null) != (lastMusicU == null)
                || (cur != null && !cur.equals(lastMusicU));
        lastMusicU = cur;
        if (changed && homePanel != null) homePanel.reload();
        if (settingsPanel != null && currentTab == 2) settingsPanel.onShown();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        ExportHelper.handleResult(this, req, res, data);
    }

    private void showTab(int i) {
        if (i < 0 || i >= panels.length) i = 0;
        currentTab = i;
        for (int k = 0; k < panels.length; k++) {
            panels[k].setVisibility(k == i ? View.VISIBLE : View.GONE);
        }
        tabs.setCurrent(i);
        // 沿用之前的约定：设置页不常驻播放胶囊
        miniBar.setVisibility(i == 2 ? View.GONE : View.VISIBLE);
        if (i == 2 && settingsPanel != null) settingsPanel.onShown();
    }
}
