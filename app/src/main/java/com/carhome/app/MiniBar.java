package com.carhome.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** شريط تقدّم صغير (للبطارية والوقود في الشريط العلوي). */
final class MiniBar extends View {
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
        float w = getWidth(), h = getHeight(), r = h / 2f;
        paint.setColor(Ui.CARD2);
        box.set(0, 0, w, h);
        c.drawRoundRect(box, r, r, paint);
        if (!Float.isNaN(pct) && pct > 0) {
            paint.setColor(pct < low ? Ui.BAD : color);
            box.set(0, 0, Math.max(h, w * pct / 100f), h);
            c.drawRoundRect(box, r, r, paint);
        }
    }
}
