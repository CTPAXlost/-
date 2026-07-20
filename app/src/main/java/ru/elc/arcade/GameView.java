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
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Random;

public final class GameView extends View implements Runnable {

    private enum Screen {
        MENU,
        PLAYING,
        PAUSED,
        LEVEL_COMPLETE,
        GAME_OVER,
        VICTORY
    }

    private enum Dialog {
        NONE,
        SETTINGS,
        INFO,
        RECORDS,
        ACHIEVEMENTS,
        RATING,
        SHOP
    }

    private enum Hero {
        LOZHKIN("Александр Ложкин", "Ловит бутылки", 0),
        RAK("Алик Рак", "Кидает поленья в котёл", 1),
        KOROLEV("Андрей Королёв", "Тушит пожар", 2);

        final String name;
        final String role;
        final int index;

        Hero(String name, String role, int index) {
            this.name = name;
            this.role = role;
            this.index = index;
        }
    }

    private static final int MAX_LEVEL = 10;
    private static final String PREFS = "elc_arcade_prefs";
    private static final String KEY_SOUND = "sound";
    private static final String KEY_VIBRATION = "vibration";

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint smallText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random random = new Random();
    private final SharedPreferences preferences;
    private final Vibrator vibrator;
    private final RectF menuDst = new RectF();
    private final ArrayList<MovingItem> movingItems = new ArrayList<>();
    private final ArrayList<PromptItem> prompts = new ArrayList<>();
    private final ArrayList<Particle> particles = new ArrayList<>();
    private final ArrayList<ActionEffect> actionEffects = new ArrayList<>();

    private Bitmap menuBitmap;
    private ToneGenerator toneGenerator;

    private Screen screen = Screen.MENU;
    private Dialog dialog = Dialog.NONE;
    private Hero hero = Hero.LOZHKIN;

    private boolean loopRunning;
    private boolean hostPaused;
    private boolean soundEnabled;
    private boolean vibrationEnabled;

    private long lastFrameTime;
    private long lastSpawnTime;
    private long flashUntil;
    private long wrongUntil;

    private int level = 1;
    private int score;
    private int lives = 3;
    private int progress;
    private int selectedLane = 3;

    private float width;
    private float height;
    private float density;

    public GameView(Context context) {
        super(context);
        setFocusable(true);
        setKeepScreenOn(true);
        density = getResources().getDisplayMetrics().density;
        menuBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.menu_elc);
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        soundEnabled = preferences.getBoolean(KEY_SOUND, true);
        vibrationEnabled = preferences.getBoolean(KEY_VIBRATION, true);
        vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);

        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);

        text.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        text.setTextAlign(Paint.Align.CENTER);
        smallText.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        smallText.setTextAlign(Paint.Align.CENTER);

        createToneGenerator();
    }

    private void createToneGenerator() {
        if (!soundEnabled || toneGenerator != null) {
            return;
        }
        try {
            toneGenerator = new ToneGenerator(AudioManager.STREAM_MUSIC, 52);
        } catch (RuntimeException ignored) {
            toneGenerator = null;
        }
    }

    private void releaseToneGenerator() {
        if (toneGenerator != null) {
            try {
                toneGenerator.release();
            } catch (RuntimeException ignored) {
                // Some devices throw while audio service is restarting.
            }
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
        releaseToneGenerator();
        if (menuBitmap != null) {
            menuBitmap.recycle();
            menuBitmap = null;
        }
        super.onDetachedFromWindow();
    }

    public void onHostPause() {
        hostPaused = true;
        if (screen == Screen.PLAYING) {
            screen = Screen.PAUSED;
        }
    }

    public void onHostResume() {
        hostPaused = false;
        if (soundEnabled) {
            createToneGenerator();
        }
        startLoop();
    }

    public boolean handleBack() {
        if (dialog != Dialog.NONE) {
            dialog = Dialog.NONE;
            invalidate();
            return true;
        }
        if (screen != Screen.MENU) {
            goToMenu();
            return true;
        }
        return false;
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
        float dt = Math.min(0.05f, Math.max(0f, (now - lastFrameTime) / 1000f));
        lastFrameTime = now;
        if (!hostPaused) {
            update(dt, now);
        }
        invalidate();
        postOnAnimation(this);
    }

    private void update(float dt, long now) {
        updateParticles(dt);
        updateActionEffects(dt);
        if (screen != Screen.PLAYING) {
            return;
        }

        if (now - lastSpawnTime >= spawnIntervalMs()) {
            if (hero == Hero.LOZHKIN) {
                if (movingItems.size() < maxActiveItems()) {
                    spawnBottle(now);
                }
            } else if (prompts.size() < maxActiveItems()) {
                spawnPrompt(now);
            }
        }

        if (hero == Hero.LOZHKIN) {
            updateBottles(dt);
        } else {
            updatePrompts(dt);
        }
    }

    private void updateBottles(float dt) {
        Iterator<MovingItem> iterator = movingItems.iterator();
        while (iterator.hasNext()) {
            MovingItem item = iterator.next();
            item.progress += dt / item.durationSeconds;
            item.rotation += dt * (item.lane < 2 ? 230f : -230f);

            if (item.progress >= 0.84f && item.progress <= 1.05f && selectedLane == item.lane) {
                iterator.remove();
                onSuccess(item.lane, item.golden ? 2 : 1, item.golden);
                if (screen != Screen.PLAYING) {
                    return;
                }
            } else if (item.progress > 1.08f) {
                PointF hit = lanePoint(item.lane);
                iterator.remove();
                onMiss(hit.x, hit.y, item.golden ? 0xFFFFC928 : 0xFF6EC37D);
                if (screen != Screen.PLAYING) {
                    return;
                }
            }
        }
    }

    private void updatePrompts(float dt) {
        Iterator<PromptItem> iterator = prompts.iterator();
        while (iterator.hasNext()) {
            PromptItem item = iterator.next();
            item.age += dt;
            if (item.age > item.lifeSeconds) {
                PointF p = lanePoint(item.lane);
                iterator.remove();
                onMiss(p.x, p.y, hero == Hero.RAK ? 0xFFFF9F2E : 0xFFFF5D34);
                if (screen != Screen.PLAYING) {
                    return;
                }
            }
        }
    }

    private void updateParticles(float dt) {
        Iterator<Particle> iterator = particles.iterator();
        while (iterator.hasNext()) {
            Particle p = iterator.next();
            p.life -= dt;
            p.x += p.vx * dt;
            p.y += p.vy * dt;
            p.vy += p.gravity * dt;
            p.rotation += p.spin * dt;
            if (p.life <= 0f) {
                iterator.remove();
            }
        }
    }

    private void updateActionEffects(float dt) {
        Iterator<ActionEffect> iterator = actionEffects.iterator();
        while (iterator.hasNext()) {
            ActionEffect effect = iterator.next();
            effect.age += dt;
            if (effect.age >= effect.life) {
                iterator.remove();
            }
        }
    }

    private void spawnBottle(long now) {
        int lane = chooseLaneWithLeastConflict(true);
        boolean golden = level >= 4 && random.nextFloat() < 0.055f + level * 0.006f;
        float jitter = 0.88f + random.nextFloat() * 0.24f;
        movingItems.add(new MovingItem(lane, travelDurationSeconds() * jitter, golden));
        lastSpawnTime = now;
    }

    private void spawnPrompt(long now) {
        int lane = chooseLaneWithLeastConflict(false);
        prompts.add(new PromptItem(lane, promptLifetimeSeconds()));
        lastSpawnTime = now;
        softTone(ToneGenerator.TONE_PROP_PROMPT, 45);
    }

    private int chooseLaneWithLeastConflict(boolean bottle) {
        ArrayList<Integer> candidates = new ArrayList<>();
        for (int lane = 0; lane < 4; lane++) {
            boolean occupied = false;
            if (bottle) {
                for (MovingItem item : movingItems) {
                    if (item.lane == lane && item.progress < 0.62f) {
                        occupied = true;
                        break;
                    }
                }
            } else {
                for (PromptItem item : prompts) {
                    if (item.lane == lane) {
                        occupied = true;
                        break;
                    }
                }
            }
            if (!occupied) {
                candidates.add(lane);
            }
        }
        if (candidates.isEmpty()) {
            return random.nextInt(4);
        }
        return candidates.get(random.nextInt(candidates.size()));
    }

    private void onSuccess(int lane, int amount, boolean special) {
        int points = (special ? 25 : 10) * level;
        score += points;
        progress += amount;
        flashUntil = SystemClock.uptimeMillis() + 120;
        PointF target = lanePoint(lane);
        createSuccessParticles(target.x, target.y, special);
        softTone(special ? ToneGenerator.TONE_PROP_ACK : ToneGenerator.TONE_PROP_BEEP, special ? 115 : 70);
        vibrate(28);

        if (progress >= targetForLevel()) {
            progress = targetForLevel();
            movingItems.clear();
            prompts.clear();
            saveProgress();
            if (level >= MAX_LEVEL) {
                screen = Screen.VICTORY;
                saveHighScore();
            } else {
                screen = Screen.LEVEL_COMPLETE;
            }
        }
    }

    private void onMiss(float x, float y, int color) {
        lives--;
        wrongUntil = SystemClock.uptimeMillis() + 240;
        createMissParticles(x, y, color);
        softTone(ToneGenerator.TONE_CDMA_ABBR_ALERT, 140);
        vibrate(90);
        if (lives <= 0) {
            lives = 0;
            movingItems.clear();
            prompts.clear();
            saveHighScore();
            screen = Screen.GAME_OVER;
        }
    }

    private void startHero(Hero selectedHero) {
        hero = selectedHero;
        level = 1;
        score = 0;
        lives = 3;
        progress = 0;
        selectedLane = 3;
        movingItems.clear();
        prompts.clear();
        particles.clear();
        actionEffects.clear();
        screen = Screen.PLAYING;
        dialog = Dialog.NONE;
        lastSpawnTime = SystemClock.uptimeMillis() - spawnIntervalMs() + 350;
        softTone(ToneGenerator.TONE_PROP_ACK, 100);
    }

    private void startNextLevel() {
        level++;
        lives = 3;
        progress = 0;
        selectedLane = 3;
        movingItems.clear();
        prompts.clear();
        particles.clear();
        actionEffects.clear();
        screen = Screen.PLAYING;
        lastSpawnTime = SystemClock.uptimeMillis() - spawnIntervalMs() + 420;
        softTone(ToneGenerator.TONE_PROP_ACK, 100);
    }

    private void retryCurrentHero() {
        startHero(hero);
    }

    private void goToMenu() {
        saveHighScore();
        screen = Screen.MENU;
        dialog = Dialog.NONE;
        movingItems.clear();
        prompts.clear();
        particles.clear();
        actionEffects.clear();
    }

    private int targetForLevel() {
        return 9 + level * 3;
    }

    private long spawnIntervalMs() {
        if (hero == Hero.LOZHKIN) {
            return Math.max(470L, 1420L - level * 85L);
        }
        return Math.max(500L, 1500L - level * 82L);
    }

    private float travelDurationSeconds() {
        return Math.max(1.15f, 3.10f - level * 0.17f);
    }

    private float promptLifetimeSeconds() {
        return Math.max(0.95f, 2.55f - level * 0.14f);
    }

    private int maxActiveItems() {
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

    private String scoreKey(Hero h) {
        return "score_" + h.index;
    }

    private String completedKey(Hero h) {
        return "completed_" + h.index;
    }

    private void saveHighScore() {
        int old = preferences.getInt(scoreKey(hero), 0);
        if (score > old) {
            preferences.edit().putInt(scoreKey(hero), score).apply();
        }
    }

    private void saveProgress() {
        int old = preferences.getInt(completedKey(hero), 0);
        int newValue = Math.max(old, level);
        preferences.edit().putInt(completedKey(hero), newValue).apply();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        width = w;
        height = h;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (screen == Screen.MENU) {
            drawMenu(canvas);
        } else {
            drawGame(canvas);
            if (screen != Screen.PLAYING) {
                drawGameOverlay(canvas);
            }
        }
        if (dialog != Dialog.NONE) {
            drawDialog(canvas);
        }
    }

    private void drawMenu(Canvas canvas) {
        canvas.drawColor(Color.rgb(7, 10, 13));
        if (menuBitmap == null || menuBitmap.isRecycled()) {
            drawFallbackMenu(canvas);
            return;
        }
        float scale = Math.min(width / menuBitmap.getWidth(), height / menuBitmap.getHeight());
        float drawW = menuBitmap.getWidth() * scale;
        float drawH = menuBitmap.getHeight() * scale;
        float left = (width - drawW) * 0.5f;
        float top = (height - drawH) * 0.5f;
        menuDst.set(left, top, left + drawW, top + drawH);

        paint.setColor(Color.WHITE);
        canvas.drawBitmap(menuBitmap, null, menuDst, paint);

        if (height > drawH + 2f) {
            paint.setShader(new LinearGradient(0, 0, 0, top, 0xFF101820, 0xFF050709, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, width, top, paint);
            paint.setShader(new LinearGradient(0, menuDst.bottom, 0, height, 0xFF050709, 0xFF101820, Shader.TileMode.CLAMP));
            canvas.drawRect(0, menuDst.bottom, width, height, paint);
            paint.setShader(null);
        }
    }

    private void drawFallbackMenu(Canvas canvas) {
        paint.setShader(new LinearGradient(0, 0, 0, height, 0xFF14212A, 0xFF080B0D, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, height, paint);
        paint.setShader(null);
        drawCenteredText(canvas, "ЕЛЦ", width * 0.5f, height * 0.18f, width * 0.22f, 0xFFFFC522);
        drawCenteredText(canvas, "ВЫБЕРИ СВОЕГО ГЕРОЯ", width * 0.5f, height * 0.28f, width * 0.052f, Color.WHITE);
        for (Hero h : Hero.values()) {
            float cx = width * (0.2f + h.index * 0.3f);
            RectF card = new RectF(cx - width * 0.13f, height * 0.36f, cx + width * 0.13f, height * 0.78f);
            drawPanel(canvas, card, heroColor(h), 0xFF20252A, width * 0.012f);
            drawCenteredText(canvas, h.name, cx, height * 0.57f, width * 0.035f, Color.WHITE);
            drawCenteredText(canvas, h.role, cx, height * 0.64f, width * 0.027f, 0xFFFFDB7A);
            drawButton(canvas, new RectF(card.left + 10, card.bottom - height * 0.09f, card.right - 10, card.bottom - 12), "ИГРАТЬ", true);
        }
    }

    private void drawGame(Canvas canvas) {
        drawGameBackground(canvas);
        drawPlayField(canvas);
        drawCharacter(canvas);
        drawHud(canvas);
        drawParticles(canvas);
        drawActionEffects(canvas);

        long now = SystemClock.uptimeMillis();
        if (now < flashUntil) {
            paint.setColor(0x35FFFFFF);
            canvas.drawRect(0, 0, width, height, paint);
        }
        if (now < wrongUntil) {
            paint.setColor(0x28FF2A20);
            canvas.drawRect(0, 0, width, height, paint);
        }
    }

    private void drawGameBackground(Canvas canvas) {
        int topColor;
        int bottomColor;
        if (hero == Hero.LOZHKIN) {
            topColor = 0xFF213427;
            bottomColor = 0xFF080D0A;
        } else if (hero == Hero.RAK) {
            topColor = 0xFF493115;
            bottomColor = 0xFF100B06;
        } else {
            topColor = 0xFF17344A;
            bottomColor = 0xFF070D13;
        }
        paint.setShader(new LinearGradient(0, 0, 0, height, topColor, bottomColor, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, height, paint);
        paint.setShader(null);

        paint.setColor(0x2AFFFFFF);
        for (int i = 0; i < 8; i++) {
            float x = width * (i / 7f);
            canvas.drawRect(x, height * 0.11f, x + Math.max(2f, width * 0.005f), height, paint);
        }
        paint.setColor(0x22000000);
        for (int i = 0; i < 9; i++) {
            float y = height * (0.17f + i * 0.09f);
            canvas.drawRect(0, y, width, y + Math.max(2f, height * 0.004f), paint);
        }
    }

    private void drawPlayField(Canvas canvas) {
        if (hero == Hero.LOZHKIN) {
            drawBottleField(canvas);
        } else if (hero == Hero.RAK) {
            drawCauldronField(canvas);
        } else {
            drawFireField(canvas);
        }
        drawLaneHints(canvas);
    }

    private void drawBottleField(Canvas canvas) {
        drawShop(canvas, true);
        drawShop(canvas, false);

        for (int lane = 0; lane < 4; lane++) {
            PointF start = bottleStart(lane);
            PointF end = lanePoint(lane);
            PointF control = bottleControl(lane);
            Path rail = new Path();
            rail.moveTo(start.x, start.y);
            rail.quadTo(control.x, control.y, end.x, end.y);
            stroke.setStrokeWidth(Math.max(5f, width * 0.012f));
            stroke.setColor(0xFF5A4B35);
            canvas.drawPath(rail, stroke);
            stroke.setStrokeWidth(Math.max(2f, width * 0.004f));
            stroke.setColor(0xFFB99B62);
            canvas.drawPath(rail, stroke);
        }

        for (MovingItem item : movingItems) {
            PointF p = quadraticPoint(bottleStart(item.lane), bottleControl(item.lane), lanePoint(item.lane), item.progress);
            drawBottle(canvas, p.x, p.y, width * 0.032f, item.rotation, item.golden);
        }

        PointF bagPoint = lanePoint(selectedLane);
        drawBag(canvas, bagPoint.x, bagPoint.y + height * 0.024f, width * 0.11f, selectedLane < 2);
    }

    private void drawCauldronField(Canvas canvas) {
        paint.setColor(0x55251910);
        canvas.drawRoundRect(new RectF(width * 0.08f, height * 0.18f, width * 0.92f, height * 0.78f), width * 0.04f, width * 0.04f, paint);
        drawPipeNetwork(canvas);
        for (PromptItem item : prompts) {
            PointF p = lanePoint(item.lane);
            float remaining = 1f - item.age / item.lifeSeconds;
            float pulse = 1f + (float) Math.sin(item.age * 8f) * 0.045f;
            drawCauldron(canvas, p.x, p.y, width * 0.105f * pulse, remaining);
        }
        drawLog(canvas, width * 0.5f, height * 0.70f, width * 0.10f, -8f);
    }

    private void drawFireField(Canvas canvas) {
        paint.setColor(0x3A0D2230);
        canvas.drawRoundRect(new RectF(width * 0.07f, height * 0.17f, width * 0.93f, height * 0.79f), width * 0.04f, width * 0.04f, paint);
        drawWarehouseWindows(canvas);
        for (PromptItem item : prompts) {
            PointF p = lanePoint(item.lane);
            float remaining = 1f - item.age / item.lifeSeconds;
            float grow = 0.82f + item.age / item.lifeSeconds * 0.32f;
            drawFire(canvas, p.x, p.y, width * 0.09f * grow, remaining);
        }
        drawHoseNozzle(canvas, width * 0.53f, height * 0.72f, width * 0.085f, -15f);
    }

    private void drawLaneHints(Canvas canvas) {
        for (int lane = 0; lane < 4; lane++) {
            PointF p = lanePoint(lane);
            boolean active = hero == Hero.LOZHKIN && selectedLane == lane;
            stroke.setStrokeWidth(active ? width * 0.012f : width * 0.006f);
            stroke.setColor(active ? 0xFFFFD83D : 0x48FFFFFF);
            canvas.drawCircle(p.x, p.y, width * 0.075f, stroke);
            if (active) {
                paint.setColor(0x2AFFF36A);
                canvas.drawCircle(p.x, p.y, width * 0.07f, paint);
            }
        }
    }

    private void drawHud(Canvas canvas) {
        RectF hud = new RectF(width * 0.02f, height * 0.018f, width * 0.98f, height * 0.13f);
        drawPanel(canvas, hud, 0xFFFFC42A, 0xE614171A, width * 0.012f);

        float buttonSize = height * 0.072f;
        RectF back = new RectF(hud.left + width * 0.018f, hud.centerY() - buttonSize * 0.5f, hud.left + width * 0.018f + buttonSize, hud.centerY() + buttonSize * 0.5f);
        RectF pause = new RectF(hud.right - width * 0.018f - buttonSize, hud.centerY() - buttonSize * 0.5f, hud.right - width * 0.018f, hud.centerY() + buttonSize * 0.5f);
        drawSmallButton(canvas, back, "←");
        drawSmallButton(canvas, pause, "Ⅱ");

        drawCenteredText(canvas, hero.name, width * 0.5f, hud.top + height * 0.035f, width * 0.048f, Color.WHITE);
        String stats = String.format(Locale.getDefault(), "УРОВЕНЬ %d/10   •   ОЧКИ %d   •   ЖИЗНИ %d", level, score, lives);
        drawCenteredText(canvas, stats, width * 0.5f, hud.top + height * 0.076f, width * 0.031f, 0xFFFFD763);

        RectF progressBg = new RectF(width * 0.18f, height * 0.139f, width * 0.82f, height * 0.157f);
        paint.setColor(0x88000000);
        canvas.drawRoundRect(progressBg, progressBg.height() * 0.5f, progressBg.height() * 0.5f, paint);
        float fraction = targetForLevel() == 0 ? 0f : Math.min(1f, progress / (float) targetForLevel());
        RectF progressFill = new RectF(progressBg.left, progressBg.top, progressBg.left + progressBg.width() * fraction, progressBg.bottom);
        paint.setShader(new LinearGradient(progressFill.left, 0, progressFill.right, 0, 0xFFFFB21A, 0xFFFFE760, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(progressFill, progressBg.height() * 0.5f, progressBg.height() * 0.5f, paint);
        paint.setShader(null);
        drawCenteredText(canvas, progress + " / " + targetForLevel(), width * 0.5f, height * 0.178f, width * 0.028f, Color.WHITE);
    }

    private void drawCharacter(Canvas canvas) {
        RectF frame = new RectF(width * 0.36f, height * 0.56f, width * 0.64f, height * 0.91f);
        drawPanel(canvas, frame, heroColor(hero), 0xEF12171A, width * 0.012f);

        Rect source = portraitSource(hero);
        RectF portrait = new RectF(frame.left + width * 0.012f, frame.top + width * 0.012f, frame.right - width * 0.012f, frame.bottom - height * 0.055f);
        int save = canvas.save();
        Path clip = new Path();
        clip.addRoundRect(portrait, width * 0.025f, width * 0.025f, Path.Direction.CW);
        canvas.clipPath(clip);
        if (menuBitmap != null && !menuBitmap.isRecycled()) {
            canvas.drawBitmap(menuBitmap, source, portrait, paint);
        } else {
            paint.setColor(heroColor(hero));
            canvas.drawRect(portrait, paint);
        }
        canvas.restoreToCount(save);

        paint.setColor(0xD9000000);
        canvas.drawRoundRect(new RectF(frame.left + width * 0.008f, frame.bottom - height * 0.064f, frame.right - width * 0.008f, frame.bottom - height * 0.008f), width * 0.018f, width * 0.018f, paint);
        drawCenteredText(canvas, hero.role.toUpperCase(Locale.getDefault()), frame.centerX(), frame.bottom - height * 0.025f, width * 0.025f, 0xFFFFD24A);
    }

    private Rect portraitSource(Hero h) {
        if (h == Hero.LOZHKIN) {
            return new Rect(30, 510, 305, 925);
        }
        if (h == Hero.RAK) {
            return new Rect(330, 510, 610, 925);
        }
        return new Rect(630, 510, 925, 925);
    }

    private void drawParticles(Canvas canvas) {
        for (Particle p : particles) {
            int alpha = (int) (255f * Math.max(0f, Math.min(1f, p.life / p.maxLife)));
            paint.setColor((p.color & 0x00FFFFFF) | (alpha << 24));
            canvas.save();
            canvas.rotate(p.rotation, p.x, p.y);
            canvas.drawRoundRect(new RectF(p.x - p.size, p.y - p.size * 0.55f, p.x + p.size, p.y + p.size * 0.55f), p.size * 0.25f, p.size * 0.25f, paint);
            canvas.restore();
        }
    }

    private void drawActionEffects(Canvas canvas) {
        for (ActionEffect effect : actionEffects) {
            float t = Math.min(1f, effect.age / effect.life);
            PointF from = new PointF(width * 0.5f, height * 0.69f);
            PointF to = lanePoint(effect.lane);
            if (effect.water) {
                stroke.setStrokeWidth(width * (0.026f - 0.012f * t));
                stroke.setColor((int) ((1f - t) * 210) << 24 | 0x005ED6FF);
                canvas.drawLine(from.x, from.y, to.x, to.y, stroke);
                stroke.setStrokeWidth(width * 0.007f);
                stroke.setColor((int) ((1f - t) * 255) << 24 | 0x00E7FAFF);
                canvas.drawLine(from.x, from.y, to.x, to.y, stroke);
            } else {
                float eased = 1f - (1f - t) * (1f - t);
                float x = from.x + (to.x - from.x) * eased;
                float y = from.y + (to.y - from.y) * eased - height * 0.12f * (float) Math.sin(Math.PI * eased);
                drawLog(canvas, x, y, width * 0.065f, (to.x < from.x ? -1 : 1) * t * 220f);
            }
        }
    }

    private void drawGameOverlay(Canvas canvas) {
        paint.setColor(0xB9000000);
        canvas.drawRect(0, 0, width, height, paint);
        RectF panel = new RectF(width * 0.10f, height * 0.27f, width * 0.90f, height * 0.76f);
        drawPanel(canvas, panel, 0xFFFFC428, 0xF21B2024, width * 0.015f);

        String title;
        String subtitle;
        String primary;
        String secondary;
        if (screen == Screen.PAUSED) {
            title = "ПАУЗА";
            subtitle = "Игра остановлена";
            primary = "ПРОДОЛЖИТЬ";
            secondary = "В МЕНЮ";
        } else if (screen == Screen.LEVEL_COMPLETE) {
            title = "УРОВЕНЬ " + level + " ПРОЙДЕН";
            subtitle = "Отличная работа! Очки: " + score;
            primary = "СЛЕДУЮЩИЙ УРОВЕНЬ";
            secondary = "В МЕНЮ";
        } else if (screen == Screen.GAME_OVER) {
            title = "ИГРА ОКОНЧЕНА";
            subtitle = "Результат: " + score + " очков";
            primary = "НАЧАТЬ ЗАНОВО";
            secondary = "В МЕНЮ";
        } else {
            title = "ВСЕ 10 УРОВНЕЙ ПРОЙДЕНЫ!";
            subtitle = hero.name + " — результат " + score;
            primary = "СЫГРАТЬ ЕЩЁ";
            secondary = "В МЕНЮ";
        }
        drawCenteredText(canvas, title, width * 0.5f, panel.top + height * 0.105f, width * 0.058f, 0xFFFFD040);
        drawCenteredText(canvas, subtitle, width * 0.5f, panel.top + height * 0.17f, width * 0.035f, Color.WHITE);
        drawButton(canvas, overlayPrimaryRect(), primary, true);
        drawButton(canvas, overlaySecondaryRect(), secondary, false);
    }

    private RectF overlayPrimaryRect() {
        return new RectF(width * 0.19f, height * 0.53f, width * 0.81f, height * 0.61f);
    }

    private RectF overlaySecondaryRect() {
        return new RectF(width * 0.19f, height * 0.64f, width * 0.81f, height * 0.72f);
    }

    private void drawDialog(Canvas canvas) {
        paint.setColor(0xC5000000);
        canvas.drawRect(0, 0, width, height, paint);
        RectF panel = new RectF(width * 0.07f, height * 0.15f, width * 0.93f, height * 0.86f);
        drawPanel(canvas, panel, 0xFFFFC12A, 0xF31A1E22, width * 0.014f);

        String title;
        List<String> lines = new ArrayList<>();
        switch (dialog) {
            case SETTINGS:
                title = "НАСТРОЙКИ";
                lines.add("Звук: " + (soundEnabled ? "ВКЛ" : "ВЫКЛ"));
                lines.add("Вибрация: " + (vibrationEnabled ? "ВКЛ" : "ВЫКЛ"));
                lines.add("Нажми на строку, чтобы переключить");
                break;
            case INFO:
                title = "КАК ИГРАТЬ";
                lines.add("Александр: выбирай один из четырёх углов и лови бутылки пакетом.");
                lines.add("Алик: нажимай на сторону, где появился котёл, чтобы бросить полено.");
                lines.add("Андрей: нажимай на сторону с огнём, чтобы направить струю воды.");
                lines.add("У каждого героя 10 уровней. Три промаха — проигрыш.");
                break;
            case RECORDS:
                title = "РЕКОРДЫ";
                for (Hero h : Hero.values()) {
                    lines.add(h.name + ": " + preferences.getInt(scoreKey(h), 0) + " очков");
                }
                break;
            case ACHIEVEMENTS:
                title = "ДОСТИЖЕНИЯ";
                for (Hero h : Hero.values()) {
                    int completed = preferences.getInt(completedKey(h), 0);
                    lines.add(h.name + ": пройдено уровней " + completed + "/10");
                }
                break;
            case RATING:
                title = "ЛОКАЛЬНЫЙ РЕЙТИНГ";
                ArrayList<Hero> rating = new ArrayList<>();
                Collections.addAll(rating, Hero.values());
                rating.sort(Comparator.comparingInt((Hero h) -> preferences.getInt(scoreKey(h), 0)).reversed());
                for (int i = 0; i < rating.size(); i++) {
                    Hero h = rating.get(i);
                    lines.add((i + 1) + ". " + h.name + " — " + preferences.getInt(scoreKey(h), 0));
                }
                break;
            case SHOP:
                title = "МАГАЗИН";
                lines.add("В первой версии всё открыто бесплатно.");
                lines.add("Позже здесь можно добавить костюмы, пакеты, котлы и пожарные рукава.");
                break;
            default:
                return;
        }

        drawCenteredText(canvas, title, width * 0.5f, panel.top + height * 0.075f, width * 0.058f, 0xFFFFD13A);
        float y = panel.top + height * 0.16f;
        for (int i = 0; i < lines.size(); i++) {
            if (dialog == Dialog.SETTINGS && i < 2) {
                RectF row = settingsRow(i);
                paint.setColor(i == 0 ? 0xFF2D4935 : 0xFF29445A);
                canvas.drawRoundRect(row, width * 0.025f, width * 0.025f, paint);
                drawCenteredText(canvas, lines.get(i), width * 0.5f, row.centerY() + width * 0.012f, width * 0.043f, Color.WHITE);
                y = row.bottom + height * 0.035f;
            } else {
                y = drawWrappedCenteredText(canvas, lines.get(i), width * 0.5f, y, panel.width() * 0.82f, width * 0.033f, Color.WHITE, height * 0.038f);
                y += height * 0.025f;
            }
        }
        drawButton(canvas, dialogCloseRect(), "ЗАКРЫТЬ", true);
    }

    private RectF settingsRow(int index) {
        float top = height * (0.34f + index * 0.12f);
        return new RectF(width * 0.17f, top, width * 0.83f, top + height * 0.085f);
    }

    private RectF dialogCloseRect() {
        return new RectF(width * 0.23f, height * 0.75f, width * 0.77f, height * 0.82f);
    }

    private void drawShop(Canvas canvas, boolean left) {
        float x0 = left ? 0 : width * 0.86f;
        float x1 = left ? width * 0.14f : width;
        paint.setColor(0xFF3A2C20);
        canvas.drawRect(x0, height * 0.16f, x1, height * 0.80f, paint);
        paint.setColor(0xFFB88C48);
        canvas.drawRect(x0, height * 0.16f, x1, height * 0.20f, paint);
        for (int i = 0; i < 5; i++) {
            float y = height * (0.28f + i * 0.105f);
            paint.setColor(0xFF5B4630);
            canvas.drawRect(x0, y, x1, y + height * 0.012f, paint);
            for (int j = 0; j < 2; j++) {
                float bx = left ? width * (0.035f + j * 0.05f) : width * (0.89f + j * 0.05f);
                drawBottle(canvas, bx, y - height * 0.022f, width * 0.016f, 0, false);
            }
        }
    }

    private void drawPipeNetwork(Canvas canvas) {
        stroke.setStrokeWidth(width * 0.025f);
        stroke.setColor(0xFF4B4F52);
        canvas.drawLine(width * 0.12f, height * 0.25f, width * 0.88f, height * 0.25f, stroke);
        canvas.drawLine(width * 0.12f, height * 0.25f, width * 0.12f, height * 0.73f, stroke);
        canvas.drawLine(width * 0.88f, height * 0.25f, width * 0.88f, height * 0.73f, stroke);
        stroke.setStrokeWidth(width * 0.008f);
        stroke.setColor(0xFFB26E2E);
        canvas.drawLine(width * 0.12f, height * 0.25f, width * 0.88f, height * 0.25f, stroke);
    }

    private void drawWarehouseWindows(Canvas canvas) {
        for (int side = 0; side < 2; side++) {
            float left = side == 0 ? width * 0.08f : width * 0.72f;
            for (int row = 0; row < 2; row++) {
                RectF window = new RectF(left, height * (0.25f + row * 0.27f), left + width * 0.20f, height * (0.39f + row * 0.27f));
                paint.setShader(new RadialGradient(window.centerX(), window.centerY(), window.width(), 0xFF476D83, 0xFF17232A, Shader.TileMode.CLAMP));
                canvas.drawRect(window, paint);
                paint.setShader(null);
                stroke.setStrokeWidth(width * 0.008f);
                stroke.setColor(0xFF566168);
                canvas.drawRect(window, stroke);
                canvas.drawLine(window.centerX(), window.top, window.centerX(), window.bottom, stroke);
                canvas.drawLine(window.left, window.centerY(), window.right, window.centerY(), stroke);
            }
        }
    }

    private PointF lanePoint(int lane) {
        float x = lane < 2 ? width * 0.20f : width * 0.80f;
        float y = lane % 2 == 0 ? height * 0.39f : height * 0.66f;
        return new PointF(x, y);
    }

    private PointF bottleStart(int lane) {
        float x = lane < 2 ? width * 0.09f : width * 0.91f;
        float y = lane % 2 == 0 ? height * 0.20f : height * 0.43f;
        return new PointF(x, y);
    }

    private PointF bottleControl(int lane) {
        PointF start = bottleStart(lane);
        PointF end = lanePoint(lane);
        float cx = (start.x + end.x) * 0.5f;
        float cy = Math.min(start.y, end.y) - height * 0.035f;
        return new PointF(cx, cy);
    }

    private PointF quadraticPoint(PointF a, PointF b, PointF c, float t) {
        float clamped = Math.max(0f, Math.min(1.15f, t));
        float inv = 1f - clamped;
        return new PointF(
                inv * inv * a.x + 2f * inv * clamped * b.x + clamped * clamped * c.x,
                inv * inv * a.y + 2f * inv * clamped * b.y + clamped * clamped * c.y
        );
    }

    private void createSuccessParticles(float x, float y, boolean special) {
        int count = special ? 18 : 10;
        int color = special ? 0xFFFFD32E : heroColor(hero);
        for (int i = 0; i < count; i++) {
            float angle = (float) (random.nextDouble() * Math.PI * 2.0);
            float speed = width * (0.10f + random.nextFloat() * 0.15f);
            particles.add(new Particle(
                    x,
                    y,
                    (float) Math.cos(angle) * speed,
                    (float) Math.sin(angle) * speed,
                    height * 0.25f,
                    0.45f + random.nextFloat() * 0.35f,
                    width * (0.006f + random.nextFloat() * 0.009f),
                    color,
                    random.nextFloat() * 360f,
                    -280f + random.nextFloat() * 560f
            ));
        }
    }

    private void createMissParticles(float x, float y, int color) {
        for (int i = 0; i < 18; i++) {
            float angle = (float) (Math.PI * (0.15 + random.nextDouble() * 0.70));
            float speed = width * (0.11f + random.nextFloat() * 0.18f);
            particles.add(new Particle(
                    x,
                    y,
                    (float) Math.cos(angle) * speed * (random.nextBoolean() ? 1f : -1f),
                    (float) -Math.sin(angle) * speed,
                    height * 0.55f,
                    0.6f + random.nextFloat() * 0.45f,
                    width * (0.007f + random.nextFloat() * 0.011f),
                    color,
                    random.nextFloat() * 360f,
                    -360f + random.nextFloat() * 720f
            ));
        }
    }

    private void drawBottle(Canvas canvas, float x, float y, float size, float rotation, boolean golden) {
        canvas.save();
        canvas.rotate(rotation, x, y);
        int body = golden ? 0xFFFFC52E : 0xFF4EA962;
        int light = golden ? 0xFFFFE27A : 0xFF9EE0A8;
        paint.setColor(body);
        RectF bottle = new RectF(x - size * 0.45f, y - size * 1.05f, x + size * 0.45f, y + size * 1.05f);
        canvas.drawRoundRect(bottle, size * 0.35f, size * 0.35f, paint);
        paint.setColor(light);
        canvas.drawRoundRect(new RectF(x - size * 0.34f, y - size * 0.88f, x - size * 0.08f, y + size * 0.62f), size * 0.13f, size * 0.13f, paint);
        paint.setColor(0xFF313536);
        canvas.drawRoundRect(new RectF(x - size * 0.25f, y - size * 1.38f, x + size * 0.25f, y - size * 0.88f), size * 0.10f, size * 0.10f, paint);
        paint.setColor(0xFFEEE5C9);
        canvas.drawRect(x - size * 0.40f, y - size * 0.10f, x + size * 0.40f, y + size * 0.28f, paint);
        canvas.restore();
    }

    private void drawBag(Canvas canvas, float x, float y, float size, boolean opensRight) {
        Path bag = new Path();
        bag.moveTo(x - size * 0.55f, y - size * 0.20f);
        bag.quadTo(x - size * 0.45f, y + size * 0.55f, x, y + size * 0.62f);
        bag.quadTo(x + size * 0.45f, y + size * 0.55f, x + size * 0.55f, y - size * 0.20f);
        bag.quadTo(x, y - size * 0.42f, x - size * 0.55f, y - size * 0.20f);
        paint.setColor(0xFFB7894E);
        canvas.drawPath(bag, paint);
        stroke.setStrokeWidth(size * 0.08f);
        stroke.setColor(0xFF5B3D21);
        canvas.drawPath(bag, stroke);
        stroke.setStrokeWidth(size * 0.045f);
        canvas.drawLine(x - size * 0.42f, y - size * 0.08f, x + size * 0.42f, y - size * 0.08f, stroke);
        drawCenteredText(canvas, opensRight ? "▶" : "◀", x, y + size * 0.28f, size * 0.28f, 0xFF5B3D21);
    }

    private void drawCauldron(Canvas canvas, float x, float y, float size, float remaining) {
        paint.setColor(0xFF1E2224);
        RectF body = new RectF(x - size, y - size * 0.35f, x + size, y + size * 0.72f);
        canvas.drawRoundRect(body, size * 0.32f, size * 0.32f, paint);
        paint.setColor(0xFF555D60);
        canvas.drawOval(new RectF(x - size * 1.05f, y - size * 0.55f, x + size * 1.05f, y - size * 0.05f), paint);
        paint.setColor(0xFFFF8B24);
        canvas.drawOval(new RectF(x - size * 0.82f, y - size * 0.43f, x + size * 0.82f, y - size * 0.14f), paint);
        stroke.setStrokeWidth(size * 0.16f);
        stroke.setColor(0xFF777F82);
        canvas.drawArc(new RectF(x - size * 1.35f, y - size * 0.20f, x - size * 0.70f, y + size * 0.55f), 80, 210, false, stroke);
        canvas.drawArc(new RectF(x + size * 0.70f, y - size * 0.20f, x + size * 1.35f, y + size * 0.55f), -110, 210, false, stroke);
        drawFire(canvas, x, y + size * 0.90f, size * 0.62f, remaining);
        drawCountdownRing(canvas, x, y, size * 1.38f, remaining, 0xFFFFB431);
    }

    private void drawFire(Canvas canvas, float x, float y, float size, float remaining) {
        Path outer = new Path();
        outer.moveTo(x, y - size * 1.25f);
        outer.cubicTo(x + size * 0.95f, y - size * 0.40f, x + size * 0.72f, y + size * 0.88f, x, y + size);
        outer.cubicTo(x - size * 0.72f, y + size * 0.88f, x - size * 0.95f, y - size * 0.15f, x - size * 0.28f, y - size * 0.58f);
        outer.cubicTo(x - size * 0.12f, y - size * 0.18f, x + size * 0.10f, y - size * 0.62f, x, y - size * 1.25f);
        paint.setColor(0xFFFF4C24);
        canvas.drawPath(outer, paint);

        Path inner = new Path();
        inner.moveTo(x, y - size * 0.60f);
        inner.cubicTo(x + size * 0.45f, y - size * 0.10f, x + size * 0.38f, y + size * 0.60f, x, y + size * 0.72f);
        inner.cubicTo(x - size * 0.40f, y + size * 0.55f, x - size * 0.35f, y + size * 0.02f, x, y - size * 0.60f);
        paint.setColor(0xFFFFC52D);
        canvas.drawPath(inner, paint);
        drawCountdownRing(canvas, x, y, size * 1.48f, remaining, 0xFF58D9FF);
    }

    private void drawCountdownRing(Canvas canvas, float x, float y, float radius, float remaining, int color) {
        stroke.setStrokeWidth(Math.max(4f, width * 0.011f));
        stroke.setColor(0x44FFFFFF);
        canvas.drawCircle(x, y, radius, stroke);
        stroke.setColor(color);
        canvas.drawArc(new RectF(x - radius, y - radius, x + radius, y + radius), -90f, 360f * Math.max(0f, remaining), false, stroke);
    }

    private void drawLog(Canvas canvas, float x, float y, float size, float rotation) {
        canvas.save();
        canvas.rotate(rotation, x, y);
        paint.setColor(0xFF8A5128);
        RectF body = new RectF(x - size, y - size * 0.32f, x + size, y + size * 0.32f);
        canvas.drawRoundRect(body, size * 0.25f, size * 0.25f, paint);
        paint.setColor(0xFFBC7A3C);
        canvas.drawOval(new RectF(x - size * 1.05f, y - size * 0.34f, x - size * 0.62f, y + size * 0.34f), paint);
        canvas.drawOval(new RectF(x + size * 0.62f, y - size * 0.34f, x + size * 1.05f, y + size * 0.34f), paint);
        stroke.setStrokeWidth(size * 0.07f);
        stroke.setColor(0xFF633516);
        canvas.drawLine(x - size * 0.35f, y - size * 0.22f, x + size * 0.30f, y + size * 0.16f, stroke);
        canvas.drawLine(x - size * 0.12f, y + size * 0.20f, x + size * 0.45f, y - size * 0.12f, stroke);
        canvas.restore();
    }

    private void drawHoseNozzle(Canvas canvas, float x, float y, float size, float rotation) {
        canvas.save();
        canvas.rotate(rotation, x, y);
        paint.setColor(0xFF1A2024);
        canvas.drawRoundRect(new RectF(x - size, y - size * 0.22f, x + size * 0.60f, y + size * 0.22f), size * 0.15f, size * 0.15f, paint);
        paint.setColor(0xFF89979D);
        canvas.drawRoundRect(new RectF(x + size * 0.45f, y - size * 0.30f, x + size, y + size * 0.30f), size * 0.12f, size * 0.12f, paint);
        stroke.setStrokeWidth(size * 0.30f);
        stroke.setColor(0xFF273137);
        canvas.drawArc(new RectF(x - size * 1.35f, y - size * 0.1f, x - size * 0.25f, y + size * 1.1f), 160, 120, false, stroke);
        canvas.restore();
    }

    private void drawPanel(Canvas canvas, RectF rect, int borderColor, int fillColor, float borderWidth) {
        paint.setColor(0x55000000);
        canvas.drawRoundRect(new RectF(rect.left + borderWidth, rect.top + borderWidth * 1.6f, rect.right + borderWidth, rect.bottom + borderWidth * 1.6f), borderWidth * 2.2f, borderWidth * 2.2f, paint);
        paint.setColor(fillColor);
        canvas.drawRoundRect(rect, borderWidth * 2.2f, borderWidth * 2.2f, paint);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(borderWidth);
        stroke.setColor(borderColor);
        canvas.drawRoundRect(rect, borderWidth * 2.2f, borderWidth * 2.2f, stroke);
    }

    private void drawButton(Canvas canvas, RectF rect, String label, boolean green) {
        int top = green ? 0xFF78C82E : 0xFF515A61;
        int bottom = green ? 0xFF2D781A : 0xFF252B2F;
        paint.setShader(new LinearGradient(0, rect.top, 0, rect.bottom, top, bottom, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(rect, rect.height() * 0.25f, rect.height() * 0.25f, paint);
        paint.setShader(null);
        stroke.setStrokeWidth(Math.max(3f, width * 0.008f));
        stroke.setColor(green ? 0xFFDFFF7B : 0xFF9DA6AC);
        canvas.drawRoundRect(rect, rect.height() * 0.25f, rect.height() * 0.25f, stroke);
        drawCenteredText(canvas, label, rect.centerX(), rect.centerY() + rect.height() * 0.12f, Math.min(width * 0.045f, rect.height() * 0.42f), Color.WHITE);
    }

    private void drawSmallButton(Canvas canvas, RectF rect, String label) {
        paint.setColor(0xFF252A2F);
        canvas.drawRoundRect(rect, rect.height() * 0.22f, rect.height() * 0.22f, paint);
        stroke.setStrokeWidth(Math.max(3f, width * 0.007f));
        stroke.setColor(0xFFFFC32D);
        canvas.drawRoundRect(rect, rect.height() * 0.22f, rect.height() * 0.22f, stroke);
        drawCenteredText(canvas, label, rect.centerX(), rect.centerY() + rect.height() * 0.14f, rect.height() * 0.46f, Color.WHITE);
    }

    private void drawCenteredText(Canvas canvas, String value, float x, float baseline, float size, int color) {
        text.setTextSize(Math.max(10f, size));
        text.setColor(color);
        text.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(value, x, baseline, text);
    }

    private float drawWrappedCenteredText(Canvas canvas, String value, float centerX, float startY, float maxWidth, float size, int color, float lineHeight) {
        smallText.setTextSize(Math.max(10f, size));
        smallText.setColor(color);
        smallText.setTextAlign(Paint.Align.CENTER);
        String[] words = value.split(" ");
        StringBuilder line = new StringBuilder();
        float y = startY;
        for (String word : words) {
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (smallText.measureText(candidate) > maxWidth && line.length() > 0) {
                canvas.drawText(line.toString(), centerX, y, smallText);
                y += lineHeight;
                line.setLength(0);
                line.append(word);
            } else {
                line.setLength(0);
                line.append(candidate);
            }
        }
        if (line.length() > 0) {
            canvas.drawText(line.toString(), centerX, y, smallText);
            y += lineHeight;
        }
        return y;
    }

    private int heroColor(Hero h) {
        if (h == Hero.LOZHKIN) {
            return 0xFF6BB24A;
        }
        if (h == Hero.RAK) {
            return 0xFFFFBE2D;
        }
        return 0xFF39A8E1;
    }

    private void softTone(int tone, int duration) {
        if (!soundEnabled) {
            return;
        }
        createToneGenerator();
        if (toneGenerator != null) {
            try {
                toneGenerator.startTone(tone, duration);
            } catch (RuntimeException ignored) {
                // Audio is optional; gameplay must never crash because of it.
            }
        }
    }

    private void vibrate(long milliseconds) {
        if (!vibrationEnabled) {
            return;
        }
        try {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(milliseconds, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    vibrator.vibrate(milliseconds);
                }
            }
        } catch (RuntimeException ignored) {
            // Haptics are optional.
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() != MotionEvent.ACTION_DOWN) {
            return true;
        }
        float x = event.getX();
        float y = event.getY();

        if (dialog != Dialog.NONE) {
            handleDialogTouch(x, y);
            return true;
        }

        if (screen == Screen.MENU) {
            handleMenuTouch(x, y);
            return true;
        }

        if (screen != Screen.PLAYING) {
            handleOverlayTouch(x, y);
            return true;
        }

        if (y < height * 0.14f) {
            if (x < width * 0.18f) {
                goToMenu();
            } else if (x > width * 0.82f) {
                screen = Screen.PAUSED;
                softTone(ToneGenerator.TONE_PROP_BEEP2, 60);
            }
            return true;
        }

        int lane = laneFromTouch(x, y);
        if (hero == Hero.LOZHKIN) {
            selectedLane = lane;
            softTone(ToneGenerator.TONE_PROP_PROMPT, 25);
            vibrate(16);
        } else {
            performPromptAction(lane);
        }
        return true;
    }

    private void handleMenuTouch(float x, float y) {
        if (menuBitmap == null || menuDst.width() <= 0f || !menuDst.contains(x, y)) {
            return;
        }
        float sx = (x - menuDst.left) * menuBitmap.getWidth() / menuDst.width();
        float sy = (y - menuDst.top) * menuBitmap.getHeight() / menuDst.height();

        if (sx < 125 && sy < 140) {
            dialog = Dialog.SETTINGS;
            return;
        }
        if (sx > 815 && sy < 140) {
            dialog = Dialog.INFO;
            return;
        }

        if (sy > 500 && sy < 1435) {
            if (sx < 310) {
                startHero(Hero.LOZHKIN);
                return;
            }
            if (sx < 620) {
                startHero(Hero.RAK);
                return;
            }
            startHero(Hero.KOROLEV);
            return;
        }

        if (sy > 1490) {
            if (sx < 235) {
                dialog = Dialog.RECORDS;
            } else if (sx < 470) {
                dialog = Dialog.ACHIEVEMENTS;
            } else if (sx < 705) {
                dialog = Dialog.RATING;
            } else {
                dialog = Dialog.SHOP;
            }
        }
    }

    private void handleDialogTouch(float x, float y) {
        if (dialog == Dialog.SETTINGS) {
            if (settingsRow(0).contains(x, y)) {
                soundEnabled = !soundEnabled;
                preferences.edit().putBoolean(KEY_SOUND, soundEnabled).apply();
                if (soundEnabled) {
                    createToneGenerator();
                    softTone(ToneGenerator.TONE_PROP_ACK, 80);
                } else {
                    releaseToneGenerator();
                }
                return;
            }
            if (settingsRow(1).contains(x, y)) {
                vibrationEnabled = !vibrationEnabled;
                preferences.edit().putBoolean(KEY_VIBRATION, vibrationEnabled).apply();
                if (vibrationEnabled) {
                    vibrate(35);
                }
                return;
            }
        }
        if (dialogCloseRect().contains(x, y)) {
            dialog = Dialog.NONE;
            softTone(ToneGenerator.TONE_PROP_BEEP, 40);
        }
    }

    private void handleOverlayTouch(float x, float y) {
        if (overlayPrimaryRect().contains(x, y)) {
            if (screen == Screen.PAUSED) {
                screen = Screen.PLAYING;
                lastSpawnTime = SystemClock.uptimeMillis();
            } else if (screen == Screen.LEVEL_COMPLETE) {
                startNextLevel();
            } else {
                retryCurrentHero();
            }
            return;
        }
        if (overlaySecondaryRect().contains(x, y)) {
            goToMenu();
        }
    }

    private int laneFromTouch(float x, float y) {
        boolean left = x < width * 0.5f;
        boolean upper = y < height * 0.58f;
        if (left) {
            return upper ? 0 : 1;
        }
        return upper ? 2 : 3;
    }

    private void performPromptAction(int lane) {
        PromptItem match = null;
        for (PromptItem item : prompts) {
            if (item.lane == lane && (match == null || item.age > match.age)) {
                match = item;
            }
        }
        if (match == null) {
            wrongUntil = SystemClock.uptimeMillis() + 90;
            softTone(ToneGenerator.TONE_PROP_NACK, 40);
            return;
        }
        prompts.remove(match);
        actionEffects.add(new ActionEffect(lane, hero == Hero.KOROLEV));
        onSuccess(lane, 1, false);
    }

    private static final class MovingItem {
        final int lane;
        final float durationSeconds;
        final boolean golden;
        float progress;
        float rotation;

        MovingItem(int lane, float durationSeconds, boolean golden) {
            this.lane = lane;
            this.durationSeconds = durationSeconds;
            this.golden = golden;
        }
    }

    private static final class PromptItem {
        final int lane;
        final float lifeSeconds;
        float age;

        PromptItem(int lane, float lifeSeconds) {
            this.lane = lane;
            this.lifeSeconds = lifeSeconds;
        }
    }

    private static final class Particle {
        float x;
        float y;
        float vx;
        float vy;
        final float gravity;
        float life;
        final float maxLife;
        final float size;
        final int color;
        float rotation;
        final float spin;

        Particle(float x, float y, float vx, float vy, float gravity, float life, float size, int color, float rotation, float spin) {
            this.x = x;
            this.y = y;
            this.vx = vx;
            this.vy = vy;
            this.gravity = gravity;
            this.life = life;
            this.maxLife = life;
            this.size = size;
            this.color = color;
            this.rotation = rotation;
            this.spin = spin;
        }
    }

    private static final class ActionEffect {
        final int lane;
        final boolean water;
        final float life;
        float age;

        ActionEffect(int lane, boolean water) {
            this.lane = lane;
            this.water = water;
            this.life = water ? 0.34f : 0.48f;
        }
    }
}
