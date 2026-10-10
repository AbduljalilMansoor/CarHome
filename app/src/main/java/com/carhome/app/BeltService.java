package com.carhome.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * خدمة تنبيه حزام الأمان: تعمل في الخلفية حتى لو كان تطبيق آخر في الواجهة.
 * إذا تحركت السيارة وكان حزام السائق (أو حزام راكب يشغل المقعد) غير مربوط لثانيتين متتاليتين،
 * تُطلق صفارة وشريطاً أحمر، وتستمر حتى يُربط الحزام أو تتوقف السيارة.
 */
public class BeltService extends Service {
    private static final String CH = "carhome_belt";
    private static final int CONFIRM_SECONDS = 2;

    private final Handler main = new Handler(Looper.getMainLooper());
    private CarBridge car;
    private HandlerThread thread;
    private Handler worker;
    private volatile boolean running;
    private SharedPreferences prefs;
    private ToneGenerator tone;
    private WindowManager wm;
    private TextView banner;
    private int badSeconds;

    /** يشغّل الخدمة إن كان التنبيه مفعّلاً وحزام السائق مضبوطاً، وإلا يوقفها. */
    static void sync(Context c) {
        SharedPreferences p = c.getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        Intent i = new Intent(c, BeltService.class);
        if (BeltLogic.alarmEnabled(p) && BeltLogic.driverConfigured(p)) c.startForegroundService(i);
        else c.stopService(i);
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CH, "Car Home — حزام الأمان", NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, CH)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("تنبيه حزام الأمان يعمل").build();
        startForeground(42, n);

        prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (!running) {
            running = true;
            car = new CarBridge();
            thread = new HandlerThread("carhome-belt");
            thread.start();
            worker = new Handler(thread.getLooper());
            final Context app = getApplicationContext();
            worker.post(new Runnable() {
                @Override public void run() {
                    if (!running) return;
                    try {
                        car.connect(app);
                        car.poll();
                        tick();
                    } catch (Throwable ignored) { }
                    if (running) worker.postDelayed(this, 1000);
                }
            });
        }
        return START_STICKY;
    }

    private void tick() {
        BeltLogic s = BeltLogic.evaluate(car, prefs);
        badSeconds = s.needsAlarm() ? badSeconds + 1 : 0;
        boolean alarm = badSeconds >= CONFIRM_SECONDS && BeltLogic.alarmEnabled(prefs);
        if (alarm) beep();
        main.post(() -> showBanner(alarm));
    }

    private void beep() {
        try {
            if (tone == null) tone = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 700);
        } catch (Throwable ignored) { }
    }

    /** شريط أحمر أعلى الشاشة فوق أي تطبيق (يتطلب صلاحية الظهور فوق التطبيقات). */
    private void showBanner(boolean show) {
        if (show && banner == null && Settings.canDrawOverlays(this)) {
            TextView t = new TextView(this);
            t.setText("⚠  اربط حزام الأمان");
            t.setTextSize(26);
            t.setTextColor(0xFFFFFFFF);
            t.setGravity(Gravity.CENTER);
            t.setBackgroundColor(0xEEC62828);
            int pad = Ui.dp(this, 14);
            t.setPadding(pad, pad, pad, pad);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP;
            try { wm.addView(t, lp); banner = t; } catch (Throwable ignored) { }
        } else if (!show && banner != null) {
            try { wm.removeView(banner); } catch (Throwable ignored) { }
            banner = null;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        running = false;
        if (thread != null) thread.quitSafely();
        if (car != null) car.release();
        if (tone != null) tone.release();
        main.post(() -> showBanner(false));
    }
}
