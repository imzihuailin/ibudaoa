package io.github.ibudaoa.updates

import java.io.Closeable
import java.net.Proxy
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.serialization.json.*

/** Public release metadata only. Never receives account, school, device or location material. */
data class ReleaseRepository private constructor(val url:String,val owner:String,val name:String) {
    val apiUrl:String get()="https://api.github.com/repos/$owner/$name/releases/latest"
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
internal data class ReleaseVersion(val major:Long,val minor:Long,val patch:Long,val pre:List<String>):Comparable<ReleaseVersion> {
    override fun compareTo(other:ReleaseVersion):Int {
        for((a,b) in listOf(major to other.major,minor to other.minor,patch to other.patch))if(a!=b)return a.compareTo(b)
        if(pre.isEmpty() || other.pre.isEmpty())return when { pre==other.pre -> 0;pre.isEmpty() -> 1;else -> -1 }
        for((a,b) in pre.zip(other.pre)) {
            if(a==b)continue
            val an=a.all(Char::isDigit);val bn=b.all(Char::isDigit)
            return when { an && bn -> if(a.length!=b.length)a.length.compareTo(b.length) else a.compareTo(b);an -> -1;bn -> 1;else -> a.compareTo(b) }
        }
        return pre.size.compareTo(other.pre.size)
    }
    companion object {
        fun parse(raw:String):ReleaseVersion?=runCatching {
            require(raw.length<=100)
            val match=Regex("v?(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})(?:-([A-Za-z0-9.-]+))?(?:\\+[A-Za-z0-9.-]+)?").matchEntire(raw) ?: return null
            require(raw.substringAfter('+',"metadata").split('.').all { it.isNotEmpty() })
            val pre=match.groupValues[4].takeIf { it.isNotEmpty() }?.split('.') ?: emptyList()
            require(pre.all { it.isNotEmpty() && !(it.all(Char::isDigit) && it.length>1 && it.startsWith('0')) })
            ReleaseVersion(match.groupValues[1].toLong(),match.groupValues[2].toLong(),match.groupValues[3].toLong(),pre)
        }.getOrNull()
    }
}
enum class UpdatePhase { IDLE, CHECKING, AVAILABLE, CURRENT, NO_RELEASE, RATE_LIMITED, FAILED, INTERRUPTED, NOT_CONFIGURED, UNSUPPORTED }
data class UpdateSummary(val phase:UpdatePhase=UpdatePhase.IDLE,val version:String?=null,val releaseUrl:String?=null) {
    val label:String get()=when(phase) {
        UpdatePhase.IDLE -> "点击检查新版本"
        UpdatePhase.CHECKING -> "正在检查更新…"
        UpdatePhase.AVAILABLE -> "发现新版本 $version，点击查看"
        UpdatePhase.CURRENT -> "当前没有更新的正式版本"
        UpdatePhase.NO_RELEASE -> "尚未找到公开发布版本"
        UpdatePhase.RATE_LIMITED -> "检查过于频繁，请稍后再试"
        UpdatePhase.FAILED -> "检查未完成，点击重试"
        UpdatePhase.INTERRUPTED -> "检查已暂停，点击重试"
        UpdatePhase.NOT_CONFIGURED -> "更新源尚未配置"
        UpdatePhase.UNSUPPORTED -> "版本格式无法比较，请查看发布页"
    }
}
internal object ReleaseUpdateDecoder {
    fun decode(bytes:ByteArray,repository:ReleaseRepository,current:String):UpdateSummary {
        require(bytes.size in 1..524_288)
        val root=Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        require(root["draft"]?.jsonPrimitive?.booleanOrNull==false && root["prerelease"]?.jsonPrimitive?.booleanOrNull==false)
        val tag=root["tag_name"]?.jsonPrimitive?.takeIf { it.isString }?.content ?: error("No version")
        val version=ReleaseVersion.parse(tag) ?: return UpdateSummary(UpdatePhase.UNSUPPORTED,releaseUrl=repository.url+"/releases")
        require(version.pre.isEmpty())
        val local=ReleaseVersion.parse(current) ?: return UpdateSummary(UpdatePhase.UNSUPPORTED,releaseUrl=repository.url+"/releases")
        val page=repository.url+"/releases/tag/"+tag
        root["html_url"]?.let { require(it.jsonPrimitive.content==page) }
        return if(version>local)UpdateSummary(UpdatePhase.AVAILABLE,tag,page) else UpdateSummary(UpdatePhase.CURRENT)
    }
}
/** GET only, no redirects, auth headers, cookies, platform API or automatic APK installation. */
internal class ReleaseUpdateClient(private val factory:(URL)->HttpsURLConnection={it.openConnection(Proxy.NO_PROXY) as HttpsURLConnection}):Closeable {
    private var active:HttpsURLConnection?=null
    private var closed=false
    fun check(repository:ReleaseRepository,current:String):UpdateSummary {
        val connection=try { synchronized(this) { check(!closed && active==null);factory(URL(repository.apiUrl)).also { active=it } } }
            catch(_:Exception) { return UpdateSummary(UpdatePhase.FAILED) }
        return try {
            connection.instanceFollowRedirects=false;connection.useCaches=false;connection.requestMethod="GET"
            connection.connectTimeout=8_000;connection.readTimeout=10_000
            connection.setRequestProperty("Accept","application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version","2026-03-10")
            connection.setRequestProperty("User-Agent","ibudaoa-update-check")
            synchronized(this) { check(!closed) }
            when(connection.responseCode) {
                404 -> UpdateSummary(UpdatePhase.NO_RELEASE)
                403,429 -> UpdateSummary(UpdatePhase.RATE_LIMITED)
                200 -> connection.inputStream.use { input ->
                    val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                    while(true) {
                        val n=input.read(buffer);if(n<0)break
                        require(output.size()+n<=524_288);output.write(buffer,0,n)
                    }
                    ReleaseUpdateDecoder.decode(output.toByteArray(),repository,current)
                }
                else -> UpdateSummary(UpdatePhase.FAILED)
            }
        } catch(_:Exception) { UpdateSummary(UpdatePhase.FAILED) }
        finally { connection.disconnect();synchronized(this) { if(active===connection)active=null } }
    }
    @Synchronized override fun close() { closed=true;active?.disconnect();active=null }
}
