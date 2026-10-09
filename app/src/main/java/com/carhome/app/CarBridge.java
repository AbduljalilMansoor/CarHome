package com.carhome.app;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * الاتصال بالسيارة:
 *  1) Autolink CarManager  ← getManager("car")، مع مستمع لحظي (مثل DisplayMirror)
 *  2) android.car CarPropertyManager (VHAL القياسي) ← احتياطي، ولمستوى الوقود
 */
public final class CarBridge {
    private static final String TAG = "CarHomeCar";
    static final int ENGINE_ON_RPM = 350;      // نفس قيمة DisplayMirror
    static final long ENGINE_OFF_HOLD_MS = 1500;
    static final long FRESH_MS = 1500;

    // معرّفات VHAL القياسية
    static final int PERF_VEHICLE_SPEED = 0x11600207;
    static final int PERF_ODOMETER = 0x11600204;
    static final int FUEL_LEVEL = 0x11600307;          // ml
    static final int INFO_FUEL_CAPACITY = 0x11600104;  // ml
    static final int EV_BATTERY_LEVEL = 0x11600308;    // Wh
    static final int INFO_EV_BATTERY_CAPACITY = 0x11600106;

    // أسماء ثوابت الإشارات (لتسجيل المستمع على إشارات محددة فقط)
    private static final String[] PROP_CLASSES = {
            "android.hardware.automotive.vehicle.V2_0.VehicleProperty",
            "vendor.autolink.automotive.vehicle.V1_0.VendorSignalProperty",
            "com.autolink.manager.car.VehicleProperty"};
    private static final String[] RT_FIELDS = {
            "VEHICLESPEEDVSOSIG_48A", "ENGINESPEED_4B2", "BMS_44_PACKPOWERREALTIME_53D",
            "FUELROLLINGCOUNTER_4B2", "HCU_DRIVEMODE_JT_522"};

    public interface Callback {
        /** يُستدعى قبل تغيّر أي قيمة، لكي تُحسب الفترة الماضية بالقيم القديمة. */
        void beforeChange(long now);
        void onFuelLitres(double litres);
    }

    public final Live live = new Live();
    private final Object lock = new Object();
    private final FuelCounter fuel = new FuelCounter();
    private volatile Callback callback;

    private Object alApi;                                // Autolink Api
    private Object al;                                   // Autolink CarManager
    private Object cluster;                              // Autolink clusterinteraction (شاشة العدادات)
    private Object chery;                                // Chery CarInfoControl
    private final Map<String, Method> cache = new HashMap<>();
    private Object listener;
    private Object propMgr;
    private Method getFloatProp, getIntProp;
    private Float fuelCapMl, evCapWh;
    private long lastConnectTry, lastEngineOnTs;

    public void setCallback(Callback c) { callback = c; }

    // ================= الاتصال =================
    public void connect(Context ctx) {
        long now = SystemClock.elapsedRealtime();
        if (al != null && propMgr != null && cluster != null && chery != null) return;
        if (lastConnectTry != 0 && now - lastConnectTry < 10_000) return;
        lastConnectTry = now;
        Context app = ctx.getApplicationContext();

        if (al == null) {
            try {
                if (alApi == null) alApi = Class.forName("com.autolink.manager.Api")
                        .getMethod("createApi", Context.class).invoke(null, app);
                if (alApi != null) al = alApi.getClass().getMethod("getManager", String.class).invoke(alApi, "car");
                if (al != null) {
                    Log.i(TAG, "Autolink CarManager connected");
                    registerRealtime();
                }
            } catch (Throwable t) {
                Log.w(TAG, "Autolink unavailable: " + t);
            }
        }
        // الوقود: VHAL لا يوفر FUEL_LEVEL في G700، فنقرؤه من شاشة العدادات (نفس ما يراه السائق)
        if (cluster == null && alApi != null) {
            try {
                cluster = alApi.getClass().getMethod("getManager", String.class).invoke(alApi, "clusterinteraction");
            } catch (Throwable t) {
                Log.w(TAG, "Autolink cluster unavailable: " + t);
            }
        }
        // العداد الحي: rangeavalDynamic بالمتر رغم اسمه (FLZCU_TOTALODOMETERBACKUP لا يتحرك أثناء القيادة)
        if (chery == null) {
            try {
                Class<?> ctl = Class.forName("com.chery.platform.CarInfoControl");
                Object c = ctl.getMethod("getInstance").invoke(null);
                ctl.getMethod("init", Context.class).invoke(c, app);
                chery = c;
            } catch (Throwable t) {
                Log.w(TAG, "Chery platform unavailable: " + t);
            }
        }
        if (propMgr == null) {
            try {
                Class<?> car = Class.forName("android.car.Car");
                Object c = car.getMethod("createCar", Context.class).invoke(null, app);
                Object pm = c == null ? null : car.getMethod("getCarManager", String.class).invoke(c, "property");
                if (pm != null) {
                    getFloatProp = pm.getClass().getMethod("getFloatProperty", int.class, int.class);
                    getIntProp = pm.getClass().getMethod("getIntProperty", int.class, int.class);
                    propMgr = pm;
                    Log.i(TAG, "CarPropertyManager connected");
                }
            } catch (Throwable t) {
                Log.w(TAG, "android.car unavailable: " + t);
            }
        }
        String src = al != null && propMgr != null ? "Autolink + VHAL"
                : al != null ? "Autolink" : propMgr != null ? "VHAL" : "غير متصل";
        if (cluster != null) src += " + Cluster";
        if (chery != null) src += " + Chery";
        live.source = src;
    }

    private void registerRealtime() {
        try {
            Object l = new AlListener(this);
            Method add1 = null, add2 = null;
            for (Method m : al.getClass().getMethods()) {
                if (!m.getName().equals("addCarPropertyListener")) continue;
                if (m.getParameterTypes().length == 1) add1 = m;
                else if (m.getParameterTypes().length == 2) add2 = m;
            }
            List<Integer> ids = resolveIds();
            if (add2 != null && ids != null) {
                try {
                    add2.invoke(al, l, ids);
                    listener = l;
                    live.realtime = "مفعّل (" + ids.size() + " إشارات)";
                } catch (Throwable t) {
                    Log.w(TAG, "narrow register failed: " + t);
                }
            }
            if (listener == null && add1 != null) {
                add1.invoke(al, l);
                listener = l;
                live.realtime = "مفعّل (كل الإشارات)";
            }
            if (listener == null) live.realtime = "غير مدعوم";
        } catch (Throwable t) {
            live.realtime = "فشل: " + t.getClass().getSimpleName();
            Log.w(TAG, "realtime listener unavailable", t);
        }
    }

    private List<Integer> resolveIds() {
        for (String cls : PROP_CLASSES) {
            try {
                Class<?> c = Class.forName(cls);
                List<Integer> ids = new ArrayList<>();
                for (String f : RT_FIELDS) ids.add(c.getField(f).getInt(null));
                return ids;
            } catch (Throwable ignored) { }
        }
        return null;
    }

    /** إلغاء تسجيل المستمع (للمعاينة في الشاشة الرئيسية). */
    public void release() {
        callback = null;
        Object l = listener;
        if (al == null || l == null) return;
        listener = null;
        for (Method m : al.getClass().getMethods()) {
            if (m.getName().equals("removeCarPropertyListener") && m.getParameterTypes().length == 1) {
                try { m.invoke(al, l); } catch (Throwable ignored) { }
                return;
            }
        }
    }

    // ================= المستمع اللحظي =================
    private void fire(long now) {
        Callback c = callback;
        if (c != null) c.beforeChange(now);
    }

    void onSpeed(float v, boolean rt) {
        if (Float.isNaN(v) || v < 0 || v > 300) return;
        long now = SystemClock.elapsedRealtime();
        synchronized (lock) {
            fire(now);
            live.speedKmh = v;
            if (rt) live.speedCbTs = now;
        }
    }

    void onRpm(int rpm, boolean rt) {
        if (rpm < 0 || rpm > 10000) return;
        long now = SystemClock.elapsedRealtime();
        synchronized (lock) {
            fire(now);
            live.rpm = rpm;
            if (rt) live.rpmCbTs = now;
            updateEngine(now);
        }
    }

    void onPower(float kw, boolean rt) {
        if (Float.isNaN(kw) || Math.abs(kw) > 500) return;
        long now = SystemClock.elapsedRealtime();
        synchronized (lock) {
            fire(now);
            live.packKw = kw;
            if (rt) live.powerCbTs = now;
        }
    }

    void onMode(int mode, boolean rt) {
        if (mode < 0 || mode > 30) return;
        long now = SystemClock.elapsedRealtime();
        synchronized (lock) {
            if (live.driveMode == null || live.driveMode != mode) fire(now);
            live.driveMode = mode;
            if (rt) live.modeCbTs = now;
        }
    }

    void onFuelCounter(float raw) {
        long now = SystemClock.elapsedRealtime();
        double l = fuel.update(raw, now);
        live.fuelCbTs = now;
        live.fuelCbCount++;
        if (l > 0) {
            Callback c = callback;
            if (c != null) c.onFuelLitres(l);
        }
    }

    private void updateEngine(long now) {
        Integer r = live.rpm;
        boolean on = r != null && r >= ENGINE_ON_RPM;
        if (on) lastEngineOnTs = now;
        boolean newState = on || (live.engineOn && now - lastEngineOnTs < ENGINE_OFF_HOLD_MS);
        if (newState != live.engineOn) {
            fire(now);
            live.engineOn = newState;
        }
    }

    // ================= القراءة الدورية (كل ثانية) =================
    public void poll() {
        long now = SystemClock.elapsedRealtime();

        if (now - live.speedCbTs > FRESH_MS) {
            Float v = al("getVEHICLESPEEDVSOSIG");
            if (v == null) { Float ms = vf(PERF_VEHICLE_SPEED); if (ms != null) v = Math.abs(ms) * 3.6f; }
            if (v != null) onSpeed(v, false);
        }
        if (now - live.rpmCbTs > FRESH_MS) {
            Float r = al("getENGINESPEED");
            if (r != null) onRpm(r.intValue(), false);
        }
        if (now - live.powerCbTs > FRESH_MS) {
            Float p = al("getBMS_44_PACKPOWERREALTIME");
            if (p != null) onPower(p, false);
        }
        if (now - live.modeCbTs > FRESH_MS) {
            Float m = al("getHCU_DRIVEMODE_JT");
            if (m == null) m = al("getATCM_DRIVEMODESW");
            if (m != null) onMode(m.intValue(), false);
        }
        // إذا لم يصل المستمع اللحظي، نحاول قراءة العداد دورياً (أقل دقة)
        if (now - live.fuelCbTs > FRESH_MS) {
            Float raw = al("getFUELROLLINGCOUNTER");
            if (raw != null) {
                double l = fuel.update(raw, now);
                if (l > 0) { Callback c = callback; if (c != null) c.onFuelLitres(l); }
            }
        }
        synchronized (lock) { updateEngine(now); }
        live.fuelFlowLph = live.engineOn ? fuel.flowLph(now) : 0f;

        // البطارية
        Float soc = range(al("getBMS_SOCLIGHT"), 0, 100);
        if (soc == null) {
            if (evCapWh == null) evCapWh = vf(INFO_EV_BATTERY_CAPACITY);
            Float lvl = vf(EV_BATTERY_LEVEL);
            if (lvl != null && evCapWh != null && evCapWh > 0) soc = range(lvl / evCapWh * 100f, 0, 100);
        }
        live.socPct = soc;

        // الوقود: من شاشة العدادات أولاً، ثم VHAL احتياطاً
        Float fuelPct = clusterFuelPct();
        if (fuelCapMl == null) fuelCapMl = vf(INFO_FUEL_CAPACITY);
        Float fuelMl = vf(FUEL_LEVEL);
        if (fuelMl != null && fuelCapMl != null && fuelCapMl > 0) {
            if (fuelPct == null) fuelPct = range(fuelMl / fuelCapMl * 100f, 0, 100);
            live.fuelLitres = fuelMl / 1000f;
        }
        live.fuelPct = fuelPct;

        Float odoM = cheryF("rangeavalDynamic");
        Float odo = odoM == null ? null : range(odoM / 1000f, 0.1f, 2_000_000);
        if (odo == null) odo = range(al("getFLZCU_TOTALODOMETERBACKUP"), 0.1f, 2_000_000);
        if (odo == null) odo = range(vf(PERF_ODOMETER), 0.1f, 2_000_000);
        live.odometerKm = odo;

        live.evRangeKm = range(al("getVCU_CLTC_RANGEAVAL"), 0, 2000);
        live.rangeKm = clusterRangeKm();

        Float g = al("getVCU_1_G_PRNDGEARACT");
        live.gear = g == null ? null : g.intValue();
    }

    // ================= أدوات =================
    private Float al(String name) {
        Object car = al;
        if (car == null) return null;
        Method m;
        synchronized (cache) {
            if (cache.containsKey(name)) m = cache.get(name);
            else {
                try { m = car.getClass().getMethod(name); } catch (Throwable t) { m = null; }
                cache.put(name, m);
            }
        }
        if (m == null) return null;
        try {
            Object v = m.invoke(car);
            return v instanceof Number ? ((Number) v).floatValue() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** خدمة العدادات تعيد 0 لكل شيء قبل اتصالها، فلا نثق بالقيمة إلا إذا كان DTE_VALUE (لا يكون صفراً) يجيب. */
    private Float clusterFuelPct() {
        Integer alive = clusterInt("DTE_VALUE");
        if (alive == null || alive <= 0) return null;
        Integer f = clusterInt("FUEL_PERCENT");
        return f == null ? null : range(f.floatValue(), 0, 100);
    }

    /** المدى الذي تعرضه شاشة العدادات: أول قيمة في قناة MCU_PERIOD_TCDTE (بنفس شرط الاتصال). */
    private Float clusterRangeKm() {
        Integer alive = clusterInt("DTE_VALUE");
        if (alive == null || alive <= 0) return null;
        Object c = cluster;
        try {
            Object v = c.getClass().getMethod("getIntegerListData", String.class).invoke(c, "MCU_PERIOD_TCDTE");
            int[] a = v instanceof int[] ? (int[]) v : null;
            return a == null || a.length == 0 ? null : range((float) a[0], 1, 3000);
        } catch (Throwable t) {
            return null;
        }
    }

    private Integer clusterInt(String channel) {
        Object c = cluster;
        if (c == null) return null;
        try {
            Object v = c.getClass().getMethod("getIntegerData", String.class).invoke(c, channel);
            return v instanceof Integer ? (Integer) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private Float cheryF(String name) {
        Object c = chery;
        if (c == null) return null;
        try {
            Object v = c.getClass().getMethod(name).invoke(c);
            return v instanceof Number ? ((Number) v).floatValue() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private Float vf(int id) {
        if (propMgr == null) return null;
        try {
            Object v = getFloatProp.invoke(propMgr, id, 0);
            return v instanceof Float ? (Float) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Float range(Float v, float min, float max) {
        if (v == null || v.isNaN() || v < min || v > max) return null;
        return v;
    }
}
