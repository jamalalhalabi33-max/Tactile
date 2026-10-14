package com.example.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.TvBrand
import com.example.model.TvDevice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "tactile_settings")

class TvStorage(private val context: Context) {
    private val keyLastUsedId = stringPreferencesKey("last_used_device_id")
    private val keySavedDevices = stringPreferencesKey("saved_devices_json")
    private val keyAtvCert = stringPreferencesKey("atv_client_cert_der")
    private val keyAtvKey = stringPreferencesKey("atv_client_key_der")

    val savedDevicesFlow: Flow<List<TvDevice>> = context.dataStore.data.map { preferences ->
        val jsonStr = preferences[keySavedDevices] ?: "[]"
        parseDevices(jsonStr)
    }

    val lastUsedDeviceIdFlow: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[keyLastUsedId]
    }

    suspend fun getLastUsedDevice(): TvDevice? {
        val devices = savedDevicesFlow.first()
        val lastId = lastUsedDeviceIdFlow.first()
        return devices.firstOrNull { it.id == lastId } ?: devices.firstOrNull()
    }

    suspend fun saveDevice(device: TvDevice) {
        context.dataStore.edit { preferences ->
            val existing = parseDevices(preferences[keySavedDevices] ?: "[]").toMutableList()
            val index = existing.indexOfFirst { it.id == device.id || it.ip == device.ip }
            if (index >= 0) {
                existing[index] = device
            } else {
                existing.add(device)
            }
            preferences[keySavedDevices] = serializeDevices(existing)
            preferences[keyLastUsedId] = device.id
        }
    }

    suspend fun updateClientKey(deviceId: String, clientKey: String) {
        context.dataStore.edit { preferences ->
            val existing = parseDevices(preferences[keySavedDevices] ?: "[]").toMutableList()
            val index = existing.indexOfFirst { it.id == deviceId }
            if (index >= 0) {
                existing[index] = existing[index].copy(clientKey = clientKey)
                preferences[keySavedDevices] = serializeDevices(existing)
            }
        }
    }

    suspend fun setLastUsedId(id: String) {
        context.dataStore.edit { preferences ->
            preferences[keyLastUsedId] = id
        }
    }

    suspend fun removeDevice(id: String) {
        context.dataStore.edit { preferences ->
            val existing = parseDevices(preferences[keySavedDevices] ?: "[]").toMutableList()
            existing.removeAll { it.id == id }
            preferences[keySavedDevices] = serializeDevices(existing)
            if (preferences[keyLastUsedId] == id) {
                preferences[keyLastUsedId] = existing.firstOrNull()?.id ?: ""
            }
        }
    }

    suspend fun getAtvCredentials(): Pair<String?, String?> {
        val prefs = context.dataStore.data.first()
        return Pair(prefs[keyAtvCert], prefs[keyAtvKey])
    }

    suspend fun saveAtvCredentials(certDerB64: String, keyDerB64: String) {
        context.dataStore.edit { preferences ->
            preferences[keyAtvCert] = certDerB64
            preferences[keyAtvKey] = keyDerB64
        }
    }

    private fun parseDevices(json: String): List<TvDevice> {
        val list = mutableListOf<TvDevice>()
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val brand = try {
                    TvBrand.valueOf(obj.getString("brand"))
                } catch (e: Exception) {
                    TvBrand.LG_WEBOS
                }
                list.add(
                    TvDevice(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        ip = obj.getString("ip"),
                        brand = brand,
                        port = obj.optInt("port", if (brand == TvBrand.LG_WEBOS) 3001 else 6466),
                        clientKey = if (obj.has("clientKey") && !obj.isNull("clientKey")) obj.getString("clientKey") else null
                    )
                )
            }
        } catch (e: Exception) {
            // Ignore corrupted JSON
        }
        return list
    }

    private fun serializeDevices(devices: List<TvDevice>): String {
        val array = JSONArray()
        for (device in devices) {
            val obj = JSONObject()
            obj.put("id", device.id)
            obj.put("name", device.name)
            obj.put("ip", device.ip)
            obj.put("brand", device.brand.name)
            obj.put("port", device.port)
            if (device.clientKey != null) {
                obj.put("clientKey", device.clientKey)
            }
            array.put(obj)
        }
        return array.toString()
    }
}
