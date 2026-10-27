package io.github.ringlink.backup

import android.content.Context
import io.github.ringlink.data.Ring
import io.github.ringlink.data.Settings
import io.github.ringlink.watch.WatchSettings
import org.json.JSONArray
import org.json.JSONObject

/**
 * The app's settings, as they go into a backup and come back out of one.
 *
 * Transient state is left out: when the last sync ran, whether a low-battery warning has already
 * been shown, where a check-in currently stands. Carried to another phone those would only be
 * wrong. Backup settings are left out too — a folder permission belongs to the device that granted
 * it.
 */
object SettingsSnapshot {

    private val FILES = listOf(Settings.FILE, WatchSettings.FILE)

    /** Keys never carried, by prefs file; a trailing '*' matches a prefix. */
    private val EXCLUDED = mapOf(
        // The migration flags record the state of this phone's Health Connect and database, not a
        // preference.
        Settings.FILE to listOf(
            Settings.KEY_LAST_SYNC,
            Settings.KEY_WARNED + "*",
            Settings.KEY_TEMP_HOURLY,
            Settings.KEY_STEPS_PENDING,
            Settings.KEY_STEP_QUARTERS_GEN,
            Settings.KEY_STEP_QUARTERS_BUILT,
        ),
        // The watch belongs to one episode — a tick bite, say. Restored on another phone it would
        // come back switched on with no alarm scheduled, which is worse than off. Its interval
        // and grace period are preferences and do come back.
        WatchSettings.FILE to listOf("enabled", "exposure_at", "last_checkin", "pending_since"),
    )

    private fun excluded(file: String, key: String): Boolean = EXCLUDED[file].orEmpty().any {
        if (it.endsWith("*")) key.startsWith(it.dropLast(1)) else key == it
    }

    fun capture(context: Context): JSONObject {
        val out = JSONObject()
        for (file in FILES) {
            val values = JSONObject()
            context.getSharedPreferences(file, Context.MODE_PRIVATE).all.forEach { (key, value) ->
                if (value == null || excluded(file, key)) return@forEach
                values.put(key, encode(value) ?: return@forEach)
            }
            out.put(file, values)
        }
        return out
    }

    /** What applying a backup's settings did, for the report. */
    data class Applied(val replacedAll: Boolean, val ringsAdded: List<String>)

    /**
     * Bring settings back from a backup.
     *
     * On a fresh install — no rings configured yet — everything comes back. On a phone already in
     * use the current settings win, and only rings the backup knows and this phone does not are
     * added: restoring an old backup must not quietly undo choices made since.
     */
    fun apply(context: Context, snapshot: JSONObject): Applied {
        val settings = Settings(context)
        val current = settings.rings
        val backedUp = ringsIn(snapshot)

        if (current.isEmpty()) {
            for (file in FILES) {
                val values = snapshot.optJSONObject(file) ?: continue
                val editor = context.getSharedPreferences(file, Context.MODE_PRIVATE).edit()
                values.keys().forEach { key ->
                    if (!excluded(file, key)) decodeInto(editor, key, values.getJSONObject(key))
                }
                editor.apply()
            }
            return Applied(replacedAll = true, ringsAdded = backedUp.map { it.address })
        }

        val added = backedUp.filter { b -> current.none { it.address.equals(b.address, ignoreCase = true) } }
        added.forEach { settings.addRing(it) }
        return Applied(replacedAll = false, ringsAdded = added.map { it.address })
    }

    private fun ringsIn(snapshot: JSONObject): List<Ring> {
        val main = snapshot.optJSONObject(Settings.FILE) ?: return emptyList()
        val raw = main.optJSONObject(Settings.KEY_RINGS)?.optString("v")
        return Ring.listFromJson(raw)
    }

    private fun encode(value: Any): JSONObject? = when (value) {
        is Boolean -> JSONObject().put("t", "b").put("v", value)
        is Int -> JSONObject().put("t", "i").put("v", value)
        is Long -> JSONObject().put("t", "l").put("v", value)
        is Float -> JSONObject().put("t", "f").put("v", value.toDouble())
        is String -> JSONObject().put("t", "s").put("v", value)
        is Set<*> -> JSONObject().put("t", "ss").put("v", JSONArray(value.map { it.toString() }))
        else -> null
    }

    private fun decodeInto(editor: android.content.SharedPreferences.Editor, key: String, o: JSONObject) {
        when (o.optString("t")) {
            "b" -> editor.putBoolean(key, o.getBoolean("v"))
            "i" -> editor.putInt(key, o.getInt("v"))
            "l" -> editor.putLong(key, o.getLong("v"))
            "f" -> editor.putFloat(key, o.getDouble("v").toFloat())
            "s" -> editor.putString(key, o.getString("v"))
            "ss" -> o.getJSONArray("v").let { a -> editor.putStringSet(key, (0 until a.length()).map(a::getString).toSet()) }
        }
    }
}
