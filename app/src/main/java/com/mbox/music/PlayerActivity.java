package com.mbox.music;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

public class PlayerActivity extends Activity implements PlayerManager.Listener {

    private PlayerManager pm;
    private ImageView ivCover;
    private TextView tvTitle, tvArtist, tvAlbum, tvPos, tvDur;
    private ImageView btnPlay, btnMode;
    private SeekBar seek;
    private boolean dragging;
    private String boundCover;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_player);
        setupFullscreen();
        overridePendingTransition(R.anim.slide_up, 0);

        pm = PlayerManager.get(this);
        if (pm.current() == null) {
            Toast.makeText(this, "还没有在播放的歌曲", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        ivCover = findViewById(R.id.ivCover);
        tvTitle = findViewById(R.id.tvTitle);
        tvArtist = findViewById(R.id.tvArtist);
        tvAlbum = findViewById(R.id.tvAlbum);
        tvPos = findViewById(R.id.tvPos);
        tvDur = findViewById(R.id.tvDur);
        btnPlay = findViewById(R.id.btnPlay);
        btnMode = findViewById(R.id.btnMode);
        seek = findViewById(R.id.seek);

        // 封面取屏幕宽度与小屏高度里较小的那个，保证居中且不被裁
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        int size = Math.min(w - dp(104), (int) (h * 0.40f));
        ivCover.setLayoutParams(new FrameLayout.LayoutParams(size, size, Gravity.CENTER));

        findViewById(R.id.btnBack).setOnClickListener(v -> closeDrawer());
        findViewById(R.id.btnPrev).setOnClickListener(v -> pm.prev());
        findViewById(R.id.btnNext).setOnClickListener(v -> pm.next());
        btnPlay.setOnClickListener(v -> pm.toggle());
        btnMode.setOnClickListener(v -> {
            pm.cycleMode();
            Toast.makeText(this, PlayerManager.modeLabel(pm.mode()), Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.btnQueue).setOnClickListener(v -> QueueDialog.show(this));

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (fromUser) tvPos.setText(mmss((long) pm.duration() * p / 1000));
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
                dragging = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                dragging = false;
                pm.seekTo((int) ((long) pm.duration() * sb.getProgress() / 1000));
            }
        });

        render();
    }

    /** 抽屉式全屏：状态栏完全透明，背景延伸到通知栏下，内容避开状态栏图标。 */
    private void setupFullscreen() {
        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.parseColor("#0E1013"));
        w.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        View root = ((android.view.ViewGroup) findViewById(android.R.id.content))
                .getChildAt(0);
        if (root != null) {
            root.setPadding(root.getPaddingLeft(), Ui.statusBarHeight(this),
                    root.getPaddingRight(), root.getPaddingBottom());
        }
    }

    private void closeDrawer() {
        finish();
        overridePendingTransition(0, R.anim.slide_down);
    }

    @Override
    public void onBackPressed() {
        closeDrawer();
    }

    @Override
    protected void onResume() {
        super.onResume();
        pm.addListener(this);
        render();
    }

    @Override
    protected void onPause() {
        pm.removeListener(this);
        super.onPause();
    }

    @Override
    public void onPlayerChanged() {
        render();
    }

    @Override
    public void onProgress(int pos, int dur) {
        if (dragging) return;
        tvPos.setText(mmss(pos));
        tvDur.setText(mmss(dur));
        seek.setProgress(dur > 0 ? (int) ((long) pos * 1000 / dur) : 0);
    }

    private void render() {
        NeteaseApi.Song s = pm.current();
        if (s == null) {
            finish();
            return;
        }
        tvTitle.setText(s.name);
        tvArtist.setText(s.artist);
        tvAlbum.setText(s.album);

        if (pm.isPreparing()) {
            btnPlay.setAlpha(0.55f);
            tvAlbum.setText(s.album.isEmpty() ? "正在获取播放地址…" : s.album + " · 正在获取播放地址…");
        } else {
            btnPlay.setAlpha(1f);
            btnPlay.setImageResource(pm.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
        }
        if (pm.lastError() != null) tvAlbum.setText(pm.lastError());

        btnMode.setImageResource(PlayerManager.modeIconRes(pm.mode()));
        btnMode.setAlpha(pm.mode() == PlayerManager.MODE_SEQUENCE ? 0.45f : 1f);

        if (!s.picUrl.equals(boundCover)) {
            boundCover = s.picUrl;
            CoverLoader.into(ivCover, s.picUrl, 640);
        }

        if (!dragging) {
            int d = pm.duration();
            int p = pm.position();
            tvDur.setText(mmss(d));
            tvPos.setText(mmss(p));
            seek.setProgress(d > 0 ? (int) ((long) p * 1000 / d) : 0);
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    static String mmss(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000;
        return String.format("%d:%02d", s / 60, s % 60);
    }
}
