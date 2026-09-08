package app.capgo.capacitor.watch.sdk

import org.json.JSONArray
import org.json.JSONObject

internal object CapgoWatchJson {
    fun valueToKotlin(value: Any?): Any? {
        return when (value) {
            is JSONObject -> objectToMap(value)
            is JSONArray -> arrayToList(value)
            JSONObject.NULL -> null
            else -> value
        }
    }

    fun objectToMap(json: JSONObject): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            result[key] = valueToKotlin(json.get(key))
        }
        return result
    }

    fun arrayToList(array: JSONArray): List<Any?> {
        val result = mutableListOf<Any?>()
        for (index in 0 until array.length()) {
            result.add(valueToKotlin(array.get(index)))
        }
        return result
    }
}
