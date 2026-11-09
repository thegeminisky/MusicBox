package com.mbox.music;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

/** 播放列表弹窗：只做查看与删除，深色卡片风格与主界面一致。 */
public class QueueDialog {

    public static void show(final Activity act) {
        final PlayerManager pm = PlayerManager.get(act);
        if (pm.queue().isEmpty()) {
            Toast.makeText(act, "播放列表是空的", Toast.LENGTH_SHORT).show();
            return;
        }

        final Dialog d = new Dialog(act);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setContentView(R.layout.dialog_queue);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setGravity(android.view.Gravity.BOTTOM);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        d.setCanceledOnTouchOutside(true);

        final ListView lv = d.findViewById(R.id.listQueue);
        final TextView tvCount = d.findViewById(R.id.tvQueueCount);
        d.findViewById(R.id.btnQClose).setOnClickListener(v -> d.dismiss());

        final BaseAdapter adapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return pm.queue().size();
            }

            @Override
            public Object getItem(int i) {
                return pm.queue().get(i);
            }

            @Override
            public long getItemId(int i) {
                return pm.queue().get(i).id;
            }

            @Override
            public View getView(int position, View convert, ViewGroup parent) {
                View row = convert != null ? convert
                        : act.getLayoutInflater().inflate(R.layout.item_queue, parent, false);
                NeteaseApi.Song s = pm.queue().get(position);
                boolean cur = position == pm.index();

                TextView idx = row.findViewById(R.id.tvQIdx);
                TextView name = row.findViewById(R.id.tvQName);
                TextView artist = row.findViewById(R.id.tvQArtist);
                idx.setText(String.valueOf(position + 1));
                idx.setTextColor(Color.parseColor(cur ? "#E5484D" : "#5C636D"));
                name.setText(s.name);
                name.setTextColor(Color.parseColor(cur ? "#E5484D" : "#F2F4F7"));
                artist.setText(s.artist);

                final int pos = position;
                row.findViewById(R.id.btnQDel).setOnClickListener(x -> {
                    if (pm.queue().size() <= 1) {
                        Toast.makeText(act, "至少保留一首", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    pm.removeAt(pos);
                    refresh();
                });
                return row;
            }

            private void refresh() {
                notifyDataSetChanged();
                tvCount.setText("播放列表 · " + pm.queue().size() + " 首");
            }
        };
        lv.setAdapter(adapter);
        tvCount.setText("播放列表 · " + pm.queue().size() + " 首");
        d.show();
    }
}
