package com.mbox.music;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

/**
 * 歌曲右侧「⋯」菜单第一项：把「歌名 歌手」复制到剪贴板，再把网易云客户端
 * 拉到前台，用户直接在搜索框粘贴即可。加歌单只能靠官方客户端。
 */
public final class SongActions {

    private static final String PKG = "com.netease.cloudmusic";

    private SongActions() {
    }

    public static void copyAndOpenNetease(Activity act, NeteaseApi.Song song) {
        String text = song.name + " " + song.artist;
        try {
            ClipboardManager cm = (ClipboardManager)
                    act.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("song", text));
            }
        } catch (Throwable ignored) {
        }

        // 不做 resolveActivity 预判：MIUI 在应用被冻结/隐藏时可能返回 null，
        // 误判成「未安装」。这里直接尝试启动，失败再往下退。
        String encoded = Uri.encode(song.name + " " + song.artist);
        String[] links = {
                "orpheus://search?keyword=" + encoded,
                "netease://search?keyword=" + encoded
        };
        for (String link : links) {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(link));
                i.setPackage(PKG);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                act.startActivity(i);
                toast(act, "已复制「" + text + "」，可直接搜索");
                return;
            } catch (Throwable ignored) {
            }
        }
        // 退一步：直接拉起主界面
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_LAUNCHER);
            i.setPackage(PKG);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(i);
            toast(act, "已复制「" + text + "」，请在搜索框粘贴");
            return;
        } catch (Throwable ignored) {
        }
        // 最后再问一次包管理器，用来区分「真没装」和「装了但拉不起来」
        boolean installed = false;
        try {
            act.getPackageManager().getPackageInfo(PKG, 0);
            installed = true;
        } catch (Throwable ignored) {
        }
        toast(act, installed
                ? "已复制「" + text + "」，请手动打开网易云音乐"
                : "已复制「" + text + "」，未检测到网易云音乐");
    }

    private static void toast(Activity act, String msg) {
        Toast.makeText(act, msg, Toast.LENGTH_LONG).show();
    }
}
