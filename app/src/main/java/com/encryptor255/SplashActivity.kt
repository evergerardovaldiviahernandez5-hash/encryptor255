package com.encryptor255

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.appcompat.app.AppCompatActivity

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        setContentView(R.layout.activity_splash)

        val icon = findViewById<View>(R.id.splashIcon)
        val bar = findViewById<View>(R.id.splashBar)

        // Fade + scale del icono
        icon.alpha = 0f
        icon.scaleX = 0.86f
        icon.scaleY = 0.86f
        icon.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(900)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()

        // Pulso del icono
        ObjectAnimator.ofFloat(icon, "alpha", 1f, 0.82f, 1f).apply {
            duration = 2200
            repeatCount = ValueAnimator.INFINITE
            startDelay = 900
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }

        // Barra que crece
        bar.scaleX = 0f
        bar.pivotX = 0f
        bar.animate().scaleX(1f).setDuration(1400).setStartDelay(200).start()

        // Salto a MainActivity
        icon.postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
        }, 2000)
    }
}
