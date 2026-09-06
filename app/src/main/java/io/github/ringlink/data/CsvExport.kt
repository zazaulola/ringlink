package io.github.ringlink.data

import android.content.Context
import android.net.Uri
import io.github.ringlink.L
import io.github.ringlink.protocol.RingClock
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes everything the app holds as CSV.
 *
 * Owning your data is most of the point of not using the vendor app, and data you cannot get out of
 * an app is not really yours. Timestamps are written in both ISO form and as the ring's raw counter,
 * so a future correction to the time anchor can be applied to an old export without re-deriving it.
 */
class CsvExport(private val context: Context, private val dao: RingDao) {

    suspend fun writeTo(uri: Uri, clock: RingClock): Int {
        var rows = 0
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        context.contentResolver.openOutputStream(uri)?.use { stream ->
            OutputStreamWriter(stream, Charsets.UTF_8).use { out ->
                out.write("# RingLink export\n")
                out.write("# epoch counters are the ring's own; the anchor used here is ${clock.epoch()}\n\n")

                out.write("section,ring,timestamp,counter,heart_rate,hrv_rmssd,spo2,respiratory_rate,motion,channel,layout\n")
                var after = Long.MIN_VALUE
                while (true) {
                    val page = dao.epochsAfter(after, PAGE)
                    if (page.isEmpty()) break
                    for (e in page) {
                        val t = iso.format(Date(clock.toUnixSeconds(e.counter) * 1000))
                        out.write(
                            "epoch,${e.ringId},$t,${e.counter},${e.heartRate.orBlank()}," +
                                "${e.hrvRmssd.orBlank()},${e.spo2.orBlank()}," +
                                "${e.respiratoryRate.orBlank()},${e.motionSum},${e.channel},${e.layout}\n",
                        )
                        rows++
                    }
                    after = page.last().counter
                }

                out.write("\nsection,ring,timestamp,battery_percent,battery_mv,steps,skin_temp_a,skin_temp_b,on_charger,state\n")
                for (d in dao.allDeviceStates()) {
                    val t = iso.format(Date(d.recordedAt))
                    out.write(
                        "device,${d.ringId},$t,${d.batteryPercent},${d.batteryMillivolts}," +
                            "${d.steps},${d.skinTempA},${d.skinTempB},${d.onCharger},${d.state}\n",
                    )
                    rows++
                }

                out.write("\nsection,ring,timestamp,counter,heart_rate,steps\n")
                for (sp in dao.allSport()) {
                    val t = iso.format(Date(clock.toUnixSeconds(sp.counter) * 1000))
                    out.write("sport,${sp.ringId},$t,${sp.counter},${sp.heartRate.orBlank()},${sp.steps}\n")
                    rows++
                }
                out.flush()
            }
        } ?: run {
            L.e("export: could not open $uri for writing")
            return 0
        }
        L.i("exported $rows rows to $uri")
        return rows
    }

    private fun Any?.orBlank(): String = this?.toString() ?: ""

    private companion object {
        /** Streamed in pages so a long history never has to fit in memory at once. */
        const val PAGE = 2000
    }
}
