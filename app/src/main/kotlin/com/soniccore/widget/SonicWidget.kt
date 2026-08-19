package com.soniccore.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.compose.ui.res.stringResource
import com.soniccore.core.ui.R
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.soniccore.MainActivity
import com.soniccore.core.data.repository.ProfileRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Home-screen widget built with Glance.
 *
 * Widgets run outside any activity, so dependencies come through a Hilt entry
 * point rather than constructor injection.
 */
class SonicWidget : GlanceAppWidget() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WidgetDependencies {
        fun profileRepository(): ProfileRepository
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val deps = EntryPointAccessors.fromApplication(
            context.applicationContext,
            WidgetDependencies::class.java,
        )
        val profiles = runCatching { deps.profileRepository().getAll() }.getOrDefault(emptyList())
        val active = profiles.firstOrNull { it.isActive }

        provideContent {
            GlanceTheme {
                WidgetContent(
                    activeProfileName = active?.name,
                    eqEnabled = active?.eq?.enabled == true,
                    profileCount = profiles.size,
                )
            }
        }
    }
}

private val Accent = ColorProvider(Color(0xFF4F8CFF))
private val Primary = ColorProvider(Color(0xFFE8ECF4))
private val Secondary = ColorProvider(Color(0xFFB6BECC))

@Composable
private fun WidgetContent(
    activeProfileName: String?,
    eqEnabled: Boolean,
    profileCount: Int,
) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(Color(0xFF11151F))
            .cornerRadius(16.dp)
            .padding(12.dp)
            .clickable(actionStartActivity<MainActivity>()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "SonicCore",
            style = TextStyle(color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Medium),
        )
        Spacer(GlanceModifier.height(4.dp))
        Text(
            text = activeProfileName ?: "No profile active",
            style = TextStyle(color = Primary, fontSize = 16.sp, fontWeight = FontWeight.Bold),
        )
        Spacer(GlanceModifier.height(3.dp))
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            Text(
                text = if (eqEnabled) "EQ on" else "EQ off",
                style = TextStyle(color = Secondary, fontSize = 11.sp),
            )
            Spacer(GlanceModifier.width(10.dp))
            Text(
                text = stringResource(R.string.widget_profiles_count, profileCount),
                style = TextStyle(color = Secondary, fontSize = 11.sp),
            )
        }
    }
}

class SonicWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SonicWidget()
}
