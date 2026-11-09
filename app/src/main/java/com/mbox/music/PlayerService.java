package com.mbox.music;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * 前台服务：撑住后台播放，同时在通知栏提供播放控件。
 *
 * 通知开关关掉时不再以前台服务方式运行（自然也就没有保活），
 * 但 App 内的播放不受影响。
 */
public class PlayerService extends Service implements PlayerManager.Listener {

    static final String CHANNEL = MBoxApp.CHANNEL_PLAYBACK;
    static final int NOTI_ID = 1101;

    static final String ACTION_TOGGLE = "com.mbox.music.action.TOGGLE";
    static final String ACTION_NEXT = "com.mbox.music.action.NEXT";
    static final String ACTION_PREV = "com.mbox.music.action.PREV";
    static final String ACTION_STOP = "com.mbox.music.action.STOP";

    private PlayerManager pm;
    private MediaSession session;
    private String lastCoverKey;
    private final Handler ui = new Handler(Looper.getMainLooper());

    /** 刷新通知；封面是异步解码的，所以 1.2s 后再补刷一次把它带出来。 */
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            pushNotification();
        }
    };

    /** 有歌在播且用户开着通知控件时才拉起前台服务。 */
    public static void start(Context c) {
        if (!controlsEnabled(c)) return;
        if (PlayerManager.get(c).current() == null) return;
        Intent i = new Intent(c, PlayerService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Throwable ignored) {
            // 后台启动受限时静默失败，不影响播放
        }
    }

    public static void stop(Context c) {
        try {
            c.stopService(new Intent(c, PlayerService.class));
        } catch (Throwable ignored) {
        }
    }

    static boolean controlsEnabled(Context c) {
        return c.getSharedPreferences(MainActivity.SP, Context.MODE_PRIVATE)
                .getBoolean(MainActivity.KEY_NOTIFY_CONTROLS, true);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        pm = PlayerManager.get(this);
        // 渠道在 MBoxApp.onCreate 里建好了，这里不再重复创建

        session = new MediaSession(this, "MBoxMusic");
        session.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                if (!pm.isPlaying()) pm.toggle();
            }

            @Override
            public void onPause() {
                if (pm.isPlaying()) pm.toggle();
            }

            @Override
            public void onSkipToNext() {
                pm.next();
            }

            @Override
            public void onSkipToPrevious() {
                pm.prev();
            }

            @Override
            public void onStop() {
                pm.stop();
            }
        });
        // 系统媒体卡片（快捷设置/锁屏）点击时用它回到播放页，
        // 不设的话点卡片没反应
        Intent openPlayer = new Intent(this, PlayerActivity.class);
        openPlayer.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        session.setSessionActivity(PendingIntent.getActivity(this, 5, openPlayer,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        session.setActive(true);
        pm.addListener(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        if (ACTION_TOGGLE.equals(a)) {
            pm.toggle();
        } else if (ACTION_NEXT.equals(a)) {
            pm.next();
        } else if (ACTION_PREV.equals(a)) {
            pm.prev();
        } else if (ACTION_STOP.equals(a)) {
            pm.stop();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        // startForegroundService 之后必须尽快 startForeground，否则 ANR。
        // 某些 ROM 在通知权限被拒时可能抛异常，兜底避免整个服务崩掉导致播放中断
        try {
            startForeground(NOTI_ID, build());
        } catch (Throwable e) {
            android.util.Log.w("MBoxPlayer", "startForeground failed: " + e);
        }
        if (pm.current() == null || !controlsEnabled(this)) {
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        pm.removeListener(this);
        if (session != null) {
            session.setActive(false);
            session.release();
            session = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onPlayerChanged() {
        if (!controlsEnabled(this)) {
            stopForeground(true);
            stopSelf();
            return;
        }
        if (pm.current() == null) {
            stopForeground(true);
            stopSelf();
            return;
        }
        ui.removeCallbacks(refresh);
        ui.post(refresh);
        ui.postDelayed(refresh, 1200);
    }

    private void pushNotification() {
        if (pm.current() == null || !controlsEnabled(this)) return;
        try {
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                    .notify(NOTI_ID, build());
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------ 通知


    private Notification build() {
        NeteaseApi.Song s = pm.current();
        boolean playing = pm.isPlaying();

        Intent open = new Intent(this, PlayerActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent piOpen = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_IMMUTABLE);

        PendingIntent piPrev = PendingIntent.getService(this, 1,
                new Intent(this, PlayerService.class).setAction(ACTION_PREV),
                PendingIntent.FLAG_IMMUTABLE);
        PendingIntent piToggle = PendingIntent.getService(this, 2,
                new Intent(this, PlayerService.class).setAction(ACTION_TOGGLE),
                PendingIntent.FLAG_IMMUTABLE);
        PendingIntent piNext = PendingIntent.getService(this, 3,
                new Intent(this, PlayerService.class).setAction(ACTION_NEXT),
                PendingIntent.FLAG_IMMUTABLE);
        // 暂停时通知可以划掉，划掉即停止播放并退出前台服务
        PendingIntent piDelete = PendingIntent.getService(this, 4,
                new Intent(this, PlayerService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_noti)
                .setContentTitle(s == null ? "云乐盒" : s.name)
                .setContentText(s == null ? "未在播放" : s.artist)
                .setContentIntent(piOpen)
                .setDeleteIntent(piDelete)
                .setOngoing(playing)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC);

        b.addAction(new Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_media_previous),
                "上一首", piPrev).build());
        b.addAction(new Notification.Action.Builder(
                Icon.createWithResource(this,
                        playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play),
                playing ? "暂停" : "播放", piToggle).build());
        b.addAction(new Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_media_next),
                "下一首", piNext).build());

        // 封面：播放器页会预取 640 尺寸，从内存缓存里直接取
        Bitmap cover = null;
        if (s != null && s.picUrl != null && !s.picUrl.isEmpty()) {
            cover = CoverLoader.peek(s.picUrl, 640);
            if (cover != null) b.setLargeIcon(cover);
        }

        if (session != null) {
            b.setStyle(new Notification.MediaStyle()
                    .setMediaSession(session.getSessionToken())
                    .setShowActionsInCompactView(0, 1, 2));

            MediaMetadata.Builder md = new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, s == null ? "" : s.name)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, s == null ? "" : s.artist)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, pm.duration());
            // MediaStyle 的封面走 MediaMetadata 的 ART 字段，只设 largeIcon 在部分 ROM 上不显示
            if (cover != null) {
                md.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, cover);
                md.putBitmap(MediaMetadata.METADATA_KEY_ART, cover);
            }
            session.setMetadata(md.build());

            session.setPlaybackState(new PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY
                            | PlaybackState.ACTION_PAUSE
                            | PlaybackState.ACTION_PLAY_PAUSE
                            | PlaybackState.ACTION_SKIP_TO_NEXT
                            | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                            | PlaybackState.ACTION_STOP)
                    .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                            pm.position(), playing ? 1f : 0f)
                    .build());
        }
        return b.build();
    }
}
