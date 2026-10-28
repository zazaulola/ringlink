package io.github.ringlink.weather

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream

/** Outdoor temperature by hour, from one weather app report. */
data class WeatherReport(
    val location: String?,
    /** Hour start (wall-clock millis) to °C. */
    val hours: Map<Long, Double>,
)

/**
 * Reads the weather that apps such as Breezy Weather and QuickWeather broadcast for Gadgetbridge.
 *
 * The format is Gadgetbridge's WeatherSpec: temperatures in kelvin, times in Unix seconds, the
 * current conditions at the time of the forecast update and an hourly forecast ahead. Anything on
 * the phone can send this broadcast, so everything is checked before it is believed: a temperature
 * no weather has ever had, or a time far from now, and the report is dropped. At worst a forged
 * report nudges where a ring is judged worn — it touches nothing else.
 */
object WeatherParser {

    /** More than any real report; a compressed one is refused if it unpacks past this. */
    const val MAX_BYTES = 512 * 1024

    private const val HOUR_MS = 3_600_000L
    private const val KELVIN = 273.15
    private val PLAUSIBLE_C = -90.0..60.0
    private const val PAST_MS = 2 * 24 * HOUR_MS
    /** Weather apps refresh every few hours; a forecast further ahead than this is not believed. */
    private const val FUTURE_MS = 2 * 24 * HOUR_MS
    /** The current conditions count as now only when the report is this fresh. */
    private const val CURRENT_FRESH_MS = HOUR_MS

    /** The primary location, from either the plain or the gzipped form of the broadcast. */
    fun parse(json: String?, gz: ByteArray?, now: Long): WeatherReport? {
        val spec = when {
            json != null -> if (json.length > MAX_BYTES) null else runCatching { JSONObject(json) }.getOrNull()
            gz != null -> gunzip(gz)?.let { text ->
                runCatching { JSONArray(text).optJSONObject(0) }.getOrNull()
            }
            else -> null
        } ?: return null
        return parseSpec(spec, now)
    }

    fun parseSpec(spec: JSONObject, now: Long): WeatherReport? {
        val hours = HashMap<Long, Double>()
        fun put(seconds: Long, kelvin: Double) {
            if (seconds <= 0 || kelvin.isNaN()) return
            val at = seconds * 1000
            if (at < now - PAST_MS || at > now + FUTURE_MS) return
            val c = kelvin - KELVIN
            if (c !in PLAUSIBLE_C) return
            hours[Math.floorDiv(at, HOUR_MS) * HOUR_MS] = c
        }
        val hourly = spec.optJSONArray("hourly")
        if (hourly != null) {
            for (i in 0 until hourly.length()) {
                val h = hourly.optJSONObject(i) ?: continue
                if (h.has("timestamp") && h.has("temp")) put(h.optLong("timestamp"), h.optDouble("temp"))
            }
        }
        // The current temperature is measured, not forecast, and wins over a forecast for its hour —
        // the hour it was sent in. The report's timestamp is when the forecast was last updated,
        // which can be older than the current conditions beside it; a report older than an hour
        // (re-sent from storage) has no current conditions worth the name.
        if (spec.has("timestamp") && spec.has("currentTemp")) {
            val stamp = spec.optLong("timestamp") * 1000
            if (now - stamp in 0..CURRENT_FRESH_MS) put(now / 1000, spec.optDouble("currentTemp"))
        }
        if (hours.isEmpty()) return null
        val location = spec.optString("location", "").takeIf { it.isNotBlank() }?.take(80)
        return WeatherReport(location, hours)
    }

    /** Unpacks at most [MAX_BYTES]; a larger payload is refused rather than read to the end. */
    fun gunzip(bytes: ByteArray): String? = runCatching {
        GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (out.size() + n > MAX_BYTES) return null
                out.write(buffer, 0, n)
            }
            out.toString(Charsets.UTF_8.name())
        }
    }.getOrNull()
}
