package com.mbox.music;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 悬浮胶囊播放条。挂在页面底部，监听 PlayerManager 自动刷新。
 * 在 onAttachedToWindow / onDetachedFromWindow 里自动注册与注销监听，调用方无需管理。
 */
public class MiniPlayerBar extends LinearLayout implements PlayerManager.Listener {

    private PlayerManager pm;
    private ImageView cover;
    private TextView title, sub;
    private ImageView play;
    private String boundCover;

    public MiniPlayerBar(Context c) {
        this(c, null);
    }

    public MiniPlayerBar(Context c, AttributeSet a) {
        super(c, a);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setBackgroundResource(R.drawable.bg_pill_player);
        setClickable(true);
        setFocusable(true);
        // 阴影要贴合圆角：outline 取自圆角背景。注意不能 setClipToOutline(true)，
        // 那会把 outline 之外的阴影一起裁掉，阴影就没了（封面自己已在 XML 里裁剪）。
        setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        setElevation(10 * getResources().getDisplayMetrics().density);
        LayoutInflater.from(c).inflate(R.layout.view_mini_player, this, true);

        cover = findViewById(R.id.miniCover);
        title = findViewById(R.id.miniTitle);
        sub = findViewById(R.id.miniSub);
        play = findViewById(R.id.miniPlay);

        pm = PlayerManager.get(c);
        play.setOnClickListener(v -> pm.toggle());
        findViewById(R.id.miniNext).setOnClickListener(v -> pm.next());
        findViewById(R.id.miniQueue).setOnClickListener(v -> {
            if (getContext() instanceof Activity) QueueDialog.show((Activity) getContext());
        });
        setOnClickListener(v -> {
            if (getContext() instanceof Activity) {
                getContext().startActivity(new Intent(getContext(), PlayerActivity.class));
            }
        });
        update();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        pm.addListener(this);
        update();
    }

    @Override
    protected void onDetachedFromWindow() {
        pm.removeListener(this);
        super.onDetachedFromWindow();
    }

    @Override
    public void onPlayerChanged() {
        update();
    }

    private void update() {
        NeteaseApi.Song s = pm.current();
        if (s == null) {
            setVisibility(GONE);
            return;
        }
        if (getVisibility() != VISIBLE) setVisibility(VISIBLE);
        title.setText(s.name);
        // 胶囊里只显示歌手，不显示码率
        sub.setText(pm.isPreparing() ? s.artist + " · 缓冲中…" : s.artist);
        play.setAlpha(pm.isPreparing() ? 0.55f : 1f);
        if (!pm.isPreparing()) {
            play.setImageResource(pm.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
        }
        if (!s.picUrl.equals(boundCover)) {
            boundCover = s.picUrl;
            CoverLoader.into(cover, s.picUrl, 160);
        }
    }
}
