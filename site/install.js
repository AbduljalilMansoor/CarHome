// صفحة التثبيت: تتصل بالسيارة عبر WebUSB (مكتبة Tango ADB في vendor/g700-adb.js)،
// وتثبّت آخر نسخة من «Car Home» وتمنحها الصلاحيات، ومنها تثبيت التطبيقات ليعمل زر «تحديث».
(function () {
  'use strict';
  var PKG = 'com.carhome.app';
  var CAR_PERMISSIONS = ['CAR_SPEED', 'CAR_ENERGY', 'CAR_MILEAGE', 'CAR_POWERTRAIN', 'CAR_INFO', 'CAR_EXTERIOR_ENVIRONMENT'];

  // "optional" = قد لا تكون موجودة في بعض السيارات، وليس خطأً
  var GRANTS = [];
  CAR_PERMISSIONS.forEach(function (p) {
    GRANTS.push({ label: 'صلاحية السيارة ' + p, cmd: 'pm grant ' + PKG + ' android.car.permission.' + p, optional: true });
  });
  GRANTS.push({ label: 'تثبيت التطبيقات (لزر «تحديث»)', cmd: 'appops set ' + PKG + ' REQUEST_INSTALL_PACKAGES allow' });
  GRANTS.push({ label: 'الإشعارات', cmd: 'pm grant ' + PKG + ' android.permission.POST_NOTIFICATIONS', optional: true });
  GRANTS.push({ label: 'استثناء من توفير الطاقة', cmd: 'dumpsys deviceidle whitelist +' + PKG, optional: true });

  function $(id) { return document.getElementById(id); }
  var statusEl = $('status'), connectBtn = $('connect'), installBtn = $('install'), logEl = $('log');
  var latest = null, car = null, busy = false;

  function setStatus(text, kind) {
    statusEl.textContent = text;
    statusEl.className = 'status' + (kind ? ' ' + kind : '');
  }
  function chip(key, text, good) {
    var li = document.querySelector('#env [data-k="' + key + '"]');
    li.querySelector('b').textContent = text;
    li.className = good ? 'good' : 'bad';
  }
  function addLog(text, kind) {
    logEl.hidden = false;
    var li = document.createElement('li');
    li.textContent = text;
    li.className = kind || '';
    logEl.appendChild(li);
    return li;
  }
  function mark(li, kind, text) {
    li.className = kind;
    if (text) li.textContent = text;
  }
  function mb(bytes) { return (bytes / 1048576).toFixed(1) + ' MB'; }
  function hex(buffer) {
    return Array.prototype.map.call(new Uint8Array(buffer), function (b) { return ('0' + b.toString(16)).slice(-2); }).join('');
  }

  // ---- environment -------------------------------------------------------
  var ua = navigator.userAgent;
  var browserName = /Edg\//.test(ua) ? 'Edge' : /OPR\//.test(ua) ? 'Opera' : /Firefox\//.test(ua) ? 'Firefox'
    : /Chrome\//.test(ua) ? 'Chrome' : /Safari\//.test(ua) ? 'Safari' : 'غير معروف';
  var hasUsb = !!(window.G700Adb && window.G700Adb.supported());
  chip('browser', browserName, /Chrome|Edg|OPR/.test(ua));
  chip('webusb', hasUsb ? 'مدعوم' : 'غير مدعوم', hasUsb);
  chip('secure', window.isSecureContext ? 'نعم' : 'لا', window.isSecureContext);

  if (!window.G700Adb) {
    setStatus('تعذّر تحميل أداة الاتصال. حدّث الصفحة.', 'bad');
  } else if (!hasUsb) {
    setStatus('هذا المتصفح لا يدعم WebUSB. افتح الصفحة في Chrome أو Edge على الكمبيوتر.', 'bad');
  } else {
    setStatus('جاهز. وصّل كابل USB بالسيارة (بعد تفعيل ADB) ثم اضغط «وصّل السيارة».');
    connectBtn.disabled = false;
  }

  // ---- latest version ----------------------------------------------------
  fetch('latest.json', { cache: 'no-store' }).then(function (r) { return r.ok ? r.json() : null; }).then(function (j) {
    if (j && j.sha256) {
      latest = j;
      $('latest').textContent = 'آخر إصدار: ' + j.version_name + ' (' + mb(j.size) + ')';
    } else {
      $('latest').textContent = 'الإصدار غير متاح حالياً.';
    }
  }).catch(function () { $('latest').textContent = 'تعذّر معرفة آخر إصدار.'; });

  // ---- connect -----------------------------------------------------------
  function explain(err) {
    var msg = String((err && err.message) || err);
    if (err && err.name === 'NotFoundError') return null; // the user closed the device picker
    if (/claim|busy|Unable to open|access denied|DeviceBusy/i.test(msg + (err && err.name))) {
      return 'الجهاز مشغول ببرنامج آخر. نفّذ adb kill-server وأغلق أي أداة ADB ثم أعد المحاولة.';
    }
    if (/auth|reject|denied/i.test(msg)) {
      return 'لم توافق السيارة على الاتصال. اضغط «وصّل السيارة» من جديد ووافق على «السماح بتصحيح USB» من شاشة السيارة.';
    }
    return 'تعذّر الاتصال: ' + msg;
  }

  connectBtn.addEventListener('click', function () {
    if (busy) return;
    busy = true;
    connectBtn.disabled = true;
    setStatus('اختر السيارة من النافذة ثم وافق على شاشة السيارة…', 'busy');
    Promise.resolve().then(function () { return window.G700Adb.connect(); }).then(function (c) {
      if (!c) { setStatus('لم تُختر سيارة.'); return null; }
      car = c;
      car.disconnected.then(function () {
        car = null;
        $('install-box').hidden = true;
        $('device').hidden = true;
        connectBtn.disabled = false;
        setStatus('انقطع الاتصال بالسيارة. وصّلها من جديد.', 'bad');
      });
      return Promise.all([car.getProp('ro.product.model'), car.getProp('ro.build.version.release')]).then(function (p) {
        $('d-model').textContent = p[0] || '—';
        $('d-android').textContent = p[1] || '—';
        $('d-serial').textContent = car.serial;
        $('device').hidden = false;
        $('install-box').hidden = false;
        setStatus('متصل بالسيارة. اضغط «ثبّت Car Home».', 'good');
      });
    }).catch(function (err) {
      var text = explain(err);
      setStatus(text || 'أُلغي الاختيار.', text ? 'bad' : '');
    }).then(function () {
      busy = false;
      if (!car) connectBtn.disabled = false;
    });
  });

  // ---- install -----------------------------------------------------------
  async function shell(cmd) { return String(await car.shell(cmd)).trim(); }

  installBtn.addEventListener('click', async function () {
    if (busy || !car || !latest) {
      if (!latest) setStatus('الإصدار غير متاح حالياً، حدّث الصفحة.', 'bad');
      return;
    }
    busy = true;
    installBtn.disabled = true;
    logEl.textContent = '';
    setStatus('جارٍ التثبيت. لا تفصل الكابل.', 'busy');
    var bar = $('progress'), barInner = $('progress-bar');
    try {
      var step = addLog('تنزيل التطبيق (' + latest.version_name + ')', 'run');
      var res = await fetch(latest.url, { cache: 'no-store' });
      if (!res.ok) throw new Error('فشل التنزيل (' + res.status + ')');
      var bytes = new Uint8Array(await res.arrayBuffer());
      var digest = hex(await crypto.subtle.digest('SHA-256', bytes));
      if (digest !== latest.sha256) throw new Error('بصمة الملف غير مطابقة، أُوقف التثبيت');
      mark(step, 'ok', 'تنزيل التطبيق والتحقق من بصمته (' + mb(bytes.length) + ')');

      step = addLog('تثبيت التطبيق على السيارة', 'run');
      bar.hidden = false;
      await car.install(bytes, function (fraction) { barInner.style.width = Math.round(fraction * 100) + '%'; });
      barInner.style.width = '100%';
      mark(step, 'ok', 'تم تثبيت التطبيق');

      var failed = [];
      for (var i = 0; i < GRANTS.length; i++) {
        var g = GRANTS[i];
        step = addLog(g.label, 'run');
        var out = await shell(g.cmd);
        var bad = /Exception|Unknown|Error|Failure|not found|Security/i.test(out);
        if (!bad) mark(step, 'ok');
        else if (g.optional) mark(step, 'skip', g.label + ' (غير متاحة في هذه السيارة)');
        else { mark(step, 'fail', g.label + ': ' + out.split('\n')[0]); failed.push(g.label); }
      }

      step = addLog('تشغيل التطبيق', 'run');
      await shell('am start -n ' + PKG + '/.MainActivity');
      mark(step, 'ok');

      if (failed.length) {
        setStatus('ثُبّت التطبيق لكن تعذّر منح بعض الصلاحيات: ' + failed.join('، '), 'bad');
      } else {
        setStatus('تم! افتح «Car Home» على شاشة السيارة. يمكنك فصل الكابل الآن.', 'good');
      }
    } catch (err) {
      var msg = String((err && err.message) || err);
      if (/INSTALL_FAILED_UPDATE_INCOMPATIBLE|signatures do not match/i.test(msg)) {
        msg = 'النسخة المثبتة موقّعة بمفتاح مختلف. احذف «Car Home» من السيارة أولاً ثم أعد المحاولة.';
      }
      addLog(msg, 'fail');
      setStatus('فشل التثبيت: ' + msg, 'bad');
    } finally {
      bar.hidden = true;
      busy = false;
      installBtn.disabled = false;
    }
  });
})();
