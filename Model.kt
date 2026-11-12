package et.maed.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** GO ውሳኔ (blind validation) እስኪሰጥ ድረስ false ይቆያል። false ሲሆን app ምንም 🟢/🔴 verdict አይሰጥም። */
const val MODEL_VALIDATED = false

val GROUND_TRUTH = listOf(
    "not_yet_available" to "Not yet available",
    "acceptable_per_protocol" to "Acceptable according to test protocol",
    "quality_concern" to "Quality concern",
    "unsafe_per_protocol" to "Unsafe according to test protocol"
)

data class Sample(
    val id: String, val foodType: String, val storage: String, val collectedAt: String,
    val tempC: Double?, val humidity: Double?, val image: String, val piImage: String?,
    val spectral: String?, val voc: String?, val groundTruth: String,
    val aiResult: String, val modelVersion: String?, val modelValidated: Boolean, val riskScore: Double?,
    val sensorStatus: String, val acquisitionStatus: String, val dataSource: String = "real"
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("sample_id", id); put("food_type", foodType); put("storage_condition", storage); put("collected_at", collectedAt)
        put("temperature_c", tempC ?: JSONObject.NULL); put("humidity_percent", humidity ?: JSONObject.NULL)
        put("rgb_image_file", image); put("pi_image_file", piImage ?: JSONObject.NULL)
        put("as7341", spectral?.let { JSONObject(it) } ?: JSONObject.NULL)
        put("bme688", voc?.let { JSONObject(it) } ?: JSONObject.NULL)
        put("ground_truth", groundTruth); put("ai_result", aiResult); put("model_version", modelVersion ?: JSONObject.NULL)
        put("model_validated", modelValidated); put("risk_score", riskScore ?: JSONObject.NULL)
        put("sensor_status", sensorStatus); put("acquisition_status", acquisitionStatus); put("data_source", dataSource)
    }

    companion object {
        fun from(o: JSONObject): Sample {
            fun d(k: String) = if (o.isNull(k)) null else o.getDouble(k)
            fun s(k: String) = if (o.isNull(k)) null else o.getString(k)
            fun j(k: String) = if (o.isNull(k)) null else o.getJSONObject(k).toString()
            return Sample(
                o.getString("sample_id"), o.getString("food_type"), o.getString("storage_condition"), o.getString("collected_at"),
                d("temperature_c"), d("humidity_percent"), o.getString("rgb_image_file"), s("pi_image_file"),
                j("as7341"), j("bme688"), o.getString("ground_truth"), o.getString("ai_result"), s("model_version"),
                o.getBoolean("model_validated"), d("risk_score"), o.getString("sensor_status"),
                o.getString("acquisition_status"), o.optString("data_source", "real")
            )
        }
    }
}

/** MODEL_VALIDATED=false ሲሆን ሁልጊዜ UNCERTAIN። እውነተኛ ሞዴል ከGO በኋላ እዚህ ይገናኛል። */
fun verdict(): String = "UNCERTAIN"

class Repo(private val ctx: Context) {
    private val dir = File(ctx.filesDir, "samples").also { it.mkdirs() }
    val imgDir = File(ctx.filesDir, "images").also { it.mkdirs() }

    fun all(): List<Sample> = (dir.listFiles { f -> f.extension == "json" } ?: emptyArray())
        .mapNotNull { try { Sample.from(JSONObject(it.readText())) } catch (e: Exception) { null } }
        .sortedByDescending { it.collectedAt }

    fun save(s: Sample) { File(dir, s.id + ".json").writeText(s.toJson().toString()) }

    fun delete(id: String) {
        all().find { it.id == id }?.let { File(imgDir, it.image).delete(); it.piImage?.let { p -> File(imgDir, p).delete() } }
        File(dir, "$id.json").delete()
    }

    fun setGroundTruth(id: String, gt: String) { all().find { it.id == id }?.let { save(it.copy(groundTruth = gt)) } }

    private fun exportDir() = File(ctx.cacheDir, "exports").also { it.mkdirs() }

    /** simulated data ከreal dataset ጋር አይቀላቀልም፤ export የሚገባው dataSource=="real" ብቻ። */
    fun exportJson(): File {
        val real = all().filter { it.dataSource == "real" }
        val root = JSONObject().put("dataset", "maed").put("schema_version", "1.4")
            .put("model_validated", MODEL_VALIDATED).put("count", real.size)
        root.put("samples", JSONArray(real.map { it.toJson() }))
        return File(exportDir(), "maed-dataset.json").also { it.writeText(root.toString(2)) }
    }

    fun exportCsv(): File {
        fun e(v: Any?): String = "\"" + (v?.toString() ?: "").replace("\"", "\"\"") + "\""
        val h = "sample_id,food_type,storage_condition,collected_at,temperature_c,humidity_percent,rgb_image_file,as7341_json,bme688_json,ground_truth,ai_result,model_version,model_validated,risk_score,sensor_status,acquisition_status,data_source"
        val rows = all().filter { it.dataSource == "real" }.map {
            listOf(it.id, it.foodType, it.storage, it.collectedAt, it.tempC, it.humidity, it.image, it.spectral, it.voc, it.groundTruth,
                it.aiResult, it.modelVersion, it.modelValidated, it.riskScore, it.sensorStatus, it.acquisitionStatus, it.dataSource)
                .joinToString(",") { v -> e(v) }
        }
        return File(exportDir(), "maed-dataset.csv").also { it.writeText((listOf(h) + rows).joinToString("\n")) }
    }
}
