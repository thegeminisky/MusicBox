package com.mbox.music;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SongsActivity extends Activity implements PlayerManager.Listener {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final List<NeteaseApi.Song> songs = new ArrayList<>();

    private PlayerManager pm;
    private SharedPreferences sp;
    private ListView list;
    private SongAdapter adapter;
    private View loading;
    private TextView tvTitle, tvSub, tvLoading;

    private String musicU;
    private String pageMode;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_songs);
        Ui.immersivePlain(this);

        sp = getSharedPreferences(MainActivity.SP, MODE_PRIVATE);
        musicU = sp.getString(MainActivity.KEY_MUSIC_U, null);
        pageMode = getIntent().getStringExtra("mode");
        if (pageMode == null) pageMode = "daily";
        if (musicU == null) {
            Toast.makeText(this, "请先登录", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        pm = PlayerManager.get(this);

        list = findViewById(R.id.list);
        loading = findViewById(R.id.loading);
        tvTitle = findViewById(R.id.tvTitle);
        tvSub = findViewById(R.id.tvSub);
        tvLoading = findViewById(R.id.tvLoading);

        tvTitle.setText(titleFor(pageMode));
        tvSub.setText("连接中…");

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnPlayAll).setOnClickListener(v -> playAll());

        adapter = new SongAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            android.util.Log.d("MBoxPlayer", "itemClick pos=" + position
                    + " size=" + songs.size());
            pm.setQueue(songs, position, true, null);   // 单点保持当前播放模式
        });

        if ("heartbeat".equals(pageMode)) {
            loadHeartbeat();
        } else {
            loadPlaylist();
        }
    }

    private String titleFor(String m) {
        String t = getIntent().getStringExtra("title");
        if (t != null && !t.isEmpty()) return t;
        if ("like".equals(m)) return "我的红心";
        if ("heartbeat".equals(m)) return "心动模式";
        if ("playlist".equals(m)) return "歌单";
        return "每日推荐";
    }

    @Override
    protected void onResume() {
        super.onResume();
        pm.addListener(this);
        lastHighlight = -2;          // 回到本页时强制重绘一次
        adapter.notifyDataSetChanged();
    }

    @Override
    protected void onPause() {
        pm.removeListener(this);
        super.onPause();
    }

    // ------------------------------------------------------------ 加载列表

    @Override
    protected void onActivityResult(int req, int res, android.content.Intent data) {
        super.onActivityResult(req, res, data);
        ExportHelper.handleResult(this, req, res, data);
    }

    /** 记录歌单最后一次被打开播放的时间，首页「我的歌单」按它从新到旧排序。 */
    private void markPlaylistPlayed(long pid) {
        try {
            android.content.SharedPreferences sp =
                    getSharedPreferences(MainActivity.SP, MODE_PRIVATE);
            org.json.JSONObject o = new org.json.JSONObject(
                    sp.getString(MainActivity.KEY_PLAYLIST_TS, "{}"));
            o.put(String.valueOf(pid), System.currentTimeMillis());
            sp.edit().putString(MainActivity.KEY_PLAYLIST_TS, o.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private void loadPlaylist() {
        final String mu = musicU;
        final String m = pageMode;
        pool.execute(() -> {
            try {
                NeteaseApi api = new NeteaseApi(mu);
                List<NeteaseApi.Song> got;
                if ("playlist".equals(m)) {
                    long pid = getIntent().getLongExtra("playlist_id", 0);
                    if (pid == 0) throw new IllegalStateException("歌单 id 无效");
                    markPlaylistPlayed(pid);
                    got = api.playlistSongs(pid);
                } else if ("like".equals(m)) {
                    got = api.playlistSongs(ensureLikePid(api));
                } else {
                    got = api.dailySongs();
                }
                final List<NeteaseApi.Song> res = got;
                ui.post(() -> bind(res, false));
            } catch (Exception e) {
                ui.post(() -> showError("加载失败：" + NeteaseApi.friendlyError(e)));
            }
        });
    }

    /** 心动模式：以红心歌单首曲为种子，按同样口味连续推荐。 */
    private void loadHeartbeat() {
        showLoading();
        tvLoading.setText("正在生成心动推荐…");
        tvSub.setText("生成中…");

        pool.execute(() -> {
            try {
                NeteaseApi api = new NeteaseApi(musicU);
                long pid = ensureLikePid(api);

                long seed = 0;
                List<NeteaseApi.Song> base = api.playlistSongs(pid);
                if (!base.isEmpty()) {
                    seed = base.get(0).id;
                } else {
                    List<NeteaseApi.Song> daily = api.dailySongs();
                    if (!daily.isEmpty()) seed = daily.get(0).id;
                }
                if (seed == 0) throw new IllegalStateException("没有可用的种子歌曲，先收藏几首吧");

                final List<NeteaseApi.Song> hb = api.heartbeatSongs(pid, seed, 100);
                ui.post(() -> bind(hb, true));
            } catch (Exception e) {
                ui.post(() -> showError("心动模式失败：" + NeteaseApi.friendlyError(e)));
            }
        });
    }

    private long ensureLikePid(NeteaseApi api) throws Exception {
        long pid = sp.getLong(MainActivity.KEY_LIKE_PID, 0);
        if (pid > 0) return pid;
        long uid = sp.getLong(MainActivity.KEY_UID, 0);
        if (uid == 0) {
            NeteaseApi.Profile p = api.account();
            if (p == null) throw new IllegalStateException("登录态失效，请重新登录");
            uid = p.uid;
        }
        pid = api.likePlaylistId(uid);
        sp.edit().putLong(MainActivity.KEY_LIKE_PID, pid)
                .putLong(MainActivity.KEY_UID, uid).apply();
        return pid;
    }

    private void bind(List<NeteaseApi.Song> res, boolean heartbeat) {
        songs.clear();
        if (res != null) songs.addAll(res);
        adapter.notifyDataSetChanged();
        loading.setVisibility(songs.isEmpty() ? View.VISIBLE : View.GONE);

        if (songs.isEmpty()) {
            tvLoading.setText(heartbeat ? "心动模式暂时没有推荐" : "这里还没有内容");
            tvSub.setText("0 首");
            return;
        }
        tvTitle.setText(heartbeat ? "心动模式" : titleFor(pageMode));
        tvSub.setText(songs.size() + " 首" + (heartbeat ? " · 按你的口味连续推荐" : ""));

        // 「进入歌单自动播放」开启时，进来直接列表循环播全部；
        // 若勾了「仅限日推/心动/红心」，其他歌单进来只展示列表。
        boolean auto = sp.getBoolean(MainActivity.KEY_AUTOPLAY_ALL, true);
        boolean onlyThree = sp.getBoolean(MainActivity.KEY_AUTOPLAY_SCOPE, true);
        boolean isThree = "daily".equals(pageMode) || "like".equals(pageMode)
                || "heartbeat".equals(pageMode);
        if (auto && (!onlyThree || isThree) && !sameAsCurrentQueue(songs)) {
            pm.setQueue(songs, 0, true, PlayerManager.MODE_LOOP_LIST);
        }
    }

    /** 当前播放队列与本次要加载的歌单逐首一致时，不再从头重播。 */
    private boolean sameAsCurrentQueue(List<NeteaseApi.Song> songs) {
        List<NeteaseApi.Song> cur = pm.queue();
        if (cur == null || cur.size() != songs.size() || songs.isEmpty()) return false;
        for (int i = 0; i < songs.size(); i++) {
            if (cur.get(i).id != songs.get(i).id) return false;
        }
        return true;
    }

    /** 进入加载态：容器显示 + 转圈显示。 */
    private void showLoading() {
        loading.setVisibility(View.VISIBLE);
        View pb = findViewById(R.id.pbLoading);
        if (pb != null) pb.setVisibility(View.VISIBLE);
    }

    private void showError(String msg) {
        // 只停掉转圈，保留容器和文字
        loading.setVisibility(View.VISIBLE);
        View pb = findViewById(R.id.pbLoading);
        if (pb != null) pb.setVisibility(View.GONE);
        tvSub.setText("加载失败");
        tvLoading.setText(msg);
    }

    private void playAll() {
        if (songs.isEmpty()) {
            Toast.makeText(this, "列表为空", Toast.LENGTH_SHORT).show();
            return;
        }
        pm.setQueue(songs, 0, true, PlayerManager.MODE_LOOP_LIST);
        Toast.makeText(this, "播放全部 · 列表循环", Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------ 播放状态

    private long lastHighlight = -2;

    @Override
    public void onPlayerChanged() {
        // 只有「当前播放曲目」变了才重绘列表；缓冲/播放状态变化只更新迷你条。
        // 否则连续失败跳歌时会把 ListView 刷爆，主线程直接卡死（表现就是黑屏）。
        NeteaseApi.Song cur = pm.current();
        long id = cur == null ? -1 : cur.id;
        if (id != lastHighlight) {
            lastHighlight = id;
            adapter.notifyDataSetChanged();
        } else {
            // 同一首歌只是播放/暂停变了：不重绘整个列表，只让可见的律动条跟着停/跳
            for (int i = 0; i < list.getChildCount(); i++) {
                EqualizerView eq = list.getChildAt(i).findViewById(R.id.eqView);
                if (eq != null) eq.setPlaying(pm.isPlaying());
            }
        }
    }


    // ------------------------------------------------------------ 列表

    private class SongAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return songs.size();
        }

        @Override
        public Object getItem(int i) {
            return songs.get(i);
        }

        @Override
        public long getItemId(int i) {
            return songs.get(i).id;
        }

        @Override
        public View getView(int position, View convert, ViewGroup parent) {
            View v = convert != null ? convert
                    : getLayoutInflater().inflate(R.layout.item_song, parent, false);
            NeteaseApi.Song s = songs.get(position);
            NeteaseApi.Song cur = pm == null ? null : pm.current();
            boolean active = cur != null && cur.id == s.id;

            TextView idx = v.findViewById(R.id.tvIndex);
            EqualizerView eq = v.findViewById(R.id.eqView);
            TextView name = v.findViewById(R.id.tvName);
            TextView sub = v.findViewById(R.id.tvSub);

            // 当前播放曲目用律动条代替序号，比一个静态三角直观
            idx.setVisibility(active ? View.GONE : View.VISIBLE);
            eq.setVisibility(active ? View.VISIBLE : View.GONE);
            idx.setText(String.valueOf(position + 1));
            idx.setTextColor(Color.parseColor("#5C636D"));
            eq.setPlaying(active && pm != null && pm.isPlaying());
            name.setText(s.name);
            name.setTextColor(Color.parseColor(active ? "#E5484D" : "#F2F4F7"));

            StringBuilder sb = new StringBuilder(s.artist);
            if (s.album != null && !s.album.isEmpty()) sb.append(" · ").append(s.album);
            if (s.fee == 4) sb.append(" · 付费");
            sub.setText(sb.toString());
            v.findViewById(R.id.btnMore).setOnClickListener(
                    x -> SongMenuDialog.show(SongsActivity.this, s));
            return v;
        }
    }
}
