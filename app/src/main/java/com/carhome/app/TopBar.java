package com.carhome.app;

import android.content.Context;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** الشريط العلوي (القسم الرابع): بيانات السيارة الحية + الساعة. */
final class TopBar {
    final LinearLayout view;
    private final Context ctx;
    private final TextView vSpeed, vMode, vGear, vBatPct, vBatRange, vFuelPct, vFuelRange, vPower, vRpm, vClock, vState;
    private final MiniBar bBattery, bFuel;
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.getDefault());

    TopBar(Context c) {
        ctx = c;
        view = Ui.card(c);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setPadding(Ui.dp(c, 14), Ui.dp(c, 8), Ui.dp(c, 14), Ui.dp(c, 8));

        // السرعة
        LinearLayout speed = col(1.0f);
        vSpeed = Ui.text(c, "—", 40, Ui.TEXT, true);
        speed.addView(vSpeed);
        speed.addView(Ui.text(c, "km/h", 12, Ui.MUTED, false));

        // النمط + الغِيار
        LinearLayout mode = col(1.0f);
        mode.setGravity(Gravity.CENTER);
        vMode = Ui.text(c, "—", 15, 0xFFFFFFFF, true);
        vMode.setGravity(Gravity.CENTER);
        vMode.setPadding(Ui.dp(c, 12), Ui.dp(c, 4), Ui.dp(c, 12), Ui.dp(c, 4));
        mode.addView(vMode);
        vGear = Ui.text(c, "—", 24, Ui.TEXT, true);
        vGear.setGravity(Gravity.CENTER);
        mode.addView(vGear);

        // البطارية
        LinearLayout bat = col(1.7f);
        LinearLayout batHead = Ui.row(c);
        batHead.setGravity(Gravity.CENTER_VERTICAL);
        batHead.addView(Ui.text(c, "🔋 البطارية", 12, Ui.MUTED, false), new LinearLayout.LayoutParams(0, -2, 1f));
        vBatPct = Ui.text(c, "—", 20, Ui.EV, true);
        batHead.addView(vBatPct);
        bat.addView(batHead);
        bBattery = new MiniBar(c, Ui.EV);
        bBattery.setLowWarning(15);
        bat.addView(bBattery, barLp());
        vBatRange = Ui.text(c, "—", 12, Ui.MUTED, false);
        bat.addView(vBatRange);

        // الوقود
        LinearLayout fuel = col(1.7f);
        LinearLayout fuelHead = Ui.row(c);
        fuelHead.setGravity(Gravity.CENTER_VERTICAL);
        fuelHead.addView(Ui.text(c, "⛽ الوقود", 12, Ui.MUTED, false), new LinearLayout.LayoutParams(0, -2, 1f));
        vFuelPct = Ui.text(c, "—", 20, Ui.ENG, true);
        fuelHead.addView(vFuelPct);
        fuel.addView(fuelHead);
        bFuel = new MiniBar(c, Ui.ENG);
        bFuel.setLowWarning(12);
        fuel.addView(bFuel, barLp());
        vFuelRange = Ui.text(c, "—", 12, Ui.MUTED, false);
        fuel.addView(vFuelRange);

        // القدرة والدورات
        LinearLayout power = col(0.8f);
        power.addView(Ui.text(c, "kW", 12, Ui.MUTED, false));
        vPower = Ui.text(c, "—", 22, Ui.EV, true);
        power.addView(vPower);
        LinearLayout rpm = col(0.8f);
        rpm.addView(Ui.text(c, "rpm", 12, Ui.MUTED, false));
        vRpm = Ui.text(c, "—", 22, Ui.ENG, true);
        rpm.addView(vRpm);

        // الساعة + حالة الطاقة
        LinearLayout clock = col(1.0f);
        clock.setGravity(Gravity.END);
        vClock = Ui.text(c, "--:--", 30, Ui.TEXT, true);
        vClock.setGravity(Gravity.END);
        clock.addView(vClock);
        vState = Ui.text(c, "", 12, Ui.MUTED, false);
        vState.setGravity(Gravity.END);
        clock.addView(vState);

        for (LinearLayout l : new LinearLayout[]{speed, mode, bat, fuel, power, rpm, clock}) view.addView(l);
    }

    private LinearLayout col(float weight) {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, weight);
        lp.setMargins(Ui.dp(ctx, 8), 0, Ui.dp(ctx, 8), 0);
        l.setLayoutParams(lp);
        return l;
    }

    private LinearLayout.LayoutParams barLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(ctx, 10));
        lp.topMargin = Ui.dp(ctx, 2);
        lp.bottomMargin = Ui.dp(ctx, 2);
        return lp;
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
        vClock.setText(timeFmt.format(new Date()));
        Float speed = l.speedKmh;
        vSpeed.setText(speed == null ? "—" : Ui.num(speed, 0));
        boolean parked = l.gear != null && l.gear == 1;
        vMode.setText(parked ? "PARK" : Live.modeName(l.driveMode));
        vMode.setBackground(Ui.round(parked ? 0xFF1565C0 : modeColor(l.driveMode), Ui.dp(ctx, 10)));
        vGear.setText(gearName(l.gear));
        vBatPct.setText(Ui.pct(l.socPct));
        bBattery.setValue(l.socPct);
        vBatRange.setText("المدى: " + km(l.evRangeKm));
        vFuelPct.setText(Ui.pct(l.fuelPct));
        bFuel.setValue(l.fuelPct);
        vFuelRange.setText("المدى: " + km(l.rangeKm));
        vPower.setText(l.packKw == null ? "—" : Ui.num(l.packKw, 1));
        vRpm.setText(l.rpm == null ? "—" : String.valueOf(l.rpm));
        vState.setText(l.engineOn ? "⛽ المحرك يعمل" : "⚡ كهرباء");
    }
}
