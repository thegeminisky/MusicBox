package com.mbox.music;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 封面图加载：内存 LruCache → 磁盘缓存 → 网络。
 *
 * 磁盘缓存是为了让冷启动时首页封面能直接从本地出图，不用再走一遍网络。
 * 缓存未命中时不清空当前图片，等新图解码完再替换，避免快速切歌时白闪。
 */
public final class CoverLoader {

    private static final int MAX_BYTES = 16 * 1024 * 1024;

    private static final LruCache<String, Bitmap> CACHE =
            new LruCache<String, Bitmap>(MAX_BYTES) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return Math.max(1, value.getByteCount());
                }
            };

    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private static final Handler UI = new Handler(Looper.getMainLooper());

    private CoverLoader() {
    }

    public static void into(ImageView iv, String url) {
        into(iv, url, 400);
    }

    public static void into(ImageView iv, String url, int size) {
        if (iv == null) return;
        if (url == null || url.isEmpty()) {
            iv.setTag(null);
            iv.setImageDrawable(null);
            return;
        }
        final String key = url + "#" + size;
        Bitmap cached = CACHE.get(key);
        if (cached != null) {
            iv.setTag(key);
            iv.setImageBitmap(cached);
            return;
        }
        iv.setTag(key);
        final Context ctx = iv.getContext().getApplicationContext();
        POOL.execute(() -> {
            Bitmap bmp = load(ctx, url, size, key);
            if (bmp == null) return;
            CACHE.put(key, bmp);
            UI.post(() -> {
                if (key.equals(iv.getTag())) iv.setImageBitmap(bmp);
            });
        });
    }

    /** 预取到缓存。切歌时就命中，封面能瞬间切换。 */
    public static void prefetch(Context c, String url, int size) {
        if (url == null || url.isEmpty() || c == null) return;
        final String key = url + "#" + size;
        if (CACHE.get(key) != null) return;
        final Context ctx = c.getApplicationContext();
        POOL.execute(() -> {
            Bitmap bmp = load(ctx, url, size, key);
            if (bmp != null) CACHE.put(key, bmp);
        });
    }

    /** 只从内存缓存同步取，取不到就返回 null（给通知栏大图标用）。 */
    public static Bitmap peek(String url, int size) {
        if (url == null || url.isEmpty()) return null;
        return CACHE.get(url + "#" + size);
    }

    private static Bitmap load(Context c, String url, int size, String key) {
        File f = diskFile(c, key);
        if (f.exists()) {
            Bitmap b = decodeFile(f, size);
            if (b != null) return b;
            f.delete();
        }
        byte[] raw = http(url, size);
        if (raw == null || raw.length == 0) return null;
        Bitmap bmp = decodeBytes(raw, size);
        if (bmp != null) writeFile(f, raw);
        return bmp;
    }

    private static File diskFile(Context c, String key) {
        File d = new File(c.getCacheDir(), "cover");
        if (!d.exists()) d.mkdirs();
        return new File(d, Integer.toHexString(key.hashCode()) + ".img");
    }

    private static byte[] http(String url, int size) {
        HttpURLConnection c = null;
        try {
            String target = size > 0
                    ? url + (url.contains("?") ? "&" : "?") + "param=" + size + "y" + size
                    : url;
            c = (HttpURLConnection) new URL(target).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0");
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                return bo.toByteArray();
            }
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static void writeFile(File f, byte[] data) {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(data);
        } catch (Throwable ignored) {
        }
    }

    private static Bitmap decodeFile(File f, int target) {
        BitmapFactory.Options probe = new BitmapFactory.Options();
        probe.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), probe);
        if (probe.outWidth <= 0) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sampleSize(probe.outWidth, probe.outHeight, target);
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(f.getAbsolutePath(), o);
    }

    private static Bitmap decodeBytes(byte[] raw, int target) {
        BitmapFactory.Options probe = new BitmapFactory.Options();
        probe.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(raw, 0, raw.length, probe);
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sampleSize(probe.outWidth, probe.outHeight, target);
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
    }

    private static int sampleSize(int w, int h, int target) {
        if (w <= 0 || h <= 0 || target <= 0) return 1;
        int longest = Math.max(w, h);
        int s = 1;
        while (longest / s > target) s *= 2;
        return s;
    }
}
