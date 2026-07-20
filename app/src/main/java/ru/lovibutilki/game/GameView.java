package ru.lovibutilki.game;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Locale;
import java.util.Random;

public final class GameView extends View implements Runnable {

    private enum State {
        MENU,
        PLAYING,
        PAUSED,
        LEVEL_COMPLETE,
        GAME_OVER,
        VICTORY
    }

    private static final int MAX_LEVEL = 10;
    private static final String PREFS = "bottle_catcher_prefs";
    private static final String KEY_HIGH_SCORE = "high_score";

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random random = new Random();
    private final ArrayList<Bottle> bottles = new ArrayList<>();
    private final ArrayList<Shard> shards = new ArrayList<>();
    private final RectF pauseRect = new RectF();
    private final RectF startButtonRect = new RectF();
    private final SharedPreferences preferences;

    private Bitmap characterBitmap;
    private ToneGenerator toneGenerator;

    private State state = State.MENU;
    private boolean loopRunning;
    private boolean hostPaused;
    private long lastFrameTime;
    private long lastSpawnTime;

    private int level = 1;
    private int score;
    private int highScore;
    private int lives = 3;
    private int caughtInLevel;
    private int selectedLane = 3;

    private float width;
    private float height;
    private float density;

    public GameView(Context context) {
        super(context);
        setFocusable(true);
        setKeepScreenOn(true);
        density = getResources().getDisplayMetrics().density;
        characterBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.personazh);
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        highScore = preferences.getInt(KEY_HIGH_SCORE, 0);

        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);

        textPaint.setTypeface(android.graphics.Typeface.create(
                android.graphics.Typeface.SANS_SERIF,
                android.graphics.Typeface.BOLD
        ));

        try {
            toneGenerator = new ToneGenerator(AudioManager.STREAM_MUSIC, 55);
        } catch (RuntimeException ignored) {
            toneGenerator = null;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startLoop();
    }

    @Override
    protected void onDetachedFromWindow() {
        loopRunning = false;
        removeCallbacks(this);
        if (toneGenerator != null) {
            toneGenerator.release();
            toneGenerator = null;
        }
        super.onDetachedFromWindow();
    }

    public void onHostPause() {
        hostPaused = true;
        if (state == State.PLAYING) {
            state = State.PAUSED;
        }
    }

    public void onHostResume() {
        hostPaused = false;
        startLoop();
    }

    private void startLoop() {
        if (!loopRunning) {
            loopRunning = true;
            lastFrameTime = SystemClock.uptimeMillis();
            postOnAnimation(this);
        }
    }

    @Override
    public void run() {
        if (!loopRunning) {
            return;
        }

        long now = SystemClock.uptimeMillis();
        float deltaSeconds = Math.min(0.045f, Math.max(0f, (now - lastFrameTime) / 1000f));
        lastFrameTime = now;

        if (!hostPaused) {
            update(deltaSeconds, now);
        }

        invalidate();
        postOnAnimation(this);
    }

    private void update(float dt, long now) {
        updateShards(dt);

        if (state != State.PLAYING) {
            return;
        }

        if (now - lastSpawnTime >= spawnIntervalMs() && bottles.size() < maxActiveBottles()) {
            spawnBottle(now);
        }

        Iterator<Bottle> iterator = bottles.iterator();
        while (iterator.hasNext()) {
            Bottle bottle = iterator.next();
            bottle.progress += dt * 1000f / bottle.durationMs;
            bottle.rotation += dt * (bottle.fromLeft ? 260f : -260f);

            if (bottle.progress >= 0.84f
                    && bottle.progress <= 1.03f
                    && bottle.lane == selectedLane) {
                iterator.remove();
                onBottleCaught(bottle);
                if (state != State.PLAYING) {
                    break;
                }
            } else if (bottle.progress > 1.08f) {
                iterator.remove();
                onBottleMissed(bottle);
                if (state != State.PLAYING) {
                    break;
                }
            }
        }
    }

    private void updateShards(float dt) {
        Iterator<Shard> iterator = shards.iterator();
        while (iterator.hasNext()) {
            Shard shard = iterator.next();
            shard.life -= dt;
            shard.x += shard.vx * dt;
            shard.vy += height * 1.2f * dt;
            shard.y += shard.vy * dt;
            shard.rotation += shard.spin * dt;
            if (shard.life <= 0f) {
                iterator.remove();
            }
        }
    }

    private void spawnBottle(long now) {
        int lane = random.nextInt(4);
        boolean golden = level >= 4 && random.nextFloat() < 0.08f + level * 0.004f;
        float speedJitter = 0.88f + random.nextFloat() * 0.23f;
        bottles.add(new Bottle(lane, travelDurationMs() * speedJitter, golden));
        lastSpawnTime = now;
    }

    private void onBottleCaught(Bottle bottle) {
        int base = bottle.golden ? 25 : 10;
        score += base * level;
        caughtInLevel += bottle.golden ? 2 : 1;
        playTone(ToneGenerator.TONE_PROP_BEEP, bottle.golden ? 115 : 70);

        if (caughtInLevel >= targetForLevel()) {
            caughtInLevel = targetForLevel();
            bottles.clear();
            if (level >= MAX_LEVEL) {
                state = State.VICTORY;
                saveHighScore();
            } else {
                state = State.LEVEL_COMPLETE;
            }
        }
    }

    private void onBottleMissed(Bottle bottle) {
        lives--;
        createShards(bottle);
        playTone(ToneGenerator.TONE_CDMA_ABBR_ALERT, 150);

        if (lives <= 0) {
            lives = 0;
            bottles.clear();
            state = State.GAME_OVER;
            saveHighScore();
        }
    }

    private void createShards(Bottle bottle) {
        Point point = bottlePoint(bottle.lane, 1.08f);
        int color = bottle.golden ? Color.rgb(245, 188, 51) : Color.rgb(69, 144, 87);
        for (int i = 0; i < 9; i++) {
            float angle = (float) (Math.PI * (0.15 + random.nextDouble() * 0.70));
            float speed = width * (0.035f + random.nextFloat() * 0.055f);
            float direction = bottle.fromLeft ? 1f : -1f;
            shards.add(new Shard(
                    point.x,
                    Math.min(height * 0.88f, point.y),
                    (float) Math.cos(angle) * speed * direction,
                    (float) -Math.sin(angle) * speed,
                    0.7f + random.nextFloat() * 0.45f,
                    color,
                    -220f + random.nextFloat() * 440f
            ));
        }
    }

    private void playTone(int tone, int durationMs) {
        if (toneGenerator != null) {
            try {
                toneGenerator.startTone(tone, durationMs);
            } catch (RuntimeException ignored) {
                // Звук не должен ломать игру на устройствах с необычной аудиосистемой.
            }
        }
    }

    private void startNewGame() {
        level = 1;
        score = 0;
        lives = 3;
        caughtInLevel = 0;
        selectedLane = 3;
        bottles.clear();
        shards.clear();
        lastSpawnTime = SystemClock.uptimeMillis() - 500L;
        state = State.PLAYING;
    }

    private void startNextLevel() {
        level++;
        lives = 3;
        caughtInLevel = 0;
        selectedLane = 3;
        bottles.clear();
        shards.clear();
        lastSpawnTime = SystemClock.uptimeMillis() - 500L;
        state = State.PLAYING;
    }

    private void saveHighScore() {
        if (score > highScore) {
            highScore = score;
            preferences.edit().putInt(KEY_HIGH_SCORE, highScore).apply();
        }
    }

    private int targetForLevel() {
        return 10 + level * 4;
    }

    private long spawnIntervalMs() {
        return Math.max(600L, 1420L - (level - 1L) * 90L);
    }

    private float travelDurationMs() {
        return Math.max(1180f, 2450f - (level - 1) * 135f);
    }

    private int maxActiveBottles() {
        if (level >= 10) return 4;
        if (level >= 7) return 3;
        if (level >= 3) return 2;
        return 1;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        width = getWidth();
        height = getHeight();

        drawBackground(canvas);
        drawShops(canvas);
        drawRails(canvas);
        drawCharacter(canvas);
        drawBottles(canvas);
        drawBag(canvas);
        drawShards(canvas);
        drawControls(canvas);
        drawHud(canvas);

        if (state != State.PLAYING) {
            drawOverlay(canvas);
        }
    }

    private void drawBackground(Canvas canvas) {
        LinearGradient sky = new LinearGradient(
                0f, 0f, 0f, height,
                new int[]{
                        Color.rgb(108, 174, 205),
                        Color.rgb(205, 219, 190),
                        Color.rgb(112, 90, 61)
                },
                new float[]{0f, 0.67f, 1f},
                Shader.TileMode.CLAMP
        );
        paint.setShader(sky);
        canvas.drawRect(0f, 0f, width, height, paint);
        paint.setShader(null);

        paint.setColor(Color.argb(42, 255, 255, 255));
        for (int i = 0; i < 7; i++) {
            float cx = width * (0.08f + i * 0.145f);
            float cy = height * (0.09f + (i % 3) * 0.035f);
            canvas.drawCircle(cx, cy, height * 0.028f, paint);
            canvas.drawCircle(cx + height * 0.025f, cy + height * 0.004f, height * 0.035f, paint);
        }

        paint.setColor(Color.rgb(86, 69, 48));
        canvas.drawRect(0f, height * 0.84f, width, height, paint);
        paint.setColor(Color.rgb(111, 88, 59));
        for (int i = 0; i < 16; i++) {
            float y = height * (0.85f + i * 0.01f);
            canvas.drawLine(0f, y, width, y + height * 0.004f, paint);
        }
    }

    private void drawShops(Canvas canvas) {
        drawShop(canvas, false);
        drawShop(canvas, true);
    }

    private void drawShop(Canvas canvas, boolean right) {
        float left = right ? width * 0.745f : width * 0.015f;
        float rightEdge = right ? width * 0.985f : width * 0.255f;
        float top = height * 0.12f;
        float bottom = height * 0.83f;

        paint.setColor(Color.rgb(213, 183, 126));
        canvas.drawRoundRect(left, top, rightEdge, bottom, height * 0.025f, height * 0.025f, paint);

        paint.setColor(Color.rgb(126, 57, 40));
        canvas.drawRect(left - width * 0.008f, top, rightEdge + width * 0.008f, top + height * 0.105f, paint);

        paint.setColor(Color.rgb(244, 214, 143));
        RectF sign = new RectF(left + width * 0.02f, top + height * 0.018f,
                rightEdge - width * 0.02f, top + height * 0.088f);
        canvas.drawRoundRect(sign, height * 0.015f, height * 0.015f, paint);
        drawCenteredText(canvas, "МАГАЗИН", sign.centerX(), sign.centerY() + height * 0.013f,
                height * 0.036f, Color.rgb(93, 45, 33));

        for (int row = 0; row < 2; row++) {
            float windowTop = height * (row == 0 ? 0.275f : 0.52f);
            RectF window = new RectF(
                    left + width * 0.035f,
                    windowTop,
                    rightEdge - width * 0.035f,
                    windowTop + height * 0.145f
            );
            paint.setColor(Color.rgb(65, 91, 93));
            canvas.drawRoundRect(window, height * 0.012f, height * 0.012f, paint);
            strokePaint.setColor(Color.rgb(247, 223, 167));
            strokePaint.setStrokeWidth(height * 0.009f);
            canvas.drawRoundRect(window, height * 0.012f, height * 0.012f, strokePaint);

            paint.setColor(Color.argb(90, 211, 244, 255));
            canvas.drawRect(window.left + width * 0.01f, window.top + height * 0.012f,
                    window.centerX() - width * 0.004f, window.bottom - height * 0.012f, paint);
        }

        paint.setColor(Color.rgb(97, 53, 36));
        RectF door = new RectF(left + width * 0.075f, height * 0.68f,
                rightEdge - width * 0.075f, bottom);
        canvas.drawRoundRect(door, height * 0.012f, height * 0.012f, paint);

        paint.setColor(Color.rgb(239, 196, 86));
        canvas.drawCircle(right ? door.left + width * 0.018f : door.right - width * 0.018f,
                door.centerY(), height * 0.008f, paint);
    }

    private void drawRails(Canvas canvas) {
        for (int lane = 0; lane < 4; lane++) {
            Point start = bottlePoint(lane, 0f);
            Point end = bottlePoint(lane, 0.82f);
            strokePaint.setStrokeWidth(height * 0.016f);
            strokePaint.setColor(Color.rgb(82, 65, 45));
            canvas.drawLine(start.x, start.y, end.x, end.y, strokePaint);
            strokePaint.setStrokeWidth(height * 0.006f);
            strokePaint.setColor(Color.rgb(205, 173, 115));
            canvas.drawLine(start.x, start.y - height * 0.014f, end.x, end.y - height * 0.014f, strokePaint);
        }
    }

    private void drawCharacter(Canvas canvas) {
        if (characterBitmap == null) {
            return;
        }

        float targetHeight = height * 0.66f;
        float aspect = characterBitmap.getWidth() / (float) characterBitmap.getHeight();
        float targetWidth = targetHeight * aspect;
        float centerX = width * 0.50f;
        RectF dst = new RectF(
                centerX - targetWidth / 2f,
                height * 0.22f,
                centerX + targetWidth / 2f,
                height * 0.22f + targetHeight
        );

        shadowPaint.setColor(Color.argb(100, 0, 0, 0));
        canvas.drawRoundRect(
                dst.left - height * 0.012f,
                dst.top + height * 0.012f,
                dst.right + height * 0.012f,
                dst.bottom + height * 0.018f,
                height * 0.035f,
                height * 0.035f,
                shadowPaint
        );

        int save = canvas.save();
        Path clip = new Path();
        clip.addRoundRect(dst, height * 0.028f, height * 0.028f, Path.Direction.CW);
        canvas.clipPath(clip);
        paint.setAlpha(255);
        canvas.drawBitmap(characterBitmap, null, dst, paint);
        canvas.restoreToCount(save);

        strokePaint.setColor(Color.rgb(55, 43, 30));
        strokePaint.setStrokeWidth(height * 0.008f);
        canvas.drawRoundRect(dst, height * 0.028f, height * 0.028f, strokePaint);
    }

    private void drawBottles(Canvas canvas) {
        for (Bottle bottle : bottles) {
            Point point = bottlePoint(bottle.lane, bottle.progress);
            float size = height * (bottle.golden ? 0.078f : 0.068f);

            canvas.save();
            canvas.rotate(bottle.rotation, point.x, point.y);
            drawBottle(canvas, point.x, point.y, size, bottle.golden);
            canvas.restore();
        }
    }

    private void drawBottle(Canvas canvas, float cx, float cy, float size, boolean golden) {
        int body = golden ? Color.rgb(244, 185, 45) : Color.rgb(62, 142, 81);
        int dark = golden ? Color.rgb(139, 89, 14) : Color.rgb(22, 78, 42);

        paint.setColor(Color.argb(65, 0, 0, 0));
        canvas.drawOval(cx - size * 0.30f, cy + size * 0.38f,
                cx + size * 0.38f, cy + size * 0.53f, paint);

        paint.setColor(body);
        canvas.drawRoundRect(
                cx - size * 0.22f,
                cy - size * 0.34f,
                cx + size * 0.22f,
                cy + size * 0.40f,
                size * 0.11f,
                size * 0.11f,
                paint
        );
        canvas.drawRect(
                cx - size * 0.10f,
                cy - size * 0.53f,
                cx + size * 0.10f,
                cy - size * 0.28f,
                paint
        );

        strokePaint.setColor(dark);
        strokePaint.setStrokeWidth(size * 0.055f);
        canvas.drawRoundRect(
                cx - size * 0.22f,
                cy - size * 0.34f,
                cx + size * 0.22f,
                cy + size * 0.40f,
                size * 0.11f,
                size * 0.11f,
                strokePaint
        );

        paint.setColor(Color.rgb(246, 237, 198));
        canvas.drawRect(
                cx - size * 0.19f,
                cy - size * 0.06f,
                cx + size * 0.19f,
                cy + size * 0.14f,
                paint
        );

        paint.setColor(dark);
        canvas.drawRect(
                cx - size * 0.12f,
                cy - size * 0.59f,
                cx + size * 0.12f,
                cy - size * 0.48f,
                paint
        );
    }

    private void drawBag(Canvas canvas) {
        Point catchPoint = bagPoint(selectedLane);
        float bagW = width * 0.105f;
        float bagH = height * 0.15f;

        paint.setColor(Color.argb(80, 0, 0, 0));
        canvas.drawOval(catchPoint.x - bagW * 0.55f, catchPoint.y + bagH * 0.38f,
                catchPoint.x + bagW * 0.55f, catchPoint.y + bagH * 0.62f, paint);

        paint.setColor(Color.rgb(233, 228, 211));
        Path bag = new Path();
        bag.moveTo(catchPoint.x - bagW * 0.48f, catchPoint.y - bagH * 0.25f);
        bag.lineTo(catchPoint.x + bagW * 0.48f, catchPoint.y - bagH * 0.25f);
        bag.lineTo(catchPoint.x + bagW * 0.39f, catchPoint.y + bagH * 0.48f);
        bag.quadTo(catchPoint.x, catchPoint.y + bagH * 0.62f,
                catchPoint.x - bagW * 0.39f, catchPoint.y + bagH * 0.48f);
        bag.close();
        canvas.drawPath(bag, paint);

        strokePaint.setColor(Color.rgb(74, 104, 132));
        strokePaint.setStrokeWidth(height * 0.011f);
        canvas.drawPath(bag, strokePaint);
        canvas.drawArc(
                catchPoint.x - bagW * 0.27f,
                catchPoint.y - bagH * 0.54f,
                catchPoint.x + bagW * 0.27f,
                catchPoint.y + bagH * 0.03f,
                190f,
                160f,
                false,
                strokePaint
        );

        drawCenteredText(canvas, "ПАКЕТ", catchPoint.x, catchPoint.y + bagH * 0.22f,
                height * 0.024f, Color.rgb(74, 104, 132));
    }

    private void drawShards(Canvas canvas) {
        for (Shard shard : shards) {
            paint.setColor(shard.color);
            paint.setAlpha((int) (255f * Math.max(0f, Math.min(1f, shard.life))));
            canvas.save();
            canvas.rotate(shard.rotation, shard.x, shard.y);
            Path triangle = new Path();
            float size = height * 0.018f;
            triangle.moveTo(shard.x, shard.y - size);
            triangle.lineTo(shard.x + size * 0.72f, shard.y + size * 0.72f);
            triangle.lineTo(shard.x - size * 0.58f, shard.y + size * 0.45f);
            triangle.close();
            canvas.drawPath(triangle, paint);
            canvas.restore();
        }
        paint.setAlpha(255);
    }

    private void drawControls(Canvas canvas) {
        float radius = height * 0.075f;
        drawControl(canvas, 0, width * 0.105f, height * 0.69f, radius, "ВЕРХ");
        drawControl(canvas, 1, width * 0.105f, height * 0.875f, radius, "НИЗ");
        drawControl(canvas, 2, width * 0.895f, height * 0.69f, radius, "ВЕРХ");
        drawControl(canvas, 3, width * 0.895f, height * 0.875f, radius, "НИЗ");
    }

    private void drawControl(Canvas canvas, int lane, float cx, float cy, float radius, String label) {
        boolean selected = lane == selectedLane;
        paint.setColor(selected
                ? Color.argb(225, 245, 193, 85)
                : Color.argb(145, 40, 43, 39));
        canvas.drawCircle(cx, cy, radius, paint);

        strokePaint.setStrokeWidth(height * 0.007f);
        strokePaint.setColor(selected
                ? Color.rgb(92, 63, 28)
                : Color.argb(210, 255, 255, 255));
        canvas.drawCircle(cx, cy, radius, strokePaint);

        String arrow = lane % 2 == 0 ? "↑" : "↓";
        drawCenteredText(canvas, arrow, cx, cy - height * 0.006f,
                height * 0.072f, selected ? Color.rgb(73, 51, 25) : Color.WHITE);
        drawCenteredText(canvas, label, cx, cy + radius * 0.70f,
                height * 0.018f, selected ? Color.rgb(73, 51, 25) : Color.WHITE);
    }

    private void drawHud(Canvas canvas) {
        paint.setColor(Color.argb(205, 30, 28, 24));
        RectF hud = new RectF(width * 0.285f, height * 0.018f,
                width * 0.715f, height * 0.145f);
        canvas.drawRoundRect(hud, height * 0.025f, height * 0.025f, paint);

        drawCenteredText(canvas,
                String.format(Locale.getDefault(), "УРОВЕНЬ %d / %d", level, MAX_LEVEL),
                width * 0.39f, height * 0.067f, height * 0.032f, Color.WHITE);

        drawCenteredText(canvas,
                String.format(Locale.getDefault(), "БУТЫЛКИ %d / %d", caughtInLevel, targetForLevel()),
                width * 0.56f, height * 0.067f, height * 0.032f, Color.WHITE);

        drawCenteredText(canvas,
                String.format(Locale.getDefault(), "СЧЁТ %d", score),
                width * 0.48f, height * 0.118f, height * 0.027f, Color.rgb(245, 193, 85));

        for (int i = 0; i < 3; i++) {
            float x = width * (0.605f + i * 0.027f);
            float y = height * 0.112f;
            drawMiniBottle(canvas, x, y, height * 0.027f,
                    i < lives ? Color.rgb(74, 163, 91) : Color.argb(75, 255, 255, 255));
        }

        pauseRect.set(width * 0.925f, height * 0.028f,
                width * 0.982f, height * 0.135f);
        paint.setColor(Color.argb(180, 25, 27, 25));
        canvas.drawRoundRect(pauseRect, height * 0.018f, height * 0.018f, paint);
        paint.setColor(Color.WHITE);
        float barW = width * 0.008f;
        canvas.drawRoundRect(pauseRect.centerX() - barW * 1.3f, pauseRect.top + height * 0.025f,
                pauseRect.centerX() - barW * 0.3f, pauseRect.bottom - height * 0.025f,
                barW, barW, paint);
        canvas.drawRoundRect(pauseRect.centerX() + barW * 0.3f, pauseRect.top + height * 0.025f,
                pauseRect.centerX() + barW * 1.3f, pauseRect.bottom - height * 0.025f,
                barW, barW, paint);
    }

    private void drawMiniBottle(Canvas canvas, float cx, float cy, float size, int color) {
        paint.setColor(color);
        canvas.drawRoundRect(cx - size * 0.22f, cy - size * 0.35f,
                cx + size * 0.22f, cy + size * 0.35f,
                size * 0.09f, size * 0.09f, paint);
        canvas.drawRect(cx - size * 0.09f, cy - size * 0.55f,
                cx + size * 0.09f, cy - size * 0.30f, paint);
    }

    private void drawOverlay(Canvas canvas) {
        paint.setColor(Color.argb(188, 9, 12, 12));
        canvas.drawRect(0f, 0f, width, height, paint);

        RectF panel = new RectF(width * 0.25f, height * 0.15f,
                width * 0.75f, height * 0.84f);
        paint.setColor(Color.argb(242, 239, 226, 188));
        canvas.drawRoundRect(panel, height * 0.04f, height * 0.04f, paint);
        strokePaint.setStrokeWidth(height * 0.010f);
        strokePaint.setColor(Color.rgb(91, 58, 32));
        canvas.drawRoundRect(panel, height * 0.04f, height * 0.04f, strokePaint);

        String title;
        String subtitle;
        String button;

        switch (state) {
            case MENU:
                title = "ЛОВИ БУТЫЛКИ";
                subtitle = "Два магазина выбрасывают бутылки.\nПерекладывай пакет в нужный угол и не дай им разбиться!";
                button = "НАЧАТЬ ИГРУ";
                break;
            case PAUSED:
                title = "ПАУЗА";
                subtitle = "Игра остановлена.\nНажми кнопку, чтобы продолжить.";
                button = "ПРОДОЛЖИТЬ";
                break;
            case LEVEL_COMPLETE:
                title = "УРОВЕНЬ ПРОЙДЕН!";
                subtitle = String.format(Locale.getDefault(),
                        "Счёт: %d\nДальше бутылки полетят быстрее.", score);
                button = "УРОВЕНЬ " + (level + 1);
                break;
            case GAME_OVER:
                title = "БУТЫЛКИ РАЗБИЛИСЬ";
                subtitle = String.format(Locale.getDefault(),
                        "Счёт: %d\nРекорд: %d", score, highScore);
                button = "ЕЩЁ РАЗ";
                break;
            case VICTORY:
                title = "ПОБЕДА!";
                subtitle = String.format(Locale.getDefault(),
                        "Все 10 уровней пройдены!\nСчёт: %d   Рекорд: %d", score, highScore);
                button = "ИГРАТЬ СНАЧАЛА";
                break;
            default:
                title = "";
                subtitle = "";
                button = "";
        }

        drawCenteredText(canvas, title, panel.centerX(), height * 0.285f,
                height * 0.067f, Color.rgb(82, 47, 28));

        drawMultilineCenteredText(canvas, subtitle, panel.centerX(), height * 0.41f,
                height * 0.031f, Color.rgb(56, 54, 47), height * 0.052f);

        if (state == State.MENU) {
            drawCenteredText(canvas, "Управление: четыре круглые кнопки по краям",
                    panel.centerX(), height * 0.58f,
                    height * 0.025f, Color.rgb(76, 92, 96));
            drawCenteredText(canvas, "Золотая бутылка считается за две",
                    panel.centerX(), height * 0.625f,
                    height * 0.023f, Color.rgb(126, 82, 25));
        }

        startButtonRect.set(width * 0.355f, height * 0.68f,
                width * 0.645f, height * 0.79f);
        paint.setColor(Color.rgb(126, 57, 40));
        canvas.drawRoundRect(startButtonRect, height * 0.028f, height * 0.028f, paint);
        drawCenteredText(canvas, button, startButtonRect.centerX(),
                startButtonRect.centerY() + height * 0.012f,
                height * 0.034f, Color.WHITE);
    }

    private void drawCenteredText(Canvas canvas, String text, float cx, float cy, float size, int color) {
        textPaint.setTextSize(size);
        textPaint.setColor(color);
        textPaint.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float baseline = cy - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(text, cx, baseline, textPaint);
    }

    private void drawMultilineCenteredText(Canvas canvas, String text, float cx, float firstY,
                                           float size, int color, float lineSpacing) {
        String[] lines = text.split("\n");
        for (int i = 0; i < lines.length; i++) {
            drawCenteredText(canvas, lines[i], cx, firstY + i * lineSpacing, size, color);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() != MotionEvent.ACTION_DOWN
                && event.getActionMasked() != MotionEvent.ACTION_MOVE) {
            return true;
        }

        float x = event.getX();
        float y = event.getY();

        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            if (state == State.PLAYING && pauseRect.contains(x, y)) {
                state = State.PAUSED;
                return true;
            }

            if (state != State.PLAYING) {
                if (state == State.MENU || state == State.GAME_OVER || state == State.VICTORY) {
                    startNewGame();
                } else if (state == State.LEVEL_COMPLETE) {
                    startNextLevel();
                } else if (state == State.PAUSED) {
                    state = State.PLAYING;
                    lastFrameTime = SystemClock.uptimeMillis();
                }
                return true;
            }
        }

        if (state == State.PLAYING) {
            selectedLane = laneFromTouch(x, y);
        }
        return true;
    }

    private int laneFromTouch(float x, float y) {
        boolean right = x >= width / 2f;
        boolean bottom = y >= height * 0.78f;

        if (right) {
            return bottom ? 3 : 2;
        }
        return bottom ? 1 : 0;
    }

    private Point bagPoint(int lane) {
        boolean right = lane >= 2;
        boolean lower = lane % 2 == 1;
        return new Point(
                width * (right ? 0.625f : 0.375f),
                height * (lower ? 0.705f : 0.46f)
        );
    }

    private Point bottlePoint(int lane, float progress) {
        boolean right = lane >= 2;
        boolean lower = lane % 2 == 1;

        float startX = width * (right ? 0.755f : 0.245f);
        float startY = height * (lower ? 0.585f : 0.34f);
        Point bag = bagPoint(lane);

        float t = Math.max(0f, Math.min(1f, progress / 0.92f));
        float x = lerp(startX, bag.x, t);
        float y = lerp(startY, bag.y, t) + height * 0.045f * t * (1f - t);

        if (progress > 0.92f) {
            float missT = Math.min(1f, (progress - 0.92f) / 0.16f);
            x = lerp(bag.x, width * (right ? 0.69f : 0.31f), missT);
            y = lerp(bag.y, height * 0.89f, missT * missT);
        }
        return new Point(x, y);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static final class Bottle {
        final int lane;
        final float durationMs;
        final boolean golden;
        final boolean fromLeft;
        float progress;
        float rotation;

        Bottle(int lane, float durationMs, boolean golden) {
            this.lane = lane;
            this.durationMs = durationMs;
            this.golden = golden;
            this.fromLeft = lane < 2;
            this.rotation = fromLeft ? -18f : 18f;
        }
    }

    private static final class Shard {
        float x;
        float y;
        float vx;
        float vy;
        float life;
        float rotation;
        final int color;
        final float spin;

        Shard(float x, float y, float vx, float vy, float life, int color, float spin) {
            this.x = x;
            this.y = y;
            this.vx = vx;
            this.vy = vy;
            this.life = life;
            this.color = color;
            this.spin = spin;
        }
    }

    private static final class Point {
        final float x;
        final float y;

        Point(float x, float y) {
            this.x = x;
            this.y = y;
        }
    }
}
