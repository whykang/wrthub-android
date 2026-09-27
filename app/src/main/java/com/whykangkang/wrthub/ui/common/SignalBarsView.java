package com.whykangkang.wrthub.ui.common;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * WiFi 信号格,对应 iOS SignalBarsView。
 * 4 格高低递增,按 dBm 分档点亮;有线设备(bars=-1)不绘制。
 */
public class SignalBarsView extends View {

    private static final int BAR_COUNT = 4;

    private int activeBars = 0;
    private boolean wired = false;

    private final Paint activePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint inactivePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public SignalBarsView(Context context) {
        this(context, null);
    }

    public SignalBarsView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        activePaint.setColor(0xFF34C759);      // iOS 绿
        inactivePaint.setColor(0x33999999);
    }

    /** bars: 0-4 点亮格数;-1 表示有线(隐藏) */
    public void setBars(int bars) {
        this.wired = bars < 0;
        this.activeBars = Math.max(0, Math.min(BAR_COUNT, bars));
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = (int) (BAR_COUNT * 6 * getResources().getDisplayMetrics().density);
        int h = (int) (16 * getResources().getDisplayMetrics().density);
        setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(h, heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (wired) return;
        int w = getWidth();
        int h = getHeight();
        float gap = w * 0.12f / (BAR_COUNT - 1);
        float barWidth = (w - gap * (BAR_COUNT - 1)) / BAR_COUNT;
        for (int i = 0; i < BAR_COUNT; i++) {
            float left = i * (barWidth + gap);
            float barHeight = h * (0.4f + 0.2f * i);
            float top = h - barHeight;
            Paint p = i < activeBars ? activePaint : inactivePaint;
            canvas.drawRoundRect(left, top, left + barWidth, h, 3f, 3f, p);
        }
    }
}