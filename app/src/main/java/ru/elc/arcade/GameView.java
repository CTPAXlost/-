package ru.elc.arcade;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Locale;
import java.util.Random;

public final class GameView extends View implements Runnable {

    private static final String TAG = "ELC_RUNTIME";

    private enum Screen {
        MENU,
        COUNTDOWN,
        PLAYING,
        PAUSED,
        LEVEL_DONE,
        GAME_OVER,
        VICTORY
    }

    private enum Hero {
        LOZHKIN("Александр Ложкин", "Ловит бутылки"),
        RAK("Алик Рак", "Кидает поленья в котёл"),
        KOROLEV("Андрей Королёв", "Тушит пожар");

        final String title;
        final String task;

        Hero(String title, String task) {
            this.title = title;
            this.task = task;
        }
    }

    private static final int MAX_LEVEL = 10;
    private static final String PREFS = "elc_v2";

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random random = new Random();
    private final ArrayList<Bottle> bottles = new ArrayList<Bottle>();
    private final ArrayList<Target> targets = new ArrayList<Target>();
    private final ArrayList<Spark> sparks = new ArrayList<Spark>();
    private final RectF menuRect = new RectF();
    private final SharedPreferences preferences;

    private Bitmap menuBitmap;
    private Bitmap headLozhkin;
    private Bitmap headRak;
    private Bitmap headKorolev;

    private Screen screen = Screen.MENU;
    private Hero hero = Hero.LOZHKIN;
    private boolean loopActive;
    private boolean hostPaused;

    private float width;
    private float height;
    private long lastFrameMs;
    private long lastSpawnMs;
    private long countdownStartMs;
    private float worldTime;

    private int level = 1;
    private int score;
    private int lives = 3;
    private int progress;
    private int selectedLane = 3;
    private int actionLane = 3;
    private float actionTimer;
    private float missFlash;
    private float successFlash;
    private String menuNotice = "";
    private long menuNoticeUntil;
    private String accessibilityState = "";

    public GameView(Context context) {
        super(context);
        setFocusable(true);
        setKeepScreenOn(true);
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);

        text.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        text.setTextAlign(Paint.Align.CENTER);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);

        loadSmallAssets();
        updateAccessibilityState();
    }

    private void loadSmallAssets() {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        headLozhkin = BitmapFactory.decodeResource(getResources(), R.drawable.head_lozhkin, options);
        headRak = BitmapFactory.decodeResource(getResources(), R.drawable.head_rak, options);
        headKorolev = BitmapFactory.decodeResource(getResources(), R.drawable.head_korolev, options);
    }

    private void ensureMenuBitmap() {
        if (menuBitmap == null || menuBitmap.isRecycled()) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inScaled = false;
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            menuBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.menu_elc, options);
        }
    }

    private void releaseMenuBitmap() {
        if (menuBitmap != null && !menuBitmap.isRecycled()) {
            menuBitmap.recycle();
        }
        menuBitmap = null;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        resumeGameLoop();
    }

    @Override
    protected void onDetachedFromWindow() {
        loopActive = false;
        removeCallbacks(this);
        super.onDetachedFromWindow();
    }

    public void resumeGameLoop() {
        hostPaused = false;
        if (!loopActive) {
            loopActive = true;
            lastFrameMs = SystemClock.uptimeMillis();
            postOnAnimation(this);
        }
    }

    public void pauseGameLoop() {
        hostPaused = true;
        if (screen == Screen.PLAYING) {
            screen = Screen.PAUSED;
        }
    }

    public boolean handleBackPressed() {
        if (screen != Screen.MENU) {
            goToMenu();
            return true;
        }
        return false;
    }

    @Override
    public void run() {
        if (!loopActive) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        float dt = Math.min(0.05f, Math.max(0f, (now - lastFrameMs) / 1000f));
        lastFrameMs = now;
        if (!hostPaused) {
            update(dt, now);
        }
        updateAccessibilityState();
        invalidate();
        postOnAnimation(this);
    }

    private void updateAccessibilityState() {
        String next = "ELC|" + screen.name() + "|" + hero.name() + "|LEVEL=" + level;
        if (!next.equals(accessibilityState)) {
            accessibilityState = next;
            setContentDescription(next);
            Log.i(TAG, "ELC_STATE " + next);
        }
    }

    private void update(float dt, long now) {
        worldTime += dt;
        if (actionTimer > 0f) {
            actionTimer = Math.max(0f, actionTimer - dt);
        }
        if (missFlash > 0f) {
            missFlash = Math.max(0f, missFlash - dt);
        }
        if (successFlash > 0f) {
            successFlash = Math.max(0f, successFlash - dt);
        }
        updateSparks(dt);

        if (screen == Screen.COUNTDOWN) {
            if (now - countdownStartMs >= 3000L) {
                screen = Screen.PLAYING;
                lastSpawnMs = now - spawnIntervalMs() + 350L;
            }
            return;
        }
        if (screen != Screen.PLAYING) {
            return;
        }

        if (now - lastSpawnMs >= spawnIntervalMs()) {
            if (hero == Hero.LOZHKIN) {
                if (bottles.size() < maxObjects()) {
                    spawnBottle();
                    lastSpawnMs = now;
                }
            } else if (targets.size() < maxObjects()) {
                spawnTarget();
                lastSpawnMs = now;
            }
        }

        if (hero == Hero.LOZHKIN) {
            updateBottles(dt);
        } else {
            updateTargets(dt);
        }
    }

    private void updateBottles(float dt) {
        Iterator<Bottle> iterator = bottles.iterator();
        while (iterator.hasNext()) {
            Bottle bottle = iterator.next();
            bottle.t += dt / bottle.duration;
            bottle.rotation += dt * (bottle.lane < 2 ? 260f : -260f);
            if (bottle.t >= 0.88f && bottle.t <= 1.06f && bottle.lane == selectedLane) {
                PointF p = lanePoint(bottle.lane);
                iterator.remove();
                onSuccess(p.x, p.y, bottle.golden ? 2 : 1, bottle.golden);
                if (screen != Screen.PLAYING) {
                    return;
                }
            } else if (bottle.t > 1.08f) {
                PointF p = lanePoint(bottle.lane);
                iterator.remove();
                onMiss(p.x, p.y);
                if (screen != Screen.PLAYING) {
                    return;
                }
            }
        }
    }

    private void updateTargets(float dt) {
        Iterator<Target> iterator = targets.iterator();
        while (iterator.hasNext()) {
            Target target = iterator.next();
            target.age += dt;
            if (target.age >= target.life) {
                PointF p = lanePoint(target.lane);
                iterator.remove();
                onMiss(p.x, p.y);
                if (screen != Screen.PLAYING) {
                    return;
                }
            }
        }
    }

    private void updateSparks(float dt) {
        Iterator<Spark> iterator = sparks.iterator();
        while (iterator.hasNext()) {
            Spark spark = iterator.next();
            spark.life -= dt;
            spark.x += spark.vx * dt;
            spark.y += spark.vy * dt;
            spark.vy += spark.gravity * dt;
            if (spark.life <= 0f) {
                iterator.remove();
            }
        }
    }

    private void spawnBottle() {
        int lane = chooseFreeLane(true);
        float duration = Math.max(1.20f, 3.05f - level * 0.17f);
        boolean golden = level >= 4 && random.nextFloat() < 0.07f;
        bottles.add(new Bottle(lane, duration * (0.90f + random.nextFloat() * 0.20f), golden));
    }

    private void spawnTarget() {
        int lane = chooseFreeLane(false);
        float life = Math.max(0.95f, 2.55f - level * 0.14f);
        targets.add(new Target(lane, life));
    }

    private int chooseFreeLane(boolean forBottle) {
        ArrayList<Integer> free = new ArrayList<Integer>();
        for (int lane = 0; lane < 4; lane++) {
            boolean used = false;
            if (forBottle) {
                for (Bottle bottle : bottles) {
                    if (bottle.lane == lane && bottle.t < 0.70f) {
                        used = true;
                        break;
                    }
                }
            } else {
                for (Target target : targets) {
                    if (target.lane == lane) {
                        used = true;
                        break;
                    }
                }
            }
            if (!used) {
                free.add(lane);
            }
        }
        if (free.isEmpty()) {
            return random.nextInt(4);
        }
        return free.get(random.nextInt(free.size()));
    }

    private void performAction(int lane) {
        actionLane = lane;
        actionTimer = 0.34f;
        if (hero == Hero.LOZHKIN) {
            selectedLane = lane;
            return;
        }

        Target matched = null;
        for (Target target : targets) {
            if (target.lane == lane) {
                matched = target;
                break;
            }
        }
        if (matched != null) {
            targets.remove(matched);
            PointF p = lanePoint(lane);
            onSuccess(p.x, p.y, 1, false);
        }
    }

    private void onSuccess(float x, float y, int amount, boolean golden) {
        score += (golden ? 25 : 10) * level;
        progress += amount;
        successFlash = 0.14f;
        addSparks(x, y, golden ? 0xFFFFD42A : heroAccent(), 18);
        if (progress >= targetForLevel()) {
            progress = targetForLevel();
            bottles.clear();
            targets.clear();
            saveBestScore();
            saveUnlockedLevel();
            if (level >= MAX_LEVEL) {
                screen = Screen.VICTORY;
            } else {
                screen = Screen.LEVEL_DONE;
            }
        }
    }

    private void onMiss(float x, float y) {
        lives--;
        missFlash = 0.22f;
        addSparks(x, y, 0xFFFF4B35, 14);
        if (lives <= 0) {
            lives = 0;
            bottles.clear();
            targets.clear();
            saveBestScore();
            screen = Screen.GAME_OVER;
        }
    }

    private void addSparks(float x, float y, int color, int count) {
        for (int i = 0; i < count; i++) {
            float angle = (float) (random.nextFloat() * Math.PI * 2.0);
            float speed = width * (0.10f + random.nextFloat() * 0.22f);
            sparks.add(new Spark(
                    x,
                    y,
                    (float) Math.cos(angle) * speed,
                    (float) Math.sin(angle) * speed,
                    width * (0.006f + random.nextFloat() * 0.006f),
                    0.35f + random.nextFloat() * 0.35f,
                    color,
                    height * 0.40f
            ));
        }
    }

    private int targetForLevel() {
        return 7 + level * 3;
    }

    private long spawnIntervalMs() {
        return Math.max(470L, 1440L - level * 88L);
    }

    private int maxObjects() {
        if (level <= 2) {
            return 1;
        }
        if (level <= 5) {
            return 2;
        }
        if (level <= 8) {
            return 3;
        }
        return 4;
    }

    private void selectHero(Hero selected) {
        hero = selected;
        level = 1;
        score = 0;
        lives = 3;
        progress = 0;
        selectedLane = 3;
        actionLane = 3;
        actionTimer = 0f;
        bottles.clear();
        targets.clear();
        sparks.clear();
        releaseMenuBitmap();
        beginCountdown();
    }

    private void beginCountdown() {
        screen = Screen.COUNTDOWN;
        countdownStartMs = SystemClock.uptimeMillis();
        lastSpawnMs = countdownStartMs;
    }

    private void nextLevel() {
        level++;
        lives = 3;
        progress = 0;
        selectedLane = 3;
        bottles.clear();
        targets.clear();
        sparks.clear();
        beginCountdown();
    }

    private void retryHero() {
        score = 0;
        lives = 3;
        progress = 0;
        level = 1;
        selectedLane = 3;
        bottles.clear();
        targets.clear();
        sparks.clear();
        beginCountdown();
    }

    private void goToMenu() {
        saveBestScore();
        bottles.clear();
        targets.clear();
        sparks.clear();
        screen = Screen.MENU;
        ensureMenuBitmap();
    }

    private void saveBestScore() {
        String key = "best_" + hero.name();
        int old = preferences.getInt(key, 0);
        if (score > old) {
            preferences.edit().putInt(key, score).apply();
        }
    }

    private void saveUnlockedLevel() {
        String key = "level_" + hero.name();
        int old = preferences.getInt(key, 1);
        int unlocked = Math.min(MAX_LEVEL, level + 1);
        if (unlocked > old) {
            preferences.edit().putInt(key, unlocked).apply();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        width = w;
        height = h;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (width <= 0f || height <= 0f) {
            return;
        }
        if (screen == Screen.MENU) {
            drawMenu(canvas);
        } else {
            drawGame(canvas);
        }
    }

    private void drawMenu(Canvas canvas) {
        ensureMenuBitmap();
        canvas.drawColor(0xFF06090C);
        if (menuBitmap == null) {
            drawFallbackMenu(canvas);
            return;
        }
        float scale = Math.min(width / menuBitmap.getWidth(), height / menuBitmap.getHeight());
        float drawW = menuBitmap.getWidth() * scale;
        float drawH = menuBitmap.getHeight() * scale;
        float left = (width - drawW) * 0.5f;
        float top = (height - drawH) * 0.5f;
        menuRect.set(left, top, left + drawW, top + drawH);
        canvas.drawBitmap(menuBitmap, null, menuRect, paint);

        if (SystemClock.uptimeMillis() < menuNoticeUntil) {
            paint.setColor(0xE6171A1D);
            RectF box = new RectF(width * 0.10f, height * 0.84f, width * 0.90f, height * 0.92f);
            canvas.drawRoundRect(box, width * 0.025f, width * 0.025f, paint);
            drawText(canvas, menuNotice, box.centerX(), box.centerY() + width * 0.015f, width * 0.037f, Color.WHITE);
        }
    }

    private void drawFallbackMenu(Canvas canvas) {
        paint.setShader(new LinearGradient(0f, 0f, 0f, height, 0xFF28313A, 0xFF070A0C, Shader.TileMode.CLAMP));
        canvas.drawRect(0f, 0f, width, height, paint);
        paint.setShader(null);
        drawText(canvas, "ЕЛЦ", width * 0.5f, height * 0.20f, width * 0.23f, 0xFFFFC72C);
        drawText(canvas, "ВЫБЕРИ ГЕРОЯ", width * 0.5f, height * 0.31f, width * 0.055f, Color.WHITE);
        drawText(canvas, "Александр", width * 0.18f, height * 0.58f, width * 0.038f, 0xFF9FE35E);
        drawText(canvas, "Алик", width * 0.50f, height * 0.58f, width * 0.038f, 0xFFFFD047);
        drawText(canvas, "Андрей", width * 0.82f, height * 0.58f, width * 0.038f, 0xFF72C8FF);
        menuRect.set(0f, 0f, width, height);
    }

    private void drawGame(Canvas canvas) {
        drawBackground(canvas);
        drawHud(canvas);
        drawObjects(canvas);
        drawCharacter(canvas);
        if (hero == Hero.KOROLEV && actionTimer > 0f) {
            drawWaterStream(canvas);
        }
        drawSparks(canvas);
        drawHint(canvas);

        if (successFlash > 0f) {
            paint.setColor(0x22FFFFFF);
            canvas.drawRect(0f, 0f, width, height, paint);
        }
        if (missFlash > 0f) {
            paint.setColor(0x35FF1E12);
            canvas.drawRect(0f, 0f, width, height, paint);
        }

        if (screen == Screen.COUNTDOWN) {
            drawCountdown(canvas);
        } else if (screen == Screen.PAUSED) {
            drawOverlay(canvas, "ПАУЗА", "Игра остановлена", "ПРОДОЛЖИТЬ", "В МЕНЮ");
        } else if (screen == Screen.LEVEL_DONE) {
            drawOverlay(canvas, "УРОВЕНЬ " + level + " ПРОЙДЕН", "Очки: " + score, "СЛЕДУЮЩИЙ", "В МЕНЮ");
        } else if (screen == Screen.GAME_OVER) {
            drawOverlay(canvas, "ИГРА ОКОНЧЕНА", "Результат: " + score, "ЗАНОВО", "В МЕНЮ");
        } else if (screen == Screen.VICTORY) {
            drawOverlay(canvas, "ВСЕ 10 УРОВНЕЙ ПРОЙДЕНЫ", "Результат: " + score, "СЫГРАТЬ ЕЩЁ", "В МЕНЮ");
        }
    }

    private void drawBackground(Canvas canvas) {
        int top;
        int bottom;
        if (hero == Hero.LOZHKIN) {
            top = 0xFF2E432D;
            bottom = 0xFF08100A;
        } else if (hero == Hero.RAK) {
            top = 0xFF493716;
            bottom = 0xFF100B05;
        } else {
            top = 0xFF17405B;
            bottom = 0xFF061019;
        }
        paint.setShader(new LinearGradient(0f, 0f, 0f, height, top, bottom, Shader.TileMode.CLAMP));
        canvas.drawRect(0f, 0f, width, height, paint);
        paint.setShader(null);

        paint.setColor(0x22FFFFFF);
        for (int i = 0; i < 7; i++) {
            float x = width * (0.07f + i * 0.145f);
            canvas.drawRect(x, height * 0.13f, x + width * 0.008f, height, paint);
        }
        paint.setColor(0x28000000);
        for (int i = 0; i < 8; i++) {
            float y = height * (0.18f + i * 0.095f);
            canvas.drawRect(0f, y, width, y + height * 0.006f, paint);
        }

        if (hero == Hero.LOZHKIN) {
            drawShop(canvas, true);
            drawShop(canvas, false);
            drawBottleRails(canvas);
        } else if (hero == Hero.RAK) {
            drawPipes(canvas);
        } else {
            drawWarehouse(canvas);
        }
    }

    private void drawHud(Canvas canvas) {
        RectF panel = new RectF(width * 0.025f, height * 0.018f, width * 0.975f, height * 0.125f);
        drawPanel(canvas, panel, heroAccent(), 0xE8171A1E, width * 0.010f);
        drawText(canvas, "‹", width * 0.075f, height * 0.083f, width * 0.095f, Color.WHITE);
        drawText(canvas, "Ⅱ", width * 0.925f, height * 0.080f, width * 0.052f, Color.WHITE);
        drawText(canvas, hero.title, width * 0.5f, height * 0.058f, width * 0.048f, Color.WHITE);
        String stats = "УРОВЕНЬ " + level + "/10   •   ОЧКИ " + score + "   •   ЖИЗНИ " + lives;
        drawText(canvas, stats, width * 0.5f, height * 0.101f, width * 0.028f, 0xFFFFDB69);

        RectF bar = new RectF(width * 0.17f, height * 0.138f, width * 0.83f, height * 0.158f);
        paint.setColor(0x99000000);
        canvas.drawRoundRect(bar, bar.height() * 0.5f, bar.height() * 0.5f, paint);
        float fraction = Math.min(1f, progress / (float) targetForLevel());
        if (fraction > 0f) {
            RectF fill = new RectF(bar.left, bar.top, bar.left + bar.width() * fraction, bar.bottom);
            paint.setColor(heroAccent());
            canvas.drawRoundRect(fill, fill.height() * 0.5f, fill.height() * 0.5f, paint);
        }
        drawText(canvas, progress + " / " + targetForLevel(), width * 0.5f, height * 0.188f, width * 0.027f, Color.WHITE);
    }

    private void drawObjects(Canvas canvas) {
        if (hero == Hero.LOZHKIN) {
            for (Bottle bottle : bottles) {
                PointF start = bottleStart(bottle.lane);
                PointF control = bottleControl(bottle.lane);
                PointF end = lanePoint(bottle.lane);
                PointF p = quadratic(start, control, end, bottle.t);
                drawBottle(canvas, p.x, p.y, width * 0.035f, bottle.rotation, bottle.golden);
            }
            PointF bag = lanePoint(selectedLane);
            drawBag(canvas, bag.x, bag.y + height * 0.035f, width * 0.12f, selectedLane < 2);
        } else {
            for (Target target : targets) {
                PointF p = lanePoint(target.lane);
                float pulse = 1f + (float) Math.sin(target.age * 8f) * 0.05f;
                float remain = Math.max(0f, 1f - target.age / target.life);
                if (hero == Hero.RAK) {
                    drawCauldron(canvas, p.x, p.y, width * 0.11f * pulse, remain);
                } else {
                    drawFire(canvas, p.x, p.y, width * 0.105f * pulse, remain);
                }
            }
        }

        for (int lane = 0; lane < 4; lane++) {
            PointF p = lanePoint(lane);
            boolean selected = hero == Hero.LOZHKIN && lane == selectedLane;
            stroke.setStrokeWidth(selected ? width * 0.010f : width * 0.004f);
            stroke.setColor(selected ? 0xFFFFD43A : 0x50FFFFFF);
            canvas.drawCircle(p.x, p.y, width * 0.085f, stroke);
        }
    }

    private void drawCharacter(Canvas canvas) {
        int activeLane = hero == Hero.LOZHKIN ? selectedLane : (actionTimer > 0f ? actionLane : 3);
        float side = activeLane < 2 ? -1f : 1f;
        float vertical = (activeLane % 2 == 0) ? -1f : 1f;
        float activity = hero == Hero.LOZHKIN ? 1f : Math.min(1f, actionTimer / 0.22f);
        float x = width * 0.5f + side * width * 0.14f * activity;
        float y = height * 0.755f + vertical * height * 0.020f * activity + (float) Math.sin(worldTime * 3.2f) * height * 0.004f;
        float s = width * 0.23f;

        canvas.save();
        canvas.translate(x, y);
        canvas.rotate(side * vertical * 3.5f * activity);

        // Shadow
        paint.setColor(0x66000000);
        canvas.drawOval(new RectF(-s * 0.52f, s * 0.85f, s * 0.52f, s * 1.10f), paint);

        // Legs
        paint.setColor(hero == Hero.RAK ? 0xFFE46C20 : 0xFF34453A);
        canvas.drawRoundRect(new RectF(-s * 0.34f, s * 0.48f, -s * 0.05f, s * 0.96f), s * 0.10f, s * 0.10f, paint);
        canvas.drawRoundRect(new RectF(s * 0.05f, s * 0.48f, s * 0.34f, s * 0.96f), s * 0.10f, s * 0.10f, paint);
        paint.setColor(0xFF171A1D);
        canvas.drawOval(new RectF(-s * 0.43f, s * 0.88f, -s * 0.02f, s * 1.06f), paint);
        canvas.drawOval(new RectF(s * 0.02f, s * 0.88f, s * 0.43f, s * 1.06f), paint);

        // Torso
        Path torso = new Path();
        torso.moveTo(-s * 0.42f, -s * 0.20f);
        torso.quadTo(-s * 0.52f, s * 0.10f, -s * 0.39f, s * 0.58f);
        torso.lineTo(s * 0.39f, s * 0.58f);
        torso.quadTo(s * 0.52f, s * 0.10f, s * 0.42f, -s * 0.20f);
        torso.close();
        paint.setColor(heroBodyColor());
        canvas.drawPath(torso, paint);
        stroke.setStrokeWidth(s * 0.035f);
        stroke.setColor(0xCC111317);
        canvas.drawPath(torso, stroke);

        drawSuitDetails(canvas, s);

        // Head sprite
        Bitmap head = currentHead();
        if (head != null) {
            float headH = s * 0.78f;
            float headW = headH * head.getWidth() / (float) head.getHeight();
            RectF headDst = new RectF(-headW * 0.5f, -s * 0.89f, headW * 0.5f, -s * 0.89f + headH);
            canvas.drawBitmap(head, null, headDst, paint);
        }

        drawArmsAndTool(canvas, s, side, vertical, activity);
        canvas.restore();
    }

    private void drawSuitDetails(Canvas canvas, float s) {
        if (hero == Hero.RAK) {
            paint.setColor(0xFF2A8D54);
            canvas.drawRect(-s * 0.41f, s * 0.20f, s * 0.41f, s * 0.28f, paint);
            paint.setColor(0xFFD8D9D3);
            canvas.drawRect(-s * 0.40f, s * 0.29f, s * 0.40f, s * 0.37f, paint);
            stroke.setStrokeWidth(s * 0.025f);
            stroke.setColor(0xFF24834A);
            canvas.drawLine(0f, -s * 0.17f, 0f, s * 0.55f, stroke);
        } else {
            paint.setColor(0x44212F21);
            for (int i = 0; i < 8; i++) {
                float px = -s * 0.32f + randomPattern(i) * s * 0.64f;
                float py = -s * 0.08f + randomPattern(i + 9) * s * 0.55f;
                canvas.drawOval(new RectF(px - s * 0.08f, py - s * 0.04f, px + s * 0.08f, py + s * 0.04f), paint);
            }
            stroke.setStrokeWidth(s * 0.025f);
            stroke.setColor(0xFF1D2420);
            canvas.drawLine(0f, -s * 0.17f, 0f, s * 0.55f, stroke);
        }
    }

    private float randomPattern(int seed) {
        int value = (seed * 73 + 19) % 97;
        return value / 97f;
    }

    private void drawArmsAndTool(Canvas canvas, float s, float side, float vertical, float activity) {
        float targetX = side * s * (0.66f + activity * 0.18f);
        float targetY = s * (0.08f + vertical * 0.22f * activity);
        stroke.setStrokeWidth(s * 0.20f);
        stroke.setColor(heroBodyColor());
        canvas.drawLine(-s * 0.28f, -s * 0.03f, targetX * 0.72f, targetY, stroke);
        canvas.drawLine(s * 0.28f, -s * 0.03f, targetX * 0.82f, targetY + s * 0.12f, stroke);
        stroke.setStrokeWidth(s * 0.07f);
        stroke.setColor(0xFF15191C);
        canvas.drawLine(-s * 0.28f, -s * 0.03f, targetX * 0.72f, targetY, stroke);
        canvas.drawLine(s * 0.28f, -s * 0.03f, targetX * 0.82f, targetY + s * 0.12f, stroke);

        if (hero == Hero.RAK) {
            float logX = targetX * (0.70f + 0.30f * activity);
            float logY = targetY - s * 0.03f;
            drawLog(canvas, logX, logY, s * 0.30f, side * -18f);
        } else if (hero == Hero.KOROLEV) {
            stroke.setStrokeWidth(s * 0.10f);
            stroke.setColor(0xFF252B30);
            canvas.drawLine(-s * 0.15f, s * 0.05f, targetX, targetY, stroke);
            stroke.setStrokeWidth(s * 0.045f);
            stroke.setColor(0xFF0F1113);
            canvas.drawLine(-s * 0.15f, s * 0.05f, targetX, targetY, stroke);
            paint.setColor(0xFF454C52);
            canvas.drawCircle(targetX, targetY, s * 0.08f, paint);
        }
    }


    private void drawWaterStream(Canvas canvas) {
        PointF target = lanePoint(actionLane);
        float side = actionLane < 2 ? -1f : 1f;
        float vertical = (actionLane % 2 == 0) ? -1f : 1f;
        float activity = Math.min(1f, actionTimer / 0.22f);
        float startX = width * 0.5f + side * width * (0.14f + 0.16f * activity);
        float startY = height * 0.755f + vertical * height * (0.020f + 0.050f * activity);
        stroke.setStrokeWidth(width * 0.027f);
        stroke.setColor(0xAA57CFFF);
        canvas.drawLine(startX, startY, target.x, target.y, stroke);
        stroke.setStrokeWidth(width * 0.008f);
        stroke.setColor(0xFFE6FAFF);
        canvas.drawLine(startX, startY, target.x, target.y, stroke);
    }

    private void drawCountdown(Canvas canvas) {
        long elapsed = SystemClock.uptimeMillis() - countdownStartMs;
        int number = 3 - (int) (elapsed / 1000L);
        paint.setColor(0xB8000000);
        canvas.drawRect(0f, 0f, width, height, paint);
        drawText(canvas, hero.title.toUpperCase(Locale.getDefault()), width * 0.5f, height * 0.34f, width * 0.060f, heroAccent());
        drawText(canvas, hero.task, width * 0.5f, height * 0.41f, width * 0.039f, Color.WHITE);
        drawText(canvas, number > 0 ? String.valueOf(number) : "СТАРТ!", width * 0.5f, height * 0.62f, width * 0.24f, 0xFFFFD331);
    }

    private void drawOverlay(Canvas canvas, String title, String subtitle, String primary, String secondary) {
        paint.setColor(0xC7000000);
        canvas.drawRect(0f, 0f, width, height, paint);
        RectF panel = new RectF(width * 0.10f, height * 0.28f, width * 0.90f, height * 0.75f);
        drawPanel(canvas, panel, heroAccent(), 0xF21B2024, width * 0.014f);
        drawText(canvas, title, width * 0.5f, height * 0.39f, width * 0.055f, heroAccent());
        drawText(canvas, subtitle, width * 0.5f, height * 0.47f, width * 0.038f, Color.WHITE);
        drawButton(canvas, primaryButton(), primary, true);
        drawButton(canvas, secondaryButton(), secondary, false);
    }

    private RectF primaryButton() {
        return new RectF(width * 0.19f, height * 0.55f, width * 0.81f, height * 0.63f);
    }

    private RectF secondaryButton() {
        return new RectF(width * 0.19f, height * 0.66f, width * 0.81f, height * 0.72f);
    }

    private void drawHint(Canvas canvas) {
        String hint;
        if (hero == Hero.LOZHKIN) {
            hint = "КАСАЙСЯ ЧЕТЫРЁХ ЗОН — ПЕРЕКЛАДЫВАЙ ПАКЕТ";
        } else if (hero == Hero.RAK) {
            hint = "КАСАЙСЯ КОТЛА — АЛИК БРОСИТ ПОЛЕНО";
        } else {
            hint = "КАСАЙСЯ ОГНЯ — АНДРЕЙ НАПРАВИТ ВОДУ";
        }
        paint.setColor(0xB8000000);
        RectF box = new RectF(width * 0.04f, height * 0.934f, width * 0.96f, height * 0.982f);
        canvas.drawRoundRect(box, width * 0.02f, width * 0.02f, paint);
        drawText(canvas, hint, width * 0.5f, height * 0.966f, width * 0.022f, 0xFFFFD85A);
    }

    private void drawSparks(Canvas canvas) {
        for (Spark spark : sparks) {
            int alpha = (int) (255f * Math.max(0f, Math.min(1f, spark.life / spark.maxLife)));
            paint.setColor((spark.color & 0x00FFFFFF) | (alpha << 24));
            canvas.drawCircle(spark.x, spark.y, spark.size, paint);
        }
    }

    private void drawShop(Canvas canvas, boolean left) {
        float x0 = left ? 0f : width * 0.82f;
        float x1 = left ? width * 0.18f : width;
        paint.setColor(0xFF2C2118);
        canvas.drawRect(x0, height * 0.20f, x1, height * 0.72f, paint);
        paint.setColor(0xFFB78638);
        canvas.drawRect(x0, height * 0.20f, x1, height * 0.25f, paint);
        paint.setColor(0xFF171A1D);
        for (int i = 0; i < 3; i++) {
            float y = height * (0.32f + i * 0.15f);
            canvas.drawRect(x0, y, x1, y + height * 0.025f, paint);
        }
        drawText(canvas, "МАГАЗИН", (x0 + x1) * 0.5f, height * 0.235f, width * 0.026f, 0xFFFFD266);
    }

    private void drawBottleRails(Canvas canvas) {
        for (int lane = 0; lane < 4; lane++) {
            PointF start = bottleStart(lane);
            PointF control = bottleControl(lane);
            PointF end = lanePoint(lane);
            Path path = new Path();
            path.moveTo(start.x, start.y);
            path.quadTo(control.x, control.y, end.x, end.y);
            stroke.setStrokeWidth(width * 0.014f);
            stroke.setColor(0xFF5A4630);
            canvas.drawPath(path, stroke);
            stroke.setStrokeWidth(width * 0.005f);
            stroke.setColor(0xFFB99A60);
            canvas.drawPath(path, stroke);
        }
    }

    private void drawPipes(Canvas canvas) {
        stroke.setStrokeWidth(width * 0.035f);
        stroke.setColor(0xFF4B4F50);
        canvas.drawLine(width * 0.08f, height * 0.25f, width * 0.92f, height * 0.25f, stroke);
        canvas.drawLine(width * 0.08f, height * 0.60f, width * 0.92f, height * 0.60f, stroke);
        stroke.setStrokeWidth(width * 0.012f);
        stroke.setColor(0xFF9A7A45);
        canvas.drawLine(width * 0.08f, height * 0.25f, width * 0.92f, height * 0.25f, stroke);
        canvas.drawLine(width * 0.08f, height * 0.60f, width * 0.92f, height * 0.60f, stroke);
    }

    private void drawWarehouse(Canvas canvas) {
        paint.setColor(0x553A5E70);
        canvas.drawRect(width * 0.08f, height * 0.20f, width * 0.92f, height * 0.72f, paint);
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 4; col++) {
                float left = width * (0.12f + col * 0.20f);
                float top = height * (0.27f + row * 0.20f);
                paint.setColor(0xFF18242A);
                canvas.drawRect(left, top, left + width * 0.13f, top + height * 0.11f, paint);
                stroke.setStrokeWidth(width * 0.004f);
                stroke.setColor(0xFF5F879A);
                canvas.drawRect(left, top, left + width * 0.13f, top + height * 0.11f, stroke);
            }
        }
    }

    private PointF lanePoint(int lane) {
        float x = lane < 2 ? width * 0.23f : width * 0.77f;
        float y = lane % 2 == 0 ? height * 0.34f : height * 0.61f;
        return new PointF(x, y);
    }

    private PointF bottleStart(int lane) {
        boolean left = lane < 2;
        float x = left ? width * 0.07f : width * 0.93f;
        float y = lane % 2 == 0 ? height * 0.29f : height * 0.51f;
        return new PointF(x, y);
    }

    private PointF bottleControl(int lane) {
        boolean left = lane < 2;
        PointF end = lanePoint(lane);
        return new PointF(left ? width * 0.14f : width * 0.86f, end.y - height * 0.08f);
    }

    private PointF quadratic(PointF a, PointF b, PointF c, float t) {
        float clamped = Math.max(0f, Math.min(1f, t));
        float one = 1f - clamped;
        return new PointF(
                one * one * a.x + 2f * one * clamped * b.x + clamped * clamped * c.x,
                one * one * a.y + 2f * one * clamped * b.y + clamped * clamped * c.y
        );
    }

    private void drawBottle(Canvas canvas, float x, float y, float size, float rotation, boolean golden) {
        canvas.save();
        canvas.translate(x, y);
        canvas.rotate(rotation);
        paint.setColor(golden ? 0xFFFFC928 : 0xFF4CAB63);
        canvas.drawRoundRect(new RectF(-size * 0.38f, -size, size * 0.38f, size), size * 0.18f, size * 0.18f, paint);
        paint.setColor(golden ? 0xFFFFE881 : 0xFFA9E3B2);
        canvas.drawRect(-size * 0.19f, -size * 1.23f, size * 0.19f, -size * 0.82f, paint);
        paint.setColor(0x55FFFFFF);
        canvas.drawRoundRect(new RectF(-size * 0.25f, -size * 0.82f, -size * 0.08f, size * 0.68f), size * 0.08f, size * 0.08f, paint);
        canvas.restore();
    }

    private void drawBag(Canvas canvas, float x, float y, float size, boolean openingRight) {
        canvas.save();
        canvas.translate(x, y);
        canvas.rotate(openingRight ? 12f : -12f);
        Path bag = new Path();
        bag.moveTo(-size * 0.55f, -size * 0.25f);
        bag.quadTo(0f, -size * 0.55f, size * 0.55f, -size * 0.25f);
        bag.lineTo(size * 0.40f, size * 0.55f);
        bag.quadTo(0f, size * 0.75f, -size * 0.40f, size * 0.55f);
        bag.close();
        paint.setColor(0xFF9A713B);
        canvas.drawPath(bag, paint);
        stroke.setStrokeWidth(size * 0.08f);
        stroke.setColor(0xFF3E2B19);
        canvas.drawPath(bag, stroke);
        stroke.setStrokeWidth(size * 0.05f);
        canvas.drawLine(-size * 0.48f, -size * 0.28f, size * 0.48f, -size * 0.28f, stroke);
        canvas.restore();
    }

    private void drawCauldron(Canvas canvas, float x, float y, float size, float remain) {
        float alpha = 0.75f + remain * 0.25f;
        paint.setColor(((int) (alpha * 255f) << 24) | 0x00272B2E);
        canvas.drawOval(new RectF(x - size, y - size * 0.35f, x + size, y + size * 0.55f), paint);
        paint.setColor(0xFFFF7A18);
        canvas.drawOval(new RectF(x - size * 0.78f, y - size * 0.30f, x + size * 0.78f, y + size * 0.03f), paint);
        stroke.setStrokeWidth(size * 0.12f);
        stroke.setColor(0xFF0F1113);
        canvas.drawOval(new RectF(x - size, y - size * 0.35f, x + size, y + size * 0.55f), stroke);
        drawFlameShape(canvas, x - size * 0.42f, y + size * 0.75f, size * 0.45f, 0xFFFFB31A);
        drawFlameShape(canvas, x + size * 0.25f, y + size * 0.76f, size * 0.38f, 0xFFFF5D19);
    }

    private void drawFire(Canvas canvas, float x, float y, float size, float remain) {
        float flicker = 0.88f + (float) Math.sin(worldTime * 14f + x) * 0.10f;
        drawFlameShape(canvas, x, y, size * flicker, 0xFFFF4C18);
        drawFlameShape(canvas, x, y + size * 0.12f, size * 0.62f * flicker, 0xFFFFC21A);
        stroke.setStrokeWidth(size * 0.08f);
        stroke.setColor(((int) (remain * 170f) << 24) | 0x00FFFFFF);
        canvas.drawCircle(x, y, size * 1.20f, stroke);
    }

    private void drawFlameShape(Canvas canvas, float x, float y, float size, int color) {
        Path flame = new Path();
        flame.moveTo(x, y - size);
        flame.cubicTo(x + size * 0.60f, y - size * 0.20f, x + size * 0.75f, y + size * 0.45f, x, y + size);
        flame.cubicTo(x - size * 0.75f, y + size * 0.45f, x - size * 0.45f, y - size * 0.10f, x, y - size);
        paint.setColor(color);
        canvas.drawPath(flame, paint);
    }

    private void drawLog(Canvas canvas, float x, float y, float size, float rotation) {
        canvas.save();
        canvas.translate(x, y);
        canvas.rotate(rotation);
        paint.setColor(0xFF8B5527);
        canvas.drawRoundRect(new RectF(-size, -size * 0.28f, size, size * 0.28f), size * 0.22f, size * 0.22f, paint);
        paint.setColor(0xFFD39A55);
        canvas.drawOval(new RectF(size * 0.72f, -size * 0.28f, size * 1.08f, size * 0.28f), paint);
        stroke.setStrokeWidth(size * 0.08f);
        stroke.setColor(0xFF4A2A16);
        canvas.drawLine(-size * 0.55f, -size * 0.15f, size * 0.45f, size * 0.10f, stroke);
        canvas.restore();
    }

    private Bitmap currentHead() {
        if (hero == Hero.RAK) {
            return headRak;
        }
        if (hero == Hero.KOROLEV) {
            return headKorolev;
        }
        return headLozhkin;
    }

    private int heroBodyColor() {
        if (hero == Hero.RAK) {
            return 0xFFF0C42C;
        }
        if (hero == Hero.KOROLEV) {
            return 0xFF53624E;
        }
        return 0xFF59704C;
    }

    private int heroAccent() {
        if (hero == Hero.RAK) {
            return 0xFFFFC928;
        }
        if (hero == Hero.KOROLEV) {
            return 0xFF50B9F2;
        }
        return 0xFF8BC34A;
    }

    private void drawPanel(Canvas canvas, RectF rect, int borderColor, int fillColor, float border) {
        paint.setColor(fillColor);
        canvas.drawRoundRect(rect, width * 0.03f, width * 0.03f, paint);
        stroke.setStrokeWidth(border);
        stroke.setColor(borderColor);
        canvas.drawRoundRect(rect, width * 0.03f, width * 0.03f, stroke);
    }

    private void drawButton(Canvas canvas, RectF rect, String label, boolean primary) {
        paint.setColor(primary ? 0xFF55A82D : 0xFF2A3035);
        canvas.drawRoundRect(rect, width * 0.025f, width * 0.025f, paint);
        stroke.setStrokeWidth(width * 0.007f);
        stroke.setColor(primary ? 0xFFBCEB62 : 0xFF767E84);
        canvas.drawRoundRect(rect, width * 0.025f, width * 0.025f, stroke);
        drawText(canvas, label, rect.centerX(), rect.centerY() + width * 0.016f, width * 0.045f, Color.WHITE);
    }

    private void drawText(Canvas canvas, String value, float x, float y, float size, int color) {
        text.setTextSize(size);
        text.setColor(color);
        text.setStyle(Paint.Style.FILL);
        canvas.drawText(value, x, y, text);
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            performClick();
            return true;
        }
        if (event.getActionMasked() != MotionEvent.ACTION_DOWN) {
            return true;
        }
        float x = event.getX();
        float y = event.getY();

        if (screen == Screen.MENU) {
            handleMenuTouch(x, y);
            return true;
        }

        if (screen == Screen.COUNTDOWN) {
            return true;
        }

        if (screen == Screen.PAUSED || screen == Screen.LEVEL_DONE || screen == Screen.GAME_OVER || screen == Screen.VICTORY) {
            handleOverlayTouch(x, y);
            return true;
        }

        if (y < height * 0.14f) {
            if (x < width * 0.18f) {
                goToMenu();
            } else if (x > width * 0.82f) {
                screen = Screen.PAUSED;
            }
            return true;
        }

        performAction(laneFromTouch(x, y));
        return true;
    }

    private void handleMenuTouch(float x, float y) {
        if (menuRect.width() <= 0f || !menuRect.contains(x, y)) {
            return;
        }
        float sx = (x - menuRect.left) * 720f / menuRect.width();
        float sy = (y - menuRect.top) * 1280f / menuRect.height();

        if (sy >= 380f && sy <= 1095f) {
            if (sx < 240f) {
                selectHero(Hero.LOZHKIN);
            } else if (sx < 480f) {
                selectHero(Hero.RAK);
            } else {
                selectHero(Hero.KOROLEV);
            }
            return;
        }

        if (sy < 120f) {
            menuNotice = sx < 360f ? "Звук и вибрация будут добавлены после проверки игры" : "Выбери героя и пройди 10 уровней";
            menuNoticeUntil = SystemClock.uptimeMillis() + 2400L;
        } else if (sy > 1110f) {
            menuNotice = "Раздел откроется в следующем обновлении";
            menuNoticeUntil = SystemClock.uptimeMillis() + 2200L;
        }
    }

    private void handleOverlayTouch(float x, float y) {
        if (primaryButton().contains(x, y)) {
            if (screen == Screen.PAUSED) {
                screen = Screen.PLAYING;
                lastSpawnMs = SystemClock.uptimeMillis();
            } else if (screen == Screen.LEVEL_DONE) {
                nextLevel();
            } else {
                retryHero();
            }
            return;
        }
        if (secondaryButton().contains(x, y)) {
            goToMenu();
        }
    }

    private int laneFromTouch(float x, float y) {
        boolean left = x < width * 0.5f;
        boolean top = y < height * 0.49f;
        if (left && top) {
            return 0;
        }
        if (left) {
            return 1;
        }
        if (top) {
            return 2;
        }
        return 3;
    }

    private static final class Bottle {
        final int lane;
        final float duration;
        final boolean golden;
        float t;
        float rotation;

        Bottle(int lane, float duration, boolean golden) {
            this.lane = lane;
            this.duration = duration;
            this.golden = golden;
        }
    }

    private static final class Target {
        final int lane;
        final float life;
        float age;

        Target(int lane, float life) {
            this.lane = lane;
            this.life = life;
        }
    }

    private static final class Spark {
        float x;
        float y;
        float vx;
        float vy;
        final float size;
        float life;
        final float maxLife;
        final int color;
        final float gravity;

        Spark(float x, float y, float vx, float vy, float size, float life, int color, float gravity) {
            this.x = x;
            this.y = y;
            this.vx = vx;
            this.vy = vy;
            this.size = size;
            this.life = life;
            this.maxLife = life;
            this.color = color;
            this.gravity = gravity;
        }
    }
}
