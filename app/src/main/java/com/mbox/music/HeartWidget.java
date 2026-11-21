package com.mbox.music;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
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
            mgr.updateAppWidget(id, buildViews(context, mgr, id));
        }
    }

    private static RemoteViews buildViews(Context c, AppWidgetManager mgr, int id) {
        RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.widget_heart);
        rv.setOnClickPendingIntent(R.id.widgetHeartRoot, playLikeIntent(c));
        applySquareSize(rv, mgr, id);
        return rv;
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

    /**
     * 桌面格子不是正方形（本机 4 列时 1×1 约 66×85dp），底板铺满就会拉成长方形。
     * API 31 起可以在运行期改 RemoteViews 里的尺寸，于是按格子自己上报的宽高取短边，
     * 得到一块和图标一样方正、又刚好铺满格子宽度的卡片。
     */
    private static void applySquareSize(RemoteViews rv, AppWidgetManager mgr, int id) {
        if (Build.VERSION.SDK_INT < 31) return;
        Bundle opts = mgr.getAppWidgetOptions(id);
        int w = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0);
        int h = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0);
        if (w <= 0 || h <= 0) return;
        // 夹一下上下限：太小看不清，太大就不是「单格」了
        int side = Math.max(52, Math.min(Math.min(w, h), 96));
        rv.setViewLayoutWidth(R.id.widgetHeartTile, side, TypedValue.COMPLEX_UNIT_DIP);
        rv.setViewLayoutHeight(R.id.widgetHeartTile, side, TypedValue.COMPLEX_UNIT_DIP);
    }
}
