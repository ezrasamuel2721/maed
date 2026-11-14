package et.maed.app

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private const val VOICE_UNCERTAIN = "ማዕድ ምርመራውን አጠናቋል። ውጤቱ ገና አልተረጋገጠም።"
private const val TEXT_UNCERTAIN = "መረጃ ተሰብስቧል፤ የተረጋገጠ የምግብ ደህንነት ሞዴል ገና አልተፈተነም።"
private val GREEN = Color(0xFF1B5E3B)
private val STORAGE = listOf("room_temperature", "refrigerated", "frozen", "other")
private val DEMO = listOf(
    "Green Plate — Addis Ababa: Doro Wat 220 ETB · Injera Combo 160 ETB · Shiro 130 ETB",
    "Hawassa Family Restaurant — Hawassa: Fish 280 ETB · Tibs 260 ETB · Pasta 150 ETB",
    "Adama Fresh Kitchen — Adama: Chicken 230 ETB · Rice 140 ETB · Salad 100 ETB"
)
private val MENU = listOf(
    "dataset" to "🗂️ Dataset", "model" to "🤖 AI ሞዴል", "screening" to "🛡️ Food Safety Screening", "target" to "🧪 ንጥረ ነገር ፈልግ", "settings" to "⚙️ ቅንብሮች", "report" to "⚠️ ሪፖርት", "diagnostics" to "🔧 Hardware Test",
    "🍽️ ምግብ ቤቶች" to "🍽️ ምግብ ቤቶች", "🏨 ሆቴሎች" to "🏨 ሆቴሎች", "📋 ሜኑዎች" to "📋 ሜኑዎች",
    "💰 ዋጋዎች" to "💰 ዋጋዎች", "🛒 ትዕዛዝ" to "🛒 ትዕዛዝ", "📍 በአካባቢዬ ያሉ" to "📍 በአካባቢዬ ያሉ",
    "👤 የንግድ ባለቤት መመዝገቢያ" to "👤 የንግድ ባለቤት መመዝገቢያ"
)

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private lateinit var repo: Repo
    private var tts: TextToSpeech? = null
    private var amVoice = false
    private var screen by mutableStateOf("home")
    private var menuOpen by mutableStateOf(false)
    private var pendingImg by mutableStateOf<File?>(null)
    private var reportPhoto by mutableStateOf<File?>(null)
    private var last by mutableStateOf<Sample?>(null)
    private var msg by mutableStateOf("")
    private var diagnostic by mutableStateOf("")
    private var diagnosticBusy by mutableStateOf(false)
    private var modelState by mutableStateOf<ModelState?>(null)
    private var modelBusy by mutableStateOf(false)
    private var modelMessage by mutableStateOf("")
    private var cameraState by mutableStateOf<CameraModelState?>(null)
    private var samples by mutableStateOf(listOf<Sample>())
    private var targetResult by mutableStateOf<TargetResult?>(null)
    private var targetMessage by mutableStateOf("")
    private var camFile: File? = null
    private var pickFor = "sample"

    private val takePic = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok && camFile != null) { pendingImg = camFile; screen = "record" } else { msg = "ፎቶ አልተነሳም።"; screen = "home" }
    }
    private val pick = registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) copyIn(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = Repo(this)
        samples = repo.all()
        modelState = FoodClassifier.state(this)
        cameraState = CameraFoodClassifier.state(this)
        setContent { MaterialTheme { App() } }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) amVoice = tts.setLanguage(Locale("am", "ET")) >= TextToSpeech.LANG_AVAILABLE
    }

    override fun onDestroy() { tts?.shutdown(); tts = null; super.onDestroy() }

    private fun speak(t: String) {
        val voice = tts ?: TextToSpeech(this, this).also { tts = it }
        if (amVoice) voice.speak(t, TextToSpeech.QUEUE_FLUSH, null, "maed")
        else msg = "የአማርኛ ድምፅ (Text-to-Speech) በዚህ ስልክ አልተገኘም። ጽሑፉን ያንብቡ።"
    }

    private fun startCamera() {
        try {
            val f = File(repo.imgDir, "tmp_${System.currentTimeMillis()}.jpg")
            camFile = f
            takePic.launch(FileProvider.getUriForFile(this, "$packageName.fileprovider", f))
        } catch (e: Exception) { msg = "ካሜራ መክፈት አልተቻለም። 🖼️ ፎቶ ምረጥ ይጠቀሙ።" }
    }

    private fun copyIn(uri: Uri) {
        try {
            val f = File(repo.imgDir, "in_${System.currentTimeMillis()}.jpg")
            contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            if (pickFor == "report") reportPhoto = f else { pendingImg = f; screen = "record" }
        } catch (e: Exception) { msg = "ፎቶውን ማንበብ አልተቻለም።" }
    }

    private fun finishSample(food: String, storage: String) {
        val img = pendingImg ?: return
        screen = "working"
        Thread {
            val p = getSharedPreferences("maed", MODE_PRIVATE)
            val a = SensorClient.acquire(p.getString("url", "") ?: "", p.getString("token", "") ?: "")
            val id = "MAED-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + "-" + UUID.randomUUID().toString().take(4)
            img.copyTo(File(repo.imgDir, "$id.jpg"), true); img.delete()
            var piName: String? = null
            if (a.piImageB64 != null) try {
                piName = "$id-pi.jpg"; File(repo.imgDir, piName).writeBytes(Base64.decode(a.piImageB64, Base64.DEFAULT))
            } catch (e: Exception) { piName = null }
            val base = Sample(id, food, storage, SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date()),
                a.tempC, a.humidity, "$id.jpg", piName, a.spectral, a.voc, "not_yet_available",
                false, "UNCERTAIN", null, false, null, a.sensorStatus, a.status, "real", null, null, null)
            val sensorResult = FoodClassifier.predict(this, base)
            val cameraResult = CameraFoodClassifier.predict(this, File(repo.imgDir, "$id.jpg"))
            val fused = if (sensorResult.modelValidated && cameraResult.modelValidated &&
                (sensorResult.verdict == "CLEAN" || sensorResult.verdict == "CONTAMINATED") &&
                sensorResult.verdict == cameraResult.verdict) {
                ClassifierResult(sensorResult.verdict,
                    listOfNotNull(sensorResult.confidence, cameraResult.confidence).minOrNull(),
                    true, "MAED-FUSION-1.0", "Sensor + camera models agreed.")
            } else {
                ClassifierResult("UNCERTAIN", null, false, null,
                    "Sensor/camera validation or agreement is insufficient.")
            }
            val screening = if (fused.verdict == "UNCERTAIN") FoodRiskScreening.screen(base, repo.all())
            else ScreeningResult("FUSION", fused.confidence, fused.reason)
            val s = base.copy(aiResult = fused.verdict, modelVersion = fused.modelVersion,
                modelValidated = fused.modelValidated, riskScore = fused.confidence ?: screening.score)
            repo.save(s)
            runOnUiThread {
                last = s; samples = repo.all()
                modelState = FoodClassifier.state(this)
                cameraState = CameraFoodClassifier.state(this)
                screen = "result"
                speak(if (fused.verdict == "CLEAN") "ማዕድ፦ ካሜራና ሴንሰር ሞዴሎች ተስማምተው ጤነኛ ብለዋል።"
                    else if (fused.verdict == "CONTAMINATED") "ማዕድ፦ ካሜራና ሴንሰር ሞዴሎች ተስማምተው የተበከለ ብለዋል።"
                    else VOICE_UNCERTAIN)
            }        }.start()
    }

    private fun trainModel() {
        if (modelBusy) return
        modelBusy = true
        modelMessage = ""
        Thread {
            val all = repo.all()
            val result = FoodClassifier.trainAndValidate(this, all)
            val cam = CameraFoodClassifier.trainAndValidate(this, all)
            runOnUiThread {
                modelState = result
                cameraState = cam
                modelBusy = false
                modelMessage = result.reason + "\n" + cam.reason
            }
        }.start()
    }

    private fun runDiagnostics() {
        val p = getSharedPreferences("maed", MODE_PRIVATE)
        val base = p.getString("url", "")?.trim()?.trimEnd('/') ?: ""
        val token = p.getString("token", "") ?: ""
        if (base.isBlank()) {
            diagnostic = "Pi URL አልተሞላም። ወደ ⚙️ ቅንብሮች ይሂዱ።"
            return
        }
        diagnosticBusy = true
        diagnostic = ""
        Thread {
            try {
                val c = URL(base + "/v1/diagnostics").openConnection() as HttpURLConnection
                c.connectTimeout = 5000
                c.readTimeout = 45000
                c.setRequestProperty("X-MAED-Token", token)
                val code = c.responseCode
                val body = if (code == 200) c.inputStream.bufferedReader().readText() else "HTTP_$code"
                runOnUiThread {
                    diagnosticBusy = false
                    diagnostic = if (code == 200) formatDiagnostic(body) else "ግንኙነት አልተሳካም፦ $body"
                }
                c.disconnect()
            } catch (e: Exception) {
                runOnUiThread {
                    diagnosticBusy = false
                    diagnostic = "Pi ላይ መድረስ አልተቻለም፦ ${e.javaClass.simpleName}"
                }
            }
        }.start()
    }

    private fun formatDiagnostic(body: String): String {
        return try {
            val o = JSONObject(body)
            val s = o.optJSONObject("sensor_status")
            val cam = s?.optString("camera", "UNKNOWN") ?: "UNKNOWN"
            val as7 = s?.optString("as7341", "UNKNOWN") ?: "UNKNOWN"
            val bme = s?.optString("bme688", "UNKNOWN") ?: "UNKNOWN"
            val elapsed = o.optDouble("elapsed_seconds", 0.0)
            "🔧 Hardware Test\n\n" +
                "📷 Camera: $cam\n" +
                "🌈 AS7341: $as7\n" +
                "🌡️ BME688: $bme\n" +
                "⏱️ Time: ${elapsed}s\n\n" +
                "🟡 Food verdict: UNCERTAIN\n" +
                "ይህ ሙከራ hardware ግንኙነትን ብቻ ይፈትሻል፤ ምግብን CLEAN/CONTAMINATED አይለይም።"
        } catch (e: Exception) {
            "የPi diagnostic መልስ ማንበብ አልተቻለም።"
        }
    }

    private fun share(f: File, mime: String) {
        val u = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = mime; putExtra(Intent.EXTRA_STREAM, u); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, null))
    }

    private fun saveReport(vals: List<String>) {
        val f = File(filesDir, "reports.json")
        val arr = try { JSONArray(f.readText()) } catch (e: Exception) { JSONArray() }
        val keys = listOf("food_type", "location", "detected_issue", "description", "contact")
        val o = JSONObject(); keys.forEachIndexed { i, k -> o.put(k, vals[i]) }
        o.put("reported_at", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date()))
        o.put("photo_file", reportPhoto?.name ?: JSONObject.NULL)
        o.put("forwarded_to_authority", false)
        arr.put(o); f.writeText(arr.toString(2)); reportPhoto = null
        msg = "ሪፖርቱ በስልክዎ ላይ ተቀምጧል። ወደ ባለሥልጣን አልተላከም — እውነተኛ API ገና አልተገናኘም።"
    }

    @Composable private fun Scroll(c: @Composable ColumnScope.() -> Unit) =
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp), content = c)

    @Composable private fun Photo(f: File?) {
        val bmp = remember(f) { f?.let { BitmapFactory.decodeFile(it.path, BitmapFactory.Options().apply { inSampleSize = 4 }) } }
        if (bmp != null) Image(bmp.asImageBitmap(), "የምግብ ፎቶ", Modifier.fillMaxWidth().heightIn(max = 260.dp))
    }

    @Composable private fun App() {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().background(GREEN).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("ማዕድ · MAED", color = Color.White, fontSize = 20.sp, modifier = Modifier.weight(1f))
                Box {
                    TextButton(onClick = { menuOpen = true }) { Text("☰ ሜኑ", color = Color.White) }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("🏠 መነሻ") }, onClick = { menuOpen = false; screen = "home" })
                        MENU.forEach { (k, l) -> DropdownMenuItem(text = { Text(l) }, onClick = { menuOpen = false; screen = k }) }
                    }
                }
            }
            Box(Modifier.weight(1f).padding(16.dp)) {
                when (screen) {
                    "home" -> Home(); "record" -> Record(); "working" -> Working(); "result" -> Result()
                    "dataset" -> Dataset(); "model" -> ModelScreen(); "screening" -> ScreeningInfo(); "target" -> TargetScreen(); "settings" -> Settings(); "report" -> Report(); "diagnostics" -> Diagnostics(); else -> Layer(screen)
                }
            }
        }
    }

    @Composable private fun Home() = Scroll {
        Spacer(Modifier.height(16.dp))
        Button(onClick = { msg = ""; startCamera() }, Modifier.fillMaxWidth().height(96.dp),
            colors = ButtonDefaults.buttonColors(containerColor = GREEN)) { Text("📷 ምግብ መርምር", fontSize = 24.sp) }
        Text("ምግብህን ከመመገብህ በፊት መርምር")
        OutlinedButton(onClick = { msg = ""; pickFor = "sample"; pick.launch("image/*") }, Modifier.fillMaxWidth()) { Text("🖼️ ፎቶ ምረጥ") }
        if (msg.isNotEmpty()) Text(msg, color = Color(0xFFB42318))
        Text("ማዕድ አሁን ① validated food model ካለ CLEAN/CONTAMINATED ይላል፤ ② የተወሰነ target ካለ PRESENT/ABSENT/UNCERTAIN ይፈልጋል፤ ③ ላብ ሳይኖር screening LOW/MEDIUM/HIGH ብሎ ያሳያል።", fontSize = 13.sp)
    }

    @Composable private fun Record() {
        var food by remember { mutableStateOf("") }
        var storage by remember { mutableStateOf(STORAGE[0]) }
        Scroll {
            Photo(pendingImg)
            Text("ቤት ውስጥ ያለ መደበኛ ምግብ ብቻ ይመዝግቡ። ሆን ብሎ የተበላሸ ወይም የተበከለ ምግብ አያዘጋጁ።", fontSize = 13.sp)
            OutlinedTextField(food, { food = it }, label = { Text("የምግብ ዓይነት") }, modifier = Modifier.fillMaxWidth())
            Text("የማከማቻ ሁኔታ")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                STORAGE.forEach { FilterChip(storage == it, { storage = it }, { Text(it.replace('_', ' '), fontSize = 11.sp) }) }
            }
            Button(onClick = { finishSample(food.trim().ifEmpty { "unspecified" }, storage) }, Modifier.fillMaxWidth()) { Text("ቀጥል — ሴንሰር ዳታ ሰብስብ") }
            TextButton(onClick = { pendingImg?.delete(); pendingImg = null; screen = "home" }) { Text("ሰርዝ") }
        }
    }

    @Composable private fun Working() = Box(Modifier.fillMaxSize(), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(); Text("በምርመራ ላይ…") }
    }

    @Composable private fun Result() = Scroll {
        val s = last
        Photo(s?.let { File(repo.imgDir, it.image) })
        Column(Modifier.fillMaxWidth().background(Color(0xFFFFF3CD)).padding(14.dp)) {
            when (s?.aiResult) {
                "CLEAN" -> { Text("🟢 CLEAN — ጤነኛ", fontSize = 22.sp); Text("VERIFIED MODEL RESULT — ይህ ውጤት የተረጋገጠ የreference ዳታ ላይ የተፈተነ ሞዴል ነው።") }
                "CONTAMINATED" -> { Text("🔴 CONTAMINATED — የተበከለ", fontSize = 22.sp); Text("VERIFIED MODEL RESULT — ይህ ውጤት የተረጋገጠ የreference ዳታ ላይ የተፈተነ ሞዴል ነው።") }
                else -> {
                    val score = s?.riskScore
                    if (score != null) {
                        val level = when {
                            score < 0.35 -> "LOW"
                            score < 0.65 -> "MEDIUM"
                            else -> "HIGH"
                        }
                        val icon = when (level) { "LOW" -> "🟢"; "MEDIUM" -> "🟡"; else -> "🔴" }
                        Text("$icon $level SCREENING RISK", fontSize = 22.sp)
                        Text("Screening score: ${String.format(Locale.US, "%.2f", score)}")
                        Text("ውጤቱ ከተመረጡ baseline መለኪያዎች ጋር ያለውን sensor-pattern ልዩነት ያሳያል።")
                        Text("⚠️ ይህ sensor-pattern screening ብቻ ነው። ምግቡ ጤነኛ ወይም የተበከለ መሆኑን አያረጋግጥም።")
                    } else {
                        Text("🟡 UNCERTAIN", fontSize = 22.sp)
                        Text(TEXT_UNCERTAIN)
                    }
                }
            }
        }
        if (s != null) {
            Text("Sample ID: ${s.id}", fontSize = 12.sp)
            Text("Acquisition: ${s.acquisitionStatus}", fontSize = 12.sp)
            Text("Sensors: ${s.sensorStatus}", fontSize = 12.sp)
            Text("model_validated=${s.modelValidated} · data_source=${s.dataSource}", fontSize = 12.sp)
        }
        if (msg.isNotEmpty()) Text(msg, fontSize = 12.sp)
        Button(onClick = { speak(VOICE_UNCERTAIN) }) { Text("🔊 እንደገና አንብብ") }
        OutlinedButton(onClick = { tts?.stop(); msg = ""; screen = "home" }) { Text("አዲስ ምርመራ") }
    }

    @Composable private fun TargetScreen() {
        var target by remember { mutableStateOf("") }
        var food by remember { mutableStateOf("") }
        var storage by remember { mutableStateOf(STORAGE[0]) }
        var present by remember { mutableStateOf(true) }
        var busy by remember { mutableStateOf(false) }
        val state = TargetDetector.state(this@MainActivity, target)
        Scroll {
            Text("🧪 የተወሰነ ንጥረ ነገር መፈለጊያ", fontSize = 22.sp)
            Text("የምትፈልጉትን ንጥረ ነገር ስም ያስገቡ። መጀመሪያ PRESENT እና ABSENT የእውነተኛ sensor reference ናሙናዎች በመሰብሰብ model መረጋገጥ አለበት።", fontSize = 13.sp)
            OutlinedTextField(target, { target = it }, label = { Text("የሚፈለገው ንጥረ ነገር") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(food, { food = it }, label = { Text("የምግብ/መጠጥ ዓይነት") }, modifier = Modifier.fillMaxWidth())
            Text("ይህ reference ንጥረ ነገሩን ይዟል?")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(present, { present = true }, { Text("PRESENT") })
                FilterChip(!present, { present = false }, { Text("ABSENT") })
            }
            Text("የማከማቻ ሁኔታ")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                STORAGE.forEach { FilterChip(storage == it, { storage = it }, { Text(it.replace('_',' '), fontSize=11.sp) }) }
            }
            Button(
                enabled = target.isNotBlank() && !busy,
                onClick = {
                    busy = true; targetMessage = ""
                    Thread {
                        val p = getSharedPreferences("maed", MODE_PRIVATE)
                        val a = SensorClient.acquire(p.getString("url","") ?: "", p.getString("token","") ?: "")
                        val id = "TGT-" + System.currentTimeMillis()
                        if (a.spectral == null || a.voc == null) {
                            runOnUiThread { busy=false; targetMessage="❌ እውነተኛ AS7341/BME688 መረጃ አልተገኘም። Reference አልተመዘገበም።" }
                        } else {
                            TargetDetector.addReference(this@MainActivity, TargetReference(id,target.trim(),present,food.trim().ifEmpty{"unspecified"},storage,
                                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX",Locale.US).format(Date()),a.spectral,a.voc))
                            runOnUiThread { busy=false; targetMessage="✅ Reference ተመዝግቧል። " + if(present) "PRESENT" else "ABSENT" }
                        }
                    }.start()
                },
                modifier = Modifier.fillMaxWidth()
            ) { if(busy) CircularProgressIndicator(modifier=Modifier.size(20.dp)) else Text("📡 Sensor reference ሰብስብ") }
            Text("PRESENT: ${state.presentCount} · ABSENT: ${state.absentCount}")
            Text("Sensitivity: ${state.sensitivity} · Specificity: ${state.specificity}", fontSize=12.sp)
            Button(enabled=target.isNotBlank(), onClick={
                Thread {
                    val r=TargetDetector.train(this@MainActivity,target)
                    runOnUiThread { targetMessage=r.reason }
                }
            }, modifier=Modifier.fillMaxWidth()) { Text("🤖 Target model አሰልጥን እና Validate አድርግ") }
            Button(enabled=target.isNotBlank() && !busy, onClick={
                busy=true; targetResult=null
                Thread {
                    val p=getSharedPreferences("maed",MODE_PRIVATE)
                    val a=SensorClient.acquire(p.getString("url","") ?: "",p.getString("token","") ?: "")
                    val r=TargetDetector.predict(this@MainActivity,target,a.spectral,a.voc)
                    runOnUiThread { busy=false; targetResult=r }
                }.start()
            }, modifier=Modifier.fillMaxWidth()) { Text("🔎 አሁን ይህን target ፈልግ") }
            targetResult?.let { r ->
                Column(Modifier.fillMaxWidth().background(Color(0xFFEEF4F0)).padding(14.dp)) {
                    when(r.verdict) {
                        "PRESENT" -> Text("🔴 TARGET PRESENT",fontSize=22.sp)
                        "ABSENT" -> Text("🟢 TARGET NOT DETECTED",fontSize=22.sp)
                        else -> Text("🟡 UNCERTAIN",fontSize=22.sp)
                    }
                    r.confidence?.let { Text("Confidence: ${String.format(Locale.US,"%.2f",it)}") }
                    Text(r.reason,fontSize=12.sp)
                }
            }
            if(targetMessage.isNotEmpty()) Text(targetMessage,fontSize=13.sp)
            Text("⚠️ ይህ ስርዓት ማንኛውንም ንጥረ ነገር በአንድ sensor በአውቶማቲክ እንደሚለይ አይቆጥርም። የተመረጠው target በAS7341/BME688 ላይ ልዩ እና ተደጋጋሚ sensor signature ካሳየ ብቻ የvalidated PRESENT/ABSENT ውጤት ይሰጣል።",fontSize=12.sp)
        }
    }

    @Composable private fun ScreeningInfo() = Scroll {
        Text("🛡️ Food Safety Screening", fontSize = 22.sp)
        Text("ላብራቶሪ ውጤት ሳይኖር የምንጠቀመው የመጀመሪያ ደረጃ screening ነው።", fontSize = 14.sp)
        Column(Modifier.fillMaxWidth().background(Color(0xFFEEF4F0)).padding(14.dp)) {
            Text("🟢 LOW", fontSize = 18.sp)
            Text("የsensor pattern ከMAED reference data ጋር በቅርበት ተገኝቷል።")
            Spacer(Modifier.height(6.dp))
            Text("🟡 MEDIUM", fontSize = 18.sp)
            Text("መካከለኛ ልዩነት ተገኝቷል።")
            Spacer(Modifier.height(6.dp))
            Text("🔴 HIGH", fontSize = 18.sp)
            Text("ከreference sensor pattern ጋር ከፍተኛ ልዩነት ተገኝቷል።")
        }
        Text("⚠️ እነዚህ ሶስቱ ደረጃዎች የብክለት ማረጋገጫ አይደሉም። በተለይ HIGH ማለት «ተበክሏል» ማለት አይደለም።", fontSize = 13.sp)
        Text("📌 በመጀመሪያ በተመሳሳይ ምግብ/ማከማቻ ሁኔታ የተለመዱ ቢያንስ 3 እውነተኛ sensor መለኪያዎችን Dataset ውስጥ Baseline ያድርጉ። ከዚያ አዲስ ናሙና ሲመረመር ከእነሱ ጋር ይነጻጸራል።", fontSize = 13.sp)
        Text("Camera image በዚህ screening engine ውስጥ ብቻውን የብክለት ማረጋገጫ አይሆንም።", fontSize = 13.sp)
    }

    @Composable private fun ModelScreen() = Scroll {
        val m = modelState ?: FoodClassifier.state(this@MainActivity)
        Text("🤖 MAED AI ሞዴል", fontSize = 22.sp)
        Text("Sensor baseline: AS7341 + BME688", fontSize = 13.sp)
        Text("Camera model: visible-appearance features", fontSize = 13.sp)
        Text("Validated AI ሞዴል ለማሰልጠን የላብ ማጣቀሻ ያላቸው ናሙናዎች ያስፈልጋሉ። ካሜራው የሚያየውን የመልክ ልዩነት ብቻ ይማራል፤ የማይታይ ብክለትን በካሜራ ብቻ አያረጋግጥም።")
        Column(Modifier.fillMaxWidth().background(Color(0xFFEEF4F0)).padding(14.dp)) {
            Text(if (m.ready) "🟢 MODEL READY" else "🟡 MODEL NOT READY", fontSize = 20.sp)
            Text("CLEAN: " + m.cleanCount + " · CONTAMINATED: " + m.contaminatedCount + " · validation: " + m.validationCount)
            Text("Sensitivity: " + m.sensitivity)
            Text("Specificity: " + m.specificity)
            Text(m.reason, fontSize = 12.sp)
        }
        val c = cameraState ?: CameraFoodClassifier.state(this@MainActivity)
        Column(Modifier.fillMaxWidth().background(Color(0xFFF4F0E8)).padding(14.dp)) {
            Text(if (c.ready) "🟢 CAMERA MODEL READY" else "🟡 CAMERA MODEL NOT READY", fontSize = 20.sp)
            Text("CLEAN: ${c.cleanCount} · CONTAMINATED: ${c.contaminatedCount} · validation: ${c.validationCount}")
            Text("Sensitivity: ${c.sensitivity} · Specificity: ${c.specificity}")
            Text(c.reason, fontSize = 12.sp)
        }
        Text("🔗 Final verdict: CLEAN/CONTAMINATED የሚሰጠው የsensor እና camera የተረጋገጡ ሞዴሎች ሲስማሙ ብቻ ነው። ካልተስማሙ → UNCERTAIN።", fontSize = 12.sp)
        if (m.ready) {
            Text("🛡️ TRUE VERIFICATION GATE: PASSED", fontSize = 18.sp)
            Text("Verification ID: ${m.verificationId ?: "available"} · False negatives: ${m.falseNegatives}")
            Button(onClick = {
                FoodClassifier.exportVerificationCertificate(this@MainActivity)?.let { share(it, "application/json") }
            }, modifier = Modifier.fillMaxWidth()) {
                Text("📄 የማረጋገጫ ሰርተፍኬት አውጣ")
            }
        } else {
            Text("🔒 TRUE VERIFICATION GATE: LOCKED — በቂ የተረጋገጠ reference data ሳይኖር ውጤቱ አይረጋገጥም።", fontSize = 12.sp)
        }
        Button(onClick = { trainModel() }, enabled = !modelBusy, modifier = Modifier.fillMaxWidth()) {
            if (modelBusy) CircularProgressIndicator(modifier = Modifier.size(20.dp)) else Text("▶ AI ሞዴልን አሰልጥን እና Validate አድርግ")
        }
        if (modelMessage.isNotEmpty()) Text(modelMessage, fontSize = 13.sp)
        Text("⚠️ ሞዴሉ የvalidation መስፈርትን ካላለፈ CLEAN/CONTAMINATED አይሰጥም።", fontSize = 12.sp)
    }

    @Composable private fun Dataset() = Scroll {
        val real = samples.count { it.dataSource == "real" }
        Text("ናሙናዎች: ${samples.size} (real: $real · simulated: ${samples.size - real})")
        Text("Export የሚያካትተው real ብቻ ነው። Ground truth + የምርመራ ዘዴ + reference ID ካለ ብቻ ለvalidation ይቆጠራል። Screening ግን የተመረጡ 📌 baseline ናሙናዎችን ብቻ እንደ ማነጻጸሪያ ይጠቀማል።", fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { if (real > 0) share(repo.exportJson(), "application/json") }) { Text("JSON") }
            Button(onClick = { if (real > 0) share(repo.exportCsv(), "text/csv") }) { Text("CSV") }
        }
        samples.forEach { s ->
            var open by remember { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth().background(Color(0xFFEEF4F0)).padding(10.dp)) {
                Text(s.id, fontSize = 13.sp); Text("${s.foodType} · ${s.storage} · ${s.acquisitionStatus}", fontSize = 12.sp)
                Text(if (s.baselineReference) "📌 SCREENING BASELINE — ይህ ናሙና ለተመሳሳይ ምግብ/ማከማቻ ማነጻጸሪያ ይጠቀማል።" else "ይህ ናሙና እንደ baseline አልተመረጠም።", fontSize = 11.sp)
                Row {
                    Box {
                        TextButton(onClick = { open = true }) { Text("GT: " + (GROUND_TRUTH.find { it.first == s.groundTruth }?.second ?: s.groundTruth), fontSize = 12.sp) }
                        DropdownMenu(open, { open = false }) {
                            GROUND_TRUTH.forEach { (k, l) -> DropdownMenuItem({ Text(l) }, { open = false; repo.setGroundTruth(s.id, k); samples = repo.all() }) }
                        }
                    }
                    TextButton(onClick = { repo.setBaselineReference(s.id, !s.baselineReference); samples = repo.all() }) { Text(if (s.baselineReference) "📌 Baseline አስወግድ" else "📌 Baseline አድርግ") }
                        TextButton(onClick = { repo.delete(s.id); samples = repo.all() }) { Text("🗑️ ሰርዝ", color = Color(0xFFB42318)) }
                }
                var labRef by remember(s.id, s.labReference) { mutableStateOf(s.labReference ?: "") }
                OutlinedTextField(
                    value = labRef,
                    onValueChange = { labRef = it },
                    label = { Text("የላብራቶሪ ማጣቀሻ") },
                    supportingText = { Text("የተረጋገጠ የላብ ሪፖርት ቁጥር ብቻ ያስገቡ።") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(onClick = { repo.setLabReference(s.id, labRef); samples = repo.all() }) {
                    Text("💾 የላብ ማጣቀሻ አስቀምጥ")
                }
                var gtMethod by remember(s.id, s.groundTruthMethod) { mutableStateOf(s.groundTruthMethod ?: "") }
                var gtRef by remember(s.id, s.groundTruthReferenceId) { mutableStateOf(s.groundTruthReferenceId ?: "") }
                var evidence by remember(s.id, s.evidenceStorage) { mutableStateOf(s.evidenceStorage ?: "") }
                OutlinedTextField(
                    value = gtMethod,
                    onValueChange = { gtMethod = it },
                    label = { Text("የground-truth ምርመራ ዘዴ") },
                    supportingText = { Text("ለምሳሌ approved laboratory / food-safety protocol") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = gtRef,
                    onValueChange = { gtRef = it },
                    label = { Text("የground-truth ሪፖርት/መለያ ቁጥር") },
                    supportingText = { Text("የላብ ሪፖርት ወይም የተፈቀደ protocol reference ቁጥር") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = evidence,
                    onValueChange = { evidence = it },
                    label = { Text("የማስረጃ ማከማቻ") },
                    supportingText = { Text("ለምሳሌ secure lab file / report archive / approved repository") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(onClick = { repo.setGroundTruthEvidence(s.id, gtMethod, gtRef, evidence); samples = repo.all() }) {
                    Text("🧾 የማረጋገጫ ማስረጃ አስቀምጥ")
                }
            }
        }
    }

    @Composable private fun Diagnostics() = Scroll {
        Text("🔧 Hardware Test", fontSize = 22.sp)
        Text("Raspberry Pi + Camera + AS7341 + BME688 የመገናኛ ሙከራ", fontSize = 13.sp)
        Button(
            onClick = { runDiagnostics() },
            enabled = !diagnosticBusy,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (diagnosticBusy) CircularProgressIndicator(modifier = Modifier.size(20.dp))
            else Text("▶ Hardware Test ጀምር")
        }
        if (diagnostic.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().background(Color(0xFFEEF4F0)).padding(14.dp)) {
                Text(diagnostic, fontSize = 14.sp)
            }
        }
        Text(
            "⚠️ ማስታወሻ፦ Hardware Test የsensor ግንኙነትን እና እውነተኛ መለኪያ መኖሩን ይፈትሻል። የምግብ ደህንነት ውሳኔ አይሰጥም።",
            fontSize = 12.sp
        )
    }

    @Composable private fun Settings() {
        val p = getSharedPreferences("maed", MODE_PRIVATE)
        var url by remember { mutableStateOf(p.getString("url", "") ?: "") }
        var tok by remember { mutableStateOf(p.getString("token", "") ?: "") }
        Scroll {
            Text("Raspberry Pi acquisition service")
            OutlinedTextField(url, { url = it; p.edit().putString("url", it).apply() }, label = { Text("URL (ለምሳሌ http://192.168.1.50:8080)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(tok, { tok = it; p.edit().putString("token", it).apply() }, label = { Text("Token (MAED_TOKEN)") }, modifier = Modifier.fillMaxWidth())
            Text("URL ባዶ ከሆነ ወይም Pi ካልተገኘ ሁኔታው SENSOR_NOT_CONNECTED ተብሎ ይመዘገባል፤ ምንም ሐሰተኛ እሴት አይፈጠርም። Simulation mode በV1.4 አልተተገበረም።", fontSize = 12.sp)
        }
    }

    @Composable private fun Report() {
        var v by remember { mutableStateOf(listOf("", "", "", "", "")) }
        val labels = listOf("የምግብ ዓይነት", "ቦታ/ምግብ ቤት", "የተገኘ ችግር", "መግለጫ", "ስልክ/ኢሜይል (አማራጭ)")
        Scroll {
            Text("⚠️ ሪፖርት (ቀን/ሰዓት በራስ-ሰር ይመዘገባል)")
            labels.forEachIndexed { i, l -> OutlinedTextField(v[i], { x -> v = v.toMutableList().also { it[i] = x } }, label = { Text(l) }, modifier = Modifier.fillMaxWidth()) }
            OutlinedButton(onClick = { pickFor = "report"; pick.launch("image/*") }) { Text(if (reportPhoto != null) "🖼️ ፎቶ ተመርጧል" else "🖼️ ፎቶ ምረጥ") }
            Button(onClick = { saveReport(v); v = listOf("", "", "", "", "") }) { Text("አስቀምጥ") }
            if (msg.isNotEmpty()) Text(msg, fontSize = 12.sp)
            Text("ከማንኛውም የመንግሥት ባለሥልጣን API ጋር አልተገናኘም።", fontSize = 12.sp)
        }
    }

    @Composable private fun Layer(title: String) = Scroll {
        Text(title, fontSize = 20.sp)
        if (title.contains("ምግብ ቤቶች") || title.contains("ሜኑዎች") || title.contains("ዋጋዎች")) {
            Text("DEMO DATA — እውነተኛ ንግዶች አይደሉም፤ ምንም አልተረጋገጠም።", fontSize = 12.sp)
            DEMO.forEach { Text(it) }
        } else Text("ይህ ክፍል በመዘጋጀት ላይ ነው።")
    }
}
