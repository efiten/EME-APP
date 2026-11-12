package be.on5gs.eme.settings

import android.content.Context
import be.on5gs.eme.chat.Hb9qBand
import be.on5gs.eme.cq.Band
import be.on5gs.eme.degradation.EmeBand

/** Bewaart gewone (niet-geheime) instellingen op het toestel. */
class SettingsRepository(context: Context) {

    private val prefs = context.getSharedPreferences("eme_settings", Context.MODE_PRIVATE)

    var locator: String
        get() = prefs.getString(KEY_LOCATOR, DEFAULT_LOCATOR) ?: DEFAULT_LOCATOR
        set(value) = prefs.edit().putString(KEY_LOCATOR, value).apply()

    var callsign: String
        get() = prefs.getString(KEY_CALLSIGN, DEFAULT_CALLSIGN) ?: DEFAULT_CALLSIGN
        set(value) = prefs.edit().putString(KEY_CALLSIGN, value).apply()

    /** Gekozen banden in de Live CQ-lijst. Standaard alleen 23cm. */
    var cqBands: Set<Band>
        get() {
            val names = prefs.getStringSet(KEY_CQ_BANDS, null) ?: return setOf(Band.B23CM)
            return names.mapNotNull { n -> Band.entries.firstOrNull { it.name == n } }.toSet()
        }
        set(value) = prefs.edit().putStringSet(KEY_CQ_BANDS, value.map { it.name }.toSet()).apply()

    /** Gekozen band in de HB9Q-chat. Standaard 1296. */
    var chatBand: Hb9qBand
        get() = Hb9qBand.entries.firstOrNull { it.name == prefs.getString(KEY_CHAT_BAND, null) } ?: Hb9qBand.B1296
        set(value) = prefs.edit().putString(KEY_CHAT_BAND, value.name).apply()

    /** Gekozen band in het tabblad Degradatie. Standaard 1296. */
    var degBand: EmeBand
        get() = EmeBand.entries.firstOrNull { it.name == prefs.getString(KEY_DEG_BAND, null) } ?: EmeBand.B1296
        set(value) = prefs.edit().putString(KEY_DEG_BAND, value.name).apply()

    companion object {
        private const val KEY_DEG_BAND = "deg_band"
        private const val KEY_CHAT_BAND = "chat_band"
        private const val KEY_LOCATOR = "locator"
        private const val KEY_CALLSIGN = "callsign"
        private const val KEY_CQ_BANDS = "cq_bands"
        const val DEFAULT_LOCATOR = "JO21SC"
        const val DEFAULT_CALLSIGN = "ON5GS"
    }
}
