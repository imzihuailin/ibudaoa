package io.github.ibudaoa.updates

import java.io.Closeable
import java.net.Proxy
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Public release metadata only. Never receives account, school, device or location material. */
data class ReleaseRepository private constructor(val url:String,val owner:String,val name:String) {
    val sourceUrl:String get()="https://raw.githubusercontent.com/$owner/$name/main/app/build.gradle.kts"
    val updateUrls:List<String> get()=listOf("https://ghfast.top/$sourceUrl", "https://gh-proxy.com/$sourceUrl", sourceUrl)
    companion object {
        fun parse(raw:String):ReleaseRepository?=runCatching {
            val uri=URI(raw.trim().removeSuffix("/"))
            require(uri.scheme=="https" && uri.host=="github.com" && uri.port==-1 && uri.userInfo==null && uri.rawQuery==null && uri.rawFragment==null)
            val parts=uri.rawPath.removePrefix("/").split('/')
            require(parts.size==2 && parts.all { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}")) && it !in setOf(".","..") })
            val name=parts[1].removeSuffix(".git");require(name.isNotBlank())
            ReleaseRepository("https://github.com/${parts[0]}/$name",parts[0],name)
        }.getOrNull()
    }
}
enum class UpdatePhase { IDLE, CHECKING, AVAILABLE, CURRENT, FAILED, INTERRUPTED, NOT_CONFIGURED }
data class UpdateSummary(val phase:UpdatePhase=UpdatePhase.IDLE,val version:String?=null) {
    val label:String get()=when(phase) {
        UpdatePhase.IDLE -> "点击检查新版本"
        UpdatePhase.CHECKING -> "正在检查更新…"
        UpdatePhase.AVAILABLE -> "发现新版本 $version，请在电脑上重新运行安装向导"
        UpdatePhase.CURRENT -> "当前已是最新版本"
        UpdatePhase.FAILED -> "检查失败，请稍后重试"
        UpdatePhase.INTERRUPTED -> "检查已暂停，点击重试"
        UpdatePhase.NOT_CONFIGURED -> "更新源尚未配置"
    }
}
/** Parse literal declarations only; remote Gradle code is never executed. */
internal object SourceUpdateDecoder {
    const val MAX_BYTES=131_072
    fun decode(bytes:ByteArray,currentCode:Int):UpdateSummary {
        require(bytes.size in 1..MAX_BYTES)
        val source=bytes.toString(Charsets.UTF_8)
        require(Regex("\\bversionCode\\s*=").findAll(source).count()==1 &&
            Regex("\\bversionName\\s*=").findAll(source).count()==1)
        val codes=Regex("(?m)^\\s*versionCode\\s*=\\s*([1-9][0-9]*)\\s*$").findAll(source).toList()
        val names=Regex("(?m)^\\s*versionName\\s*=\\s*\"([A-Za-z0-9][A-Za-z0-9.+_-]{0,99})\"\\s*$").findAll(source).toList()
        require(codes.size==1 && names.size==1)
        val code=codes.single().groupValues[1].toInt()
        return if(code>currentCode)UpdateSummary(UpdatePhase.AVAILABLE,names.single().groupValues[1])
            else UpdateSummary(UpdatePhase.CURRENT)
    }
}
/** Public source GET only. Fixed mirrors, no redirects or account material. */
internal class ReleaseUpdateClient(private val factory:(URL)->HttpsURLConnection={it.openConnection(Proxy.NO_PROXY) as HttpsURLConnection}):Closeable {
    private var active:HttpsURLConnection?=null
    private var closed=false
    fun check(repository:ReleaseRepository,currentCode:Int):UpdateSummary {
        for(source in repository.updateUrls) {
            if(synchronized(this) { closed })break
            val result=runCatching { read(URL(source),currentCode) }.getOrNull()
            if(result!=null && !synchronized(this) { closed })return result
        }
        return UpdateSummary(UpdatePhase.FAILED)
    }
    private fun read(url:URL,currentCode:Int):UpdateSummary {
        val connection=synchronized(this) {
            check(!closed && active==null)
            factory(url).also { active=it }
        }
        try {
            connection.instanceFollowRedirects=false;connection.useCaches=false;connection.requestMethod="GET"
            connection.connectTimeout=4_000;connection.readTimeout=4_000
            connection.setRequestProperty("Accept","text/plain")
            connection.setRequestProperty("User-Agent","ibudaoa-update-check")
            connection.setRequestProperty("Cache-Control","no-cache")
            val deadline=System.nanoTime()+8_000_000_000L
            check(connection.responseCode==200)
            return connection.inputStream.use { input ->
                val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(4096)
                while(true) {
                    check(!synchronized(this) { closed } && System.nanoTime()<deadline)
                    val n=input.read(buffer);if(n<0)break
                    require(output.size()+n<=SourceUpdateDecoder.MAX_BYTES)
                    output.write(buffer,0,n)
                }
                SourceUpdateDecoder.decode(output.toByteArray(),currentCode)
            }
        } finally { connection.disconnect();synchronized(this) { if(active===connection)active=null } }
    }
    @Synchronized override fun close() { closed=true;active?.disconnect();active=null }
}
