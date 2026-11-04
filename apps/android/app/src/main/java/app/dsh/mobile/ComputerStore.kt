package app.dsh.mobile

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

class ComputerStore(context: Context) {
    private val preferences = EncryptedSharedPreferences.create(
        context,
        "computers",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun list(): List<Computer> {
        val raw = preferences.getString("items", "[]") ?: "[]"
        val array = JSONArray(raw)
        return List(array.length()) { index ->
            val item = array.getJSONObject(index)
            Computer(
                id = item.getString("id"),
                name = item.getString("name"),
                origin = item.getString("origin"),
                token = item.getString("token"),
                certificateSha256 = item.optString("certificateSha256").ifEmpty { null },
                lastSeen = item.optLong("lastSeen"),
            )
        }
    }

    fun save(computer: Computer) {
        val next = list().filterNot { it.origin == computer.origin } + computer
        val array = JSONArray()
        next.forEach { item ->
            array.put(JSONObject()
                .put("id", item.id)
                .put("name", item.name)
                .put("origin", item.origin)
                .put("token", item.token)
                .put("certificateSha256", item.certificateSha256)
                .put("lastSeen", item.lastSeen))
        }
        preferences.edit().putString("items", array.toString()).apply()
    }

    fun delete(id: String) {
        val remaining = list().filterNot { it.id == id }
        preferences.edit().remove("items").apply()
        remaining.forEach { save(it) }
    }

    fun active(): Computer? {
        val id = preferences.getString("active", null) ?: return list().firstOrNull()
        return list().find { it.id == id } ?: list().firstOrNull()
    }

    fun activate(id: String) {
        preferences.edit().putString("active", id).apply()
    }
}
