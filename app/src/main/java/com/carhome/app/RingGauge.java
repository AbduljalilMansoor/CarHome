package com.carhome.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/** عداد دائري لنسبة (البطارية أو الوقود): قوس بالنسبة، والقيمة في الوسط، وتحتها العنوان. */
final class RingGauge extends View {
    private static final float START = 135f, SWEEP = 270f;   // قوس مفتوح من الأسفل

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint big = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final String label;
    private final int color;
    private float pct = Float.NaN;    // القيمة المعروضة (تتحرك بنعومة نحو target)
    private float target = Float.NaN;
    private float lowPct = -1;        // تحت هذه النسبة يصبح اللون أحمر

    RingGauge(Context c, String label, int color) {
        super(c);
        this.label = label;
        this.color = color;
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeCap(Paint.Cap.ROUND);
        track.setColor(Ui.CARD2);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeCap(Paint.Cap.ROUND);
        big.setColor(Ui.TEXT);
        big.setTextAlign(Paint.Align.CENTER);
        big.setTypeface(Typeface.DEFAULT_BOLD);
        small.setColor(Ui.MUTED);
        small.setTextAlign(Paint.Align.CENTER);
    }

    void setLowWarning(float below) { lowPct = below; }

    void setValue(Float v) {
        float t = v == null ? Float.NaN : Math.max(0, Math.min(100, v));
        if (Float.isNaN(t) ? Float.isNaN(target) : t == target) return;
        target = t;
        if (Float.isNaN(pct) || Float.isNaN(t)) pct = t;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float s = Math.min(getWidth(), getHeight());
        float stroke = s * 0.09f;
        float cx = getWidth() / 2f, cy = getHeight() / 2f, r = (s - stroke) / 2f;
        box.set(cx - r, cy - r, cx + r, cy + r);

        track.setStrokeWidth(stroke);
        c.drawArc(box, START, SWEEP, false, track);

        // حركة ناعمة نحو القيمة الجديدة
        if (!Float.isNaN(target) && Math.abs(target - pct) > 0.05f) {
            pct += (target - pct) * 0.2f;
            postInvalidateOnAnimation();
        } else {
            pct = target;
        }

        boolean has = !Float.isNaN(pct);
        if (has && pct > 0.5f) {
            arc.setStrokeWidth(stroke);
            arc.setColor(lowPct > 0 && pct < lowPct ? Ui.BAD : color);
            c.drawArc(box, START, SWEEP * pct / 100f, false, arc);
        }

        big.setTextSize(s * 0.22f);
        small.setTextSize(s * 0.095f);
        c.drawText(has ? Math.round(pct) + "%" : "—", cx, cy + s * 0.06f, big);
        c.drawText(label, cx, cy + s * 0.20f, small);
    }
}
