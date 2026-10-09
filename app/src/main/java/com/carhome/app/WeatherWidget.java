package com.carhome.app;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Locale;

/**
 * ودجت الطقس (Open-Meteo: مجاني بلا مفتاح). الموقع: المدينة التي تحددها، وإن لم تحدد فموقع السيارة (GPS) إن توفرت الصلاحية.
 */
final class WeatherWidget extends Widget {
    private static final long REFRESH_MS = 30 * 60 * 1000L;
    private static final String K_CITY = "wcity", K_LAT = "wlat", K_LON = "wlon";

    private final SharedPreferences prefs;
    private TextView vIcon, vTemp, vInfo;
    private long lastFetch;
    private volatile boolean fetching;

    WeatherWidget(android.app.Activity a) {
        super(a);
        prefs = a.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
    }

    @Override String id() { return WEATHER; }
    @Override String title() { return "الطقس"; }
    @Override String description() { return "درجة الحرارة والحالة الجوية لمدينتك أو لموقع السيارة"; }
    @Override String icon() { return "🌤"; }
    @Override float weight() { return 1.2f; }

    @Override
    View createView() {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(Ui.dp(act, 10), Ui.dp(act, 4), Ui.dp(act, 8), Ui.dp(act, 4));
        vIcon = Ui.text(act, "🌤", 30, Ui.TEXT, false);
        box.addView(vIcon);
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(act, 8), 0, 0, 0);
        vTemp = Ui.text(act, "--", 22, Ui.TEXT, true);
        vInfo = Ui.text(act, "جاري التحميل…", 11, Ui.MUTED, false);
        vInfo.setSingleLine(true);
        vInfo.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(vTemp);
        col.addView(vInfo);
        box.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        box.setOnClickListener(v -> menu());
        return box;
    }

    @Override
    void refresh() {
        if (vTemp == null || fetching) return;
        if (System.currentTimeMillis() - lastFetch > REFRESH_MS) fetch();
    }

    private void menu() {
        String[] items = {"تحديث الآن", "تغيير المدينة"};
        new AlertDialog.Builder(act).setTitle("الطقس").setItems(items, (d, which) -> {
            if (which == 0) { lastFetch = 0; refresh(); } else askCity();
        }).show();
    }

    private void askCity() {
        final EditText et = new EditText(act);
        et.setSingleLine(true);
        et.setHint("اسم المدينة");
        et.setText(prefs.getString(K_CITY, ""));
        new AlertDialog.Builder(act).setTitle("مدينة الطقس").setView(et)
                .setPositiveButton("حفظ", (d, w) -> geocode(et.getText().toString().trim()))
                .setNegativeButton("استخدام موقع السيارة", (d, w) -> {
                    prefs.edit().remove(K_CITY).remove(K_LAT).remove(K_LON).apply();
                    lastFetch = 0;
                    refresh();
                })
                .setNeutralButton("إلغاء", null).show();
    }

    private void geocode(final String name) {
        if (name.isEmpty()) return;
        new Thread(() -> {
            try {
                String json = http("https://geocoding-api.open-meteo.com/v1/search?count=1&language=ar&format=json&name="
                        + URLEncoder.encode(name, "UTF-8"));
                JSONArray res = new JSONObject(json).optJSONArray("results");
                if (res == null || res.length() == 0) { toast("لم أجد هذه المدينة"); return; }
                JSONObject r = res.getJSONObject(0);
                prefs.edit().putString(K_CITY, r.optString("name", name))
                        .putString(K_LAT, String.valueOf(r.getDouble("latitude")))
                        .putString(K_LON, String.valueOf(r.getDouble("longitude"))).apply();
                lastFetch = 0;
                act.runOnUiThread(this::refresh);
            } catch (Exception e) {
                toast("تعذر البحث (تحقق من الإنترنت)");
            }
        }).start();
    }

    private void fetch() {
        fetching = true;
        new Thread(() -> {
            long retryAt = System.currentTimeMillis() - REFRESH_MS + 2 * 60 * 1000L;   // أعد المحاولة بعد دقيقتين
            try {
                double[] ll = location();
                if (ll == null) {
                    lastFetch = retryAt;
                    show("🌤", "--", "اضغط لتحديد المدينة");
                    return;
                }
                String json = http(String.format(Locale.US,
                        "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m,weather_code&timezone=auto",
                        ll[0], ll[1]));
                JSONObject cur = new JSONObject(json).getJSONObject("current");
                int code = cur.getInt("weather_code");
                long t = Math.round(cur.getDouble("temperature_2m"));
                String city = prefs.getString(K_CITY, "");
                lastFetch = System.currentTimeMillis();
                show(icon(code), t + "°", describe(code) + (city.isEmpty() ? "" : " · " + city));
            } catch (Exception e) {
                lastFetch = retryAt;
                show(null, null, "تعذر التحديث — تحقق من الإنترنت");
            } finally {
                fetching = false;
            }
        }).start();
    }

    private double[] location() {
        String lat = prefs.getString(K_LAT, null), lon = prefs.getString(K_LON, null);
        if (lat != null && lon != null) {
            try { return new double[]{Double.parseDouble(lat), Double.parseDouble(lon)}; } catch (Exception ignored) { }
        }
        if (act.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null;
        LocationManager lm = (LocationManager) act.getSystemService(Context.LOCATION_SERVICE);
        for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER}) {
            try {
                Location l = lm.getLastKnownLocation(p);
                if (l != null) return new double[]{l.getLatitude(), l.getLongitude()};
            } catch (Exception ignored) { }
        }
        return null;
    }

    private void show(final String icon, final String temp, final String info) {
        act.runOnUiThread(() -> {
            if (icon != null) vIcon.setText(icon);
            if (temp != null) vTemp.setText(temp);
            vInfo.setText(info);
        });
    }

    private void toast(final String s) {
        act.runOnUiThread(() -> Toast.makeText(act, s, Toast.LENGTH_SHORT).show());
    }

    private static String http(String u) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(10000);
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private static String icon(int c) {
        if (c == 0) return "☀️";
        if (c <= 2) return "⛅";
        if (c == 3) return "☁️";
        if (c == 45 || c == 48) return "🌫️";
        if (c <= 57) return "🌦️";
        if (c <= 67) return "🌧️";
        if (c <= 77) return "❄️";
        if (c <= 82) return "🌦️";
        if (c <= 86) return "🌨️";
        return "⛈️";
    }

    private static String describe(int c) {
        if (c == 0) return "صافٍ";
        if (c <= 2) return "غائم جزئياً";
        if (c == 3) return "غائم";
        if (c == 45 || c == 48) return "ضباب";
        if (c <= 57) return "رذاذ";
        if (c <= 67) return "مطر";
        if (c <= 77) return "ثلج";
        if (c <= 82) return "زخات مطر";
        if (c <= 86) return "زخات ثلج";
        return "عاصفة رعدية";
    }
}
