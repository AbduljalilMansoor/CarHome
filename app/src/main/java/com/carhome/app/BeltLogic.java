package com.carhome.app;

import android.content.SharedPreferences;

/**
 * منطق حزام الأمان: يقرأ الإشارات التي حدّدها المستخدم (اسم الإشارة + القيمة التي تعني «مربوط/مشغول»)
 * ويحدّد هل يجب إطلاق التنبيه.
 */
final class BeltLogic {
    static final String K_DNAME = "bd_name", K_DVAL = "bd_val";   // حزام السائق
    static final String K_PNAME = "bp_name", K_PVAL = "bp_val";   // حزام الراكب
    static final String K_ONAME = "po_name", K_OVAL = "po_val";   // إشغال مقعد الراكب
    static final String K_ALARM = "belt_alarm";                    // تفعيل التنبيه الصوتي
    static final float MOVING_KMH = 5f;

    /** -1 غير معروف · حزام: 1 مربوط / 0 غير مربوط · مقعد: 1 مشغول / 0 فارغ */
    int driver = -1, pass = -1, passOcc = -1;
    boolean moving;

    static boolean driverConfigured(SharedPreferences p) { return !p.getString(K_DNAME, "").isEmpty(); }

    static boolean alarmEnabled(SharedPreferences p) { return p.getBoolean(K_ALARM, true); }

    static BeltLogic evaluate(CarBridge car, SharedPreferences p) {
        BeltLogic r = new BeltLogic();
        r.driver = state(car, p, K_DNAME, K_DVAL);
        r.pass = state(car, p, K_PNAME, K_PVAL);
        r.passOcc = state(car, p, K_ONAME, K_OVAL);
        Float sp = car.live.speedKmh;
        r.moving = sp != null && sp > MOVING_KMH;
        return r;
    }

    private static int state(CarBridge car, SharedPreferences p, String kName, String kVal) {
        String name = p.getString(kName, "");
        String saved = p.getString(kVal, "");
        if (name.isEmpty() || saved.isEmpty()) return -1;
        Double v = car.readGetter(name);
        if (v == null) return -1;
        try {
            return Math.abs(v - Double.parseDouble(saved)) < 0.5 ? 1 : 0;
        } catch (Exception e) {
            return -1;
        }
    }

    /** السيارة تتحرك وأحد الراكبين المعنيين غير مربوط (الراكب فقط إن كان مقعده مشغولاً). */
    boolean needsAlarm() {
        return moving && (driver == 0 || (passOcc == 1 && pass == 0));
    }
}
