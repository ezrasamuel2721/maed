package et.maed.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class Acq(
    val status: String, val sensorStatus: String, val tempC: Double?, val humidity: Double?,
    val spectral: String?, val voc: String?, val piImageB64: String?
)

/** Raspberry Pi acquisition client. Hardware ካልተገናኘ ምንም እሴት አይፈጠርም፤ ሁኔታው በግልጽ ይመዘገባል። */
object SensorClient {
    private fun none(status: String, why: String) = Acq(status, "{\"link\":\"$why\"}", null, null, null, null, null)

    fun acquire(baseUrl: String, token: String): Acq {
        if (baseUrl.isBlank()) return none("SENSOR_NOT_CONNECTED", "SENSOR_NOT_CONNECTED")
        return try {
            val c = URL(baseUrl.trimEnd('/') + "/v1/acquire").openConnection() as HttpURLConnection
            c.connectTimeout = 5000; c.readTimeout = 40000
            c.setRequestProperty("X-MAED-Token", token)
            if (c.responseCode != 200) return none("NO_REAL_SENSOR_DATA", "HTTP_" + c.responseCode)
            val o = JSONObject(c.inputStream.bufferedReader().readText())
            fun num(k: String) = if (o.isNull(k)) null else o.getDouble(k)
            Acq(
                o.optString("acquisition_status", "NO_REAL_SENSOR_DATA"),
                o.optJSONObject("sensor_status")?.toString() ?: "{}",
                num("temperature_c"), num("humidity_percent"),
                o.optJSONObject("as7341")?.toString(), o.optJSONObject("bme688")?.toString(),
                if (o.isNull("image_jpeg_base64")) null else o.getString("image_jpeg_base64")
            )
        } catch (e: Exception) {
            none("SENSOR_NOT_CONNECTED", e.javaClass.simpleName)
        }
    }
}
