package com.mbox.music;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.widget.RemoteViews;

/**
 * 1×1 桌面小组件：红心歌单。
 *
 * 点一下直接进「我的红心」并开始播放，省掉「开 App → 首页 → 找卡片」这三步。
 * 播放本身交给 SongsActivity（它已经处理了取歌单、取播放地址、队列缓存），
 * 这里只负责把入口和「点了就必须播」这个意图传过去（force_play）。
 */
public class HeartWidget extends AppWidgetProvider {

    /** 固定的 requestCode：同一个 PendingIntent 反复复用，不会越积越多。 */
    private static final int REQ_PLAY_LIKE = 0x4C4B;

    private static final String EXTRA_FORCE_PLAY = "force_play";

    @Override
    public void onUpdate(Context context, AppWidgetManager mgr, int[] appWidgetIds) {
        for (int id : appWidgetIds) {
            logOptions(context, mgr, id);
            mgr.updateAppWidget(id, buildViews(context));
        }
    }

    /** 启动器上报的格子尺寸/密度：调图标对齐时要靠这些数，出问题先看这条日志。 */
    private static void logOptions(Context c, AppWidgetManager mgr, int id) {
        if (Build.VERSION.SDK_INT < 31) return;
        android.os.Bundle o = mgr.getAppWidgetOptions(id);
        android.util.DisplayMetrics dm = c.getResources().getDisplayMetrics();
        android.util.Log.d("MBoxWidget", "id=" + id
                + " min=" + o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
                + "x" + o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
                + " max=" + o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)
                + "x" + o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
                + " density=" + dm.density
                + " screen=" + dm.widthPixels + "x" + dm.heightPixels);
    }

    private static RemoteViews buildViews(Context c) {
        RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.widget_heart);
        rv.setOnClickPendingIntent(R.id.widgetHeartRoot, playLikeIntent(c));
        applyIconMetrics(rv);
        return rv;
    }

    /**
     * 让 1×1 和桌面图标「同宽同高、上沿齐平」。
     *
     * 小组件只能拿到格子尺寸，拿不到启动器图标的实际大小和它在格子里的上内缩
     * （MIUI：58dp / 内缩 0；ColorOS：56dp / 内缩 9dp），所以这里按厂商启动器的实测值给档位，
     * 未知启动器取中间值，宁可差几 dp 也不写死某一台机器的数值。
     */
    private static int[] iconMetrics() {
        String m = (Build.MANUFACTURER + " " + Build.BRAND).toLowerCase(java.util.Locale.ROOT);
        if (m.contains("xiaomi") || m.contains("redmi") || m.contains("poco")) {
            return new int[]{58, 0};      // MIUI / HyperOS
        }
        if (m.contains("oneplus") || m.contains("oppo") || m.contains("realme")) {
            return new int[]{56, 9};      // ColorOS / OxygenOS
        }
        return new int[]{56, 4};
    }

    private static void applyIconMetrics(RemoteViews rv) {
        // 运行期改尺寸/边距要 API 31+，低版本用 layout 里写死的 56dp 兜底
        if (Build.VERSION.SDK_INT < 31) return;
        int[] m = iconMetrics();
        rv.setViewLayoutWidth(R.id.widgetHeartTile, m[0], android.util.TypedValue.COMPLEX_UNIT_DIP);
        rv.setViewLayoutHeight(R.id.widgetHeartTile, m[0], android.util.TypedValue.COMPLEX_UNIT_DIP);
        if (m[1] > 0) {
            rv.setViewLayoutMargin(R.id.widgetHeartTile, RemoteViews.MARGIN_TOP,
                    m[1], android.util.TypedValue.COMPLEX_UNIT_DIP);
        }
    }

    private static PendingIntent playLikeIntent(Context c) {
        Intent i = new Intent(c, SongsActivity.class);
        i.putExtra("mode", "like");
        i.putExtra(EXTRA_FORCE_PLAY, true);
        // CLEAR_TOP + SINGLE_TOP：红心页已经在栈里就复用（走 onNewIntent 续播），
        // 不在就新开一个，避免点几次叠出好几个歌单页。
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(c, REQ_PLAY_LIKE, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
