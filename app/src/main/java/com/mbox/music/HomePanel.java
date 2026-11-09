package com.mbox.music;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 首页面板：三个大卡片 + 三条横滑。 */
public class HomePanel {

    static final String KEY_DAILY_COVER = "daily_cover";

    private final Activity act;
    private final View root;
    private final SharedPreferences sp;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(2);

    private final LinearLayout rowPlaylists, rowNews, rowTops, rowMine;
    private final View mineSection;
    private final ImageView ivDaily;
    private boolean loading;

    public HomePanel(Activity a, View r) {
        act = a;
        root = r;
        sp = a.getSharedPreferences(MainActivity.SP, Activity.MODE_PRIVATE);

        rowPlaylists = r.findViewById(R.id.rowPlaylists);
        rowNews = r.findViewById(R.id.rowNews);
        rowTops = r.findViewById(R.id.rowTops);
        rowMine = r.findViewById(R.id.rowMine);
        mineSection = r.findViewById(R.id.mineSection);
        ivDaily = r.findViewById(R.id.ivDaily);

        // 灰度：底图去饱和，再叠一层半透明深色让白字清楚
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(0);
        ivDaily.setColorFilter(new ColorMatrixColorFilter(cm));

        r.findViewById(R.id.btnDaily).setOnClickListener(v -> open("daily"));
        r.findViewById(R.id.btnHeartbeat).setOnClickListener(v -> open("heartbeat"));
        r.findViewById(R.id.btnLike).setOnClickListener(v -> open("like"));

        loadCachedRecommend();
        loadDailyCover();
        loadMine();
        loadRecommend();
    }

    /** 登录状态变化后由 MainActivity 调用，清掉旧的横滑内容重新拉一遍。 */
    public void reload() {
        rowPlaylists.removeAllViews();
        rowNews.removeAllViews();
        rowTops.removeAllViews();
        rowMine.removeAllViews();
        mineSection.setVisibility(View.GONE);
        loading = false;
        loadDailyCover();
        loadMine();
        loadRecommend();
    }

    // ------------------------------------------------------------ 我的歌单

    /**
     * 我的歌单：排除红心（specialType=5），按本地记录的「最后播放时间」从新到旧排列。
     * 没播过的排在后面，保持接口原有顺序（Collections.sort 是稳定排序）。
     */
    private void loadMine() {
        final String mu = musicU();
        if (mu == null) return;
        pool.execute(() -> {
            try {
                NeteaseApi api = new NeteaseApi(mu);
                // KEY_UID 只有打开过设置页才会写入，首次启动时是 0，
                // 直接 return 会让「我的歌单」永远不显示，所以这里补一次查询
                long uid = sp.getLong(MainActivity.KEY_UID, 0);
                if (uid <= 0) {
                    NeteaseApi.Profile p = api.account();
                    if (p == null) return;
                    uid = p.uid;
                    sp.edit().putLong(MainActivity.KEY_UID, uid).apply();
                }
                List<NeteaseApi.Playlist> all = api.userPlaylists(uid);
                final List<NeteaseApi.Playlist> mine = new ArrayList<>();
                for (NeteaseApi.Playlist p : all) {
                    if (p.specialType == 5) continue;
                    mine.add(p);
                }
                if (mine.isEmpty()) return;
                JSONObject ts;
                try {
                    ts = new JSONObject(sp.getString(MainActivity.KEY_PLAYLIST_TS, "{}"));
                } catch (Exception e) {
                    ts = new JSONObject();
                }
                final JSONObject stamps = ts;
                java.util.Collections.sort(mine, (a, b) -> Long.compare(
                        stamps.optLong(String.valueOf(b.id), 0),
                        stamps.optLong(String.valueOf(a.id), 0)));
                ui.post(() -> {
                    mineSection.setVisibility(View.VISIBLE);
                    fillPlaylists(rowMine, mine, false);
                });
            } catch (Exception e) {
                android.util.Log.w("MBoxHome", "mine fail " + e);
            }
        });
    }

    private String musicU() {
        return sp.getString(MainActivity.KEY_MUSIC_U, null);
    }

    private void open(String mode) {
        if (musicU() == null) {
            Toast.makeText(act, "请先登录", Toast.LENGTH_SHORT).show();
            act.startActivity(new Intent(act, LoginActivity.class));
            return;
        }
        Intent i = new Intent(act, SongsActivity.class);
        i.putExtra("mode", mode);
        act.startActivity(i);
    }

    private void openPlaylist(NeteaseApi.Playlist p) {
        if (musicU() == null) {
            Toast.makeText(act, "请先登录", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(act, SongsActivity.class);
        i.putExtra("mode", "playlist");
        i.putExtra("playlist_id", p.id);
        i.putExtra("title", p.name);
        act.startActivity(i);
    }

    // ------------------------------------------------------------ 每日推荐封面

    private void loadDailyCover() {
        String cached = sp.getString(KEY_DAILY_COVER, null);
        if (cached != null && !cached.isEmpty()) applyCover(cached);
        final String mu = musicU();
        if (mu == null) return;
        pool.execute(() -> {
            try {
                List<NeteaseApi.Song> songs = new NeteaseApi(mu).dailySongs();
                if (songs.isEmpty()) return;
                final String u = songs.get(0).picUrl;
                if (u == null || u.isEmpty()) return;
                sp.edit().putString(KEY_DAILY_COVER, u).apply();
                ui.post(() -> applyCover(u));
            } catch (Exception ignored) {
            }
        });
    }

    private void applyCover(String url) {
        CoverLoader.into(ivDaily, url, 240);
    }

    // ------------------------------------------------------------ 推荐数据

    private void loadRecommend() {
        final String mu = musicU();
        if (mu == null || loading) return;
        loading = true;
        pool.execute(() -> {
            try {
                NeteaseApi api = new NeteaseApi(mu);
                List<NeteaseApi.Playlist> pls = api.recommendPlaylists(6);
                final List<NeteaseApi.Song> news = api.newSongs(6);
                List<NeteaseApi.Playlist> topsAll = api.toplists();
                final List<NeteaseApi.Playlist> tops = topsAll.size() > 10
                        ? new ArrayList<>(topsAll.subList(0, 10)) : topsAll;

                List<NeteaseApi.Playlist> radar = api.radarPlaylists();
                final List<NeteaseApi.Playlist> cards = new ArrayList<>(pls);
                NeteaseApi.Playlist pick = null;
                for (NeteaseApi.Playlist r : radar) {
                    boolean dup = false;
                    for (NeteaseApi.Playlist c : cards) {
                        if (c.id == r.id) {
                            dup = true;
                            break;
                        }
                    }
                    if (!dup) {
                        pick = r;
                        break;
                    }
                }
                if (pick == null && !radar.isEmpty()) pick = radar.get(0);
                if (pick != null) {
                    pick.note = pick.note.isEmpty() ? "雷达歌单" : "雷达 · " + pick.note;
                    cards.add(Math.min(1, cards.size()), pick);
                }

                ui.post(() -> {
                    loading = false;
                    fillPlaylists(rowPlaylists, cards, false);
                    fillSongs(rowNews, news);
                    fillPlaylists(rowTops, tops, true);
                    saveCachedRecommend(cards, news, tops);
                });
            } catch (Exception e) {
                ui.post(() -> loading = false);
            }
        });
    }

    private void fillPlaylists(LinearLayout row, List<NeteaseApi.Playlist> list, boolean topMode) {
        row.removeAllViews();
        for (final NeteaseApi.Playlist p : list) {
            View v = act.getLayoutInflater().inflate(R.layout.item_card, row, false);
            ((TextView) v.findViewById(R.id.tvName)).setText(p.name);
            TextView note = v.findViewById(R.id.tvNote);
            if (topMode) {
                note.setText(p.note);
            } else if (p.note != null && p.note.startsWith("雷达")) {
                note.setText(p.note);
            } else if (p.playCount > 0) {
                note.setText(fmtCount(p.playCount) + " 播放");
            } else {
                note.setText(p.note);
            }
            CoverLoader.into((ImageView) v.findViewById(R.id.ivCover), p.cover, 240);
            v.setOnClickListener(x -> openPlaylist(p));
            row.addView(v);
        }
    }

    private void fillSongs(LinearLayout row, final List<NeteaseApi.Song> songs) {
        row.removeAllViews();
        for (int i = 0; i < songs.size(); i++) {
            final int idx = i;
            NeteaseApi.Song s = songs.get(i);
            View v = act.getLayoutInflater().inflate(R.layout.item_card, row, false);
            ((TextView) v.findViewById(R.id.tvName)).setText(s.name);
            ((TextView) v.findViewById(R.id.tvNote)).setText(s.artist);
            CoverLoader.into((ImageView) v.findViewById(R.id.ivCover), s.picUrl, 240);
            v.setOnClickListener(x -> {
                PlayerManager.get(act).setQueue(songs, idx, true, null);
                act.startActivity(new Intent(act, PlayerActivity.class));
            });
            row.addView(v);
        }
    }


    static String fmtCount(long c) {
        if (c >= 100000000L) return String.format("%.1f亿", c / 100000000.0);
        if (c >= 10000L) return String.format("%.1f万", c / 10000.0);
        return String.valueOf(c);
    }

    // ------------------------------------------------------------ 缓存

    private void saveCachedRecommend(List<NeteaseApi.Playlist> cards,
                                     List<NeteaseApi.Song> news,
                                     List<NeteaseApi.Playlist> tops) {
        try {
            JSONObject o = new JSONObject();
            o.put("pl", plJson(cards));
            o.put("news", newsJson(news));
            o.put("tops", plJson(tops));
            sp.edit().putString(MainActivity.KEY_RECOMMEND_CACHE, o.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private void loadCachedRecommend() {
        String raw = sp.getString(MainActivity.KEY_RECOMMEND_CACHE, null);
        if (raw == null || raw.isEmpty()) return;
        try {
            JSONObject o = new JSONObject(raw);
            List<NeteaseApi.Playlist> cards = plFrom(o.optJSONArray("pl"));
            List<NeteaseApi.Song> news = newsFrom(o.optJSONArray("news"));
            List<NeteaseApi.Playlist> tops = plFrom(o.optJSONArray("tops"));
            if (!cards.isEmpty()) fillPlaylists(rowPlaylists, cards, false);
            if (!news.isEmpty()) fillSongs(rowNews, news);
            if (!tops.isEmpty()) fillPlaylists(rowTops, tops, true);
        } catch (Exception ignored) {
        }
    }

    private static JSONArray plJson(List<NeteaseApi.Playlist> list) throws Exception {
        JSONArray a = new JSONArray();
        for (NeteaseApi.Playlist p : list) {
            JSONObject o = new JSONObject();
            o.put("id", p.id);
            o.put("name", p.name);
            o.put("cover", p.cover);
            o.put("pc", p.playCount);
            o.put("note", p.note);
            a.put(o);
        }
        return a;
    }

    private static List<NeteaseApi.Playlist> plFrom(JSONArray a) {
        List<NeteaseApi.Playlist> out = new ArrayList<>();
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            NeteaseApi.Playlist p = new NeteaseApi.Playlist();
            p.id = o.optLong("id");
            p.name = o.optString("name", "");
            p.cover = o.optString("cover", "");
            p.playCount = o.optLong("pc");
            p.note = o.optString("note", "");
            out.add(p);
        }
        return out;
    }

    private static JSONArray newsJson(List<NeteaseApi.Song> list) throws Exception {
        JSONArray a = new JSONArray();
        for (NeteaseApi.Song s : list) {
            JSONObject o = new JSONObject();
            o.put("id", s.id);
            o.put("name", s.name);
            o.put("artist", s.artist);
            o.put("album", s.album);
            o.put("pic", s.picUrl);
            o.put("dur", s.duration);
            o.put("fee", s.fee);
            a.put(o);
        }
        return a;
    }

    private static List<NeteaseApi.Song> newsFrom(JSONArray a) {
        List<NeteaseApi.Song> out = new ArrayList<>();
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            NeteaseApi.Song s = new NeteaseApi.Song();
            s.id = o.optLong("id");
            s.name = o.optString("name", "");
            s.artist = o.optString("artist", "");
            s.album = o.optString("album", "");
            s.picUrl = o.optString("pic", "");
            s.duration = o.optLong("dur");
            s.fee = o.optInt("fee");
            out.add(s);
        }
        return out;
    }
}
