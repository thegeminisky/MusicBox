package com.mbox.music;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 音频本地缓存。
 *
 * 目录 caches/audio/<songId>.<ext>；访问时间记在独立的 SharedPreferences 里，
 * 超过「空间上限」或「歌曲数上限」时，按最久未访问优先淘汰，正在播放的那首不动。
 */
public class AudioCache {

    private static final String TAG = "MBoxCache";
    private static final String PREF = "audio_cache_index";
    /** 可选的容量档位（GB） */
    static final double[] GB_STEPS = {0.5, 1, 1.5, 2, 5, 8, 10, 20};

    private static AudioCache instance;

    public static synchronized AudioCache get(Context c) {
        if (instance == null) instance = new AudioCache(c.getApplicationContext());
        return instance;
    }

    private final Context app;
    private File dir;
    private final SharedPreferences idx;
    private final ExecutorService pool = Executors.newFixedThreadPool(2);

    private AudioCache(Context c) {
        app = c;
        dir = resolveDir();
        idx = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        cleanBadCaches();
    }

    /** 内部存储 or 外部（App 专属）存储，由设置页决定。 */
    private File internalDir() {
        return new File(app.getCacheDir(), "audio");
    }

    private File externalDir() {
        return app.getExternalFilesDir("audio");
    }

    /** 固定用内部私有目录（不提供切换：外部目录在部分机型上会被清理得更随意）。 */
    private File resolveDir() {
        File d = internalDir();
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** 切换存储位置后调用：重建目录并重新扫描。旧缓存不会自动迁移。 */
    public void relocate() {
        dir = resolveDir();
        cleanBadCaches();
    }

    private static void clearDir(File d) {
        File[] fs = d.listFiles();
        if (fs != null) for (File f : fs) f.delete();
    }

    /** 清掉历史版本误存的坏缓存（HTML 错误页、截断文件、.tmp 残留）。 */
    private void cleanBadCaches() {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.getName().endsWith(".tmp")) {
                f.delete();
                continue;
            }
            byte[] head = new byte[8];
            try (java.io.FileInputStream fis = new java.io.FileInputStream(f)) {
                int n = fis.read(head);
                // 宽松清理：只删明确的 HTML/JSON 错误页和极小文件，
                // 避免把某些不带 ID3 头的正常 mp3 误删
                boolean bad = n < 4 || head[0] == '<' || head[0] == '{'
                        || f.length() < 4096;
                if (bad) {
                    Log.w(TAG, "remove bad cache " + f.getName());
                    f.delete();
                }
            } catch (Exception e) {
                f.delete();
            }
        }
    }

    public File find(long id) {
        File[] fs = dir.listFiles();
        if (fs == null) return null;
        String prefix = id + ".";
        for (File f : fs) {
            String n = f.getName();
            if (n.startsWith(prefix) && !n.endsWith(".tmp")) return f;
        }
        return null;
    }

    public boolean has(long id) {
        return find(id) != null;
    }

    public void touch(long id) {
        idx.edit().putLong(String.valueOf(id), System.currentTimeMillis()).apply();
    }

    public long totalBytes() {
        return dirBytes(dir);
    }

    public int count() {
        File[] fs = dir.listFiles();
        if (fs == null) return 0;
        int n = 0;
        for (File f : fs) if (!f.getName().endsWith(".tmp")) n++;
        return n;
    }

    public long coverBytes() {
        return dirBytes(new File(app.getCacheDir(), "cover"));
    }

    /** 音乐以外的杂项占用（基准数据、索引文件等） */
    public long otherBytes() {
        return dirBytes(app.getCacheDir()) - totalBytes() - coverBytes();
    }

    private static long dirBytes(File d) {
        if (d == null || !d.exists()) return 0;
        long sum = 0;
        File[] fs = d.listFiles();
        if (fs != null) for (File f : fs) if (f.isFile()) sum += f.length();
        return sum;
    }

    // ------------------------------------------------------------ 下载

    /** 下载到缓存；已有则只刷新访问时间。不会阻塞调用线程。 */
    public void download(final NeteaseApi.Song s) {
        if (s == null || s.url == null) return;
        if (has(s.id)) {
            touch(s.id);
            return;
        }
        final String url = s.url;
        final long id = s.id;
        final String ext = extOf(s.type, url);
        pool.execute(() -> {
            File tmp = new File(dir, id + ".tmp");
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(25000);
                int code = c.getResponseCode();
                if (code != 200 && code != 206) {
                    Log.w(TAG, "bad http " + code + " id=" + id);
                    tmp.delete();
                    return;
                }
                // 服务器声明的总长度，用来判断有没有被截断
                long total = -1;
                String cr = c.getHeaderField("Content-Range");
                if (cr != null && cr.contains("/")) {
                    try {
                        total = Long.parseLong(cr.substring(cr.lastIndexOf('/') + 1).trim());
                    } catch (Exception ignored) {
                    }
                }
                if (total < 0) total = c.getHeaderFieldLong("Content-Length", -1);

                long got = 0;
                try (InputStream in = c.getInputStream();
                     FileOutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        got += n;
                    }
                }
                if (got < 4096 || (total > 0 && got < total)) {
                    Log.w(TAG, "truncated id=" + id + " got=" + got + " expected=" + total);
                    tmp.delete();
                    return;
                }
                // 拒绝明确的 HTML/JSON 错误页；Content-Length 已负责防截断，
                // 这里不对魔数做严格限制，免得误伤不带 ID3 头的正常 mp3
                byte[] head = new byte[8];
                try (java.io.FileInputStream fis = new java.io.FileInputStream(tmp)) {
                    int hn = fis.read(head);
                    if (hn < 4 || head[0] == '<' || head[0] == '{') {
                        Log.w(TAG, "not audio id=" + id + " head=" + hn);
                        tmp.delete();
                        return;
                    }
                }
                File dst = new File(dir, id + "." + ext);
                if (dst.exists()) dst.delete();
                if (!tmp.renameTo(dst)) {
                    tmp.delete();
                    return;
                }
                touch(id);
                trim();
            } catch (Throwable t) {
                tmp.delete();
            } finally {
                if (c != null) c.disconnect();
            }
        });
    }

    private static String extOf(String type, String url) {
        String t = type == null ? "" : type.toLowerCase();
        if (t.contains("flac")) return "flac";
        if (t.contains("mp3")) return "mp3";
        if (t.contains("m4a") || t.contains("aac")) return "m4a";
        if (url != null) {
            int q = url.indexOf('?');
            String p = q > 0 ? url.substring(0, q) : url;
            int dot = p.lastIndexOf('.');
            if (dot > 0 && p.length() - dot <= 6) return p.substring(dot + 1);
        }
        return "bin";
    }

    // ------------------------------------------------------------ 淘汰

    /** 按两个维度共同判断，超了就删最久没听的那首。 */
    public void trim() {
        final long maxB = maxBytes();
        final int maxN = maxSongs();
        if (maxB <= 0 && maxN <= 0) return;

        File[] fs = dir.listFiles();
        if (fs == null) return;
        List<File> files = new ArrayList<>();
        for (File f : fs) if (!f.getName().endsWith(".tmp")) files.add(f);
        if (files.isEmpty()) return;

        Collections.sort(files, (a, b) -> Long.compare(lastUsed(a), lastUsed(b)));

        long total = 0;
        for (File f : files) total += f.length();
        int n = files.size();

        long curId = -1;
        NeteaseApi.Song cur = PlayerManager.get(app).current();
        if (cur != null) curId = cur.id;

        for (File f : files) {
            boolean sizeOk = maxB <= 0 || total <= maxB;
            boolean countOk = maxN <= 0 || n <= maxN;
            if (sizeOk && countOk) break;
            long id = idOf(f);
            if (id == curId) continue;          // 正在播放的不淘汰
            long len = f.length();
            if (f.delete()) {
                total -= len;
                n--;
                idx.edit().remove(String.valueOf(id)).apply();
            }
        }
    }

    private long lastUsed(File f) {
        long v = idx.getLong(String.valueOf(idOf(f)), 0);
        return v > 0 ? v : f.lastModified();
    }

    private static long idOf(File f) {
        String nm = f.getName();
        int dot = nm.indexOf('.');
        try {
            return Long.parseLong(dot > 0 ? nm.substring(0, dot) : nm);
        } catch (Exception e) {
            return -1;
        }
    }

    private SharedPreferences sp() {
        return app.getSharedPreferences(MainActivity.SP, Context.MODE_PRIVATE);
    }

    public long maxBytes() {
        int i = sp().getInt(MainActivity.KEY_CACHE_MAX_GB, 3);
        int k = Math.max(0, Math.min(i, GB_STEPS.length - 1));
        return (long) (GB_STEPS[k] * 1024L * 1024L * 1024L);
    }

    public int maxSongs() {
        return sp().getInt(MainActivity.KEY_CACHE_MAX_SONGS, 0);
    }

    public static String sizeText(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return String.format("%.0f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / 1048576.0);
        return String.format("%.2f GB", bytes / 1073741824.0);
    }

    public void clearAll() {
        // 内部和外部都清，否则切换过位置的设备会留下旧缓存
        clearDir(internalDir());
        File ext = externalDir();
        if (ext != null) clearDir(ext);
        clearDir(new File(app.getCacheDir(), "cover"));
        idx.edit().clear().apply();
        relocate();
    }
}
