package com.mbox.music;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.View;

/**
 * 当前播放曲目左侧的律动条。播放时四根柱子按随机高度跳，暂停时定格。
 * 不用属性动画，Handler + invalidate 足够轻，ListView 复用时也不会泄漏。
 */
public class EqualizerView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] levels = {0.35f, 0.75f, 0.5f, 0.9f};
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean playing;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!playing) return;
            for (int i = 0; i < levels.length; i++) {
                levels[i] = 0.18f + (float) Math.random() * 0.82f;
            }
            invalidate();
            ui.postDelayed(this, 120);
        }
    };

    public EqualizerView(Context c) {
        this(c, null);
    }

    public EqualizerView(Context c, AttributeSet a) {
        super(c, a);
        paint.setColor(Color.parseColor("#E5484D"));
        paint.setStyle(Paint.Style.FILL);
    }

    public void setPlaying(boolean p) {
        if (playing == p) return;
        playing = p;
        ui.removeCallbacks(tick);
        if (p) ui.post(tick);
        else invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int n = levels.length;
        float slot = getWidth() / (float) n;
        float barW = slot * 0.52f;
        float h = getHeight();
        for (int i = 0; i < n; i++) {
            float x = slot * i + (slot - barW) / 2f;
            float bh = h * levels[i];
            canvas.drawRoundRect(x, h - bh, x + barW, h, barW / 2f, barW / 2f, paint);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (playing) {
            ui.removeCallbacks(tick);
            ui.post(tick);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        ui.removeCallbacks(tick);
        super.onDetachedFromWindow();
    }
}
