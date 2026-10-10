package com.carhome.app;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * ودجت حزام الأمان: حالة حزام السائق والراكب، والتنبيه الصوتي (تنفذه BeltService) عند القيادة بحزام غير مربوط.
 * الإعداد: لأن أسماء إشارات الحزام تختلف بين السيارات، تختار الإشارة الصحيحة من قائمة بقيمها الحية؛
 * وتسجَّل القيمة الحالية على أنها «مربوط/مشغول» (فاربط الحزام أو اجلس قبل الاختيار).
 */
final class SeatBeltWidget extends Widget {
    private final SharedPreferences prefs;
    private TextView vDriver, vPass;
    private LinearLayout box;
    private boolean lastAlarmLook;

    SeatBeltWidget(android.app.Activity a, CarBridge c) {
        super(a, c);
        prefs = a.getSharedPreferences(MainActivity.PREFS, android.content.Context.MODE_PRIVATE);
    }

    @Override String id() { return BELT; }
    @Override String title() { return "حزام الأمان"; }
    @Override String description() { return "حالة حزام السائق والراكب، وصفارة إنذار إن تحركت السيارة بحزام غير مربوط"; }
    @Override String icon() { return "🪢"; }
    @Override float weight() { return 1.4f; }

    @Override
    View createView() {
        box = new LinearLayout(act);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(Ui.dp(act, 8), Ui.dp(act, 4), Ui.dp(act, 8), Ui.dp(act, 4));
        vDriver = column(box, "السائق");
        vPass = column(box, "الراكب");
        box.setOnClickListener(v -> setupMenu());
        return box;
    }

    private TextView column(LinearLayout parent, String label) {
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.addView(Ui.text(act, label, 12, Ui.MUTED, false));
        TextView t = Ui.text(act, "—", 17, Ui.TEXT, true);
        t.setGravity(Gravity.CENTER);
        col.addView(t);
        parent.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        return t;
    }

    @Override
    void refresh() {
        if (vDriver == null) return;
        if (!BeltLogic.driverConfigured(prefs)) {
            vDriver.setText("اضغط للإعداد");
            vPass.setText("—");
            look(false);
            return;
        }
        BeltLogic s = BeltLogic.evaluate(car, prefs);
        vDriver.setText(s.driver < 0 ? "—" : s.driver == 1 ? "🟢 مربوط" : "🔴 غير مربوط");
        String pass;
        if (s.passOcc == 0) pass = "⚪ فارغ";
        else if (s.pass < 0) pass = s.passOcc == 1 ? "👤 جالس" : "—";
        else pass = s.pass == 1 ? "🟢 مربوط" : (s.passOcc == 1 || s.passOcc < 0) ? "🔴 غير مربوط" : "⚪ فارغ";
        vPass.setText(pass);
        look(s.needsAlarm());
    }

    /** وميض أحمر أثناء الإنذار. */
    private void look(boolean alarm) {
        boolean blink = alarm && (System.currentTimeMillis() / 500) % 2 == 0;
        if (blink == lastAlarmLook) return;
        lastAlarmLook = blink;
        box.setBackground(Ui.round(blink ? 0xFFC62828 : Ui.CARD2, Ui.dp(act, 14)));
    }

    // ---------------- الإعداد ----------------
    private String roleLine(String label, String keyName) {
        String n = prefs.getString(keyName, "");
        return label + "\n   " + (n.isEmpty() ? "غير مضبوط" : n);
    }

    private void setupMenu() {
        boolean on = BeltLogic.alarmEnabled(prefs);
        String[] items = {
                roleLine("① حزام السائق — اربطه ثم اختر الإشارة", BeltLogic.K_DNAME),
                roleLine("② حزام الراكب — اربطه ثم اختر الإشارة", BeltLogic.K_PNAME),
                roleLine("③ مقعد الراكب — اجعل شخصاً يجلس ثم اختر الإشارة", BeltLogic.K_ONAME),
                (on ? "☑" : "☐") + "  الصفارة عند القيادة بحزام غير مربوط",
                "مسح كل الإعدادات"
        };
        new AlertDialog.Builder(act).setTitle("إعداد حزام الأمان").setItems(items, (d, which) -> {
            if (which == 0) askKeyword("حزام السائق", BeltLogic.K_DNAME, BeltLogic.K_DVAL, "BELT");
            else if (which == 1) askKeyword("حزام الراكب", BeltLogic.K_PNAME, BeltLogic.K_PVAL, "BELT");
            else if (which == 2) askKeyword("مقعد الراكب", BeltLogic.K_ONAME, BeltLogic.K_OVAL, "OCC");
            else if (which == 3) {
                prefs.edit().putBoolean(BeltLogic.K_ALARM, !on).apply();
                BeltService.sync(act);
                setupMenu();
            } else {
                prefs.edit().remove(BeltLogic.K_DNAME).remove(BeltLogic.K_DVAL).remove(BeltLogic.K_PNAME)
                        .remove(BeltLogic.K_PVAL).remove(BeltLogic.K_ONAME).remove(BeltLogic.K_OVAL).apply();
                BeltService.sync(act);
                toast("تم المسح");
            }
        }).setNegativeButton("إغلاق", null).show();
    }

    private void askKeyword(final String role, final String kName, final String kVal, String defaultKeyword) {
        final EditText et = new EditText(act);
        et.setSingleLine(true);
        et.setText(defaultKeyword);
        new AlertDialog.Builder(act).setTitle("بحث عن إشارة: " + role)
                .setMessage("اكتب جزءاً من اسم الإشارة (مثل BELT أو BUCKLE أو SBR أو OCC أو SEAT).")
                .setView(et)
                .setPositiveButton("بحث", (d, w) -> showCandidates(role, kName, kVal, et.getText().toString().trim()))
                .setNegativeButton("رجوع", (d, w) -> setupMenu())
                .show();
    }

    private void showCandidates(final String role, final String kName, final String kVal, final String keyword) {
        final List<String> names = car.findGetters(keyword);
        if (names.isEmpty()) {
            new AlertDialog.Builder(act).setTitle("لا نتائج")
                    .setMessage("لا توجد إشارة تحوي «" + keyword + "». جرّب كلمة أخرى.\n(إن كانت السيارة غير متصلة بعد فانتظر لحظات ثم أعد المحاولة.)")
                    .setPositiveButton("بحث آخر", (d, w) -> askKeyword(role, kName, kVal, keyword))
                    .setNegativeButton("رجوع", (d, w) -> setupMenu()).show();
            return;
        }
        int n = Math.min(names.size(), 60);
        final String[] items = new String[n];
        for (int i = 0; i < n; i++) {
            Double v = car.readGetter(names.get(i));
            items[i] = names.get(i).substring(3) + "  =  " + (v == null ? "?" : trim(v));
        }
        new AlertDialog.Builder(act)
                .setTitle(role + " — اختر الإشارة التي تتغير (" + names.size() + ")")
                .setItems(items, (d, which) -> {
                    String name = names.get(which);
                    Double v = car.readGetter(name);
                    if (v == null) { toast("تعذرت قراءة الإشارة"); return; }
                    prefs.edit().putString(kName, name).putString(kVal, String.valueOf(v)).apply();
                    BeltService.sync(act);
                    toast("تم: القيمة الحالية " + trim(v) + " تعني (مربوط/مشغول)");
                })
                .setNeutralButton("تحديث القيم", (d, w) -> showCandidates(role, kName, kVal, keyword))
                .setNegativeButton("بحث آخر", (d, w) -> askKeyword(role, kName, kVal, keyword))
                .show();
    }

    private static String trim(double v) { return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v); }

    private void toast(String s) { Toast.makeText(act, s, Toast.LENGTH_LONG).show(); }
}
