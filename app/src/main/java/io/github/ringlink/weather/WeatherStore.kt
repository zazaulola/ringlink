package io.github.ringlink.weather

import io.github.ringlink.data.RingDao
import io.github.ringlink.data.WeatherHourEntity
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.ConcurrentHashMap

/**
 * Outdoor temperature by hour, as weather apps have reported it.
 *
 * Each report replaces what an earlier one said about the same hours: a later forecast is a better
 * one, and the current conditions are a measurement. A recent copy is kept in memory for judging
 * live readings, which arrive every few seconds and should not each cost a query.
 */
class WeatherStore(private val dao: RingDao) {

    suspend fun save(report: WeatherReport, receivedAt: Long) {
        dao.upsertWeather(report.hours.map { (hour, c) -> WeatherHourEntity(hour, c, receivedAt) })
        if (enabled) report.hours.forEach { (hour, c) -> recent[hour] = c to receivedAt }
        val cutoff = receivedAt - KEEP_MS
        recent.keys.removeIf { it < cutoff }
    }

    suspend fun between(from: Long, to: Long): List<WeatherHourEntity> = dao.weatherBetween(from, to)

    fun atFlow(at: Long): Flow<WeatherHourEntity?> = dao.weatherAtFlow(at)

    /** Load the recent hours into memory, for a process that starts after reports came in. */
    suspend fun warm(now: Long = System.currentTimeMillis()) {
        if (!enabled || recent.isNotEmpty()) return
        dao.weatherBetween(now - KEEP_MS, now + KEEP_MS).forEach { recent[it.hourStart] = it.tempC to it.receivedAt }
    }

    companion object {
        private const val HOUR_MS = 3_600_000L
        private const val KEEP_MS = 2 * 24 * HOUR_MS

        /** A forecast made further ahead of its hour than this is not trusted as that hour's weather. */
        const val MAX_LEAD_MS = 2 * 24 * HOUR_MS

        /** Hour start to °C and when it was received. */
        private val recent = ConcurrentHashMap<Long, Pair<Double, Long>>()

        /**
         * Whether weather is switched on. Off, nothing stored is used — not the cache, not the table —
         * so switching it off takes effect at once, whatever the last report said about days ahead.
         */
        @Volatile var enabled: Boolean = false
            set(on) {
                field = on
                if (!on) recent.clear()
            }

        /** Whether a stored hour is a usable account of that hour's weather. */
        fun usable(hourStart: Long, receivedAt: Long): Boolean = hourStart - receivedAt <= MAX_LEAD_MS

        /** The outdoor temperature in the hour of [at], if weather is on and a report covered it. */
        fun cachedAt(at: Long): Double? {
            if (!enabled) return null
            val hour = Math.floorDiv(at, HOUR_MS) * HOUR_MS
            val (c, received) = recent[hour] ?: return null
            return c.takeIf { usable(hour, received) }
        }
    }
}
