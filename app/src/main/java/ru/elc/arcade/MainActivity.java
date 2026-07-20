package ru.elc.arcade;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private static final String TAG = "ELC_RUNTIME";
    private GameView gameView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUi();
        try {
            gameView = new GameView(this);
            setContentView(gameView);
            applySmokeIntent(getIntent());
        } catch (RuntimeException runtimeException) {
            Log.e(TAG, "STARTUP_ERROR", runtimeException);
            showStartupError();
        }
    }


    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        applySmokeIntent(intent);
    }

    private void applySmokeIntent(Intent intent) {
        if (gameView == null || intent == null || !isDebuggable()) {
            return;
        }
        final String command = intent.getStringExtra("elc_smoke");
        if (command == null || command.trim().isEmpty()) {
            return;
        }
        gameView.post(new Runnable() {
            @Override
            public void run() {
                gameView.runSmokeCommand(command);
            }
        });
    }

    private boolean isDebuggable() {
        return (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    private void showStartupError() {
        TextView error = new TextView(this);
        error.setBackgroundColor(Color.rgb(14, 18, 21));
        error.setTextColor(Color.WHITE);
        error.setGravity(Gravity.CENTER);
        error.setTextSize(22f);
        error.setPadding(48, 48, 48, 48);
        error.setText("ЕЛЦ\n\nНе удалось запустить игровой экран.\nПерезапусти приложение.\n\nВерсия 1.2.0");
        setContentView(error);
    }

    private void hideSystemUi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                );
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            );
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUi();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (gameView != null) {
            gameView.onHostPause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        if (gameView != null) {
            gameView.onHostResume();
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (gameView != null && gameView.handleBack()) {
            return;
        }
        super.onBackPressed();
    }
}
