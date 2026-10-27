package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Random;

/** Hujan dan kilat di atas wallpaper, ikut cuaca Semenyih. Cerah: tak lukis apa-apa. */
final class WeatherOverlay extends View {
    private static final int CLEAR = 0, RAIN = 1, THUNDER = 2;
    private static final String API = "https://api.open-meteo.com/v1/forecast?latitude=2.95&longitude=101.84&current=weather_code";
    private static final long FETCH_MS = 15 * 60_000L, POLL_MS = 5_000L, FRAME_MS = 33L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Random rnd = new Random();
    private final Paint rainPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int count;
    private final float density;
    private final float[] x, y, len, speed, lines;
    private volatile int fetched = CLEAR;
    private volatile long lastFetch;
    private int mode = CLEAR;
    private long last, nextFlash, flashStart;
    private boolean running, ready;

    WeatherOverlay(Context context, boolean reduced) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        count = reduced ? 28 : 55;
        x = new float[count];
        y = new float[count];
        len = new float[count];
        speed = new float[count];
        lines = new float[count * 4];
        rainPaint.setColor(0x99C4DCF5);
        rainPaint.setStrokeWidth(1.6f * density);
        rainPaint.setStrokeCap(Paint.Cap.ROUND);
        setFocusable(false);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        for (int i = 0; i < count; i++) {
            x[i] = rnd.nextFloat() * w;
            y[i] = rnd.nextFloat() * h;
            len[i] = (14 + rnd.nextFloat() * 22) * density;
            speed[i] = (600 + rnd.nextFloat() * 500) * density;
        }
        ready = w > 0 && h > 0;
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

    private void start() {
        if (running) return;
        running = true;
        lastFetch = SystemClock.uptimeMillis() - FETCH_MS;
        main.post(tick);
    }

    private void stop() {
        running = false;
        main.removeCallbacks(tick);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            poll();
            main.postDelayed(this, POLL_MS);
        }
    };

    private void poll() {
        if (SystemClock.uptimeMillis() - lastFetch >= FETCH_MS) fetch();
        int m = fetched;
        try {
            String t = Settings.Global.getString(getContext().getContentResolver(), "glass_weather_test");
            if ("rain".equals(t)) m = RAIN;
            else if ("thunder".equals(t)) m = THUNDER;
            else if ("clear".equals(t)) m = CLEAR;
        } catch (Exception ignored) {
        }
        if (m != mode) {
            mode = m;
            last = SystemClock.uptimeMillis();
            nextFlash = last + 2500;
            invalidate();
        }
    }

    private void fetch() {
        lastFetch = SystemClock.uptimeMillis();
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(API).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
                r.close();
                int code = new JSONObject(sb.toString()).getJSONObject("current").getInt("weather_code");
                if (code >= 95) fetched = THUNDER;
                else if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) fetched = RAIN;
                else fetched = CLEAR;
            } catch (Exception e) {
                lastFetch = SystemClock.uptimeMillis() - FETCH_MS + 60_000L;
            }
        }, "weather").start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mode == CLEAR || !ready) return;
        long now = SystemClock.uptimeMillis();
        float dt = Math.min(0.1f, (now - last) / 1000f);
        last = now;
        int w = getWidth(), h = getHeight();
        for (int i = 0; i < count; i++) {
            y[i] += speed[i] * dt;
            x[i] += speed[i] * dt * 0.18f;
            if (y[i] > h || x[i] > w) {
                y[i] = -len[i];
                x[i] = rnd.nextFloat() * w;
            }
            lines[i * 4] = x[i];
            lines[i * 4 + 1] = y[i];
            lines[i * 4 + 2] = x[i] - len[i] * 0.18f;
            lines[i * 4 + 3] = y[i] - len[i];
        }
        canvas.drawLines(lines, rainPaint);
        if (mode == THUNDER) {
            if (now >= nextFlash) {
                flashStart = now;
                nextFlash = now + 5000 + rnd.nextInt(9000);
            }
            long t = now - flashStart;
            float a = t < 90 ? 0.6f : t < 130 ? 0.1f : t < 230 ? 0.45f
                    : t < 450 ? 0.15f * (1f - (t - 230) / 220f) : 0f;
            if (a > 0f) canvas.drawColor(Color.argb((int) (a * 255), 220, 230, 255));
        }
        postInvalidateDelayed(FRAME_MS);
    }
    }
