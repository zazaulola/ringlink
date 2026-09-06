package io.github.ringlink.ble

/** What the ring reports about itself over the standard Device Information Service. */
data class DeviceInfo(
    val manufacturer: String? = null,
    val model: String? = null,
    val serial: String? = null,
    val firmware: String? = null,
    val hardware: String? = null,
) {
    val isEmpty: Boolean
        get() = listOfNotNull(manufacturer, model, serial, firmware, hardware).isEmpty()

    /** One line for the UI, skipping whatever the ring did not report. */
    fun summary(): String = listOfNotNull(
        model,
        firmware?.let { "firmware $it" },
        hardware?.let { "hardware $it" },
    ).joinToString(" · ").ifEmpty { manufacturer ?: "unknown" }
}
