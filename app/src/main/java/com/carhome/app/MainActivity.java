package com.carhome.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Car Home: شاشة رئيسية (Launcher) لسيارة جيتور.
 * - لوحة حية: سرعة، نمط القيادة، الغِيار، بطارية، وقود، مدى، قدرة، دورات، عدّاد.
 * - شريط تطبيقات مفضّلة (ضغط مطوّل على أي تطبيق في القائمة لتثبيته).
 * - قائمة بكل التطبيقات المثبتة.
 * البيانات تأتي من CarBridge (نفس كود TripLog الذي يقرأ Autolink).
 */
public class MainActivity extends Activity {
    static final String PREFS = "carhome";
    static final String KEY_PINNED = "pinned";
    static final String NEW_TRIP_PKG = "com.newtrip.app";

    private static final class AppItem {
        String pkg, cls, label;
        Drawable icon;
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    // بيانات السيارة
    private CarBridge car;
    private HandlerThread pollThread;
    private Handler pollHandler;
    private volatile boolean polling;

    // واجهة
    private FrameLayout rootFrame;
    private LinearLayout dockApps;
    private FrameLayout appsPage;
    private GridLayout grid;
    private TextView vSpeed, vMode, vGear, vSource, vClock, vDate;
    private TextView vPower, vRpm, vOdo, vEvRange, vRange, updateBtn;
    private RingGauge gBattery, gFuel;

    private List<AppItem> apps = new ArrayList<>();
    private boolean appsDirty = true;
    private Updater.Info pendingUpdate;

    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("EEEE d MMMM", Locale.getDefault());

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            render();
            ui.postDelayed(this, 1000);
        }
    };

    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            appsDirty = true;
            loadApps();
        }
    };

    // ======================= دورة الحياة =======================
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Updater.init(this);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!prefs.contains(KEY_PINNED)) prefs.edit().putString(KEY_PINNED, NEW_TRIP_PKG).apply();

        car = new CarBridge();

        rootFrame = new FrameLayout(this);
        rootFrame.setBackgroundColor(Ui.BG);
        rootFrame.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);

        int p = Ui.dp(this, 16);
        LinearLayout home = new LinearLayout(this);
        home.setOrientation(LinearLayout.HORIZONTAL);
        home.setPadding(p, p, p, p);
        home.addView(buildDock(), new LinearLayout.LayoutParams(Ui.dp(this, 112), -1));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, -1, 1f);
        clp.setMargins(p, 0, p, 0);
        home.addView(buildCenter(), clp);
        home.addView(buildSide(), new LinearLayout.LayoutParams(Ui.dp(this, 280), -1));
        rootFrame.addView(home, new FrameLayout.LayoutParams(-1, -1));

        appsPage = buildAppsPage();
        appsPage.setVisibility(View.GONE);
        rootFrame.addView(appsPage, new FrameLayout.LayoutParams(-1, -1));
        setContentView(rootFrame);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addDataScheme("package");
        registerReceiver(pkgReceiver, f);

        loadApps();
        checkUpdateQuietly();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (appsDirty) loadApps();
        startPolling();
        ui.removeCallbacks(tick);
        ui.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(tick);
        stopPolling();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(pkgReceiver); } catch (Exception ignored) { }
        stopPolling();
        car.release();
    }

    /** زر Home أثناء وجودنا في الواجهة: ارجع للشاشة الرئيسية وأغلق قائمة التطبيقات. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        hideApps();
    }

    @Override
    public void onBackPressed() {
        if (appsPage.getVisibility() == View.VISIBLE) hideApps();
        // وإلا: لا شيء، فنحن الشاشة الرئيسية
    }

    // ======================= قراءة السيارة =======================
    private void startPolling() {
        if (polling) return;
        polling = true;
        pollThread = new HandlerThread("carhome-poll");
        pollThread.start();
        pollHandler = new Handler(pollThread.getLooper());
        final Context app = getApplicationContext();
        pollHandler.post(new Runnable() {
            @Override public void run() {
                if (!polling) return;
                try {
                    car.connect(app);   // يعيد المحاولة وحده كل 10 ثوانٍ حتى يتصل
                    car.poll();
                } catch (Throwable ignored) { }
                if (polling) pollHandler.postDelayed(this, 1000);
            }
        });
    }

    private void stopPolling() {
        polling = false;
        if (pollThread != null) { pollThread.quitSafely(); pollThread = null; }
    }

    // ======================= بناء الواجهة =======================
    private View buildDock() {
        LinearLayout dock = Ui.card(this);
        dock.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = Ui.dp(this, 10);
        dock.setPadding(pad, pad, pad, pad);

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        dockApps = new LinearLayout(this);
        dockApps.setOrientation(LinearLayout.VERTICAL);
        dockApps.setGravity(Gravity.CENTER_HORIZONTAL);
        sv.addView(dockApps, new FrameLayout.LayoutParams(-1, -2));
        dock.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

        TextView all = Ui.text(this, "⊞", 38, Ui.TEXT, true);
        all.setGravity(Gravity.CENTER);
        all.setBackground(Ui.round(Ui.CARD2, Ui.dp(this, 16)));
        all.setOnClickListener(v -> showApps());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 84), Ui.dp(this, 84));
        lp.topMargin = pad;
        dock.addView(all, lp);
        return dock;
    }

    private View buildCenter() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        int m = Ui.dp(this, 6);

        // --- السرعة + النمط + الغِيار
        LinearLayout sp = Ui.card(this);
        sp.setOrientation(LinearLayout.HORIZONTAL);
        sp.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout sCol = new LinearLayout(this);
        sCol.setOrientation(LinearLayout.VERTICAL);
        vSpeed = Ui.text(this, "—", 92, Ui.TEXT, true);
        sCol.addView(vSpeed);
        sCol.addView(Ui.text(this, "km/h", 18, Ui.MUTED, false));
        sp.addView(sCol, new LinearLayout.LayoutParams(0, -2, 1f));

        LinearLayout rCol = new LinearLayout(this);
        rCol.setOrientation(LinearLayout.VERTICAL);
        rCol.setGravity(Gravity.CENTER_HORIZONTAL);
        vMode = Ui.text(this, "—", 22, 0xFFFFFFFF, true);
        vMode.setGravity(Gravity.CENTER);
        int hp = Ui.dp(this, 18), vp = Ui.dp(this, 8);
        vMode.setPadding(hp, vp, hp, vp);
        rCol.addView(vMode);
        vGear = Ui.text(this, "—", 54, Ui.TEXT, true);
        vGear.setGravity(Gravity.CENTER);
        rCol.addView(vGear);
        vSource = Ui.text(this, "", 14, Ui.MUTED, false);
        vSource.setGravity(Gravity.CENTER);
        rCol.addView(vSource);
        sp.addView(rCol, new LinearLayout.LayoutParams(Ui.dp(this, 220), -2));
        LinearLayout.LayoutParams splp = new LinearLayout.LayoutParams(-1, -2);
        splp.setMargins(m, m, m, m);
        c.addView(sp, splp);

        // --- العدادات الدائرية
        LinearLayout gcard = Ui.card(this);
        gcard.setOrientation(LinearLayout.HORIZONTAL);
        gcard.setGravity(Gravity.CENTER);
        gBattery = new RingGauge(this, "البطارية", Ui.EV);
        gBattery.setLowWarning(15);
        gFuel = new RingGauge(this, "الوقود", Ui.ENG);
        gFuel.setLowWarning(12);
        vEvRange = Ui.text(this, "—", 18, Ui.MUTED, false);
        vRange = Ui.text(this, "—", 18, Ui.MUTED, false);
        gcard.addView(gaugeColumn(gBattery, vEvRange), new LinearLayout.LayoutParams(0, -2, 1f));
        gcard.addView(gaugeColumn(gFuel, vRange), new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-1, 0, 1f);
        glp.setMargins(m, m, m, m);
        c.addView(gcard, glp);

        // --- بطاقات صغيرة
        LinearLayout tiles = Ui.row(this);
        vPower = Ui.tile(tiles, "القدرة (kW)", Ui.EV);
        vRpm = Ui.tile(tiles, "الدورات (rpm)", Ui.ENG);
        vOdo = Ui.tile(tiles, "العدّاد", Ui.TEXT);
        c.addView(tiles, new LinearLayout.LayoutParams(-1, -2));
        return c;
    }

    private View gaugeColumn(RingGauge g, TextView under) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        int s = Ui.dp(this, 200);
        col.addView(g, new LinearLayout.LayoutParams(s, s));
        under.setGravity(Gravity.CENTER);
        col.addView(under);
        return col;
    }

    private View buildSide() {
        LinearLayout s = new LinearLayout(this);
        s.setOrientation(LinearLayout.VERTICAL);

        LinearLayout clock = Ui.card(this);
        clock.setGravity(Gravity.CENTER_HORIZONTAL);
        vClock = Ui.text(this, "--:--", 68, Ui.TEXT, true);
        vDate = Ui.text(this, "", 18, Ui.MUTED, false);
        clock.addView(vClock);
        clock.addView(vDate);
        s.addView(clock, new LinearLayout.LayoutParams(-1, -2));

        int gap = Ui.dp(this, 12);
        s.addView(sideButton("🧭  New Trip", 0xFF1E88E5, v -> launchPackage(NEW_TRIP_PKG)), sideLp(gap));
        s.addView(sideButton("⚙  الإعدادات", 0xFF455A64,
                v -> startSafely(new Intent(Settings.ACTION_SETTINGS))), sideLp(gap));
        updateBtn = sideButton("⟳  تحديث", 0xFF37474F, v -> onUpdateClicked());
        s.addView(updateBtn, sideLp(gap));
        return s;
    }

    private TextView sideButton(String label, int color, View.OnClickListener l) {
        TextView b = Ui.button(this, label, color, l);
        b.setPadding(Ui.dp(this, 16), Ui.dp(this, 20), Ui.dp(this, 16), Ui.dp(this, 20));
        return b;
    }

    private LinearLayout.LayoutParams sideLp(int gap) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = gap;
        return lp;
    }

    private FrameLayout buildAppsPage() {
        FrameLayout page = new FrameLayout(this);
        page.setBackgroundColor(Ui.BG);
        page.setClickable(true);   // لا تمرّر اللمس للشاشة تحتها

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 20);
        col.setPadding(p, p, p, p);

        LinearLayout header = Ui.row(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(Ui.text(this, "كل التطبيقات", 28, Ui.TEXT, true));
        TextView hint = Ui.text(this, "   اضغط مطوّلاً على تطبيق لتثبيته أو حذفه", 15, Ui.MUTED, false);
        header.addView(hint, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView close = Ui.text(this, "✕", 32, Ui.TEXT, true);
        close.setGravity(Gravity.CENTER);
        close.setBackground(Ui.round(Ui.CARD2, Ui.dp(this, 14)));
        close.setOnClickListener(v -> hideApps());
        header.addView(close, new LinearLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 64)));
        col.addView(header);

        ScrollView sv = new ScrollView(this);
        grid = new GridLayout(this);
        sv.addView(grid, new FrameLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, 0, 1f);
        slp.topMargin = Ui.dp(this, 12);
        col.addView(sv, slp);

        page.addView(col, new FrameLayout.LayoutParams(-1, -1));
        return page;
    }

    private void showApps() { appsPage.setVisibility(View.VISIBLE); }

    private void hideApps() {
        if (appsPage != null) appsPage.setVisibility(View.GONE);
    }

    // ======================= التطبيقات =======================
    private void loadApps() {
        final PackageManager pm = getPackageManager();
        new Thread(() -> {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> list = pm.queryIntentActivities(q, 0);
            List<AppItem> out = new ArrayList<>();
            for (ResolveInfo ri : list) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName())) continue;
                AppItem a = new AppItem();
                a.pkg = pkg;
                a.cls = ri.activityInfo.name;
                a.label = String.valueOf(ri.loadLabel(pm));
                a.icon = ri.loadIcon(pm);
                out.add(a);
            }
            Collections.sort(out, (x, y) -> x.label.compareToIgnoreCase(y.label));
            ui.post(() -> {
                apps = out;
                appsDirty = false;
                fillGrid();
                renderDock();
            });
        }).start();
    }

    private void fillGrid() {
        grid.removeAllViews();
        int cell = Ui.dp(this, 132);
        int cols = Math.max(4, getResources().getDisplayMetrics().widthPixels / cell - 1);
        grid.setColumnCount(cols);
        for (AppItem a : apps) grid.addView(appCell(a, cell, 76, 16));
    }

    private View appCell(AppItem a, int width, int iconDp, int labelSp) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = Ui.dp(this, 8);
        c.setPadding(pad, pad, pad, pad);
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(a.icon);
        c.addView(iv, new LinearLayout.LayoutParams(Ui.dp(this, iconDp), Ui.dp(this, iconDp)));
        TextView t = Ui.text(this, a.label, labelSp, Ui.TEXT, false);
        t.setGravity(Gravity.CENTER);
        t.setMaxLines(2);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        c.addView(t, new LinearLayout.LayoutParams(-1, -2));
        c.setLayoutParams(new GridLayout.LayoutParams());
        c.getLayoutParams().width = width;
        c.setOnClickListener(v -> { hideApps(); launch(a); });
        c.setOnLongClickListener(v -> { appMenu(a); return true; });
        return c;
    }

    private void renderDock() {
        dockApps.removeAllViews();
        int size = Ui.dp(this, 76);
        for (String pkg : getPinned()) {
            AppItem a = find(pkg);
            if (a == null) continue;
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(a.icon);
            int pad = Ui.dp(this, 8);
            iv.setPadding(pad, pad, pad, pad);
            iv.setOnClickListener(v -> launch(a));
            iv.setOnLongClickListener(v -> { appMenu(a); return true; });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.bottomMargin = Ui.dp(this, 8);
            dockApps.addView(iv, lp);
        }
    }

    private AppItem find(String pkg) {
        for (AppItem a : apps) if (a.pkg.equals(pkg)) return a;
        return null;
    }

    private List<String> getPinned() {
        List<String> l = new ArrayList<>();
        String s = prefs.getString(KEY_PINNED, "");
        for (String x : s.split(",")) if (!x.isEmpty()) l.add(x);
        return l;
    }

    private void setPinned(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String x : l) { if (sb.length() > 0) sb.append(','); sb.append(x); }
        prefs.edit().putString(KEY_PINNED, sb.toString()).apply();
        renderDock();
    }

    private void appMenu(AppItem a) {
        final List<String> pinned = getPinned();
        final boolean isPinned = pinned.contains(a.pkg);
        String[] items = {
                isPinned ? "إلغاء التثبيت من الشريط" : "تثبيت في الشريط",
                "معلومات التطبيق",
                "حذف التطبيق"
        };
        new AlertDialog.Builder(this).setTitle(a.label).setItems(items, (d, which) -> {
            if (which == 0) {
                if (isPinned) pinned.remove(a.pkg); else pinned.add(a.pkg);
                setPinned(pinned);
            } else if (which == 1) {
                startSafely(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.pkg)));
            } else {
                startSafely(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + a.pkg)));
            }
        }).show();
    }

    private void launch(AppItem a) {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(a.pkg, a.cls)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        startSafely(i);
    }

    private void launchPackage(String pkg) {
        Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) { toast("التطبيق غير مثبّت"); return; }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startSafely(i);
    }

    private void startSafely(Intent i) {
        try { startActivity(i); } catch (Exception e) { toast("تعذر الفتح"); }
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    // ======================= عرض القيم =======================
    private static String gearName(Integer g) {
        if (g == null) return "—";
        switch (g) {
            case 1: return "P";
            case 2: return "R";
            case 3: return "N";
            case 4: return "D";
            default: return String.valueOf(g);
        }
    }

    private static int modeColor(Integer m) {
        if (m == null) return 0xFF455A64;
        switch (m) {
            case 0: return 0xFF2E7D32;   // ECO
            case 1: return 0xFF1E88E5;   // NORMAL
            case 2: return 0xFFE53935;   // SPORT
            default: return 0xFF6A1B9A;  // أخرى
        }
    }

    private static String km(Float v) { return v == null ? "—" : Ui.num(v, 0) + " km"; }

    private void render() {
        Date now = new Date();
        vClock.setText(timeFmt.format(now));
        vDate.setText(dateFmt.format(now));

        Live l = car.live;
        Float speed = l.speedKmh;
        vSpeed.setText(speed == null ? "—" : Ui.num(speed, 0));

        boolean parked = l.gear != null && l.gear == 1;
        vMode.setText(parked ? "PARK" : Live.modeName(l.driveMode));
        vMode.setBackground(Ui.round(parked ? 0xFF1565C0 : modeColor(l.driveMode), Ui.dp(this, 12)));
        vGear.setText(gearName(l.gear));
        vSource.setText((l.engineOn ? "⛽ المحرك يعمل" : "⚡ كهرباء") + "\n" + l.source);

        gBattery.setValue(l.socPct);
        gFuel.setValue(l.fuelPct);
        vEvRange.setText("المدى الكهربائي: " + km(l.evRangeKm));
        vRange.setText("المدى الكلي: " + km(l.rangeKm));

        vPower.setText(l.packKw == null ? "—" : Ui.num(l.packKw, 1));
        vRpm.setText(l.rpm == null ? "—" : String.valueOf(l.rpm));
        vOdo.setText(km(l.odometerKm));
    }

    // ======================= التحديث الذاتي =======================
    private void checkUpdateQuietly() {
        new Thread(() -> {
            try {
                Updater.Info i = Updater.fetchLatest();
                if (i != null && i.code > Updater.installedCode(this)) {
                    ui.post(() -> {
                        pendingUpdate = i;
                        updateBtn.setText("⬆  تحديث متوفر");
                        updateBtn.setBackground(Ui.round(Ui.GOOD, Ui.dp(this, 14)));
                    });
                }
            } catch (Exception ignored) { }
        }).start();
    }

    private void onUpdateClicked() {
        if (!getPackageManager().canRequestPackageInstalls()) {
            message("لكي يحدّث التطبيق نفسه يحتاج صلاحية تثبيت التطبيقات، وشاشة السيارة لا تعرض هذا الخيار في الإعدادات.\n\n"
                    + "ثبّت التطبيق مرة واحدة من صفحة التثبيت بالكمبيوتر (تمنحه الصلاحية تلقائياً):\n"
                    + Updater.installPage() + "\n\nبعدها يعمل زر «تحديث» دائماً بدون كمبيوتر.");
            return;
        }
        TextView status = Ui.text(this, "جاري البحث عن تحديث…", 18, Ui.TEXT, false);
        status.setPadding(Ui.dp(this, 24), Ui.dp(this, 20), Ui.dp(this, 24), Ui.dp(this, 20));
        AlertDialog dlg = new AlertDialog.Builder(this).setView(status).setCancelable(false).show();
        new Thread(() -> {
            try {
                Updater.Info i = Updater.fetchLatest();
                long mine = Updater.installedCode(this);
                ui.post(() -> {
                    dlg.dismiss();
                    if (i == null || i.code <= mine) {
                        message("لديك آخر نسخة ✅\n\nالنسخة الحالية: v" + Updater.installedName(this));
                    } else {
                        offerUpdate(i);
                    }
                });
            } catch (Exception e) {
                ui.post(() -> { dlg.dismiss(); message("تعذر الاتصال بـ GitHub.\nتأكد من اتصال الشاشة بالإنترنت.\n\n" + e.getMessage()); });
            }
        }).start();
    }

    private void offerUpdate(Updater.Info i) {
        String notes = i.notes == null || i.notes.trim().isEmpty() ? "" : "\n\nما الجديد:\n" + i.notes.trim();
        new AlertDialog.Builder(this)
                .setTitle("نسخة جديدة متوفرة")
                .setMessage("النسخة الحالية: v" + Updater.installedName(this)
                        + "\nالنسخة الجديدة: " + i.tag + notes)
                .setPositiveButton("تحديث الآن", (d, w) -> install(i))
                .setNegativeButton("لاحقاً", null)
                .show();
    }

    private void install(Updater.Info i) {
        TextView status = Ui.text(this, "جاري التحميل… 0%", 18, Ui.TEXT, false);
        status.setPadding(Ui.dp(this, 24), Ui.dp(this, 20), Ui.dp(this, 24), Ui.dp(this, 20));
        AlertDialog dlg = new AlertDialog.Builder(this).setView(status).setCancelable(false).show();
        new Thread(() -> {
            try {
                final int[] last = {-1};
                Updater.downloadAndInstall(this, i, (done, total) -> {
                    int pct = total > 0 ? (int) (done * 100 / total) : -1;
                    if (pct != last[0]) {
                        last[0] = pct;
                        ui.post(() -> status.setText(pct >= 0 ? "جاري التحميل… " + pct + "%"
                                : "جاري التحميل… " + (done / 1024) + " KB"));
                    }
                });
                ui.post(() -> {
                    dlg.dismiss();
                    updateBtn.setText("⟳  تحديث");
                    updateBtn.setBackground(Ui.round(0xFF37474F, Ui.dp(this, 14)));
                });
            } catch (Exception e) {
                ui.post(() -> { dlg.dismiss(); message("فشل التحميل:\n" + e.getMessage()); });
            }
        }).start();
    }

    private void message(String m) {
        new AlertDialog.Builder(this).setMessage(m).setPositiveButton("حسناً", null).show();
    }
}
