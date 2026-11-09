package com.mbox.music;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 常驻顶部导航：首页 / 搜索 / 设置。在 MainActivity 里是同级面板切换，其他页面则回到主界面。 */
public class TopTabs extends LinearLayout {

    public interface OnTabSelected {
        void onTab(int i);
    }

    private final TextView[] tabs = new TextView[3];
    private OnTabSelected listener;

    public TopTabs(Context c) {
        this(c, null);
    }

    public TopTabs(Context c, AttributeSet a) {
        super(c, a);
        setOrientation(HORIZONTAL);
        setBackgroundColor(Color.parseColor("#12151A"));
        setPadding(0, Ui.statusBarHeight(c), 0, 0);
        LayoutInflater.from(c).inflate(R.layout.view_top_tabs, this, true);

        tabs[0] = findViewById(R.id.tabHome);
        tabs[1] = findViewById(R.id.tabSearch);
        tabs[2] = findViewById(R.id.tabSettings);

        for (int i = 0; i < 3; i++) {
            final int idx = i;
            tabs[i].setOnClickListener(v -> select(idx));
        }
        setCurrent(current(c));
    }

    public void setOnTabSelected(OnTabSelected l) {
        listener = l;
    }

    public void setCurrent(int cur) {
        for (int i = 0; i < 3; i++) {
            boolean on = i == cur;
            tabs[i].setTextColor(Color.parseColor(on ? "#F2F4F7" : "#6B727C"));
            tabs[i].setTypeface(null, on
                    ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            tabs[i].setBackgroundResource(on ? R.drawable.bg_tab_active : 0);
        }
    }

    private void select(int i) {
        if (listener != null) {
            listener.onTab(i);
            return;
        }
        // 非主界面：回到 MainActivity 并指定目标 tab
        Context c = getContext();
        Intent in = new Intent(c, MainActivity.class);
        in.putExtra("tab", i);
        in.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        c.startActivity(in);
        if (c instanceof Activity && !(c instanceof MainActivity)) {
            ((Activity) c).finish();
        }
    }

    private static int current(Context c) {
        if (c instanceof MainActivity) return 0;
        return -1;
    }
}
