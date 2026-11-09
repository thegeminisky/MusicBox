package com.mbox.music;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.provider.Settings;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

/** 让内容延伸到状态栏下方，状态栏透明，避免顶部出现一条独立的色带。 */
public final class Ui {

    private Ui() {
    }

    public static int statusBarHeight(android.content.Context c) {
        int id = c.getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (id > 0) return c.getResources().getDimensionPixelSize(id);
        return (int) (24 * c.getResources().getDisplayMetrics().density);
    }

    public static void immersiveStatusBar(Activity act) {
        Window w = act.getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        // 状态栏与 TopTabs 用同一个不透明色，两者连成一整条，不会有色差
        w.setStatusBarColor(Color.parseColor("#FF12151A"));
        w.setNavigationBarColor(Color.parseColor("#0E1013"));
        w.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    /** 不带顶部菜单的页面：状态栏与页面背景同色，内容同样延伸到通知栏区域。 */
    public static void immersivePlain(Activity act) {
        Window w = act.getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        w.setStatusBarColor(Color.parseColor("#FF0E1013"));
        w.setNavigationBarColor(Color.parseColor("#0E1013"));
        w.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        // 根布局自己让出状态栏高度，避免标题/按钮被图标压住
        android.view.ViewGroup content =
                (android.view.ViewGroup) act.findViewById(android.R.id.content);
        if (content != null && content.getChildCount() > 0) {
            View root = content.getChildAt(0);
            root.setPadding(root.getPaddingLeft(), statusBarHeight(act),
                    root.getPaddingRight(), root.getPaddingBottom());
        }
    }

    // ------------------------------------------------------------ 保活引导

    /** 打开 MIUI 的自启动管理；跳不过去就退到应用详情页。 */
    public static void openAutoStart(Activity a) {
        try {
            a.startActivity(new Intent().setComponent(new ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity")));
            return;
        } catch (Throwable ignored) {
        }
        openAppDetail(a);
    }

    /** 打开省电策略。MIUI 走 powerkeeper，其他系统退到电池优化列表，再不行走应用详情。 */
    public static void openBattery(Activity a) {
        try {
            Intent i = new Intent();
            i.setComponent(new ComponentName("com.miui.powerkeeper",
                    "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"));
            i.putExtra("package_name", a.getPackageName());
            i.putExtra("package_label", "云乐盒");
            a.startActivity(i);
            return;
        } catch (Throwable ignored) {
        }
        try {
            a.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            return;
        } catch (Throwable ignored) {
        }
        openAppDetail(a);
    }

    private static void openAppDetail(Activity a) {
        try {
            a.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + a.getPackageName())));
        } catch (Throwable ignored) {
        }
    }
}
