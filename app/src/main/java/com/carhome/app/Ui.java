package com.carhome.app;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** ألوان وأدوات رسم وتنسيق مشتركة. */
final class Ui {
    static final int BG = 0xFF0B1116;
    static final int CARD = 0xFF16212A;
    static final int CARD2 = 0xFF1D2B36;
    static final int LINE = 0xFF2A3A47;
    static final int TEXT = 0xFFECEFF1;
    static final int MUTED = 0xFF8FA3B1;
    static final int EV = 0xFF29B6F6;      // أزرق كهرباء
    static final int ENG = 0xFFFFA726;     // برتقالي محرك
    static final int GOOD = 0xFF43A047;
    static final int BAD = 0xFFE53935;

    private Ui() { }

    static int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    static LinearLayout card(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(round(CARD, dp(c, 16)));
        int p = dp(c, 16);
        l.setPadding(p, p, p, p);
        return l;
    }

    /** بطاقة بعنوان صغير وقيمة كبيرة. تعيد TextView القيمة لتحديثها لاحقاً. */
    static TextView tile(LinearLayout row, String label, int valueColor) {
        Context c = row.getContext();
        LinearLayout card = card(c);
        card.addView(text(c, label, 14, MUTED, false));
        TextView v = text(c, "—", 30, valueColor, true);
        v.setSingleLine(true);
        card.addView(v);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        int m = dp(c, 6);
        lp.setMargins(m, m, m, m);
        row.addView(card, lp);
        return v;
    }

    static LinearLayout row(Context c) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        return r;
    }

    static TextView button(Context c, String s, int color, View.OnClickListener l) {
        TextView b = text(c, s, 22, 0xFFFFFFFF, true);
        b.setGravity(Gravity.CENTER);
        b.setBackground(round(color, dp(c, 14)));
        int p = dp(c, 18);
        b.setPadding(p * 2, p, p * 2, p);
        b.setOnClickListener(l);
        b.setClickable(true);
        return b;
    }

    // ---------- تنسيق ----------
    static String dur(long ms) {
        long m = ms / 60000;
        return String.format(Locale.US, "%d:%02d", m / 60, m % 60);
    }

    static String clock(long ms) {
        long s = ms / 1000;
        return String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
    }

    static String num(double v, int d) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "—";
        return String.format(Locale.US, "%." + d + "f", v);
    }

    static String pct(float v) { return Float.isNaN(v) ? "—" : String.format(Locale.US, "%.0f%%", v); }

    static String pct(Float v) { return v == null ? "—" : pct(v.floatValue()); }

    static String date(long wall) {
        return new SimpleDateFormat("yyyy-MM-dd  HH:mm", Locale.US).format(new Date(wall));
    }

    static String time(long wall) {
        return new SimpleDateFormat("HH:mm", Locale.US).format(new Date(wall));
    }

    static double avg(double km, long ms) { return ms > 0 ? km / (ms / 3_600_000.0) : Double.NaN; }
}
