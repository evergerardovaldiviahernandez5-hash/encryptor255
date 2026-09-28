package com.encryptor255

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // ⚠️ installSplashScreen() debe ir ANTES de super.onCreate()
        val splash = installSplashScreen()
        splash.setKeepOnScreenCondition { false }
        super.onCreate(savedInstanceState)

        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        setContentView(R.layout.activity_splash)

        val icon = findViewById<View>(R.id.splashIcon)
        val bar = findViewById<View>(R.id.splashBar)

        icon.alpha = 0f
        icon.scaleX = 0.86f
        icon.scaleY = 0.86f
        icon.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(700)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()

        ObjectAnimator.ofFloat(icon, "alpha", 1f, 0.82f, 1f).apply {
            duration = 2200
            repeatCount = ValueAnimator.INFINITE
            startDelay = 900
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }

        bar.scaleX = 0f
        bar.pivotX = 0f
        bar.animate().scaleX(1f).setDuration(1200).setStartDelay(200).start()

        // 1400ms custom + ~400ms native ≈ 1.8s total
        icon.postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                overrideActivityTransition(
                    OVERRIDE_TRANSITION_OPEN,
                    android.R.anim.fade_in,
                    android.R.anim.fade_out
                )
            } else {
                @Suppress("DEPRECATION")
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            }
            finish()
        }, 1400)
    }
}
