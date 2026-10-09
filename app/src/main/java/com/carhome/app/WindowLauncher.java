package com.carhome.app;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Display;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * تشغيل تطبيق في منطقة محددة من الشاشة وعلى شاشة محددة.
 * - الشاشة: ActivityOptions.setLaunchDisplayId (واجهة عامة).
 * - المنطقة: ActivityOptions.setLaunchBounds (نافذة حرة Freeform). تعمل فقط إذا كان نظام السيارة يدعم
 *   النوافذ الحرة (انظر «فحص قدرات النوافذ» في إعدادات Car Home).
 */
final class WindowLauncher {
    private WindowLauncher() { }

    static final int FRONT = 0, REAR = 5;
    private static final int WINDOWING_MODE_FREEFORM = 5;

    /** @return null عند النجاح، وإلا نص الخطأ. */
    static String launch(Activity act, AppItem a, int displayId, Rect bounds) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setClassName(a.pkg, a.cls)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                            | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            ActivityOptions o = ActivityOptions.makeBasic();
            o.setLaunchDisplayId(displayId);
            if (bounds != null) {
                o.setLaunchBounds(bounds);
                try {   // محاولة إضافية: فرض وضع Freeform (واجهة مخفية، قد تُرفض فيُكتفى بالحدود)
                    ActivityOptions.class.getMethod("setLaunchWindowingMode", int.class).invoke(o, WINDOWING_MODE_FREEFORM);
                } catch (Throwable ignored) { }
            }
            act.startActivity(i, o.toBundle());
            markOpen(act, a.pkg);
            return null;
        } catch (Throwable t) {
            return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        }
    }

    private static final String KEY_OPEN = "open";
    private static int cascade = 0;

    // ---------- تتبع التطبيقات التي فتحها Car Home وإغلاقها ----------
    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
    }

    static void markOpen(Context c, String pkg) {
        Set<String> s = new HashSet<>(prefs(c).getStringSet(KEY_OPEN, new HashSet<String>()));
        s.add(pkg);
        prefs(c).edit().putStringSet(KEY_OPEN, s).apply();
    }

    static Set<String> openApps(Context c) {
        return new HashSet<>(prefs(c).getStringSet(KEY_OPEN, new HashSet<String>()));
    }

    static void closeAll(Context c) { closeApps(c, openApps(c)); }

    /**
     * نظام السيارة لا يرسم زر إغلاق لنوافذ التطبيقات، وتطبيق عادي لا يملك صلاحية إغلاق نافذة تطبيق آخر مباشرة.
     * الحل: نُرجع Car Home للأمام فيغطي النوافذ فتصبح التطبيقات «في الخلفية»، ثم نطلب من النظام إنهاء
     * عملياتها (KILL_BACKGROUND_PROCESSES). التطبيق الذي يشغّل صوتاً (خدمة أمامية) قد لا يُغلق بهذه الطريقة.
     */
    static void closeApps(final Context c, Collection<String> pkgs) {
        final List<String> list = new ArrayList<>(pkgs);
        if (list.isEmpty()) {
            Toast.makeText(c, "لا توجد تطبيقات مفتوحة من Car Home", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(c, "جاري إغلاق التطبيقات…", Toast.LENGTH_SHORT).show();
        try {
            c.startActivity(new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        } catch (Exception ignored) { }
        final ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        Handler h = new Handler(Looper.getMainLooper());
        for (long delay : new long[]{1200, 2800}) {
            h.postDelayed(() -> {
                for (String p : list) {
                    try { am.killBackgroundProcesses(p); } catch (Exception ignored) { }
                }
            }, delay);
        }
        h.postDelayed(() -> {
            Set<String> s = openApps(c);
            s.removeAll(list);
            prefs(c).edit().putStringSet(KEY_OPEN, s).apply();
        }, 3000);
    }

    /**
     * فتح التطبيق في نافذة جديدة مستقلة (Freeform) قابلة للإغلاق، أو بملء الشاشة إن طُلب ذلك.
     * زر الإغلاق/شريط العنوان تعرضه واجهة نظام السيارة للنوافذ الحرة.
     */
    static String launchWindow(Activity act, AppItem a, int displayId, boolean windowed) {
        return launch(act, a, displayId, windowed ? windowBounds(act, displayId) : null);
    }

    /** نافذة في وسط الشاشة بحجم 72% مع إزاحة بسيطة لكل نافذة جديدة حتى لا تتطابق النوافذ. */
    private static Rect windowBounds(Context c, int displayId) {
        DisplayManager dm = (DisplayManager) c.getSystemService(Context.DISPLAY_SERVICE);
        Display d = dm == null ? null : dm.getDisplay(displayId);
        if (d == null) return null;
        android.graphics.Point p = new android.graphics.Point();
        d.getRealSize(p);
        int w = Math.round(p.x * 0.72f), h = Math.round(p.y * 0.72f);
        int step = Math.round(28 * c.getResources().getDisplayMetrics().density);
        int off = (cascade++ % 5) * step;
        int left = Math.min((p.x - w) / 2 + off, p.x - w);
        int top = Math.min((p.y - h) / 2 + off, p.y - h);
        return new Rect(left, top, left + w, top + h);
    }

    static String displayLabel(int id, String name) {
        if (id == FRONT) return "Display 0 (Front)";
        if (id == REAR) return "Display 5 (Rear)";
        return "Display " + id + (name == null ? "" : " (" + name + ")");
    }

    /** الشاشتان المعروضتان للاختيار: الأمامية (0) والخلفية (5) فقط. */
    static List<int[]> displays(Context c) {
        List<int[]> out = new ArrayList<>();
        out.add(new int[]{FRONT});
        out.add(new int[]{REAR});
        return out;
    }

    /** تقرير يوضح هل النوافذ الحرة والشاشات متاحة، مع أوامر ADB اللازمة إن لم تكن. */
    static String capabilityReport(Context c) {
        StringBuilder sb = new StringBuilder();
        PackageManager pm = c.getPackageManager();
        boolean ff = pm.hasSystemFeature("android.software.freeform_window_management");
        int ffSetting = Settings.Global.getInt(c.getContentResolver(), "enable_freeform_support", 0);
        int resizable = Settings.Global.getInt(c.getContentResolver(), "force_resizable_activities", 0);
        sb.append("أندرويد API ").append(android.os.Build.VERSION.SDK_INT).append('\n');
        sb.append("ميزة النوافذ الحرة في النظام: ").append(ff ? "نعم" : "لا").append('\n');
        sb.append("enable_freeform_support = ").append(ffSetting).append('\n');
        sb.append("force_resizable_activities = ").append(resizable).append('\n');
        sb.append("الأيقونة العائمة (صلاحية الظهور فوق التطبيقات): ")
                .append(Settings.canDrawOverlays(c) ? "مسموحة" : "غير مسموحة").append("\n\n");
        sb.append("الشاشات:\n");
        DisplayManager dm = (DisplayManager) c.getSystemService(Context.DISPLAY_SERVICE);
        if (dm != null) {
            for (Display d : dm.getDisplays()) {
                android.graphics.Point p = new android.graphics.Point();
                d.getRealSize(p);
                sb.append(" • ").append(displayLabel(d.getDisplayId(), d.getName()))
                        .append("  ").append(p.x).append('×').append(p.y).append('\n');
            }
        }
        sb.append("\nملاحظة: زر إغلاق النافذة وشريط عنوانها تعرضهما واجهة النظام للنوافذ الحرة؛ إن لم يظهرا فاستخدم زر الرجوع.\n");
        if (!ff || ffSetting == 0 || resizable == 0) {
            sb.append("\nلتفعيل تشغيل تطبيقين معاً نفّذ بـ ADB ثم أعد تشغيل السيارة:\n")
                    .append("settings put global enable_freeform_support 1\n")
                    .append("settings put global force_resizable_activities 1\n")
                    .append("(صفحة التثبيت تنفذهما تلقائياً.)");
        }
        return sb.toString();
    }
}
