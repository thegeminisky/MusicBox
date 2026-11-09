package com.mbox.music;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class CacheActivity extends Activity {

    private SharedPreferences sp;
    private AudioCache cache;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView tvTotal, tvAudioSize, tvCoverSize, tvOtherSize, tvCacheCount, tvSizeValue;
    private View segAudio, segCover, segOther;
    private Switch swPreload;
    private SeekBar seekSize;
    private EditText etSongs;




    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_cache);
        Ui.immersiveStatusBar(this);
        sp = getSharedPreferences(MainActivity.SP, MODE_PRIVATE);
        cache = AudioCache.get(this);

        tvTotal = findViewById(R.id.tvTotal);
        tvAudioSize = findViewById(R.id.tvAudioSize);
        tvCoverSize = findViewById(R.id.tvCoverSize);
        tvOtherSize = findViewById(R.id.tvOtherSize);
        tvCacheCount = findViewById(R.id.tvCacheCount);
        tvSizeValue = findViewById(R.id.tvSizeValue);
        segAudio = findViewById(R.id.segAudio);
        segCover = findViewById(R.id.segCover);
        segOther = findViewById(R.id.segOther);
        swPreload = findViewById(R.id.swPreload);
        seekSize = findViewById(R.id.seekSize);
        // 档位以数组为准，避免以后加档忘了同步改 XML 的 max
        seekSize.setMax(AudioCache.GB_STEPS.length - 1);
        etSongs = findViewById(R.id.etSongs);

        alignScaleTicks();
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        findViewById(R.id.rowPreload).setOnClickListener(v -> {
            boolean next = !sp.getBoolean(MainActivity.KEY_PRELOAD_NEXT, true);
            sp.edit().putBoolean(MainActivity.KEY_PRELOAD_NEXT, next).apply();
            swPreload.setChecked(next);
        });

        seekSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                tvSizeValue.setText(AudioCache.GB_STEPS[Math.min(p, AudioCache.GB_STEPS.length - 1)] + " GB");
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                sp.edit().putInt(MainActivity.KEY_CACHE_MAX_GB, sb.getProgress()).apply();
                new Thread(() -> cache.trim()).start();
            }
        });

        etSongs.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                int v = 0;
                try {
                    v = Integer.parseInt(e.toString().trim());
                } catch (Exception ignored) {
                }
                sp.edit().putInt(MainActivity.KEY_CACHE_MAX_SONGS, Math.max(0, v)).apply();
            }
        });

        findViewById(R.id.btnClear).setOnClickListener(v ->
                new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                        .setTitle("清空全部缓存")
                        .setMessage("会删除已下载的歌曲和封面图，下次播放需要重新联网。")
                        .setPositiveButton("清空", (d, w) -> {
                            cache.clearAll();
                            Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show();
                            render();
                        })
                        .setNegativeButton("取消", null)
                        .show());

        loadSettings();
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    /**
     * SeekBar 的 thumb 位置是 i/(n-1)，而等宽 weight 的刻度中心是 (i+0.5)/n，
     * 两者天然错位（1G、5G 尤其明显）。布局完成后按 thumb 真实 x 平移中间各档。
     * 首尾两档用 gravity=start/end 贴边，不参与平移以免被裁掉。
     */
    private void alignScaleTicks() {
        final int[] ids = {R.id.tick0, R.id.tick1, R.id.tick2, R.id.tick3,
                R.id.tick4, R.id.tick5, R.id.tick6, R.id.tick7};
        seekSize.post(() -> {
            int usable = seekSize.getWidth()
                    - seekSize.getPaddingLeft() - seekSize.getPaddingRight();
            if (usable <= 0) return;
            int n = ids.length;
            for (int i = 1; i < n - 1; i++) {
                View t = findViewById(ids[i]);
                if (t == null || t.getWidth() == 0) continue;
                float center = t.getLeft() + t.getWidth() / 2f;
                float thumbX = seekSize.getPaddingLeft() + (float) usable * i / (n - 1);
                t.setTranslationX(thumbX - center);
            }
        });
    }

    private void loadSettings() {
        swPreload.setChecked(sp.getBoolean(MainActivity.KEY_PRELOAD_NEXT, true));
        int idx = sp.getInt(MainActivity.KEY_CACHE_MAX_GB, 3);
        idx = Math.max(0, Math.min(idx, AudioCache.GB_STEPS.length - 1));
        seekSize.setProgress(idx);
        tvSizeValue.setText(AudioCache.GB_STEPS[idx] + " GB");
        etSongs.setText(String.valueOf(sp.getInt(MainActivity.KEY_CACHE_MAX_SONGS, 0)));
    }

    private void render() {
        new Thread(() -> {
            final long audio = cache.totalBytes();
            final long cover = cache.coverBytes();
            final long other = Math.max(0, cache.otherBytes());
            final int count = cache.count();
            ui.post(() -> applyStats(audio, cover, other, count));
        }).start();
    }

    private void applyStats(long audio, long cover, long other, int count) {
        long total = audio + cover + other;
        tvTotal.setText("共占用 " + AudioCache.sizeText(total));
        tvAudioSize.setText(AudioCache.sizeText(audio));
        tvCoverSize.setText(AudioCache.sizeText(cover));
        tvOtherSize.setText(AudioCache.sizeText(other));
        tvCacheCount.setText("已缓存 " + count + " 首");

        setWeight(segAudio, audio);
        setWeight(segCover, cover);
        setWeight(segOther, other);
    }

    private void setWeight(View v, long bytes) {
        android.widget.LinearLayout.LayoutParams lp =
                (android.widget.LinearLayout.LayoutParams) v.getLayoutParams();
        lp.width = 0;
        // 空类别给 0 权重，条上就看不到；全空时下面兜底给均分
        lp.weight = bytes > 0 ? bytes : 0;
        v.setLayoutParams(lp);
        v.setVisibility(bytes > 0 ? View.VISIBLE : View.GONE);
    }
}
