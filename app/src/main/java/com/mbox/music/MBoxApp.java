package com.mbox.music;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;

/**
 * 通知渠道必须在这里创建，不能等到 PlayerService.onCreate 才建。
 *
 * 原因（Android 13+）：如果 POST_NOTIFICATIONS 已被拒绝，此时再调
 * createNotificationChannel 会静默失败；随后 startForeground 找不到渠道，
 * 直接抛 RemoteServiceException，服务崩掉，后台播放随之停止。
 * 放到 Application.onCreate 建，渠道在权限被拒之前就已经存在，
 * startForeground 就能正常把服务提到前台（通知不显示但服务有效）。
 *
 * 另外媒体类通知本身是 POST_NOTIFICATIONS 的豁免项，只要走
 * MediaStyle + MediaSession，系统媒体控件仍会显示在通知栏/快捷设置里。
 */
public class MBoxApp extends Application {

    static final String CHANNEL_PLAYBACK = "playback";

    @Override
    public void onCreate() {
        super.onCreate();
        createPlaybackChannel();
        NeteaseApi.setExtraCookie(DeviceProfile.extraCookie(this));
    }


    private void createPlaybackChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL_PLAYBACK) != null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_PLAYBACK, "播放控制", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("显示当前播放的歌曲与播放控制按钮");
        ch.setShowBadge(false);
        ch.setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(ch);
    }
}
