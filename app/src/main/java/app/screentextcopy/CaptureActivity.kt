package app.screentextcopy

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Full-screen host for the capture screen, used only by the screen-capture fallback
 * (when the accessibility service is off). With the service on, the same screen is
 * shown as a layer over the current app instead (see TriggerService).
 */
class CaptureActivity : AppCompatActivity(), CaptureHost {

    private lateinit var ui: CaptureUi
    private var loaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        disableTransitions()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        ui = CaptureUi(this, this)
        setContentView(ui.root)
        loadCapture()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        loadCapture()
    }

    private fun loadCapture() {
        val bmp = CaptureHolder.bitmap
        CaptureHolder.bitmap = null
        if (bmp == null) {
            if (!loaded) finish()
            return
        }
        loaded = true
        val words = CaptureHolder.directWords
        CaptureHolder.directWords = emptyList()
        ui.load(bmp, words)
    }

    // ---------- CaptureHost ----------

    override fun close() {
        finish()
    }

    override fun show(builder: MaterialAlertDialogBuilder): AlertDialog = builder.show()

    override val alive: Boolean
        get() = !isFinishing && !isDestroyed

    // ---------- Transitions ----------

    private fun disableTransitions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    override fun finish() {
        super.finish()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    override fun onDestroy() {
        ui.destroy()
        super.onDestroy()
    }
}
