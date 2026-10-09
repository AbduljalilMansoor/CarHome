package com.carhome.app;

import android.app.Activity;
import android.view.View;

/**
 * ودجت قابل للوضع في الشريط السفلي. لإضافة ودجت جديد: صنف يرث Widget ثم أضف معرّفه إلى ALL و create().
 */
abstract class Widget {
    static final String WEATHER = "weather", MEDIA = "media";
    static final String[] ALL = {WEATHER, MEDIA};

    final Activity act;

    Widget(Activity a) { act = a; }

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

    static Widget create(String id, Activity a) {
        if (WEATHER.equals(id)) return new WeatherWidget(a);
        if (MEDIA.equals(id)) return new MediaWidget(a);
        return null;
    }
}
