package studio.alans.meallog

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The sheet over CamAPS after a meal bolus. Dismissing it is always one tap: X, "Not now", the dim
 * area above it or Back. It closes itself after logging.
 */
class SheetActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        getSystemService(NotificationManager::class.java).cancel(Notify.SHEET)
        val at = intent.getLongExtra("at", System.currentTimeMillis())
        val units = intent.getDoubleExtra("units", 0.0)
        val carbs = intent.getDoubleExtra("carbs", -1.0).takeIf { it >= 0 }
        setContent {
            MealTheme {
                Box(Modifier.fillMaxSize().background(Color(0x66000000))
                    .clickable(remember { MutableInteractionSource() }, null) { finish() },
                    contentAlignment = Alignment.BottomCenter) {
                    Surface(
                        Modifier.fillMaxWidth().imePadding()
                            .clickable(remember { MutableInteractionSource() }, null) { },
                        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                        tonalElevation = 3.dp,
                    ) {
                        Box(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState())) {
                            MealPanel(at, carbs, units, sheet = true) { finish() }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }

    companion object {
        fun intent(c: Context, at: Long, units: Double, carbs: Double): Intent =
            Intent(c, SheetActivity::class.java).putExtra("at", at).putExtra("units", units).putExtra("carbs", carbs)
    }
}
