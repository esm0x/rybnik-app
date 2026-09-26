package com.adminstack.rybnik.widget

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.adminstack.rybnik.Graph
import com.adminstack.rybnik.MainActivity
import com.adminstack.rybnik.data.air.AirState
import com.adminstack.rybnik.data.waste.Collection
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Home-screen widget: the next waste collection and the current PM10.
 *
 * Those two are picked because they are the questions with a deadline. Everything else in
 * the app can wait until you open it; putting the bins out cannot.
 *
 * The widget can be the reason the process starts, so nothing here may assume the app was
 * opened first: the waste cache is re-read on every render, and the air reading is fetched
 * with a timeout so a dead network shows a dash instead of hanging the launcher.
 */
class WasteWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val settings = Graph.prefs.settings.first()

        Graph.wasteRepo.loadCache()
        val next = settings.wasteAddress?.let { address ->
            runCatching { Graph.wasteRepo.schedule().nextCollection(address.rejonId) }
                .getOrNull()
        }

        val air = withTimeoutOrNull(AIR_TIMEOUT_MS) {
            runCatching { Graph.airRepo.refresh() }
            Graph.airRepo.state.value
        } ?: Graph.airRepo.state.value

        provideContent {
            GlanceTheme {
                WidgetBody(
                    next = next,
                    address = settings.wasteAddress?.pretty,
                    air = air,
                )
            }
        }
    }

    private companion object {
        const val AIR_TIMEOUT_MS = 8_000L
    }
}

class WasteWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WasteWidget()
}

private val DAY_FMT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale("pl", "PL"))

@androidx.compose.runtime.Composable
private fun WidgetBody(next: Collection?, address: String?, air: AirState) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .padding(14.dp)
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        Text(
            "Mój Rybnik",
            style = TextStyle(
                fontSize = 12.sp(),
                color = GlanceTheme.colors.onSurfaceVariant,
            ),
        )
        Spacer(GlanceModifier.height(6.dp))

        when {
            address == null -> Text(
                "Wybierz adres w aplikacji, żeby zobaczyć wywóz",
                style = TextStyle(fontSize = 14.sp(), color = GlanceTheme.colors.onSurface),
            )

            next == null -> Text(
                "Brak nadchodzących terminów",
                style = TextStyle(fontSize = 14.sp(), color = GlanceTheme.colors.onSurface),
            )

            else -> {
                Text(
                    next.date.humanLabel(),
                    style = TextStyle(
                        fontSize = 16.sp(),
                        fontWeight = FontWeight.Bold,
                        color = GlanceTheme.colors.onSurface,
                    ),
                )
                Spacer(GlanceModifier.height(2.dp))
                Text(
                    next.types.joinToString(", ") { it.label },
                    style = TextStyle(
                        fontSize = 13.sp(),
                        color = GlanceTheme.colors.onSurfaceVariant,
                    ),
                )
            }
        }

        Spacer(GlanceModifier.height(10.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                air.pm10?.let { "PM10 ${it.value.toInt()} µg/m³" } ?: "PM10 —",
                style = TextStyle(
                    fontSize = 14.sp(),
                    fontWeight = FontWeight.Medium,
                    color = ColorProvider(Color(air.severity.color)),
                ),
            )
            Spacer(GlanceModifier.width(6.dp))
            Text(
                air.severity.label,
                style = TextStyle(
                    fontSize = 12.sp(),
                    color = GlanceTheme.colors.onSurfaceVariant,
                ),
            )
        }
    }
}

/** Glance takes TextUnit, and importing the sp extension collides with Compose UI's. */
private fun Int.sp() = androidx.compose.ui.unit.TextUnit(
    this.toFloat(),
    androidx.compose.ui.unit.TextUnitType.Sp,
)

private fun ColorProvider(color: Color) = androidx.glance.unit.ColorProvider(color)

private fun LocalDate.humanLabel(): String = when (this) {
    LocalDate.now() -> "Dziś"
    LocalDate.now().plusDays(1) -> "Jutro"
    else -> format(DAY_FMT).replaceFirstChar { it.uppercase(Locale("pl", "PL")) }
}
