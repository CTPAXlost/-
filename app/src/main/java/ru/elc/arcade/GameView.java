package ru.elc.arcade;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
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
    private static final int MAX_LEVEL = 10;
    private static final int ORIGINAL_SKIN_COST = 100;
    private static final int FUN_SKIN_COST = 200;
    private static final long LEVEL_OUTRO_DURATION_MS = 3400L;
    private static final String PREFS = "elc_v2";

    private enum Screen {
        MENU,
        SHOP,
        COUNTDOWN,
        PLAYING,
        LEVEL_OUTRO,
        PAUSED,
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

    private enum Skin {
        DEFAULT("Базовый"),
        ORIGINAL("Оригинальная одежда"),
        FUN("Весёлый скин");

        final String title;

        Skin(String title) {
            this.title = title;
        }
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Random random = new Random();
    private final ArrayList<Bottle> bottles = new ArrayList<Bottle>();
    private final ArrayList<Target> targets = new ArrayList<Target>();
    private final ArrayList<Spark> sparks = new ArrayList<Spark>();
    private final RectF menuRect = new RectF();
    private final SharedPreferences preferences;
    private final Hero[] shopHeroes = new Hero[]{Hero.LOZHKIN, Hero.RAK, Hero.KOROLEV};
    private final RectF[] shopCardRects = new RectF[]{new RectF(), new RectF(), new RectF()};
    private final RectF[] shopOriginalButtonRects = new RectF[]{new RectF(), new RectF(), new RectF()};
    private final RectF[] shopFunButtonRects = new RectF[]{new RectF(), new RectF(), new RectF()};

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
    private long levelOutroStartMs;
    private float worldTime;

    private int level = 1;
    private int score;
    private int lives = 3;
    private int progress;
    private int coins;
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
        coins = preferences.getInt("coins", 0);

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
        if (screen == Screen.SHOP) {
            goToMenu();
            return true;
        }
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
        String next = "ELC|" + screen.name() + "|" + hero.name() + "|LEVEL=" + level + "|COINS=" + coins;
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

        if (screen == Screen.LEVEL_OUTRO) {
            if (now - levelOutroStartMs >= LEVEL_OUTRO_DURATION_MS) {
                if (level >= MAX_LEVEL) {
                    screen = Screen.VICTORY;
                } else {
                    nextLevel();
                }
            }
            return;
        }

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
        awardCoins(golden ? 3 : 1);
        successFlash = 0.14f;
        addSparks(x, y, golden ? 0xFFFFD42A : heroAccent(), 18);
        if (progress >= targetForLevel()) {
            progress = targetForLevel();
            awardCoins(8 + level * 2);
            bottles.clear();
            targets.clear();
            saveBestScore();
            saveUnlockedLevel();
            beginLevelOutro();
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

    private void awardCoins(int amount) {
        if (amount <= 0) {
            return;
        }
        coins += amount;
        preferences.edit().putInt("coins", coins).apply();
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

    private void beginLevelOutro() {
        screen = Screen.LEVEL_OUTRO;
        levelOutroStartMs = SystemClock.uptimeMillis();
        actionTimer = 0f;
        bottles.clear();
        targets.clear();
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

    private void openShop() {
        screen = Screen.SHOP;
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

    private boolean isSkinOwned(Hero targetHero, Skin skin) {
        if (skin == Skin.DEFAULT) {
            return true;
        }
        return preferences.getBoolean("skin_owned_" + targetHero.name() + "_" + skin.name(), false);
    }

    private Skin selectedSkin(Hero targetHero) {
        int value = preferences.getInt("skin_selected_" + targetHero.name(), 0);
        if (value == 2 && isSkinOwned(targetHero, Skin.FUN)) {
            return Skin.FUN;
        }
        if (value == 1 && isSkinOwned(targetHero, Skin.ORIGINAL)) {
            return Skin.ORIGINAL;
        }
        return Skin.DEFAULT;
    }

    private Skin selectedSkin() {
        return selectedSkin(hero);
    }

    private void selectSkin(Hero targetHero, Skin skin) {
        if (!isSkinOwned(targetHero, skin)) {
            return;
        }
        int value = skin == Skin.ORIGINAL ? 1 : (skin == Skin.FUN ? 2 : 0);
        preferences.edit().putInt("skin_selected_" + targetHero.name(), value).apply();
    }

    private int skinCost(Skin skin) {
        return skin == Skin.FUN ? FUN_SKIN_COST : ORIGINAL_SKIN_COST;
    }

    private String skinDisplayName(Hero targetHero, Skin skin) {
        if (skin == Skin.ORIGINAL) {
            return "Оригинальная одежда";
        }
        if (skin == Skin.FUN) {
            if (targetHero == Hero.LOZHKIN) {
                return "Праздничный ловец";
            }
            if (targetHero == Hero.RAK) {
                return "Супер-Алик";
            }
            return "Пляжный пожарный";
        }
        return "Базовый";
    }

    private void buyOrSelectSkin(Hero targetHero, Skin skin) {
        if (skin == Skin.DEFAULT) {
            selectSkin(targetHero, skin);
            return;
        }
        String displayName = skinDisplayName(targetHero, skin);
        if (isSkinOwned(targetHero, skin)) {
            selectSkin(targetHero, skin);
            showNotice(targetHero.title + ": выбран скин «" + displayName + "»", 1800L);
            return;
        }
        int cost = skinCost(skin);
        if (coins < cost) {
            showNotice("Не хватает монет. Нужно " + cost, 1800L);
            return;
        }
        coins -= cost;
        int selectedValue = skin == Skin.FUN ? 2 : 1;
        preferences.edit()
                .putInt("coins", coins)
                .putBoolean("skin_owned_" + targetHero.name() + "_" + skin.name(), true)
                .putInt("skin_selected_" + targetHero.name(), selectedValue)
                .apply();
        showNotice(targetHero.title + ": «" + displayName + "» куплен за " + cost + " монет", 2200L);
    }

    private void showNotice(String notice, long durationMs) {
        menuNotice = notice;
        menuNoticeUntil = SystemClock.uptimeMillis() + durationMs;
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
        } else if (screen == Screen.SHOP) {
            drawShopScreen(canvas);
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
        drawCoinBadge(canvas, width * 0.50f, height * 0.070f, false);
        drawFloatingNotice(canvas);
    }

    private void drawFloatingNotice(Canvas canvas) {
        if (SystemClock.uptimeMillis() < menuNoticeUntil) {
            paint.setColor(0xE6171A1D);
            RectF box = new RectF(width * 0.08f, height * 0.84f, width * 0.92f, height * 0.92f);
            canvas.drawRoundRect(box, width * 0.025f, width * 0.025f, paint);
            drawText(canvas, menuNotice, box.centerX(), box.centerY() + width * 0.015f, width * 0.031f, Color.WHITE);
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
        drawCoinBadge(canvas, width * 0.50f, height * 0.10f, false);
    }

    private void drawShopScreen(Canvas canvas) {
        drawMenu(canvas);
        paint.setColor(0xD9060A0C);
        canvas.drawRect(0f, 0f, width, height, paint);

        RectF panel = new RectF(width * 0.05f, height * 0.04f, width * 0.95f, height * 0.96f);
        drawPanel(canvas, panel, 0xFFFFC72C, 0xF01A1E22, width * 0.010f);
        drawText(canvas, "МАГАЗИН СКИНОВ", width * 0.5f, height * 0.095f, width * 0.055f, Color.WHITE);
        drawText(canvas, "Оригинальные — 100 • Весёлые — 200 монет", width * 0.5f, height * 0.132f, width * 0.027f, 0xFFFFD766);
        drawText(canvas, "‹", width * 0.10f, height * 0.10f, width * 0.10f, Color.WHITE);
        drawCoinBadge(canvas, width * 0.82f, height * 0.10f, true);

        float top = height * 0.16f;
        float cardH = height * 0.225f;
        float gap = height * 0.018f;
        for (int i = 0; i < shopHeroes.length; i++) {
            Hero itemHero = shopHeroes[i];
            RectF card = shopCardRects[i];
            card.set(width * 0.075f, top + i * (cardH + gap), width * 0.925f, top + i * (cardH + gap) + cardH);
            drawShopCard(canvas, card, itemHero, i);
        }

        drawText(canvas, "Монеты выдаются за попадания и пройденные уровни", width * 0.5f, height * 0.935f, width * 0.022f, 0xFFCFD4D8);
        drawFloatingNotice(canvas);
    }

    private void drawShopCard(Canvas canvas, RectF rect, Hero itemHero, int index) {
        drawPanel(canvas, rect, shopAccent(itemHero), 0xF7252A2E, width * 0.006f);
        drawText(canvas, itemHero.title, rect.centerX(), rect.top + rect.height() * 0.15f, width * 0.034f, Color.WHITE);

        RectF preview = new RectF(rect.left + rect.width() * 0.025f, rect.top + rect.height() * 0.16f, rect.left + rect.width() * 0.30f, rect.bottom - rect.height() * 0.06f);
        drawShopPreview(canvas, preview, itemHero, Skin.FUN);
        drawText(canvas, skinDisplayName(itemHero, Skin.FUN), preview.centerX(), rect.bottom - rect.height() * 0.03f, width * 0.020f, 0xFFFFD766);

        RectF originalButton = shopOriginalButtonRects[index];
        originalButton.set(rect.left + rect.width() * 0.35f, rect.top + rect.height() * 0.28f, rect.right - rect.width() * 0.04f, rect.top + rect.height() * 0.52f);
        drawSkinShopRow(canvas, itemHero, Skin.ORIGINAL, originalButton);

        RectF funButton = shopFunButtonRects[index];
        funButton.set(rect.left + rect.width() * 0.35f, rect.top + rect.height() * 0.64f, rect.right - rect.width() * 0.04f, rect.top + rect.height() * 0.88f);
        drawSkinShopRow(canvas, itemHero, Skin.FUN, funButton);
    }

    private void drawSkinShopRow(Canvas canvas, Hero itemHero, Skin skin, RectF button) {
        boolean owned = isSkinOwned(itemHero, skin);
        boolean selected = selectedSkin(itemHero) == skin;
        int cost = skinCost(skin);
        String name = skinDisplayName(itemHero, skin);
        float labelY = button.top - width * 0.012f;
        drawText(canvas, name, button.centerX(), labelY, width * 0.022f, skin == Skin.FUN ? 0xFFFFD766 : 0xFFDCE3E7);
        String label;
        boolean primary;
        if (!owned) {
            label = "КУПИТЬ ЗА " + cost;
            primary = true;
        } else if (!selected) {
            label = "ВЫБРАТЬ";
            primary = true;
        } else {
            label = "ВЫБРАНО";
            primary = false;
        }
        drawCompactButton(canvas, button, label, primary, skin == Skin.FUN ? 0xFF8A4ED6 : 0xFF55A82D);
    }

    private void drawCompactButton(Canvas canvas, RectF rect, String label, boolean primary, int primaryColor) {
        paint.setColor(primary ? primaryColor : 0xFF2A3035);
        canvas.drawRoundRect(rect, width * 0.018f, width * 0.018f, paint);
        stroke.setStrokeWidth(width * 0.005f);
        stroke.setColor(primary ? 0xFFD8F0A0 : 0xFF767E84);
        canvas.drawRoundRect(rect, width * 0.018f, width * 0.018f, stroke);
        drawText(canvas, label, rect.centerX(), rect.centerY() + width * 0.011f, width * 0.027f, Color.WHITE);
    }

    private void drawShopPreview(Canvas canvas, RectF rect, Hero itemHero, Skin skin) {
        canvas.save();
        canvas.translate(rect.centerX(), rect.centerY() + rect.height() * 0.10f);
        float s = rect.height() * 0.31f;
        paint.setColor(0x66000000);
        canvas.drawOval(new RectF(-s * 0.65f, s * 1.10f, s * 0.65f, s * 1.35f), paint);

        if (skin == Skin.FUN && itemHero == Hero.RAK) {
            drawCape(canvas, s);
        }

        paint.setColor(heroLegColor(itemHero, skin));
        canvas.drawRoundRect(new RectF(-s * 0.34f, s * 0.48f, -s * 0.05f, s * 0.98f), s * 0.10f, s * 0.10f, paint);
        canvas.drawRoundRect(new RectF(s * 0.05f, s * 0.48f, s * 0.34f, s * 0.98f), s * 0.10f, s * 0.10f, paint);
        paint.setColor(0xFF171A1D);
        canvas.drawOval(new RectF(-s * 0.43f, s * 0.90f, -s * 0.02f, s * 1.10f), paint);
        canvas.drawOval(new RectF(s * 0.02f, s * 0.90f, s * 0.43f, s * 1.10f), paint);

        Path torso = new Path();
        torso.moveTo(-s * 0.42f, -s * 0.20f);
        torso.quadTo(-s * 0.52f, s * 0.10f, -s * 0.39f, s * 0.58f);
        torso.lineTo(s * 0.39f, s * 0.58f);
        torso.quadTo(s * 0.52f, s * 0.10f, s * 0.42f, -s * 0.20f);
        torso.close();
        paint.setColor(heroBodyColor(itemHero, skin));
        canvas.drawPath(torso, paint);
        stroke.setStrokeWidth(s * 0.035f);
        stroke.setColor(0xCC111317);
        canvas.drawPath(torso, stroke);
        drawSuitDetails(canvas, s, itemHero, skin);

        Bitmap head = headForHero(itemHero);
        if (head != null) {
            float headH = s * 0.85f;
            float headW = headH * head.getWidth() / (float) head.getHeight();
            RectF headDst = new RectF(-headW * 0.5f, -s * 0.92f, headW * 0.5f, -s * 0.92f + headH);
            drawHeadBitmap(canvas, head, headDst, itemHero != Hero.RAK);
        }
        if (skin == Skin.FUN) {
            drawFunAccessories(canvas, s, itemHero);
        }
        canvas.restore();
    }

    private void drawCoinBadge(Canvas canvas, float cx, float cy, boolean compact) {
        float w = compact ? width * 0.26f : width * 0.28f;
        float h = compact ? height * 0.045f : height * 0.038f;
        RectF badge = new RectF(cx - w * 0.5f, cy - h * 0.5f, cx + w * 0.5f, cy + h * 0.5f);
        paint.setColor(0xE814181B);
        canvas.drawRoundRect(badge, h * 0.55f, h * 0.55f, paint);
        stroke.setStrokeWidth(width * 0.004f);
        stroke.setColor(0xFFFFC72C);
        canvas.drawRoundRect(badge, h * 0.55f, h * 0.55f, stroke);
        paint.setColor(0xFFFFC72C);
        canvas.drawCircle(badge.left + h * 0.60f, cy, h * 0.23f, paint);
        drawText(canvas, "МОНЕТЫ " + coins, badge.centerX() + h * 0.10f, cy + h * 0.18f, compact ? width * 0.024f : width * 0.022f, Color.WHITE);
    }

    private void drawGame(Canvas canvas) {
        drawBackground(canvas);
        drawHud(canvas);

        if (screen == Screen.LEVEL_OUTRO) {
            drawLevelOutro(canvas);
        } else {
            drawObjects(canvas);
            drawCharacter(canvas);
            if (hero == Hero.KOROLEV && actionTimer > 0f) {
                drawWaterStream(canvas);
            }
            drawSparks(canvas);
            drawHint(canvas);
        }

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
        } else if (screen == Screen.GAME_OVER) {
            drawOverlay(canvas, "ИГРА ОКОНЧЕНА", "Результат: " + score + " • Монеты: " + coins, "ЗАНОВО", "В МЕНЮ");
        } else if (screen == Screen.VICTORY) {
            drawOverlay(canvas, "ВСЕ 10 УРОВНЕЙ ПРОЙДЕНЫ", "Результат: " + score + " • Монеты: " + coins, "СЫГРАТЬ ЕЩЁ", "В МЕНЮ");
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
        drawCharacterAt(canvas, x, y, width * 0.23f, side * vertical * 3.5f * activity, side, vertical, activity, true);
    }

    private void drawCharacterAt(Canvas canvas, float x, float y, float s, float rotation, float side, float vertical, float activity, boolean drawTool) {
        Skin skin = selectedSkin();
        canvas.save();
        canvas.translate(x, y);
        canvas.rotate(rotation);

        paint.setColor(0x66000000);
        canvas.drawOval(new RectF(-s * 0.52f, s * 0.85f, s * 0.52f, s * 1.10f), paint);

        if (skin == Skin.FUN && hero == Hero.RAK) {
            drawCape(canvas, s);
        }

        paint.setColor(heroLegColor(hero, skin));
        canvas.drawRoundRect(new RectF(-s * 0.34f, s * 0.48f, -s * 0.05f, s * 0.96f), s * 0.10f, s * 0.10f, paint);
        canvas.drawRoundRect(new RectF(s * 0.05f, s * 0.48f, s * 0.34f, s * 0.96f), s * 0.10f, s * 0.10f, paint);
        paint.setColor(0xFF171A1D);
        canvas.drawOval(new RectF(-s * 0.43f, s * 0.88f, -s * 0.02f, s * 1.06f), paint);
        canvas.drawOval(new RectF(s * 0.02f, s * 0.88f, s * 0.43f, s * 1.06f), paint);

        Path torso = new Path();
        torso.moveTo(-s * 0.42f, -s * 0.20f);
        torso.quadTo(-s * 0.52f, s * 0.10f, -s * 0.39f, s * 0.58f);
        torso.lineTo(s * 0.39f, s * 0.58f);
        torso.quadTo(s * 0.52f, s * 0.10f, s * 0.42f, -s * 0.20f);
        torso.close();
        paint.setColor(heroBodyColor(hero, skin));
        canvas.drawPath(torso, paint);
        stroke.setStrokeWidth(s * 0.035f);
        stroke.setColor(0xCC111317);
        canvas.drawPath(torso, stroke);

        drawSuitDetails(canvas, s, hero, skin);

        Bitmap head = currentHead();
        if (head != null) {
            float headH = s * 0.78f;
            float headW = headH * head.getWidth() / (float) head.getHeight();
            RectF headDst = new RectF(-headW * 0.5f, -s * 0.89f, headW * 0.5f, -s * 0.89f + headH);
            drawHeadBitmap(canvas, head, headDst, hero != Hero.RAK);
        }
        if (skin == Skin.FUN) {
            drawFunAccessories(canvas, s, hero);
        }

        drawArmsAndTool(canvas, s, side, vertical, activity, drawTool);
        canvas.restore();
    }

    private void drawHeadBitmap(Canvas canvas, Bitmap head, RectF dst, boolean brighten) {
        if (brighten) {
            ColorMatrix matrix = new ColorMatrix(new float[]{
                    1.12f, 0f, 0f, 0f, 16f,
                    0f, 1.12f, 0f, 0f, 16f,
                    0f, 0f, 1.12f, 0f, 16f,
                    0f, 0f, 0f, 1f, 0f
            });
            bitmapPaint.setColorFilter(new ColorMatrixColorFilter(matrix));
        } else {
            bitmapPaint.setColorFilter(null);
        }
        canvas.drawBitmap(head, null, dst, bitmapPaint);
        bitmapPaint.setColorFilter(null);
    }

    private void drawSuitDetails(Canvas canvas, float s, Hero targetHero, Skin skin) {
        if (skin == Skin.FUN) {
            drawFunSuitDetails(canvas, s, targetHero);
            return;
        }
        if (skin == Skin.ORIGINAL) {
            drawOriginalSuitDetails(canvas, s, targetHero);
            return;
        }
        if (targetHero == Hero.RAK) {
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

    private void drawOriginalSuitDetails(Canvas canvas, float s, Hero targetHero) {
        if (targetHero == Hero.LOZHKIN) {
            paint.setColor(0xFF445B3B);
            canvas.drawRoundRect(new RectF(-s * 0.42f, -s * 0.17f, s * 0.42f, s * 0.03f), s * 0.08f, s * 0.08f, paint);
            paint.setColor(0xFF6A7F59);
            canvas.drawRoundRect(new RectF(-s * 0.13f, -s * 0.20f, s * 0.13f, s * 0.56f), s * 0.03f, s * 0.03f, paint);
            paint.setColor(0x553B4A2F);
            for (int i = 0; i < 10; i++) {
                float px = -s * 0.33f + randomPattern(i + 3) * s * 0.66f;
                float py = -s * 0.10f + randomPattern(i + 13) * s * 0.58f;
                canvas.drawOval(new RectF(px - s * 0.10f, py - s * 0.05f, px + s * 0.10f, py + s * 0.05f), paint);
            }
            paint.setColor(0xFF708564);
            canvas.drawRoundRect(new RectF(-s * 0.26f, -s * 0.01f, -s * 0.04f, s * 0.16f), s * 0.04f, s * 0.04f, paint);
            canvas.drawRoundRect(new RectF(s * 0.04f, -s * 0.01f, s * 0.26f, s * 0.16f), s * 0.04f, s * 0.04f, paint);
        } else if (targetHero == Hero.RAK) {
            paint.setColor(0xFF1B8E52);
            canvas.drawRoundRect(new RectF(-s * 0.44f, -s * 0.15f, s * 0.44f, -s * 0.05f), s * 0.06f, s * 0.06f, paint);
            paint.setColor(0xFFE4E3DA);
            canvas.drawRoundRect(new RectF(-s * 0.42f, s * 0.10f, s * 0.42f, s * 0.19f), s * 0.04f, s * 0.04f, paint);
            paint.setColor(0xFF1B8E52);
            canvas.drawRoundRect(new RectF(-s * 0.42f, s * 0.20f, s * 0.42f, s * 0.28f), s * 0.04f, s * 0.04f, paint);
            stroke.setStrokeWidth(s * 0.026f);
            stroke.setColor(0xFF268E55);
            canvas.drawLine(0f, -s * 0.17f, 0f, s * 0.55f, stroke);
            paint.setColor(0xFF2E6AC9);
            canvas.drawRect(s * 0.19f, -s * 0.08f, s * 0.34f, s * 0.03f, paint);
            paint.setColor(0xFFF1F1F1);
            canvas.drawRect(s * 0.19f, -s * 0.045f, s * 0.34f, -s * 0.01f, paint);
            paint.setColor(0xFFDB4040);
            canvas.drawRect(s * 0.19f, -s * 0.01f, s * 0.34f, s * 0.03f, paint);
        } else {
            paint.setColor(0xFF5F6E5A);
            canvas.drawRoundRect(new RectF(-s * 0.44f, -s * 0.16f, s * 0.44f, -s * 0.04f), s * 0.06f, s * 0.06f, paint);
            paint.setColor(0xFF6F7768);
            canvas.drawRoundRect(new RectF(-s * 0.28f, -s * 0.19f, s * 0.28f, -s * 0.04f), s * 0.07f, s * 0.07f, paint);
            paint.setColor(0xFF3B423C);
            canvas.drawRoundRect(new RectF(-s * 0.15f, -s * 0.15f, s * 0.15f, s * 0.54f), s * 0.03f, s * 0.03f, paint);
            paint.setColor(0x55434F36);
            for (int i = 0; i < 10; i++) {
                float px = -s * 0.33f + randomPattern(i + 5) * s * 0.66f;
                float py = -s * 0.10f + randomPattern(i + 15) * s * 0.58f;
                canvas.drawOval(new RectF(px - s * 0.10f, py - s * 0.05f, px + s * 0.10f, py + s * 0.05f), paint);
            }
        }
    }

    private void drawFunSuitDetails(Canvas canvas, float s, Hero targetHero) {
        if (targetHero == Hero.LOZHKIN) {
            paint.setColor(0xFFFFFFFF);
            canvas.drawRoundRect(new RectF(-s * 0.43f, -s * 0.18f, s * 0.43f, -s * 0.05f), s * 0.06f, s * 0.06f, paint);
            canvas.drawRoundRect(new RectF(-s * 0.09f, -s * 0.17f, s * 0.09f, s * 0.56f), s * 0.03f, s * 0.03f, paint);
            paint.setColor(0xFF176A3B);
            canvas.drawRect(-s * 0.42f, s * 0.28f, s * 0.42f, s * 0.39f, paint);
            paint.setColor(0xFFFFD12A);
            canvas.drawRoundRect(new RectF(-s * 0.11f, s * 0.27f, s * 0.11f, s * 0.40f), s * 0.02f, s * 0.02f, paint);
            paint.setColor(0x66FFFFFF);
            for (int i = 0; i < 7; i++) {
                float px = -s * 0.30f + randomPattern(i + 30) * s * 0.60f;
                float py = -s * 0.02f + randomPattern(i + 40) * s * 0.45f;
                canvas.drawCircle(px, py, s * 0.045f, paint);
            }
        } else if (targetHero == Hero.RAK) {
            paint.setColor(0xFFFFD12A);
            Path star = new Path();
            for (int i = 0; i < 10; i++) {
                double a = -Math.PI / 2.0 + i * Math.PI / 5.0;
                float r = (i % 2 == 0 ? s * 0.17f : s * 0.075f);
                float x = (float) Math.cos(a) * r;
                float y = s * 0.10f + (float) Math.sin(a) * r;
                if (i == 0) {
                    star.moveTo(x, y);
                } else {
                    star.lineTo(x, y);
                }
            }
            star.close();
            canvas.drawPath(star, paint);
            paint.setColor(0xFFE64242);
            canvas.drawRect(-s * 0.42f, s * 0.35f, s * 0.42f, s * 0.46f, paint);
            paint.setColor(0xFFFFD12A);
            canvas.drawRoundRect(new RectF(-s * 0.12f, s * 0.33f, s * 0.12f, s * 0.48f), s * 0.03f, s * 0.03f, paint);
        } else {
            paint.setColor(0xFFFFD34D);
            for (int i = 0; i < 8; i++) {
                float px = -s * 0.31f + randomPattern(i + 50) * s * 0.62f;
                float py = -s * 0.07f + randomPattern(i + 61) * s * 0.48f;
                drawFlower(canvas, px, py, s * 0.055f, i % 2 == 0 ? 0xFFFF6B5D : 0xFFFFC93A);
            }
            paint.setColor(0xFF143A63);
            canvas.drawRoundRect(new RectF(-s * 0.42f, s * 0.32f, s * 0.42f, s * 0.43f), s * 0.04f, s * 0.04f, paint);
        }
    }

    private void drawFlower(Canvas canvas, float x, float y, float r, int color) {
        paint.setColor(color);
        canvas.drawCircle(x - r, y, r * 0.72f, paint);
        canvas.drawCircle(x + r, y, r * 0.72f, paint);
        canvas.drawCircle(x, y - r, r * 0.72f, paint);
        canvas.drawCircle(x, y + r, r * 0.72f, paint);
        paint.setColor(0xFFFFF1A6);
        canvas.drawCircle(x, y, r * 0.45f, paint);
    }

    private void drawCape(Canvas canvas, float s) {
        Path cape = new Path();
        cape.moveTo(-s * 0.34f, -s * 0.15f);
        cape.lineTo(-s * 0.58f, s * 0.58f);
        cape.quadTo(0f, s * 0.78f, s * 0.58f, s * 0.58f);
        cape.lineTo(s * 0.34f, -s * 0.15f);
        cape.close();
        paint.setColor(0xFFD83E3E);
        canvas.drawPath(cape, paint);
        stroke.setStrokeWidth(s * 0.025f);
        stroke.setColor(0xFF7D1C28);
        canvas.drawPath(cape, stroke);
    }

    private void drawFunAccessories(Canvas canvas, float s, Hero targetHero) {
        if (targetHero == Hero.LOZHKIN) {
            Path hat = new Path();
            hat.moveTo(-s * 0.36f, -s * 0.72f);
            hat.quadTo(0f, -s * 1.34f, s * 0.34f, -s * 0.74f);
            hat.lineTo(s * 0.14f, -s * 0.67f);
            hat.quadTo(0f, -s * 0.88f, -s * 0.26f, -s * 0.66f);
            hat.close();
            paint.setColor(0xFFE33B3B);
            canvas.drawPath(hat, paint);
            paint.setColor(Color.WHITE);
            canvas.drawRoundRect(new RectF(-s * 0.39f, -s * 0.78f, s * 0.37f, -s * 0.64f), s * 0.06f, s * 0.06f, paint);
            canvas.drawCircle(s * 0.34f, -s * 0.75f, s * 0.10f, paint);
        } else if (targetHero == Hero.RAK) {
            paint.setColor(0xBB3A2C84);
            canvas.drawRoundRect(new RectF(-s * 0.29f, -s * 0.60f, s * 0.29f, -s * 0.47f), s * 0.06f, s * 0.06f, paint);
            paint.setColor(0xFFEDF8FF);
            canvas.drawCircle(-s * 0.13f, -s * 0.535f, s * 0.035f, paint);
            canvas.drawCircle(s * 0.13f, -s * 0.535f, s * 0.035f, paint);
        } else {
            paint.setColor(0xEE14171A);
            canvas.drawRoundRect(new RectF(-s * 0.31f, -s * 0.61f, -s * 0.02f, -s * 0.48f), s * 0.04f, s * 0.04f, paint);
            canvas.drawRoundRect(new RectF(s * 0.02f, -s * 0.61f, s * 0.31f, -s * 0.48f), s * 0.04f, s * 0.04f, paint);
            stroke.setStrokeWidth(s * 0.035f);
            stroke.setColor(0xFF14171A);
            canvas.drawLine(-s * 0.02f, -s * 0.55f, s * 0.02f, -s * 0.55f, stroke);
        }
    }

    private float randomPattern(int seed) {
        int value = (seed * 73 + 19) % 97;
        return value / 97f;
    }

    private void drawArmsAndTool(Canvas canvas, float s, float side, float vertical, float activity, boolean showTool) {
        float targetX = side * s * (0.66f + activity * 0.18f);
        float targetY = s * (0.08f + vertical * 0.22f * activity);
        stroke.setStrokeWidth(s * 0.20f);
        stroke.setColor(heroBodyColor(hero, selectedSkin()));
        canvas.drawLine(-s * 0.28f, -s * 0.03f, targetX * 0.72f, targetY, stroke);
        canvas.drawLine(s * 0.28f, -s * 0.03f, targetX * 0.82f, targetY + s * 0.12f, stroke);
        stroke.setStrokeWidth(s * 0.07f);
        stroke.setColor(0xFF15191C);
        canvas.drawLine(-s * 0.28f, -s * 0.03f, targetX * 0.72f, targetY, stroke);
        canvas.drawLine(s * 0.28f, -s * 0.03f, targetX * 0.82f, targetY + s * 0.12f, stroke);

        if (hero == Hero.RAK && showTool) {
            float logX = targetX * (0.70f + 0.30f * activity);
            float logY = targetY - s * 0.03f;
            drawLog(canvas, logX, logY, s * 0.30f, side * -18f);
        } else if (hero == Hero.KOROLEV && showTool) {
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

    private void drawLevelOutro(Canvas canvas) {
        float t = clamp01((SystemClock.uptimeMillis() - levelOutroStartMs) / (float) LEVEL_OUTRO_DURATION_MS);
        paint.setColor(0x66000000);
        canvas.drawRoundRect(new RectF(width * 0.10f, height * 0.22f, width * 0.90f, height * 0.31f), width * 0.025f, width * 0.025f, paint);
        drawText(canvas, "УРОВЕНЬ " + level + " ПРОЙДЕН!", width * 0.5f, height * 0.275f, width * 0.054f, heroAccent());

        if (hero == Hero.LOZHKIN) {
            drawLozhkinOutro(canvas, t);
        } else if (hero == Hero.RAK) {
            drawRakOutro(canvas, t);
        } else {
            drawKorolevOutro(canvas, t);
        }

        String next = level >= MAX_LEVEL ? "ФИНАЛ!" : "СЛЕДУЮЩИЙ УРОВЕНЬ...";
        drawText(canvas, next, width * 0.5f, height * 0.90f, width * 0.033f, Color.WHITE);

        float fade = smooth(clamp01((t - 0.84f) / 0.16f));
        if (fade > 0f) {
            paint.setColor(((int) (fade * 255f) << 24));
            canvas.drawRect(0f, 0f, width, height, paint);
        }
    }

    private void drawLozhkinOutro(Canvas canvas, float t) {
        float drink = smooth(clamp01(t / 0.38f));
        float fall = smooth(clamp01((t - 0.42f) / 0.38f));
        float x = width * 0.50f + width * 0.12f * fall;
        float y = height * 0.70f + height * 0.11f * fall;
        float rotation = 82f * fall;
        drawCharacterAt(canvas, x, y, width * 0.25f, rotation, 1f, -1f, 0.20f, false);

        float bottleX = width * (0.62f - 0.085f * drink + 0.08f * fall);
        float bottleY = height * (0.70f - 0.17f * drink + 0.10f * fall);
        float bottleRotation = -58f - 35f * drink + 95f * fall;
        drawBottle(canvas, bottleX, bottleY, width * 0.034f, bottleRotation, false);

        if (t > 0.43f) {
            float wobble = (float) Math.sin((t - 0.43f) * 35f);
            drawText(canvas, wobble > 0f ? "ОЙ..." : "УХ!", width * 0.44f, height * 0.48f, width * 0.050f, 0xFFFFE27A);
        } else {
            drawText(canvas, "ЗА УРОВЕНЬ!", width * 0.5f, height * 0.44f, width * 0.046f, 0xFFFFE27A);
        }
    }

    private void drawRakOutro(Canvas canvas, float t) {
        float windup = smooth(clamp01(t / 0.24f));
        float throwPhase = smooth(clamp01((t - 0.23f) / 0.48f));
        float bob = (float) Math.sin(t * 25f) * height * 0.006f;
        drawCharacterAt(canvas, width * 0.46f, height * 0.72f + bob, width * 0.25f, -10f * windup + 14f * throwPhase, 1f, -1f, 1f, false);

        float startX = width * 0.61f;
        float startY = height * 0.68f;
        float endX = width * 0.50f;
        float endY = height * 0.48f;
        float logX = startX + (endX - startX) * throwPhase;
        float logY = startY + (endY - startY) * throwPhase;
        float logSize = width * (0.055f + 0.33f * throwPhase);
        drawLog(canvas, logX, logY, logSize, -20f + 400f * throwPhase);

        if (throwPhase > 0.78f) {
            float impact = clamp01((throwPhase - 0.78f) / 0.22f);
            stroke.setStrokeWidth(width * 0.018f * (1f - impact));
            stroke.setColor(((int) ((1f - impact) * 230f) << 24) | 0x00FFD441);
            canvas.drawCircle(width * 0.5f, height * 0.48f, width * (0.08f + impact * 0.22f), stroke);
            paint.setColor(((int) ((1f - impact) * 110f) << 24) | 0x00FFFFFF);
            canvas.drawRect(0f, 0f, width, height, paint);
        }
        drawText(canvas, "ЛОВИ ПОЛЕНО!", width * 0.5f, height * 0.40f, width * 0.047f, 0xFFFFD766);
    }

    private void drawKorolevOutro(Canvas canvas, float t) {
        float sway = (float) Math.sin(t * 22f);
        float x = width * 0.48f + sway * width * 0.025f;
        float y = height * 0.72f - Math.abs(sway) * height * 0.012f;
        drawCharacterAt(canvas, x, y, width * 0.25f, sway * 5f, 1f, -1f, 1f, true);

        float startX = x + width * 0.17f;
        float startY = y - height * 0.01f;
        for (int i = 0; i < 5; i++) {
            float wave = (float) Math.sin(t * 18f + i * 0.9f);
            float endX = width * (0.15f + i * 0.18f);
            float endY = height * (0.37f + wave * 0.07f);
            stroke.setStrokeWidth(width * (0.010f + i * 0.0015f));
            stroke.setColor(0xB65CD4FF);
            canvas.drawLine(startX, startY, endX, endY, stroke);
            stroke.setStrokeWidth(width * 0.0035f);
            stroke.setColor(0xFFE9FCFF);
            canvas.drawLine(startX, startY, endX, endY, stroke);
            paint.setColor(0xCC9BEAFF);
            canvas.drawCircle(endX, endY, width * 0.010f, paint);
        }
        for (int i = 0; i < 12; i++) {
            float a = t * 5f + i * 0.55f;
            float dropX = width * 0.5f + (float) Math.cos(a) * width * (0.20f + (i % 3) * 0.04f);
            float dropY = height * 0.48f + (float) Math.sin(a * 1.3f) * height * 0.15f;
            paint.setColor(0xAA7BDFFF);
            canvas.drawCircle(dropX, dropY, width * 0.007f, paint);
        }
        drawText(canvas, "ВОДЫ НЕ ЖАЛЕТЬ!", width * 0.5f, height * 0.40f, width * 0.046f, 0xFFA8ECFF);
    }

    private float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private float smooth(float value) {
        float v = clamp01(value);
        return v * v * (3f - 2f * v);
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
        return headForHero(hero);
    }

    private Bitmap headForHero(Hero targetHero) {
        if (targetHero == Hero.RAK) {
            return headRak;
        }
        if (targetHero == Hero.KOROLEV) {
            return headKorolev;
        }
        return headLozhkin;
    }

    private int heroBodyColor(Hero targetHero, Skin skin) {
        if (skin == Skin.FUN) {
            if (targetHero == Hero.LOZHKIN) {
                return 0xFFD93C3C;
            }
            if (targetHero == Hero.RAK) {
                return 0xFF315BC7;
            }
            return 0xFF18A8A0;
        }
        if (targetHero == Hero.RAK) {
            return skin == Skin.ORIGINAL ? 0xFFF2CD37 : 0xFFF0C42C;
        }
        if (targetHero == Hero.KOROLEV) {
            return skin == Skin.ORIGINAL ? 0xFF5E6F57 : 0xFF53624E;
        }
        return skin == Skin.ORIGINAL ? 0xFF657A55 : 0xFF59704C;
    }

    private int heroLegColor(Hero targetHero, Skin skin) {
        if (skin == Skin.FUN) {
            if (targetHero == Hero.LOZHKIN) {
                return 0xFF176A3B;
            }
            if (targetHero == Hero.RAK) {
                return 0xFF263E91;
            }
            return 0xFFFFC928;
        }
        return targetHero == Hero.RAK ? 0xFFE46C20 : 0xFF34453A;
    }

    private int heroAccent() {
        return shopAccent(hero);
    }

    private int shopAccent(Hero targetHero) {
        if (targetHero == Hero.RAK) {
            return 0xFFFFC928;
        }
        if (targetHero == Hero.KOROLEV) {
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
        if (screen == Screen.SHOP) {
            handleShopTouch(x, y);
            return true;
        }
        if (screen == Screen.COUNTDOWN || screen == Screen.LEVEL_OUTRO) {
            return true;
        }
        if (screen == Screen.PAUSED || screen == Screen.GAME_OVER || screen == Screen.VICTORY) {
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
            showNotice(sx < 360f ? "Здесь меню настроек — пока оставлено для будущих обновлений" : "Выбери героя и пройди 10 уровней", 2400L);
        } else if (sy > 1110f) {
            if (sx > 540f) {
                openShop();
            } else {
                showNotice("Этот раздел откроется позже. Магазин уже работает", 2200L);
            }
        }
    }

    private void handleShopTouch(float x, float y) {
        if (x < width * 0.18f && y < height * 0.16f) {
            goToMenu();
            return;
        }
        for (int i = 0; i < shopHeroes.length; i++) {
            if (shopOriginalButtonRects[i].contains(x, y)) {
                buyOrSelectSkin(shopHeroes[i], Skin.ORIGINAL);
                return;
            }
            if (shopFunButtonRects[i].contains(x, y)) {
                buyOrSelectSkin(shopHeroes[i], Skin.FUN);
                return;
            }
        }
    }

    private void handleOverlayTouch(float x, float y) {
        if (primaryButton().contains(x, y)) {
            if (screen == Screen.PAUSED) {
                screen = Screen.PLAYING;
                lastSpawnMs = SystemClock.uptimeMillis();
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
