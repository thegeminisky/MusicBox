package com.mbox.music;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.TextView;

/** 歌曲右侧「⋯」菜单：复制歌名去官方客户端加歌单 / 导出音乐。 */
public class SongMenuDialog {

    public static void show(final Activity act, final NeteaseApi.Song song) {
        final Dialog d = new Dialog(act);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setContentView(R.layout.dialog_song_menu);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setGravity(Gravity.BOTTOM);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        ((TextView) d.findViewById(R.id.tvMenuTitle)).setText(song.name);
        // 第一项：复制歌名并拉起网易云（加歌单只能用官方客户端）
        d.findViewById(R.id.rowOpenNetease).setOnClickListener(v -> {
            d.dismiss();
            SongActions.copyAndOpenNetease(act, song);
        });
        d.findViewById(R.id.rowExport).setOnClickListener(v -> {
            d.dismiss();
            ExportHelper.start(act, song);
        });
        d.findViewById(R.id.btnMenuCancel).setOnClickListener(v -> d.dismiss());
        d.show();
    }
}
