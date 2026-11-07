package de.anlagen.solaruebersicht

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Einstellungen. Das Passwort liegt verschlüsselt (Schlüssel im Android-Keystore),
 * alles andere in normalen App-Einstellungen.
 */
class Prefs(context: Context) {
    private val plain = context.getSharedPreferences("einstellungen", Context.MODE_PRIVATE)
    private val secure: SharedPreferences? = try {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, "zugang", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        null   // Keystore nicht verfügbar → Passwort wird nicht gespeichert
    }

    var email: String
        get() = plain.getString("email", "") ?: ""
        set(v) = plain.edit().putString("email", v).apply()

    val password: String?
        get() = secure?.getString("password", null)

    fun savePassword(pw: String) {
        secure?.edit()?.putString("password", pw)?.apply()
    }

    fun clearPassword() {
        secure?.edit()?.remove("password")?.apply()
    }

    /** Abfrage-Intervall der Live-Werte in Sekunden */
    var interval: Int
        get() = plain.getInt("interval", 10)
        set(v) = plain.edit().putInt("interval", v).apply()

    /** Home-Assistant-Anlagen (ohne Token) */
    var haSites: List<HaConfig>
        get() = try {
            val a = org.json.JSONArray(plain.getString("ha_sites", "[]"))
            (0 until a.length()).map { HaConfig.fromJson(a.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
        set(v) = plain.edit().putString("ha_sites", org.json.JSONArray(v.map { it.toJson() }).toString()).apply()

    /** Zugriffstoken einer Home-Assistant-Anlage – verschlüsselt gespeichert */
    fun haToken(name: String): String? = secure?.getString("ha_token_$name", null)

    fun setHaToken(name: String, token: String?) {
        secure?.edit()?.apply { if (token == null) remove("ha_token_$name") else putString("ha_token_$name", token) }?.apply()
    }

    /** Karten, die als Energiefluss statt mit Gauges angezeigt werden (Standard: nur "Gesamt") */
    var flowCards: Set<String>
        get() = if (plain.contains("flow_cards")) plain.getStringSet("flow_cards", emptySet()) ?: emptySet() else setOf(TOTAL_CARD)
        set(v) = plain.edit().putStringSet("flow_cards", v).apply()

    /** Anlagen, die ausgeblendet werden sollen */
    var hidden: Set<String>
        get() = plain.getStringSet("hidden", emptySet()) ?: emptySet()
        set(v) = plain.edit().putStringSet("hidden", v).apply()
}
