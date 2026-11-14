package et.maed.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.sqrt

const val DATASET_SCHEMA_VERSION = "1.7"
const val MODEL_VERSION = "MAED-SENSOR-CENTROID-1.1-VERIFIED"
const val MIN_SAMPLES_PER_CLASS = 20
const val MIN_SENSITIVITY = 0.90
const val MIN_SPECIFICITY = 0.90

val GROUND_TRUTH = listOf(
    "not_yet_available" to "Not yet available",
    "clean_per_protocol" to "CLEAN — lab/protocol confirmed",
    "contaminated_per_protocol" to "CONTAMINATED — lab/protocol confirmed"
)

data class Sample(
    val id: String, val foodType: String, val storage: String, val collectedAt: String,
    val tempC: Double?, val humidity: Double?, val image: String, val piImage: String?,
    val spectral: String?, val voc: String?, val groundTruth: String,
    val aiResult: String, val modelVersion: String?, val modelValidated: Boolean, val riskScore: Double?,
    val sensorStatus: String, val acquisitionStatus: String, val dataSource: String = "real", val labReference: String? = null, val groundTruthMethod: String? = null, val groundTruthReferenceId: String? = null, val evidenceStorage: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("sample_id", id); put("food_type", foodType); put("storage_condition", storage); put("collected_at", collectedAt)
        put("temperature_c", tempC ?: JSONObject.NULL); put("humidity_percent", humidity ?: JSONObject.NULL)
        put("rgb_image_file", image); put("pi_image_file", piImage ?: JSONObject.NULL)
        put("as7341", spectral?.let { JSONObject(it) } ?: JSONObject.NULL)
        put("bme688", voc?.let { JSONObject(it) } ?: JSONObject.NULL)
        put("ground_truth", groundTruth); put("ai_result", aiResult); put("model_version", modelVersion ?: JSONObject.NULL)
        put("model_validated", modelValidated); put("risk_score", riskScore ?: JSONObject.NULL)
        put("sensor_status", sensorStatus); put("acquisition_status", acquisitionStatus)
        put("data_source", dataSource); put("lab_reference", labReference ?: JSONObject.NULL); put("ground_truth_method", groundTruthMethod ?: JSONObject.NULL); put("ground_truth_reference_id", groundTruthReferenceId ?: JSONObject.NULL); put("evidence_storage", evidenceStorage ?: JSONObject.NULL)
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
                o.optBoolean("model_validated", false), d("risk_score"), o.getString("sensor_status"),
                o.getString("acquisition_status"), o.optString("data_source", "real"), o.optString("lab_reference", null), o.optString("ground_truth_method", null), o.optString("ground_truth_reference_id", null), o.optString("evidence_storage", null)
            )
        }
    }
}

data class ModelState(
    val ready: Boolean, val version: String?, val sensitivity: Double, val specificity: Double,
    val cleanCount: Int, val contaminatedCount: Int, val validationCount: Int, val reason: String,
    val falseNegatives: Int = 0, val verificationId: String? = null
)

data class ClassifierResult(
    val verdict: String, val confidence: Double?, val modelValidated: Boolean,
    val modelVersion: String?, val reason: String
)

object FoodClassifier {
    private const val PREF = "maed_model"
    private const val KEY = "state"
    private const val FEATURE_COUNT = 14

    private fun features(s: Sample): DoubleArray? {
        if (s.spectral == null || s.voc == null) return null
        return try {
            val sp = JSONObject(s.spectral)
            val vo = JSONObject(s.voc)
            val keys = arrayOf("F1_415nm","F2_445nm","F3_480nm","F4_515nm","F5_555nm","F6_590nm","F7_630nm","F8_680nm","clear","nir")
            val out = DoubleArray(FEATURE_COUNT)
            for (i in keys.indices) out[i] = sp.getDouble(keys[i])
            out[10] = vo.getDouble("temperature_c")
            out[11] = vo.getDouble("humidity_percent")
            out[12] = vo.getDouble("pressure_hpa")
            out[13] = vo.getDouble("gas_resistance_ohm")
            if (out.any { !it.isFinite() }) null else out
        } catch (_: Exception) { null }
    }

    private fun save(ctx: Context, state: JSONObject) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, state.toString()).apply()

    private fun load(ctx: Context): JSONObject? =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, null)?.let { try { JSONObject(it) } catch (_: Exception) { null } }

    fun state(ctx: Context): ModelState {
        val o = load(ctx) ?: return ModelState(false, null, 0.0, 0.0, 0, 0, 0, "ምንም የተረጋገጠ ሞዴል የለም።")
        return ModelState(o.optBoolean("ready"), o.optString("version", null), o.optDouble("sensitivity"), o.optDouble("specificity"),
            o.optInt("clean_count"), o.optInt("contaminated_count"), o.optInt("validation_count"), o.optString("reason"), o.optInt("false_negatives", 0), o.optString("verification_id", null))
    }

    fun trainAndValidate(ctx: Context, all: List<Sample>): ModelState {
        val usable = all.filter { it.dataSource == "real" && it.labReference?.isNotBlank() == true &&
            it.groundTruthMethod?.isNotBlank() == true && it.groundTruthReferenceId?.isNotBlank() == true && it.evidenceStorage?.isNotBlank() == true &&
            (it.groundTruth == "clean_per_protocol" || it.groundTruth == "contaminated_per_protocol") }
            .mapNotNull { s -> features(s)?.let { s to it } }
        val clean = usable.filter { it.first.groundTruth == "clean_per_protocol" }
        val bad = usable.filter { it.first.groundTruth == "contaminated_per_protocol" }
        if (clean.size < MIN_SAMPLES_PER_CLASS || bad.size < MIN_SAMPLES_PER_CLASS)
            return ModelState(false, null, 0.0, 0.0, clean.size, bad.size, 0, "ቢያንስ $MIN_SAMPLES_PER_CLASS CLEAN እና $MIN_SAMPLES_PER_CLASS CONTAMINATED የላብ ማጣቀሻ ያላቸው ናሙናዎች ያስፈልጋሉ።")

        val train = usable.filter { kotlin.math.abs(it.first.id.hashCode()) % 5 != 0 }
        val test = usable.filter { kotlin.math.abs(it.first.id.hashCode()) % 5 == 0 }
        if (test.none { it.first.groundTruth == "clean_per_protocol" } || test.none { it.first.groundTruth == "contaminated_per_protocol" })
            return ModelState(false, null, 0.0, 0.0, clean.size, bad.size, test.size, "Blind validation ክፍሉ ሁለቱንም ክፍሎች አላካተተም። ተጨማሪ ናሙና ያስገቡ።")

        val means = DoubleArray(FEATURE_COUNT)
        val stds = DoubleArray(FEATURE_COUNT)
        for (j in 0 until FEATURE_COUNT) {
            means[j] = train.map { it.second[j] }.average()
            val variance = train.map { val d = it.second[j] - means[j]; d*d }.average()
            stds[j] = sqrt(variance).takeIf { it > 1e-9 } ?: 1.0
        }
        fun norm(x: DoubleArray) = DoubleArray(FEATURE_COUNT) { j -> (x[j] - means[j]) / stds[j] }
        fun centroid(label: String): DoubleArray {
            val rows = train.filter { it.first.groundTruth == label }.map { norm(it.second) }
            return DoubleArray(FEATURE_COUNT) { j -> rows.sumOf { it[j] } / rows.size }
        }
        val cleanCentroid = centroid("clean_per_protocol")
        val badCentroid = centroid("contaminated_per_protocol")

        fun pred(x: DoubleArray): String {
            val n = norm(x)
            val dc = sqrt(n.indices.sumOf { j -> val d=n[j]-cleanCentroid[j]; d*d })
            val db = sqrt(n.indices.sumOf { j -> val d=n[j]-badCentroid[j]; d*d })
            return if (dc <= db) "clean_per_protocol" else "contaminated_per_protocol"
        }

        var tp=0; var tn=0; var fp=0; var fn=0
        test.forEach { s ->
            val p=pred(s.second)
            if (s.first.groundTruth=="contaminated_per_protocol" && p=="contaminated_per_protocol") tp++
            if (s.first.groundTruth=="clean_per_protocol" && p=="clean_per_protocol") tn++
            if (s.first.groundTruth=="clean_per_protocol" && p=="contaminated_per_protocol") fp++
            if (s.first.groundTruth=="contaminated_per_protocol" && p=="clean_per_protocol") fn++
        }
        val sensitivity = if (tp+fn==0) 0.0 else tp.toDouble()/(tp+fn)
        val specificity = if (tn+fp==0) 0.0 else tn.toDouble()/(tn+fp)
        val ready = sensitivity >= MIN_SENSITIVITY && specificity >= MIN_SPECIFICITY && fn == 0
        if (!ready) return ModelState(false, MODEL_VERSION, sensitivity, specificity, clean.size, bad.size, test.size,
            "Validation አልፏል አይደለም። sensitivity=$sensitivity specificity=$specificity false-negative=$fn", fn)

        val verificationId = "MAED-VERIFIED-" + System.currentTimeMillis().toString()
        save(ctx, JSONObject().apply {
            put("ready", true); put("version", MODEL_VERSION); put("sensitivity", sensitivity); put("specificity", specificity)
            put("clean_count", clean.size); put("contaminated_count", bad.size); put("validation_count", test.size)
            put("false_negatives", fn); put("verification_id", verificationId)
            put("reason", "Blind validation passed")
            put("means", JSONArray(means.toList())); put("stds", JSONArray(stds.toList()))
            put("clean_centroid", JSONArray(cleanCentroid.toList())); put("contaminated_centroid", JSONArray(badCentroid.toList()))
        })
        return ModelState(true, MODEL_VERSION, sensitivity, specificity, clean.size, bad.size, test.size,
            "Blind validation passed. Verification ID: $verificationId", fn, verificationId)
    }

    fun exportVerificationCertificate(ctx: Context): File? {
        val o = load(ctx) ?: return null
        if (!o.optBoolean("ready")) return null
        val f = File(ctx.cacheDir, "MAED-Verification-${o.optString("verification_id","unknown")}.json")
        val cert = JSONObject().apply {
            put("certificate_type", "MAED_TRUE_VERIFICATION")
            put("verification_id", o.optString("verification_id"))
            put("model_version", o.optString("version"))
            put("status", "VERIFIED_MODEL")
            put("sensitivity", o.optDouble("sensitivity"))
            put("specificity", o.optDouble("specificity"))
            put("false_negatives", o.optInt("false_negatives"))
            put("clean_samples", o.optInt("clean_count"))
            put("contaminated_samples", o.optInt("contaminated_count"))
            put("blind_validation_samples", o.optInt("validation_count"))
            put("issued_at", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US).format(java.util.Date()))
            put("scope", "Only the food classes and acquisition conditions represented by the qualified reference dataset.")
            put("limitation", "This certificate verifies model validation against the qualified reference dataset; it is not a laboratory certificate for an individual food item.")
            put("ground_truth_requirement", "Every training/validation sample must have qualified laboratory or approved food-safety protocol reference.")
        }
        f.writeText(cert.toString(2))
        return f
    }

    fun predict(ctx: Context, s: Sample): ClassifierResult {
        val o = load(ctx)
        if (o == null || !o.optBoolean("ready")) return ClassifierResult("UNCERTAIN", null, false, null, "Validated model not ready.")
        val x = features(s) ?: return ClassifierResult("UNCERTAIN", null, false, o.optString("version"), "Required real sensor features are missing.")
        return try {
            val means = DoubleArray(FEATURE_COUNT) { o.getJSONArray("means").getDouble(it) }
            val stds = DoubleArray(FEATURE_COUNT) { o.getJSONArray("stds").getDouble(it) }
            val c = DoubleArray(FEATURE_COUNT) { o.getJSONArray("clean_centroid").getDouble(it) }
            val b = DoubleArray(FEATURE_COUNT) { o.getJSONArray("contaminated_centroid").getDouble(it) }
            val n = DoubleArray(FEATURE_COUNT) { (x[it]-means[it])/stds[it] }
            val dc=sqrt(n.indices.sumOf { j -> val d=n[j]-c[j]; d*d })
            val db=sqrt(n.indices.sumOf { j -> val d=n[j]-b[j]; d*d })
            val confidence = (kotlin.math.abs(db-dc)/(db+dc+1e-9)).coerceIn(0.0,1.0)
            if (confidence < 0.20) ClassifierResult("UNCERTAIN", confidence, true, o.optString("version"), "Model margin is too small.")
            else if (dc < db) ClassifierResult("CLEAN", confidence, true, o.optString("version"), "Validated model prediction.")
            else ClassifierResult("CONTAMINATED", confidence, true, o.optString("version"), "Validated model prediction.")
        } catch (_: Exception) { ClassifierResult("UNCERTAIN", null, false, o.optString("version"), "Model data invalid.") }
    }
}


data class ScreeningResult(
    val level: String,
    val score: Double?,
    val reason: String
)

object FoodRiskScreening {
    private const val MIN_REFERENCE = 3
    private const val FEATURE_COUNT = 14

    private fun features(s: Sample): DoubleArray? {
        if (s.spectral == null || s.voc == null) return null
        return try {
            val sp = JSONObject(s.spectral)
            val vo = JSONObject(s.voc)
            val keys = arrayOf(
                "F1_415nm", "F2_445nm", "F3_480nm", "F4_515nm", "F5_555nm",
                "F6_590nm", "F7_630nm", "F8_680nm", "clear", "nir"
            )
            val out = DoubleArray(FEATURE_COUNT)
            for (i in keys.indices) out[i] = sp.getDouble(keys[i])
            out[10] = vo.getDouble("temperature_c")
            out[11] = vo.getDouble("humidity_percent")
            out[12] = vo.getDouble("pressure_hpa")
            out[13] = vo.getDouble("gas_resistance_ohm")
            if (out.any { !it.isFinite() }) null else out
        } catch (_: Exception) {
            null
        }
    }

    private fun median(values: List<Double>): Double {
        val v = values.sorted()
        if (v.isEmpty()) return 0.0
        val mid = v.size / 2
        return if (v.size % 2 == 0) (v[mid - 1] + v[mid]) / 2.0 else v[mid]
    }

    private fun robustScale(values: List<Double>): Double {
        val m = median(values)
        val mad = median(values.map { kotlin.math.abs(it - m) })
        return (1.4826 * mad).takeIf { it > 1e-9 } ?: 1.0
    }

    fun screen(current: Sample, all: List<Sample>): ScreeningResult {
        val x = features(current)
            ?: return ScreeningResult(
                "UNAVAILABLE", null,
                "የAS7341/BME688 እውነተኛ መረጃ አልተሟላም።"
            )

        val refs = all.filter {
            it.id != current.id &&
            it.dataSource == "real" &&
            it.foodType.equals(current.foodType, ignoreCase = true) &&
            it.storage == current.storage
        }.mapNotNull { features(it) }

        val fallback = all.filter {
            it.id != current.id && it.dataSource == "real"
        }.mapNotNull { features(it) }

        val rows = if (refs.size >= MIN_REFERENCE) refs else fallback
        if (rows.size < MIN_REFERENCE) {
            return ScreeningResult(
                "UNAVAILABLE", null,
                "ለscreening ቢያንስ $MIN_REFERENCE የእውነተኛ AS7341/BME688 መለኪያዎች ያስፈልጋሉ።"
            )
        }

        // Robust baseline: median + MAD reduces the effect of one bad reference reading.
        val center = DoubleArray(FEATURE_COUNT) { j -> median(rows.map { it[j] }) }
        val scale = DoubleArray(FEATURE_COUNT) { j -> robustScale(rows.map { it[j] }) }

        // Gas resistance is highly skewed; log-transform it before comparing patterns.
        val robustZ = DoubleArray(FEATURE_COUNT) { j ->
            val value = if (j == 13) kotlin.math.ln(x[j].coerceAtLeast(1.0)) else x[j]
            val refValues = rows.map {
                val v = if (j == 13) kotlin.math.ln(it[j].coerceAtLeast(1.0)) else it[j]
                v
            }
            val c0 = median(refValues)
            val s0 = robustScale(refValues)
            kotlin.math.abs((value - c0) / s0)
        }

        // Spectral channels carry more weight; environmental/VOC channels are supporting evidence.
        val weighted = robustZ.mapIndexed { j, z ->
            val w = when (j) {
                in 0..9 -> 1.0
                10 -> 0.35
                11 -> 0.35
                12 -> 0.20
                13 -> 0.75
                else -> 1.0
            }
            z * z * w
        }.average()

        val distance = sqrt(weighted)
        val score = (1.0 - kotlin.math.exp(-distance / 2.5)).coerceIn(0.0, 1.0)
        val level = when {
            score < 0.30 -> "LOW"
            score < 0.60 -> "MEDIUM"
            else -> "HIGH"
        }

        val source = if (refs.size >= MIN_REFERENCE) {
            "ተመሳሳይ ምግብ/ማከማቻ የreference መረጃ"
        } else {
            "የMAED አጠቃላይ reference መረጃ"
        }

        return ScreeningResult(
            level,
            score,
            "ይህ robust sensor-pattern screening ነው፤ የብክለት ማረጋገጫ አይደለም። " +
                "የተለየ pattern ካለ ብቻ የማስጠንቀቂያ ደረጃ ይጨምራል። Reference: $source."
        )
    }
}

fun verdict(): String = "UNCERTAIN"

class Repo(private val ctx: Context) {
    private val dir = File(ctx.filesDir, "samples").also { it.mkdirs() }
    val imgDir = File(ctx.filesDir, "images").also { it.mkdirs() }
    fun all(): List<Sample> = (dir.listFiles { f -> f.extension == "json" } ?: emptyArray()).mapNotNull { try { Sample.from(JSONObject(it.readText())) } catch (_: Exception) { null } }.sortedByDescending { it.collectedAt }
    fun save(s: Sample) { File(dir, s.id + ".json").writeText(s.toJson().toString()) }
    fun delete(id: String) { all().find { it.id == id }?.let { File(imgDir, it.image).delete(); it.piImage?.let { p -> File(imgDir, p).delete() } }; File(dir, "$id.json").delete() }
    fun setGroundTruth(id: String, gt: String) { all().find { it.id == id }?.let { save(it.copy(groundTruth = gt)) } }
    fun setLabReference(id: String, reference: String?) { all().find { it.id == id }?.let { save(it.copy(labReference = reference?.trim()?.ifEmpty { null })) } }
    fun setGroundTruthEvidence(id: String, method: String?, referenceId: String?, evidence: String?) { all().find { it.id == id }?.let { save(it.copy(groundTruthMethod = method?.trim()?.ifEmpty { null }, groundTruthReferenceId = referenceId?.trim()?.ifEmpty { null }, evidenceStorage = evidence?.trim()?.ifEmpty { null })) } }
    private fun exportDir() = File(ctx.cacheDir, "exports").also { it.mkdirs() }
    fun exportJson(): File { val real=all().filter { it.dataSource=="real" }; val root=JSONObject().put("dataset","maed").put("schema_version",DATASET_SCHEMA_VERSION).put("model_validated",FoodClassifier.state(ctx).ready).put("count",real.size); root.put("samples",JSONArray(real.map { it.toJson() })); return File(exportDir(),"maed-dataset.json").also { it.writeText(root.toString(2)) } }
    fun exportCsv(): File {
        fun e(v: Any?): String {
            return "\"" + (v?.toString() ?: "").replace("\"", "\"\"") + "\""
        }
        val header = "sample_id,food_type,storage_condition,collected_at,temperature_c,humidity_percent,rgb_image_file,as7341_json,bme688_json,ground_truth,ai_result,model_version,model_validated,risk_score,sensor_status,acquisition_status,data_source,lab_reference,ground_truth_method,ground_truth_reference_id,evidence_storage"
        val rows = all()
            .filter { it.dataSource == "real" }
            .map { sample ->
                listOf(
                    sample.id, sample.foodType, sample.storage, sample.collectedAt,
                    sample.tempC, sample.humidity, sample.image, sample.spectral,
                    sample.voc, sample.groundTruth, sample.aiResult, sample.modelVersion,
                    sample.modelValidated, sample.riskScore, sample.sensorStatus,
                    sample.acquisitionStatus, sample.dataSource, sample.labReference, sample.groundTruthMethod, sample.groundTruthReferenceId, sample.evidenceStorage
                ).joinToString(",") { value -> e(value) }
            }
        return File(exportDir(), "maed-dataset.csv").also {
            it.writeText((listOf(header) + rows).joinToString("\n"))
        }
    }
}
