package com.mbox.music;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Base64;
import android.util.DisplayMetrics;

import org.json.JSONObject;

import java.net.URLEncoder;
import java.security.SecureRandom;

/**
 * 网易云客户端的设备标识字段。
 *
 * 从官方客户端的 cm_cookie_storage（MMKV）里扒出来的 19 个 cookie，
 * 按来源分三类：
 *
 * 1) 设备信息类 —— 每次请求现算，跟 Build/屏幕走
 *    brand / mobilename / osver / resolution / screenType
 * 2) 客户端常量 —— 客户端版本相关，固定值
 *    os=android, appver=9.6.22, versioncode=9006022, packageType=release,
 *    channel, buildver=261106194426, EVNSM=1.0.0, ntes_kaola_ad=1
 * 3) 首次启动生成并持久化 —— 服务端只要求「同一个设备始终一致」
 *    NMCID   = 随机6字母.毫秒时间戳.01.4
 *    deviceId / sDeviceId = base64("\tMAC\t16hex\t16hex") 再 URL 编码
 *    __csrf  = 32 位 hex
 *    NMTID   = 服务端 Set-Cookie 下发，保存后回传
 *
 * 注意：NMDI（base64 后以 "CSJM" 开头的加密设备信息）和 URS_APPID（192 位 hex）
 * 无法从设备信息推导，客户端内部另有算法/服务端下发，这里不做伪造。
 */
public final class DeviceProfile {

    static final String KEY_PROFILE = "device_profile";

    /** 官方客户端版本，决定 appver/versioncode/buildver 三个字段的一致性。 */
    private static final String APP_VER = "9.6.22";
    private static final String VERSION_CODE = "9006022";
    private static final String BUILD_VER = "261106194426";
    private static final String CHANNEL = "netease";

    private static final SecureRandom RND = new SecureRandom();
    private static final String HEX = "0123456789abcdef";
    private static final String LOW = "abcdefghijklmnopqrstuvwxyz";

    private static JSONObject cached;

    private DeviceProfile() {
    }

    public static synchronized JSONObject get(Context c) {
        if (cached != null) return cached;
        Context app = c.getApplicationContext();
        SharedPreferences sp = app.getSharedPreferences(MainActivity.SP, Context.MODE_PRIVATE);
        JSONObject o = null;
        try {
            o = new JSONObject(sp.getString(KEY_PROFILE, "{}"));
        } catch (Exception ignored) {
        }
        // 只要 NMCID 在，就认为这份身份已经固定了，不再重算
        if (o == null || o.optString("NMCID").isEmpty()) {
            o = build(app);
            sp.edit().putString(KEY_PROFILE, o.toString()).apply();
        }
        cached = o;
        return o;
    }

    private static JSONObject build(Context c) {
        JSONObject o = new JSONObject();
        try {
            // ---- 客户端常量 ----
            o.put("os", "android");
            o.put("appver", APP_VER);
            o.put("versioncode", VERSION_CODE);
            o.put("packageType", "release");
            o.put("channel", CHANNEL);
            o.put("buildver", BUILD_VER);
            o.put("EVNSM", "1.0.0");
            o.put("ntes_kaola_ad", "1");

            // ---- 设备信息（现算） ----
            o.put("brand", capitalize(Build.BRAND));
            o.put("mobilename", Build.MODEL == null ? "" : Build.MODEL);
            o.put("osver", Build.VERSION.RELEASE == null ? "" : Build.VERSION.RELEASE);
            DisplayMetrics dm = c.getResources().getDisplayMetrics();
            int longSide = Math.max(dm.widthPixels, dm.heightPixels);
            int shortSide = Math.min(dm.widthPixels, dm.heightPixels);
            // 官方客户端的格式是「高x宽」，如 2320x1080
            o.put("resolution", longSide + "x" + shortSide);
            o.put("screenType", dm.densityDpi >= 440 ? "xxhdpi"
                    : dm.densityDpi >= 320 ? "xhdpi" : "other");

            // ---- 首次生成后固定 ----
            o.put("NMCID", randLetters(6) + "." + System.currentTimeMillis() + ".01.4");
            o.put("__csrf", randHex(32));
            // Android 10+ 拿不到真实 MAC，官方客户端在同一系统上读到的也是
            // 02:00:00:00:00:00，所以这里直接用这个占位值
            String raw = "\t02:00:00:00:00:00\t" + randHex(16) + "\t" + randHex(16);
            String b64 = Base64.encodeToString(raw.getBytes("UTF-8"), Base64.NO_WRAP);
            String enc = URLEncoder.encode(b64, "UTF-8");
            o.put("deviceId", enc);
            o.put("sDeviceId", enc);
        } catch (Exception ignored) {
        }
        return o;
    }

    /** 追加到请求 Cookie 的尾巴（不含 MUSIC_U / __csrf / NMTID，那几个单独管）。 */
    static String extraCookie(Context c) {
        JSONObject o = get(c);
        StringBuilder sb = new StringBuilder();
        String[] keys = {"os", "appver", "versioncode", "packageType", "channel",
                "buildver", "EVNSM", "ntes_kaola_ad", "brand", "mobilename", "osver",
                "resolution", "screenType", "NMCID", "deviceId", "sDeviceId"};
        for (String k : keys) {
            String v = o.optString(k, "");
            if (v.isEmpty()) continue;
            if (sb.length() > 0) sb.append("; ");
            sb.append(k).append('=').append(v);
        }
        return sb.toString();
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return "";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String randLetters(int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(LOW.charAt(RND.nextInt(LOW.length())));
        return sb.toString();
    }

    private static String randHex(int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(HEX.charAt(RND.nextInt(16)));
        return sb.toString();
    }
}
