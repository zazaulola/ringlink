package io.github.ringlink.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class WeatherParserTest {

    private val now = 1_793_000_000_000L                  // 2026-10-27
    private val hour = Math.floorDiv(now, 3_600_000L) * 3_600_000L
    private fun s(ms: Long) = ms / 1000

    /** Shaped like Breezy Weather's broadcast: kelvin, Unix seconds, current plus hourly. */
    private val spec = """
        {"timestamp":${s(now - 600_000)},"location":"Moscow","currentTemp":270,
         "hourly":[{"timestamp":${s(hour)},"temp":271},{"timestamp":${s(hour + 3_600_000)},"temp":272}]}
    """.trimIndent()

    @Test fun `kelvin becomes celsius by hour, and the current temperature wins its hour`() {
        val r = WeatherParser.parse(spec, null, now)!!
        assertEquals("Moscow", r.location)
        val currentHour = Math.floorDiv(now - 600_000, 3_600_000L) * 3_600_000L
        assertEquals(-3.15, r.hours.getValue(currentHour), 1e-9)
        assertEquals(-1.15, r.hours.getValue(hour + 3_600_000), 1e-9)
    }

    @Test fun `implausible temperatures and far-off times are dropped`() {
        val bad = """{"timestamp":${s(now)},"currentTemp":500,
            "hourly":[{"timestamp":${s(now + 30L * 86_400_000)},"temp":280},{"timestamp":${s(now)},"temp":50}]}"""
        assertNull(WeatherParser.parse(bad, null, now))
    }

    @Test fun `the gzipped form is read from its first location`() {
        val array = "[$spec,{\"location\":\"Elsewhere\",\"timestamp\":${s(now)},\"currentTemp\":300}]"
        val r = WeatherParser.parse(null, gzip(array.toByteArray()), now)!!
        assertEquals("Moscow", r.location)
    }

    /** A small payload that unpacks into megabytes is refused, not read to the end. */
    @Test fun `a gzip bomb is refused`() {
        val bomb = gzip(ByteArray(WeatherParser.MAX_BYTES * 4) { ' '.code.toByte() })
        assertNull(WeatherParser.parse(null, bomb, now))
    }

    @Test fun `garbage is ignored`() {
        assertNull(WeatherParser.parse("not json", null, now))
        assertNull(WeatherParser.parse(null, byteArrayOf(1, 2, 3), now))
        assertNull(WeatherParser.parse(null, null, now))
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    /** Re-sent from storage hours later, a report's "current" conditions are not now's. */
    @Test fun `stale current conditions are not filed as now`() {
        val old = """{"timestamp":${s(now - 3 * 3_600_000)},"currentTemp":300,"hourly":[{"timestamp":${s(hour)},"temp":271}]}"""
        val r = WeatherParser.parse(old, null, now)!!
        assertEquals(-2.15, r.hours.getValue(hour), 1e-9)
    }

    @Test fun `a forecast more than two days ahead is not taken`() {
        val far = """{"hourly":[{"timestamp":${s(now + 3L * 86_400_000)},"temp":280}]}"""
        assertNull(WeatherParser.parse(far, null, now))
    }
}
