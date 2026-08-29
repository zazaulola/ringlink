package io.github.ringlink.data

import org.json.JSONArray
import org.json.JSONObject

/** One ring the user owns. [address] is its BLE MAC and doubles as its stable identity. */
data class Ring(val address: String, val name: String) {
    /** Short label for the UI: "RingConn Gen3-F749" -> "F749". */
    val shortName: String get() = name.substringAfterLast('-', name)

    companion object {
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
