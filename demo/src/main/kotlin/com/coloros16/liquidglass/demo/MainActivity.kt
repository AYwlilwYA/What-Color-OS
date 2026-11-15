package com.coloros16.liquidglass.demo

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast

/**
 * 液态玻璃 demo 入口：全屏 [LiquidGlassDemoView] + 右上角 SF 通道探针按钮。
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 隐藏系统栏（API 35+ edge-to-edge 强制，需显式隐藏以最大化可视区域）
        if (Build.VERSION.SDK_INT >= 35) {
            window.decorView.windowInsetsController?.apply {
                hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }

        val root = FrameLayout(this)
        val glassView = LiquidGlassDemoView(this)
        glassView.backdropAlpha = 140
        root.addView(
            glassView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        val btn = Button(this).apply {
            text = "SF 通道"
            setOnClickListener {
                val err = SfBlurProbe.apply(this@MainActivity, 60)
                Toast.makeText(
                    this@MainActivity,
                    if (err == null) "申报成功（无异常）" else "失败：$err",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
        root.addView(
            btn,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END,
            ).apply {
                topMargin = 120
                rightMargin = 40
            },
        )
        setContentView(root)
    }
}
