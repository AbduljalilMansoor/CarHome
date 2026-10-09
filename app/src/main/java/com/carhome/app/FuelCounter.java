package com.carhome.app;

/**
 * عدّاد الوقود FUELROLLINGCOUNTER (نفس معايرة DisplayMirror):
 * كل نبضة = 7.885e-5 لتر، والعداد يدور 0..254 ثم يرجع للصفر.
 * لا نقبل الفرق إلا إذا كانت القراءتان متقاربتين (≤ 800ms)، وإلا قد نفوّت دورة كاملة.
 */
final class FuelCounter {
    static final double LSB_LITRES = 7.885190279921517E-5;
    static final float MAX_VALID_RAW = 0.020107236f;
    static final int MODULUS = 255;
    static final long WRAP_SAFE_MS = 800;

    private int last = -1;
    private long lastTs;

    // نافذة صغيرة لحساب الاستهلاك اللحظي (لتر/ساعة)
    private final long[] winTs = new long[64];
    private final double[] winL = new double[64];
    private int head = -1, filled;
    private double total;

    /** @return اللترات المضافة منذ القراءة السابقة (0 إن لم يمكن الحساب). */
    synchronized double update(float raw, long now) {
        if (Float.isNaN(raw) || raw < 0 || raw > MAX_VALID_RAW) { last = -1; return 0; }
        int c = Math.round(raw / (float) LSB_LITRES);
        if (c < 0 || c >= MODULUS) { last = -1; return 0; }
        double added = 0;
        if (last >= 0) {
            long dt = now - lastTs;
            int d = c - last;
            if (d < 0) d += MODULUS;
            int maxD = (int) Math.ceil(dt * 90.0 / 283.8668500771746) + 2; // سقف 90 لتر/ساعة
            if (dt > 0 && dt <= WRAP_SAFE_MS && d <= maxD) added = d * LSB_LITRES;
        }
        last = c;
        lastTs = now;
        total += added;
        push(now, total);
        return added;
    }

    private void push(long t, double v) {
        if (filled > 0 && t - winTs[head] < 100) { winTs[head] = t; winL[head] = v; return; }
        head = (head + 1) % winTs.length;
        winTs[head] = t; winL[head] = v;
        if (filled < winTs.length) filled++;
    }

    /** الاستهلاك اللحظي على نافذة ~3 ثوانٍ. */
    synchronized float flowLph(long now) {
        if (filled < 2 || now - winTs[head] > 3000) return Float.NaN;
        for (int i = 1; i < filled; i++) {
            int idx = ((head - i) % winTs.length + winTs.length) % winTs.length;
            long dt = now - winTs[idx];
            if (dt >= 2500) {
                return (float) ((total - winL[idx]) * 3_600_000.0 / dt);
            }
        }
        return Float.NaN;
    }
}
