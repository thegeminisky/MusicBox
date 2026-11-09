package com.mbox.music;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 搜索面板：/api/search/get 单曲搜索，结果作为播放队列。 */
public class SearchPanel {

    private final Activity act;
    private final View root;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final List<NeteaseApi.Song> results = new ArrayList<>();
    private final PlayerManager pm;

    private final EditText et;
    private final TextView tvEmpty;
    private final View loading;
    private final BaseAdapter adapter;

    public SearchPanel(Activity a, View r) {
        act = a;
        root = r;
        pm = PlayerManager.get(a);

        et = r.findViewById(R.id.etSearch);
        tvEmpty = r.findViewById(R.id.tvEmpty);
        loading = r.findViewById(R.id.loading);
        ListView list = r.findViewById(R.id.list);

        adapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return results.size();
            }

            @Override
            public Object getItem(int i) {
                return results.get(i);
            }

            @Override
            public long getItemId(int i) {
                return results.get(i).id;
            }

            @Override
            public View getView(int position, View convert, ViewGroup parent) {
                View row = convert != null ? convert
                        : a.getLayoutInflater().inflate(R.layout.item_song, parent, false);
                NeteaseApi.Song s = results.get(position);
                boolean cur = pm.current() != null && pm.current().id == s.id;

                TextView idx = row.findViewById(R.id.tvIndex);
                TextView name = row.findViewById(R.id.tvName);
                TextView sub = row.findViewById(R.id.tvSub);

                idx.setText(String.valueOf(position + 1));
                idx.setTextColor(Color.parseColor(cur ? "#E5484D" : "#5C636D"));
                name.setText(s.name);
                name.setTextColor(Color.parseColor(cur ? "#E5484D" : "#F2F4F7"));
                sub.setText(s.artist + (s.album == null || s.album.isEmpty()
                        ? "" : " · " + s.album));
                row.findViewById(R.id.btnMore).setOnClickListener(
                        x -> SongMenuDialog.show(act, s));
                return row;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, i, id) -> play(i));

        et.setOnEditorActionListener((v, action, ev) -> {
            search();
            return true;
        });
        r.findViewById(R.id.btnSearch).setOnClickListener(v -> search());
    }

    private void play(int i) {
        if (i < 0 || i >= results.size()) return;
        pm.setQueue(new ArrayList<>(results), i, true, PlayerManager.MODE_LOOP_LIST);
        act.startActivity(new Intent(act, PlayerActivity.class));
    }

    private void search() {
        final String kw = et.getText().toString().trim();
        if (kw.isEmpty()) {
            Toast.makeText(act, "输入点什么再搜", Toast.LENGTH_SHORT).show();
            return;
        }
        // 必须实时读：首次启动未登录时 MainActivity 已建好本面板，
        // 登录完成后不会重建，用构造时的快照会一直误判成未登录
        final String mu = act.getSharedPreferences(MainActivity.SP, Activity.MODE_PRIVATE)
                .getString(MainActivity.KEY_MUSIC_U, null);
        if (mu == null) {
            Toast.makeText(act, "请先登录", Toast.LENGTH_SHORT).show();
            act.startActivity(new Intent(act, LoginActivity.class));
            return;
        }
        loading.setVisibility(View.VISIBLE);
        tvEmpty.setText("");
        pool.execute(() -> {
            try {
                final List<NeteaseApi.Song> got = new NeteaseApi(mu).searchSongs(kw, 0, 30);
                ui.post(() -> {
                    results.clear();
                    results.addAll(got);
                    adapter.notifyDataSetChanged();
                    loading.setVisibility(View.GONE);
                    tvEmpty.setText(got.isEmpty() ? "没有找到相关歌曲" : "");
                });
            } catch (Exception e) {
                ui.post(() -> {
                    loading.setVisibility(View.GONE);
                    tvEmpty.setText("搜索失败：" + NeteaseApi.friendlyError(e));
                });
            }
        });
    }
}
