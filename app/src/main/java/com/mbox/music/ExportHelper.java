package com.mbox.music;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;

/**
 * 导出音乐：用 ACTION_CREATE_DOCUMENT 让用户自己选目录并改名
 * （系统文件选择器自带重命名，默认名预填「歌名-歌手-年月日时分秒」）。
 */
public class ExportHelper {

    static final int REQ_EXPORT = 0x9101;
    private static NeteaseApi.Song pending;

    public static void start(Activity act, NeteaseApi.Song song) {
        pending = song;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("audio/*");
        i.putExtra(Intent.EXTRA_TITLE, suggestName(song));
        try {
            act.startActivityForResult(i, REQ_EXPORT);
        } catch (Exception e) {
            Toast.makeText(act, "没有可用的文件管理器", Toast.LENGTH_SHORT).show();
        }
    }

    /** 歌名-歌手-年月日时分秒 + 扩展名，去掉文件系统非法字符。 */
    static String suggestName(NeteaseApi.Song s) {
        String ts = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(new Date());
        String base = clean(s.name) + "-" + clean(s.artist) + "-" + ts;
        if (base.length() > 80) base = base.substring(0, 80);
        return base + ".mp3";
    }

    private static String clean(String in) {
        if (in == null || in.isEmpty()) return "未知";
        String out = in.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_").trim();
        // 多个歌手用 "/" 连接时已经变成 "_"
        return out.isEmpty() ? "未知" : out;
    }

    /** 在 Activity.onActivityResult 里转进来。 */
    public static void handleResult(final Activity act, int requestCode,
                                    int resultCode, Intent data) {
        if (requestCode != REQ_EXPORT) return;
        final NeteaseApi.Song song = pending;
        pending = null;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return;
        if (song == null) return;
        final Uri uri = data.getData();
        Toast.makeText(act, "正在导出…", Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            boolean ok = false;
            String err = null;
            try {
                writeTo(act, song, uri);
                ok = true;
            } catch (Exception e) {
                err = NeteaseApi.friendlyError(e);
            }
            final boolean done = ok;
            final String msg = err;
            act.runOnUiThread(() -> Toast.makeText(act,
                    done ? "导出完成" : "导出失败：" + msg, Toast.LENGTH_SHORT).show());
        }).start();
    }

    private static void writeTo(Activity act, NeteaseApi.Song song, Uri uri) throws Exception {
        ContentResolver cr = act.getContentResolver();
        File local = AudioCache.get(act).find(song.id);
        if (local != null && local.length() > 4096) {
            try (InputStream in = new FileInputStream(local);
                 OutputStream out = cr.openOutputStream(uri)) {
                if (out == null) throw new Exception("无法写入目标文件");
                copy(in, out);
            }
            return;
        }
        // 没缓存就现拉一份，不落盘直接写进目标文件
        String mu = act.getSharedPreferences(MainActivity.SP, Activity.MODE_PRIVATE)
                .getString(MainActivity.KEY_MUSIC_U, null);
        if (mu == null) throw new Exception("请先登录");
        NeteaseApi api = new NeteaseApi(mu);
        String level = act.getSharedPreferences(MainActivity.SP, Activity.MODE_PRIVATE)
                .getString(MainActivity.KEY_LEVEL, "exhigh");
        api.fillUrls(Collections.singletonList(song), level);
        String base = song.rawUrl != null ? song.rawUrl : song.url;
        String playable = NeteaseApi.resolvePlayable(base);
        if (playable == null) throw new Exception("无法获取播放地址");

        HttpURLConnection c = (HttpURLConnection) new URL(playable).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(30000);
        try (InputStream in = c.getInputStream();
             OutputStream out = cr.openOutputStream(uri)) {
            if (out == null) throw new Exception("无法写入目标文件");
            copy(in, out);
        } finally {
            c.disconnect();
        }
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.flush();
    }
}
