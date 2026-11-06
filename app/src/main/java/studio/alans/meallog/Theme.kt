package studio.alans.meallog

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
fun MealTheme(content: @Composable () -> Unit) {
    val c = LocalContext.current
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(c) else dynamicLightColorScheme(c),
        content = content)
}
