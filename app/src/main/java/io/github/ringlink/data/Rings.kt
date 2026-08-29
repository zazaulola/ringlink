package io.github.ringlink.data

import org.json.JSONArray
import org.json.JSONObject

/** One ring the user owns. [address] is its BLE MAC and doubles as its stable identity. */
data class Ring(val address: String, val name: String) {
    /** Short label for the UI: "RingConn Gen3-F749" -> "F749". */
    val shortName: String get() = name.substringAfterLast('-', name)

    /** Generation, read from the advertised name ("RingConn Gen3-F749" -> 3). Null if unknown. */
    val generation: Int? get() = GENERATION.find(name)?.groupValues?.get(1)?.toIntOrNull()

    /**
     * Whether this ring can buzz at all.
     *
     * Only Gen 3 has a vibration motor — earlier rings are entirely passive, so a vibrate command
     * to one is silently ignored by the hardware. Worth knowing rather than discovering as a
     * mysteriously silent ring.
     */
    val canVibrate: Boolean get() = (generation ?: 3) >= 3

    companion object {
        private val GENERATION = Regex("""Gen\s*(\d+)""", RegexOption.IGNORE_CASE)

        fun listFromJson(raw: String?): List<Ring> {
            if (raw.isNullOrBlank()) return emptyList()
            return runCatching {
                val arr = JSONArray(raw)
                (0 until arr.length()).map {
                    val o = arr.getJSONObject(it)
                    Ring(o.getString("address"), o.optString("name", o.getString("address")))
                }
            }.getOrDefault(emptyList())
        }

        fun listToJson(rings: List<Ring>): String = JSONArray().apply {
            rings.forEach { put(JSONObject().put("address", it.address).put("name", it.name)) }
        }.toString()
    }
}
