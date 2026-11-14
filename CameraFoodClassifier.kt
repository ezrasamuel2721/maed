package et.maed.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.sqrt

const val CAMERA_MODEL_VERSION = "MAED-CAMERA-APPEARANCE-1.0"
const val CAMERA_MIN_SAMPLES_PER_CLASS = 20
const val CAMERA_MIN_SENSITIVITY = 0.90
const val CAMERA_MIN_SPECIFICITY = 0.90

data class CameraModelState(
    val ready: Boolean,
    val version: String?,
    val sensitivity: Double,
    val specificity: Double,
    val cleanCount: Int,
    val contaminatedCount: Int,
    val validationCount: Int,
    val reason: String
)

data class CameraClassifierResult(
    val verdict: String,
    val confidence: Double?,
    val modelValidated: Boolean,
    val modelVersion: String?,
    val reason: String
)

object CameraFoodClassifier {
    private const val PREF = "maed_camera_model"
    private const val KEY = "state"
    private const val FEATURE_COUNT = 18

    private fun features(file: File): DoubleArray? {
        if (!file.exists() || file.length() <= 0) return null
        val src = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        val bmp = Bitmap.createScaledBitmap(src, 32, 32, true)
        val sum = DoubleArray(3)
        val sumSq = DoubleArray(3)
        val hist = DoubleArray(9)
        val n = 32 * 32
        for (y in 0 until 32) for (x in 0 until 32) {
            val p = bmp.getPixel(x, y)
            val r = ((p shr 16) and 255).toDouble() / 255.0
            val g = ((p shr 8) and 255).toDouble() / 255.0
            val b = (p and 255).toDouble() / 255.0
            val v = doubleArrayOf(r, g, b)
            for (i in 0..2) { sum[i] += v[i]; sumSq[i] += v[i] * v[i] }
            val ri = (r * 2.999).toInt().coerceIn(0, 2)
            val gi = (g * 2.999).toInt().coerceIn(0, 2)
            val bi = (b * 2.999).toInt().coerceIn(0, 2)
            hist[ri] += 1.0
            hist[3 + gi] += 1.0
            hist[6 + bi] += 1.0
        }
        val out = DoubleArray(FEATURE_COUNT)
        for (i in 0..2) {
            out[i] = sum[i] / n
            val variance = sumSq[i] / n - out[i] * out[i]
            out[3 + i] = sqrt(variance.coerceAtLeast(0.0))
        }
        for (i in 0..8) out[6 + i] = hist[i] / n
        if (out.any { !it.isFinite() }) return null
        return out
    }

    private fun save(ctx: Context, state: JSONObject) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, state.toString()).apply()

    private fun load(ctx: Context): JSONObject? =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, null)?.let {
            try { JSONObject(it) } catch (_: Exception) { null }
        }

    fun state(ctx: Context): CameraModelState {
        val o = load(ctx) ?: return CameraModelState(false, null, 0.0, 0.0, 0, 0, 0, "የካሜራ ሞዴል ገና አልተሰራም።")
        return CameraModelState(
            o.optBoolean("ready"), o.optString("version", null),
            o.optDouble("sensitivity"), o.optDouble("specificity"),
            o.optInt("clean_count"), o.optInt("contaminated_count"),
            o.optInt("validation_count"), o.optString("reason")
        )
    }

    fun trainAndValidate(ctx: Context, all: List<Sample>): CameraModelState {
        val usable = all.filter {
            it.dataSource == "real" &&
            it.labReference?.isNotBlank() == true &&
            (it.groundTruth == "clean_per_protocol" || it.groundTruth == "contaminated_per_protocol")
        }.mapNotNull { s ->
            features(File(Repo(ctx).imgDir, s.image))?.let { s to it }
        }
        val clean = usable.filter { it.first.groundTruth == "clean_per_protocol" }
        val bad = usable.filter { it.first.groundTruth == "contaminated_per_protocol" }
        if (clean.size < CAMERA_MIN_SAMPLES_PER_CLASS || bad.size < CAMERA_MIN_SAMPLES_PER_CLASS) {
            return CameraModelState(false, null, 0.0, 0.0, clean.size, bad.size, 0,
                "ካሜራ ሞዴል፦ ቢያንስ $CAMERA_MIN_SAMPLES_PER_CLASS CLEAN እና $CAMERA_MIN_SAMPLES_PER_CLASS CONTAMINATED የላብ ማጣቀሻ ያላቸው የምስል ናሙናዎች ያስፈልጋሉ።")
        }

        val train = usable.filter { kotlin.math.abs(it.first.id.hashCode()) % 5 != 0 }
        val test = usable.filter { kotlin.math.abs(it.first.id.hashCode()) % 5 == 0 }
        if (test.none { it.first.groundTruth == "clean_per_protocol" } || test.none { it.first.groundTruth == "contaminated_per_protocol" }) {
            return CameraModelState(false, null, 0.0, 0.0, clean.size, bad.size, test.size,
                "ካሜራ blind validation ክፍሉ ሁለቱንም ክፍሎች አላካተተም።")
        }

        val means = DoubleArray(FEATURE_COUNT)
        val stds = DoubleArray(FEATURE_COUNT)
        for (j in 0 until FEATURE_COUNT) {
            means[j] = train.map { it.second[j] }.average()
            val variance = train.map { val d = it.second[j] - means[j]; d * d }.average()
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
            val dc = sqrt(n.indices.sumOf { j -> val d = n[j] - cleanCentroid[j]; d * d })
            val db = sqrt(n.indices.sumOf { j -> val d = n[j] - badCentroid[j]; d * d })
            return if (dc <= db) "clean_per_protocol" else "contaminated_per_protocol"
        }

        var tp = 0; var tn = 0; var fp = 0; var fn = 0
        test.forEach { s ->
            val p = pred(s.second)
            if (s.first.groundTruth == "contaminated_per_protocol" && p == "contaminated_per_protocol") tp++
            if (s.first.groundTruth == "clean_per_protocol" && p == "clean_per_protocol") tn++
            if (s.first.groundTruth == "clean_per_protocol" && p == "contaminated_per_protocol") fp++
            if (s.first.groundTruth == "contaminated_per_protocol" && p == "clean_per_protocol") fn++
        }
        val sens = if (tp + fn == 0) 0.0 else tp.toDouble() / (tp + fn)
        val spec = if (tn + fp == 0) 0.0 else tn.toDouble() / (tn + fp)
        val ready = sens >= CAMERA_MIN_SENSITIVITY && spec >= CAMERA_MIN_SPECIFICITY && fn == 0
        val reason = if (ready) "Camera appearance model passed blind validation." else
            "Camera model NOT READY: sensitivity=" + String.format(Locale.US, "%.2f", sens) +
                ", specificity=" + String.format(Locale.US, "%.2f", spec) + ", false_negatives=" + fn

        val o = JSONObject().apply {
            put("ready", ready); put("version", CAMERA_MODEL_VERSION)
            put("sensitivity", sens); put("specificity", spec)
            put("clean_count", clean.size); put("contaminated_count", bad.size); put("validation_count", test.size)
            put("reason", reason)
            put("means", JSONArray(means.toList()))
            put("stds", JSONArray(stds.toList()))
            put("clean_centroid", JSONArray(cleanCentroid.toList()))
            put("bad_centroid", JSONArray(badCentroid.toList()))
        }
        if (ready) save(ctx, o) else ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(KEY).apply()
        return CameraModelState(ready, if (ready) CAMERA_MODEL_VERSION else null, sens, spec, clean.size, bad.size, test.size, reason)
    }

    fun predict(ctx: Context, imageFile: File): CameraClassifierResult {
        val o = load(ctx) ?: return CameraClassifierResult("UNCERTAIN", null, false, null, "የተረጋገጠ ካሜራ ሞዴል የለም።")
        if (!o.optBoolean("ready")) return CameraClassifierResult("UNCERTAIN", null, false, null, "የተረጋገጠ ካሜራ ሞዴል የለም።")
        val x = features(imageFile) ?: return CameraClassifierResult("UNCERTAIN", null, true, o.optString("version", CAMERA_MODEL_VERSION), "ምስሉ ሊተነተን አልተቻለም።")
        return try {
            fun arr(k: String) = o.getJSONArray(k)
            val means = DoubleArray(FEATURE_COUNT) { arr("means").getDouble(it) }
            val stds = DoubleArray(FEATURE_COUNT) { arr("stds").getDouble(it) }
            val cc = DoubleArray(FEATURE_COUNT) { arr("clean_centroid").getDouble(it) }
            val bc = DoubleArray(FEATURE_COUNT) { arr("bad_centroid").getDouble(it) }
            val n = DoubleArray(FEATURE_COUNT) { j -> (x[j] - means[j]) / stds[j] }
            val dc = sqrt(n.indices.sumOf { j -> val d = n[j] - cc[j]; d * d })
            val db = sqrt(n.indices.sumOf { j -> val d = n[j] - bc[j]; d * d })
            val margin = kotlin.math.abs(db - dc) / (db + dc + 1e-9)
            val verdict = if (dc <= db) "CLEAN" else "CONTAMINATED"
            CameraClassifierResult(verdict, margin.coerceIn(0.0, 1.0), true, o.optString("version", CAMERA_MODEL_VERSION),
                "Validated camera appearance model.")
        } catch (_: Exception) {
            CameraClassifierResult("UNCERTAIN", null, true, o.optString("version", CAMERA_MODEL_VERSION), "የካሜራ ሞዴል መረጃ ተበላሽቷል።")
        }
    }
}
