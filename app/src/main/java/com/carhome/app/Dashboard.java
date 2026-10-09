package com.carhome.app;

import android.content.Context;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** لوحة بيانات السيارة (ودجت مدمج): سرعة، نمط، غِيار، بطارية، وقود، مدى، قدرة، دورات، عدّاد. */
final class Dashboard {
    final ScrollView view;
    private final Context ctx;
    private final TextView vSpeed, vMode, vGear, vSource, vEvRange, vRange, vPower, vRpm, vOdo;
    private final RingGauge gBattery, gFuel;

    Dashboard(Context c) {
        ctx = c;
        view = new ScrollView(c);
        view.setVerticalScrollBarEnabled(false);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(c, 8), Ui.dp(c, 4), Ui.dp(c, 8), Ui.dp(c, 8));
        view.addView(col, new FrameLayout.LayoutParams(-1, -2));

        // سرعة + نمط + غِيار
        LinearLayout top = Ui.row(c);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout sCol = new LinearLayout(c);
        sCol.setOrientation(LinearLayout.VERTICAL);
        vSpeed = Ui.text(c, "—", 72, Ui.TEXT, true);
        sCol.addView(vSpeed);
        sCol.addView(Ui.text(c, "km/h", 16, Ui.MUTED, false));
        top.addView(sCol, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout rCol = new LinearLayout(c);
        rCol.setOrientation(LinearLayout.VERTICAL);
        rCol.setGravity(Gravity.CENTER_HORIZONTAL);
        vMode = Ui.text(c, "—", 18, 0xFFFFFFFF, true);
        vMode.setGravity(Gravity.CENTER);
        vMode.setPadding(Ui.dp(c, 14), Ui.dp(c, 6), Ui.dp(c, 14), Ui.dp(c, 6));
        rCol.addView(vMode);
        vGear = Ui.text(c, "—", 40, Ui.TEXT, true);
        vGear.setGravity(Gravity.CENTER);
        rCol.addView(vGear);
        vSource = Ui.text(c, "", 12, Ui.MUTED, false);
        vSource.setGravity(Gravity.CENTER);
        rCol.addView(vSource);
        top.addView(rCol, new LinearLayout.LayoutParams(Ui.dp(c, 150), -2));
        col.addView(top, new LinearLayout.LayoutParams(-1, -2));

        // عدادات دائرية
        LinearLayout gauges = Ui.row(c);
        gauges.setGravity(Gravity.CENTER);
        gBattery = new RingGauge(c, "البطارية", Ui.EV);
        gBattery.setLowWarning(15);
        gFuel = new RingGauge(c, "الوقود", Ui.ENG);
        gFuel.setLowWarning(12);
        vEvRange = Ui.text(c, "—", 14, Ui.MUTED, false);
        vRange = Ui.text(c, "—", 14, Ui.MUTED, false);
        gauges.addView(gaugeColumn(gBattery, vEvRange), new LinearLayout.LayoutParams(0, -2, 1f));
        gauges.addView(gaugeColumn(gFuel, vRange), new LinearLayout.LayoutParams(0, -2, 1f));
        col.addView(gauges, new LinearLayout.LayoutParams(-1, -2));

        // بطاقات صغيرة
        LinearLayout tiles = Ui.row(c);
        vPower = Ui.tile(tiles, "kW", Ui.EV);
        vRpm = Ui.tile(tiles, "rpm", Ui.ENG);
        vOdo = Ui.tile(tiles, "العدّاد", Ui.TEXT);
        for (TextView t : new TextView[]{vPower, vRpm, vOdo}) t.setTextSize(22);
        col.addView(tiles, new LinearLayout.LayoutParams(-1, -2));
    }

    private LinearLayout gaugeColumn(RingGauge g, TextView under) {
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        int s = Ui.dp(ctx, 150);
        col.addView(g, new LinearLayout.LayoutParams(s, s));
        under.setGravity(Gravity.CENTER);
        col.addView(under);
        return col;
    }

    static String gearName(Integer g) {
        if (g == null) return "—";
        switch (g) {
            case 1: return "P";
            case 2: return "R";
            case 3: return "N";
            case 4: return "D";
            default: return String.valueOf(g);
        }
    }

    static int modeColor(Integer m) {
        if (m == null) return 0xFF455A64;
        switch (m) {
            case 0: return 0xFF2E7D32;   // ECO
            case 1: return 0xFF1E88E5;   // NORMAL
            case 2: return 0xFFE53935;   // SPORT
            default: return 0xFF6A1B9A;  // أخرى
        }
    }

    private static String km(Float v) { return v == null ? "—" : Ui.num(v, 0) + " km"; }

    void render(Live l) {
        Float speed = l.speedKmh;
        vSpeed.setText(speed == null ? "—" : Ui.num(speed, 0));
        boolean parked = l.gear != null && l.gear == 1;
        vMode.setText(parked ? "PARK" : Live.modeName(l.driveMode));
        vMode.setBackground(Ui.round(parked ? 0xFF1565C0 : modeColor(l.driveMode), Ui.dp(ctx, 12)));
        vGear.setText(gearName(l.gear));
        vSource.setText((l.engineOn ? "⛽ المحرك يعمل" : "⚡ كهرباء") + "\n" + l.source);
        gBattery.setValue(l.socPct);
        gFuel.setValue(l.fuelPct);
        vEvRange.setText("مدى كهربائي: " + km(l.evRangeKm));
        vRange.setText("المدى الكلي: " + km(l.rangeKm));
        vPower.setText(l.packKw == null ? "—" : Ui.num(l.packKw, 1));
        vRpm.setText(l.rpm == null ? "—" : String.valueOf(l.rpm));
        vOdo.setText(km(l.odometerKm));
    }
}
