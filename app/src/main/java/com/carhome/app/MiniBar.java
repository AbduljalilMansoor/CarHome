package com.carhome.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** شريط مقطّع إلى 10 شُرط (للبطارية والوقود في الشريط العلوي). */
final class MiniBar extends View {
    private static final int SEGMENTS = 10;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final int color;
    private float pct = Float.NaN;
    private float low = -1;

    MiniBar(Context c, int color) {
        super(c);
        this.color = color;
    }

    void setLowWarning(float below) { low = below; }

    void setValue(Float v) {
        float t = v == null ? Float.NaN : Math.max(0, Math.min(100, v));
        if (Float.compare(t, pct) != 0) { pct = t; invalidate(); }
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float gap = Math.max(3f, w * 0.015f);
        float seg = (w - gap * (SEGMENTS - 1)) / SEGMENTS;
        float r = Math.min(h / 3f, seg / 2f);
        int fillColor = !Float.isNaN(pct) && pct < low ? Ui.BAD : color;
        for (int i = 0; i < SEGMENTS; i++) {
            float left = i * (seg + gap);
            paint.setColor(Ui.CARD2);
            box.set(left, 0, left + seg, h);
            c.drawRoundRect(box, r, r, paint);
            if (!Float.isNaN(pct)) {
                float fill = Math.min(1f, (pct - i * 10f) / 10f);   // كل شرطة = 10%
                if (fill > 0) {
                    paint.setColor(fillColor);
                    box.set(left, 0, left + seg * fill, h);
                    c.drawRoundRect(box, r, r, paint);
                }
            }
        }
    }
}
