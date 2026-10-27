package com.gathin.tvhome;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/** Waktu solat JAKIM di bawah jam: 6 kolum, atau 1 petak yang berputar. */
final class PrayerStrip extends View {
    private static final String ZONE = "SGR01";
    private static final String API = "https://www.e-solat.gov.my/index.php?r=esolatApi/TakwimSolat&period=today&zone=" + ZONE;
    private static final String[] NAMES = {"Subuh", "Syuruk", "Zohor", "Asar", "Maghrib", "Isyak"};
    private static final String[] KEYS = {"fajr", "syuruk", "dhuhr", "asr", "maghrib", "isha"};
    private static final int[] ROTATE = {0, 2, 3, 4, 5};
    private static final boolean USE_24H = false;
    private static final float GAP_DP = 10f;
    private static final long TICK_MS = 10_000L, RETRY_MS = 5 * 60_000L, ROTATE_MS = 6_000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final float d;
    private final boolean reduced;
    private final SharedPreferences prefs;
    private final int[] mins = new int[6];
    private final String[] labels = new String[6];
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Typeface nameFace = Typeface.create("sans-serif", Typeface.NORMAL);
    private final Typeface timeFace = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private boolean hasData, rotate, running;
    private String dataDay = "";
    private volatile boolean fetching;
    private long lastTry;
    private int cur = -1, rotPos;
    private float fade = 1f;
    private ValueAnimator anim;

    private PrayerStrip(Context ctx, boolean reduced) {
        super(ctx);
        d = ctx.getResources().getDisplayMetrics().density;
        this.reduced = reduced;
        prefs = ctx.getSharedPreferences("prayer", Context.MODE_PRIVATE);
        setFocusable(false);
        setClickable(false);
        setVisibility(INVISIBLE);
        String t = prefs.getString("times", "");
        if (t.split(",").length == 6) apply(t.split(","), prefs.getString("day", ""));
    }

    /** Letak di bawah baris jam. */
    static void attach(Context ctx, FrameLayout root, View dateBlock, boolean reduced) {
        final PrayerStrip s = new PrayerStrip(ctx, reduced);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        root.addView(s, lp);
        final View row = (View) dateBlock.getParent();
        row.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, orr, ob) -> s.place(root, row));
        row.post(() -> s.place(root, row));
    }

    private void place(FrameLayout root, View row) {
        if (row.getWidth() == 0) return;
        Rect r = new Rect(0, 0, row.getWidth(), row.getHeight());
        root.offsetDescendantRectToMyCoords(row, r);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
        int top = r.bottom + (int) (GAP_DP * d);
        if (lp.leftMargin != r.left || lp.topMargin != top) {
            lp.leftMargin = r.left;
            lp.topMargin = top;
            setLayoutParams(lp);
        }
        if (getVisibility() != VISIBLE) setVisibility(VISIBLE);
    }

    // ---- data ----------------------------------------------------------------------------------

    private static Calendar kl() {
        return Calendar.getInstance(TimeZone.getTimeZone("Asia/Kuala_Lumpur"));
    }

    private static String dayKey(Calendar c) {
        return String.format(Locale.US, "%04d-%02d-%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    private boolean apply(String[] hhmm, String day) {
        try {
            int[] m = new int[6];
            String[] l = new String[6];
            for (int i = 0; i < 6; i++) {
                String s = hhmm[i].trim();
                int h = Integer.parseInt(s.substring(0, 2));
                int mi = Integer.parseInt(s.substring(3, 5));
                m[i] = h * 60 + mi;
                int hh = h % 12 == 0 ? 12 : h % 12;
                l[i] = USE_24H ? String.format(Locale.US, "%02d:%02d", h, mi)
                        : String.format(Locale.US, "%d:%02d", hh, mi);
            }
            System.arraycopy(m, 0, mins, 0, 6);
            System.arraycopy(l, 0, labels, 0, 6);
            dataDay = day;
            hasData = true;
            cur = computeCur();
            invalidate();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Solat semasa: yang terakhir bermula. Sebelum Subuh, masih Isyak malam tadi. */
    private int computeCur() {
        if (!hasData) return -1;
        Calendar c = kl();
        int m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        int r = 5;
        for (int i = 0; i < 6; i++) if (mins[i] <= m) r = i;
        return r;
    }

    private void fetch() {
        if (fetching) return;
        fetching = true;
        lastTry = SystemClock.uptimeMillis();
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(API).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                c.setRequestProperty("User-Agent", "GlassTV");
                BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String ln;
                while ((ln = r.readLine()) != null) sb.append(ln);
                r.close();
                JSONObject p = new JSONObject(sb.toString()).getJSONArray("prayerTime").getJSONObject(0);
                final String[] t = new String[6];
                for (int i = 0; i < 6; i++) t[i] = p.getString(KEYS[i]).substring(0, 5);
                final String day = dayKey(kl());
                main.post(() -> {
                    if (apply(t, day)) {
                        prefs.edit().putString("day", day).putString("times", TextUtils.join(",", t)).apply();
                    }
                });
            } catch (Exception ignored) {
                // Tiada internet: paparan guna data simpanan, cuba semula selepas 5 minit.
            } finally {
                fetching = false;
            }
        }, "prayer-times").start();
    }

    // ---- masa ----------------------------------------------------------------------------------

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            refresh();
            main.postDelayed(this, TICK_MS);
        }
    };

    private final Runnable rotateTick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            if (rotate && hasData) swap();
            main.postDelayed(this, ROTATE_MS);
        }
    };

    private void refresh() {
        String mode = null;
        try {
            mode = Settings.Global.getString(getContext().getContentResolver(), "glass_prayer_mode");
        } catch (Exception ignored) {
        }
        boolean r = "rotate".equals(mode);
        int c = computeCur();
        if (r != rotate) {
            rotate = r;
            cur = c;
            for (int i = 0; i < ROTATE.length; i++) if (ROTATE[i] == c) rotPos = i;
            requestLayout();
            invalidate();
        }
        // Tengah malam tarikh bertukar: ambil data hari baru.
        long now = SystemClock.uptimeMillis();
        if (!dayKey(kl()).equals(dataDay) && (lastTry == 0 || now - lastTry >= RETRY_MS)) fetch();
        if (c != cur) {
            cur = c;
            invalidate();
        }
    }

    private void swap() {
        if (reduced) {
            rotPos = (rotPos + 1) % ROTATE.length;
            invalidate();
            return;
        }
        anim = ValueAnimator.ofFloat(1f, 0f);
        anim.setDuration(250);
        anim.addUpdateListener(a -> {
            fade = (float) a.getAnimatedValue();
            invalidate();
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator an) {
                if (!running) {
                    fade = 1f;
                    return;
                }
                rotPos = (rotPos + 1) % ROTATE.length;
                ValueAnimator in = ValueAnimator.ofFloat(0f, 1f);
                in.setDuration(250);
                in.addUpdateListener(a2 -> {
                    fade = (float) a2.getAnimatedValue();
                    invalidate();
                });
                anim = in;
                in.start();
            }
        });
        anim.start();
    }

    private void start() {
        if (running) return;
        running = true;
        main.post(tick);
        main.postDelayed(rotateTick, ROTATE_MS);
    }

    private void stop() {
        running = false;
        main.removeCallbacks(tick);
        main.removeCallbacks(rotateTick);
        if (anim != null) anim.cancel();
        fade = 1f;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        start();
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) start();
        else stop();
    }

    // ---- lukisan -------------------------------------------------------------------------------

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension((int) ((rotate ? 150 : 6 * 60 + 16) * d), (int) (56 * d));
    }

    private int fa(int argb) {
        int a = (int) (Color.alpha(argb) * fade);
        return (argb & 0x00FFFFFF) | (a << 24);
    }

    private void label(Canvas c, String s, float x, float y, float size, Typeface f, int color) {
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(size);
        text.setTypeface(f);
        text.setColor(fa(color));
        c.drawText(s, x, y, text);
    }

    /** Panel kaca lutsinar: isi gelap nipis, garis tepi dan sinar di atas. */
    private void panel(Canvas c, int w, int h) {
        float rad = 18 * d;
        rect.set(0, 0, w, h);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0x4D000000);
        c.drawRoundRect(rect, rad, rad, fill);
        rect.set(d / 2f, d / 2f, w - d / 2f, h - d / 2f);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(d);
        line.setColor(0x33FFFFFF);
        c.drawRoundRect(rect, rad, rad, line);
        line.setColor(0x40FFFFFF);
        c.drawLine(rad, 1.5f * d, w - rad, 1.5f * d, line);
    }

    /** Lencana "frosted": kilauan lembut, isi kaca, garis tepi dan sinar di atas. */
    private void badge(Canvas c, float l, float t, float r, float b) {
        float rad = 12 * d;
        fill.setStyle(Paint.Style.FILL);
        for (int k = 3; k >= 1; k--) {
            float e = k * 2f * d;
            fill.setColor(fa(Color.argb(10 + (3 - k) * 7, 255, 255, 255)));
            rect.set(l - e, t - e, r + e, b + e);
            c.drawRoundRect(rect, rad + e, rad + e, fill);
        }
        rect.set(l, t, r, b);
        fill.setColor(fa(0x40FFFFFF));
        c.drawRoundRect(rect, rad, rad, fill);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(d);
        line.setColor(fa(0x8CFFFFFF));
        c.drawRoundRect(rect, rad, rad, line);
        line.setColor(fa(0x66FFFFFF));
        c.drawLine(l + rad, t + 1.5f * d, r - rad, t + 1.5f * d, line);
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        panel(c, w, h);
        if (!hasData) {
            label(c, "Menunggu waktu solat...", w / 2f, h / 2f + 5 * d, 12 * d, nameFace, 0xB3FFFFFF);
            return;
        }
        if (rotate) {
            int idx = ROTATE[rotPos];
            boolean on = idx == cur;
            if (on) badge(c, 8 * d, 7 * d, w - 8 * d, h - 7 * d);
            label(c, NAMES[idx], w / 2f, 22 * d, 12 * d, nameFace, on ? 0xFFFFFFFF : 0xB3FFFFFF);
            label(c, labels[idx], w / 2f, 46 * d, 20 * d, timeFace, on ? 0xFFFFFFFF : 0xE6FFFFFF);
            return;
        }
        float pad = 8 * d, colW = (w - 2 * pad) / 6f;
        for (int i = 0; i < 6; i++) {
            float left = pad + colW * i, cx = left + colW / 2f;
            boolean on = i == cur;
            if (on) badge(c, left + 3 * d, 6 * d, left + colW - 3 * d, h - 6 * d);
            label(c, NAMES[i], cx, 21 * d, 10.5f * d, nameFace, on ? 0xFFFFFFFF : 0xB3FFFFFF);
            label(c, labels[i], cx, 43 * d, 16.5f * d, timeFace, on ? 0xFFFFFFFF : 0xE6FFFFFF);
        }
    }
}
