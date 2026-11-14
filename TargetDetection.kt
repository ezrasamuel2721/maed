package et.maed.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.sqrt

data class TargetReference(
    val id: String,
    val target: String,
    val present: Boolean,
    val foodType: String,
    val storage: String,
    val collectedAt: String,
    val spectral: String?,
    val voc: String?
) {
    fun toJson() = JSONObject().apply {
        put("id", id); put("target", target); put("present", present)
        put("food_type", foodType); put("storage", storage); put("collected_at", collectedAt)
        put("as7341", spectral?.let { JSONObject(it) } ?: JSONObject.NULL)
        put("bme688", voc?.let { JSONObject(it) } ?: JSONObject.NULL)
    }
    companion object {
        fun from(o: JSONObject) = TargetReference(
            o.getString("id"), o.getString("target"), o.getBoolean("present"),
            o.optString("food_type","unspecified"), o.optString("storage","other"),
            o.getString("collected_at"),
            if (o.isNull("as7341")) null else o.getJSONObject("as7341").toString(),
            if (o.isNull("bme688")) null else o.getJSONObject("bme688").toString()
        )
    }
}

data class TargetModelState(
    val target: String, val ready: Boolean, val presentCount: Int, val absentCount: Int,
    val sensitivity: Double, val specificity: Double, val reason: String
)

data class TargetResult(val verdict: String, val confidence: Double?, val reason: String)

object TargetDetector {
    private const val FEATURE_COUNT=14
    private const val MIN_PER_CLASS=10
    private const val MIN_SENS=0.90
    private const val MIN_SPEC=0.90
    private const val PREF="maed_target_model"
    private const val KEY="state"
    private const val DATA="maed_target_refs.json"

    private fun dir(ctx: Context)=File(ctx.filesDir,"target").also{it.mkdirs()}
    private fun refsFile(ctx: Context)=File(dir(ctx),DATA)

    fun all(ctx: Context): List<TargetReference> =
        try {
            val a=JSONArray(refsFile(ctx).takeIf{it.exists()}?.readText() ?: "[]")
            (0 until a.length()).mapNotNull { try{TargetReference.from(a.getJSONObject(it))}catch(_:Exception){null} }
        } catch(_:Exception){ emptyList() }

    fun addReference(ctx: Context, r: TargetReference) {
        val a=JSONArray()
        all(ctx).forEach{a.put(it.toJson())}; a.put(r.toJson())
        refsFile(ctx).writeText(a.toString(2))
    }

    private fun features(spectral:String?, voc:String?): DoubleArray? = try {
        if(spectral==null || voc==null) return null
        val sp=JSONObject(spectral); val vo=JSONObject(voc)
        val keys=arrayOf("F1_415nm","F2_445nm","F3_480nm","F4_515nm","F5_555nm","F6_590nm","F7_630nm","F8_680nm","clear","nir")
        DoubleArray(FEATURE_COUNT).also { x ->
            for(i in keys.indices)x[i]=sp.getDouble(keys[i])
            x[10]=vo.getDouble("temperature_c"); x[11]=vo.getDouble("humidity_percent")
            x[12]=vo.getDouble("pressure_hpa"); x[13]=vo.getDouble("gas_resistance_ohm")
            if(x.any{!it.isFinite()}) throw IllegalArgumentException()
        }
    } catch(_:Exception){null}

    private fun features(r:TargetReference)=features(r.spectral,r.voc)

    private fun save(ctx:Context,o:JSONObject)=ctx.getSharedPreferences(PREF,Context.MODE_PRIVATE).edit().putString(KEY,o.toString()).apply()
    private fun load(ctx:Context)=ctx.getSharedPreferences(PREF,Context.MODE_PRIVATE).getString(KEY,null)?.let{try{JSONObject(it)}catch(_:Exception){null}}

    fun state(ctx:Context,target:String):TargetModelState {
        val rs=all(ctx).filter{it.target.equals(target.trim(),true)}
        val o=load(ctx)
        if(o==null || !o.optString("target").equals(target.trim(),true))
            return TargetModelState(target,false,rs.count{it.present},rs.count{!it.present},0.0,0.0,"ሞዴል አልተሠራም።")
        return TargetModelState(target,o.optBoolean("ready"),rs.count{it.present},rs.count{!it.present},
            o.optDouble("sensitivity"),o.optDouble("specificity"),o.optString("reason"))
    }

    fun train(ctx:Context,target:String):TargetModelState {
        val t=target.trim()
        val usable=all(ctx).filter{it.target.equals(t,true)}.mapNotNull{r->features(r)?.let{x->r to x}}
        val present=usable.filter{it.first.present}; val absent=usable.filter{!it.first.present}
        if(present.size<MIN_PER_CLASS || absent.size<MIN_PER_CLASS)
            return TargetModelState(t,false,present.size,absent.size,0.0,0.0,"ቢያንስ $MIN_PER_CLASS PRESENT እና $MIN_PER_CLASS ABSENT የsensor reference ናሙናዎች ያስፈልጋሉ።")
        val train=usable.filter{Math.abs(it.first.id.hashCode())%10<7}
        val test=usable.filter{Math.abs(it.first.id.hashCode())%10>=7}
        if(test.none{it.first.present}||test.none{!it.first.present})
            return TargetModelState(t,false,present.size,absent.size,0.0,0.0,"Blind validation ሁለቱንም classes አላካተተም።")
        val mean=DoubleArray(FEATURE_COUNT); val sd=DoubleArray(FEATURE_COUNT)
        for(j in 0 until FEATURE_COUNT){
            mean[j]=train.map{it.second[j]}.average()
            sd[j]=(train.map{val d=it.second[j]-mean[j];d*d}.average()).let{sqrt(it).takeIf{v->v>1e-9}?:1.0}
        }
        fun norm(x:DoubleArray)=DoubleArray(FEATURE_COUNT){j->(x[j]-mean[j])/sd[j]}
        fun centroid(p:Boolean)=DoubleArray(FEATURE_COUNT){
            val rows=train.filter{it.first.present==p}.map{norm(it.second)}
            DoubleArray(FEATURE_COUNT){j->rows.sumOf{it[j]}/rows.size}
        }
        val pc=centroid(true); val ac=centroid(false)
        fun pred(x:DoubleArray):Boolean{
            val n=norm(x)
            val dp=sqrt(n.indices.sumOf{j->val d=n[j]-pc[j];d*d})
            val da=sqrt(n.indices.sumOf{j->val d=n[j]-ac[j];d*d})
            return dp<da
        }
        var tp=0;var tn=0;var fp=0;var fn=0
        test.forEach{r->val p=pred(r.second);if(r.first.present&&p)tp++;if(!r.first.present&&!p)tn++;if(!r.first.present&&p)fp++;if(r.first.present&&!p)fn++}
        val sens=if(tp+fn==0)0.0 else tp.toDouble()/(tp+fn)
        val spec=if(tn+fp==0)0.0 else tn.toDouble()/(tn+fp)
        val ready=sens>=MIN_SENS&&spec>=MIN_SPEC&&fn==0
        if(!ready)return TargetModelState(t,false,present.size,absent.size,sens,spec,"Validation አልፏልም። sensitivity=$sens specificity=$spec false-negative=$fn")
        save(ctx,JSONObject().apply{
            put("target",t);put("ready",true);put("sensitivity",sens);put("specificity",spec);put("reason","Blind validation passed")
            put("mean",JSONArray(mean.toList()));put("sd",JSONArray(sd.toList()));put("present",JSONArray(pc.toList()));put("absent",JSONArray(ac.toList()))
        })
        return TargetModelState(t,true,present.size,absent.size,sens,spec,"Blind validation passed.")
    }

    fun predict(ctx:Context,target:String,spectral:String?,voc:String?):TargetResult {
        val o=load(ctx)?:return TargetResult("UNCERTAIN",null,"ለዚህ target የተረጋገጠ model የለም።")
        if(!o.optBoolean("ready")||!o.optString("target").equals(target.trim(),true))return TargetResult("UNCERTAIN",null,"Model ለዚህ target ዝግጁ አይደለም።")
        val x=features(spectral,voc)?:return TargetResult("UNCERTAIN",null,"AS7341/BME688 መረጃ አልተሟላም።")
        return try{
            val mean=DoubleArray(FEATURE_COUNT){o.getJSONArray("mean").getDouble(it)}
            val sd=DoubleArray(FEATURE_COUNT){o.getJSONArray("sd").getDouble(it)}
            val pc=DoubleArray(FEATURE_COUNT){o.getJSONArray("present").getDouble(it)}
            val ac=DoubleArray(FEATURE_COUNT){o.getJSONArray("absent").getDouble(it)}
            val n=DoubleArray(FEATURE_COUNT){(x[it]-mean[it])/sd[it]}
            val dp=sqrt(n.indices.sumOf{j->val d=n[j]-pc[j];d*d})
            val da=sqrt(n.indices.sumOf{j->val d=n[j]-ac[j];d*d})
            val conf=(kotlin.math.abs(da-dp)/(da+dp+1e-9)).coerceIn(0.0,1.0)
            if(conf<0.20)TargetResult("UNCERTAIN",conf,"Model margin በቂ አይደለም።")
            else if(dp<da)TargetResult("PRESENT",conf,"Validated target-detection model.")
            else TargetResult("ABSENT",conf,"Validated target-detection model.")
        }catch(_:Exception){TargetResult("UNCERTAIN",null,"Model data ተበላሽቷል።")}
    }
}
