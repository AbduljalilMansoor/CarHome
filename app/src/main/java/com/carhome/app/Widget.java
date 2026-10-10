package com.carhome.app;

import android.app.Activity;
import android.view.View;

/**
 * ودجت قابل للوضع في الشريط السفلي. لإضافة ودجت جديد: صنف يرث Widget ثم أضف معرّفه إلى ALL و create().
 */
abstract class Widget {
    static final String WEATHER = "weather", MEDIA = "media", BELT = "belt";
    static final String[] ALL = {WEATHER, MEDIA, BELT};

    final Activity act;
    final CarBridge car;

    Widget(Activity a, CarBridge c) { act = a; car = c; }

    abstract String id();
    abstract String title();
    abstract String description();
    abstract String icon();
    /** العرض النسبي داخل الشريط. */
    abstract float weight();
    /** يبني واجهة الودجت (مرة واحدة عند وضعه في الشريط). */
    abstract View createView();
    /** يُستدعى كل ثانية على الخيط الرئيسي. */
    void refresh() { }

    static Widget create(String id, Activity a, CarBridge c) {
        if (WEATHER.equals(id)) return new WeatherWidget(a, c);
        if (MEDIA.equals(id)) return new MediaWidget(a, c);
        if (BELT.equals(id)) return new SeatBeltWidget(a, c);
        return null;
    }
}
