package app.unroll

import android.app.Application
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.unroll.ui.Root
import app.unroll.ui.UnrollTheme

class UnrollApplication : Application() {
    lateinit var state: AppState
        private set

    override fun onCreate() {
        super.onCreate()
        context = this
        state = AppState(this)
    }

    companion object {
        lateinit var context: Context
            private set
    }
}

class MainActivity : ComponentActivity() {
    private val state get() = (application as UnrollApplication).state

    override fun onCreate(savedInstanceState: Bundle?) {
        // Dark-only app: light status / navigation bar icons everywhere.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            // 3-button navigation gets a dark scrim so scrolling content never shows through the
            // buttons (Samsung default); gesture navigation stays fully transparent.
            navigationBarStyle = SystemBarStyle.dark(0xE6000000.toInt()),
        )
        super.onCreate(savedInstanceState)
        handle(intent)
        setContent { UnrollTheme { Root(state) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        state.pro.refresh()
    }

    /** Launched by plugging in a camera: Android has already granted USB access for this device. */
    private fun handle(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        val device: UsbDevice? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
        device?.let { state.usb.attached = it; state.usb.connect(it) }
    }
}
