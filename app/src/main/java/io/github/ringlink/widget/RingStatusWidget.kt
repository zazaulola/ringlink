package io.github.ringlink.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Build
import android.os.PowerManager
import android.text.format.DateFormat
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import io.github.ringlink.L
import io.github.ringlink.R
import io.github.ringlink.ble.RingService
import io.github.ringlink.data.RingDatabase
import io.github.ringlink.data.Settings
import io.github.ringlink.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A home-screen widget with each ring's charge and state and its charging case.
 *
 * Drawn by the app, not on a timer: the service asks for a redraw whenever a ring changes, a redraw
 * that would show the same words is skipped, and one is booked for the moment the words would
 * change on their own — a ring going quiet, an "… ago" moving on. The system's half-hourly update
 * covers the times the app is not running.
 */
class RingStatusWidget : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH) {
            RingService.refresh(context, "widget")
            request(context)
            return
        }
        super.onReceive(context, intent)
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        scope.launch {
            try {
                draw(context.applicationContext, force = true)
            } finally {
                pending.finish()
            }
        }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: android.os.Bundle) {
        request(context)
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val renderLock = Mutex()
        private val dirty = AtomicBoolean(false)
        @Volatile private var lastDrawn: List<String>? = null
        @Volatile private var dueJob: Job? = null

        private data class Row(val root: Int, val name: Int, val battery: Int, val status: Int, val case: Int, val line: Int)

        private val ROWS = listOf(
            Row(R.id.ring1, R.id.ring1_name, R.id.ring1_battery, R.id.ring1_status, R.id.ring1_case, R.id.ring1_line),
            Row(R.id.ring2, R.id.ring2_name, R.id.ring2_battery, R.id.ring2_status, R.id.ring2_case, R.id.ring2_line),
            Row(R.id.ring3, R.id.ring3_name, R.id.ring3_battery, R.id.ring3_status, R.id.ring3_case, R.id.ring3_line),
        )

        /** How soon a change is drawn: changes come in bursts, and one redraw covers a burst. */
        private const val SETTLE_MS = 2_000L

        /**
         * Ask for a redraw soon. A request made while one is pending is covered by it; the flag is
         * cleared before the state is read, so a change landing during a redraw asks for another.
         */
        fun request(context: Context) {
            if (dirty.getAndSet(true)) return
            val app = context.applicationContext
            scope.launch {
                delay(SETTLE_MS)
                dirty.set(false)
                draw(app, force = false)
            }
        }

        private suspend fun draw(app: Context, force: Boolean) {
            try {
                val due = render(app, force)
                dueJob?.cancel()
                if (due != null) {
                    dueJob = scope.launch {
                        delay((due - System.currentTimeMillis()).coerceAtLeast(SETTLE_MS))
                        draw(app, force = false)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A widget is not worth the service it shares the process with.
                L.e("widget: redraw failed", e)
            }
        }

        /** Returns when the words drawn will next change by themselves, if they will. */
        private suspend fun render(context: Context, force: Boolean): Long? = renderLock.withLock {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, RingStatusWidget::class.java))
            if (ids.isEmpty()) return@withLock null
            // Nobody is looking. The screen coming on asks for a redraw, and for fresh state.
            if (!force && !context.getSystemService(PowerManager::class.java).isInteractive) return@withLock null
            val now = System.currentTimeMillis()
            val settings = Settings(context)
            val running = RingService.instance != null
            val tiles = tiles(context, settings, running)
            val low = settings.lowBatteryPercent
            val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            val asOf = tiles.mapNotNull { it.lastSeenAt }.maxOrNull()
            val title = context.getString(R.string.app_name) +
                (asOf?.let { " · " + DateFormat.getTimeFormat(context).format(Date(it)) } ?: "")
            val words = listOf("running=$running", "night=$night", title) + tiles.map { t ->
                listOf(t.name, RingTileText.battery(t), RingTileText.status(t, now), RingTileText.case(t, now), RingTileText.tone(t, low, now))
                    .joinToString("|")
            }
            if (force || words != lastDrawn) {
                manager.updateAppWidget(ids, views(context, tiles, low, now, running, title))
                lastDrawn = words
            }
            RingTileText.nextChange(tiles, now)
        }

        private suspend fun tiles(context: Context, settings: Settings, running: Boolean): List<RingTile> {
            val dao = RingDatabase.get(context).dao()
            // Only the service holds a link. Without it no ring is heard, however recent its row.
            val live = if (running) RingService.state.value else null
            return settings.rings.take(ROWS.size).map { ring ->
                val latest = dao.latestDeviceStateNow(ring.address)
                val case = dao.latestCaseReading(ring.address)
                val state = live?.ringOrNull(ring.address)
                // Set only by a reading this run of the service has handled.
                val judged = state?.skinTemp != null
                RingTile(
                    name = ring.shortName,
                    battery = latest?.batteryPercent ?: state?.battery,
                    connected = state?.connected == true,
                    charging = if (judged) state!!.onCharger else latest?.onCharger ?: false,
                    wearKnown = judged,
                    worn = state?.worn == true,
                    lastSeenAt = latest?.recordedAt,
                    docked = latest?.caseBattery != null,
                    casePercent = case?.caseBattery,
                    casePlugged = case?.caseCharging == true,
                    caseSeenAt = case?.recordedAt,
                )
            }
        }

        private fun views(context: Context, tiles: List<RingTile>, low: Int, now: Long, running: Boolean, title: String): RemoteViews {
            val full = fullViews(context, tiles, low, now, running, title)
            if (Build.VERSION.SDK_INT < 31) return full
            // The launcher picks the larger layout that fits the widget's size.
            val rows = tiles.size.coerceAtLeast(1)
            return RemoteViews(
                mapOf(
                    SizeF(120f, 24f + 20f * rows) to compactViews(context, tiles, low, now, running),
                    SizeF(160f, 40f + 46f * rows) to full,
                ),
            )
        }

        private fun fullViews(context: Context, tiles: List<RingTile>, low: Int, now: Long, running: Boolean, title: String): RemoteViews {
            val v = RemoteViews(context.packageName, R.layout.widget_ring_status)
            v.setTextViewText(R.id.widget_title, if (running) title else NOT_RUNNING)
            v.setOnClickPendingIntent(R.id.widget_refresh, refresh(context))
            setColor(context, v, R.id.widget_title, if (running) R.color.widget_dim else R.color.widget_low)
            v.setViewVisibility(R.id.widget_empty, if (tiles.isEmpty()) View.VISIBLE else View.GONE)
            v.setTextViewText(R.id.widget_empty, NO_RING)
            ROWS.forEachIndexed { i, row ->
                val t = tiles.getOrNull(i)
                if (t == null) {
                    v.setViewVisibility(row.root, View.GONE)
                    return@forEachIndexed
                }
                v.setViewVisibility(row.root, View.VISIBLE)
                v.setTextViewText(row.name, t.name)
                v.setTextViewText(row.battery, RingTileText.battery(t))
                setColor(context, v, row.battery, toneColor(RingTileText.tone(t, low, now)))
                v.setTextViewText(row.status, RingTileText.status(t, now))
                val case = RingTileText.case(t, now)
                v.setViewVisibility(row.case, if (case == null) View.GONE else View.VISIBLE)
                v.setTextViewText(row.case, case ?: "")
            }
            v.setOnClickPendingIntent(android.R.id.background, openApp(context))
            return v
        }

        private fun compactViews(context: Context, tiles: List<RingTile>, low: Int, now: Long, running: Boolean): RemoteViews {
            val v = RemoteViews(context.packageName, R.layout.widget_ring_status_compact)
            v.setViewVisibility(R.id.widget_title, if (running) View.GONE else View.VISIBLE)
            v.setTextViewText(R.id.widget_title, NOT_RUNNING)
            v.setViewVisibility(R.id.widget_empty, if (tiles.isEmpty()) View.VISIBLE else View.GONE)
            v.setTextViewText(R.id.widget_empty, NO_RING)
            ROWS.forEachIndexed { i, row ->
                val t = tiles.getOrNull(i)
                v.setViewVisibility(row.line, if (t == null) View.GONE else View.VISIBLE)
                if (t == null) return@forEachIndexed
                val line = SpannableStringBuilder()
                line.append(t.name, StyleSpan(Typeface.BOLD), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                line.append("  ")
                val tone = RingTileText.tone(t, low, now)
                val battery = RingTileText.battery(t)
                if (tone == BatteryTone.LOW || tone == BatteryTone.CHARGING) {
                    // Spans carry a fixed colour, so only the two tones that read on either theme.
                    val color = if (tone == BatteryTone.LOW) SPAN_LOW else SPAN_CHARGING
                    line.append(battery, ForegroundColorSpan(color), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                } else {
                    line.append(battery, StyleSpan(Typeface.BOLD), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                line.append("  ").append(RingTileText.status(t, now))
                RingTileText.case(t, now)?.let { line.append(" · ").append(it) }
                v.setTextViewText(row.line, line)
            }
            v.setOnClickPendingIntent(android.R.id.background, openApp(context))
            return v
        }

        private fun toneColor(tone: BatteryTone) = when (tone) {
            BatteryTone.LOW -> R.color.widget_low
            BatteryTone.CHARGING -> R.color.widget_charging
            BatteryTone.NORMAL -> R.color.widget_text
            BatteryTone.UNKNOWN -> R.color.widget_dim
        }

        private fun refresh(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, 0,
            Intent(context, RingStatusWidget::class.java).setAction(ACTION_REFRESH),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        /**
         * By resource where the launcher can resolve it, so the colours follow its light or dark
         * theme. Older launchers get the colour as it is now; the theme is part of what decides a
         * redraw, so a switch is redrawn.
         */
        private fun setColor(context: Context, v: RemoteViews, id: Int, color: Int) {
            if (Build.VERSION.SDK_INT >= 31) {
                v.setColorStateList(id, "setTextColor", color)
            } else {
                v.setTextColor(id, context.getColor(color))
            }
        }

        private const val ACTION_REFRESH = "io.github.ringlink.widget.REFRESH"
        private const val NOT_RUNNING = "RingLink is not running — tap to open it"
        private const val NO_RING = "No ring added yet — open RingLink to add one."
        private const val SPAN_LOW = 0xFFE53935.toInt()
        private const val SPAN_CHARGING = 0xFF43A047.toInt()
    }
}
