package io.github.ibudaoa.capture

import android.content.Context
import java.io.Closeable
import io.github.ibudaoa.diagnostics.DiagnosticConfig
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*

enum class AccountSessionPhase { EMPTY, CONNECTING, RESTORED, READY, CHECKING, SUSPENDED, RETRYABLE, RECONNECT_REQUIRED, STORAGE_ERROR, CLEARED, FAILED }
enum class AccountSessionEnd { BACKGROUND, DISCONNECTED, EXPIRED, REPLACED, ACCOUNT_FAILURE, CAPTURE_FAILED, STORAGE_INVALID, STORAGE_UNAVAILABLE }
enum class AccountRefreshOutcome { NOT_ATTEMPTED, IN_PROGRESS, VERIFIED, UNCONFIRMED, REJECTED, STORAGE_FAILED, NOT_AVAILABLE }
enum class AccountRefreshState { MISSING, AVAILABLE, IN_FLIGHT, UNVERIFIED }
data class AccountSessionSnapshot(
    val phase:AccountSessionPhase=AccountSessionPhase.EMPTY,
    val end:AccountSessionEnd?=null,
    val acquisition:AccountReadResult?=null,
    val lastRead:AccountReadResult?=null,
    val reads:Int=0,
    val credentialsPersisted:Boolean=false,
    val restoredFromStorage:Boolean=false,
    val protocolReady:Boolean=false,
    val refreshAvailable:Boolean=false,
    val refreshAttempts:Int=0,
    val refreshOutcome:AccountRefreshOutcome=AccountRefreshOutcome.NOT_ATTEMPTED,
    val preparation:LivePreparationSnapshot=LivePreparationSnapshot(),
    val protocolCaptureProblem:ProtocolCaptureProblem=ProtocolCaptureProblem.NONE,
    val lastPreparation:LivePreparationSnapshot?=null,
    val preparationCurrent:Boolean=false,
    val refreshState:AccountRefreshState=AccountRefreshState.MISSING,
) {
    val available:Boolean get()=phase in setOf(AccountSessionPhase.RESTORED,AccountSessionPhase.READY,
        AccountSessionPhase.CHECKING,AccountSessionPhase.SUSPENDED,AccountSessionPhase.RETRYABLE)
    val label:String get()=when(phase) {
        AccountSessionPhase.EMPTY -> "尚未连接账号"
        AccountSessionPhase.CONNECTING -> "正在连接账号"
        AccountSessionPhase.RESTORED -> "已恢复保存的登录，等待核验"
        AccountSessionPhase.READY -> if(lastRead?.readAccepted==true) "已连接，独立读取成功" else "已保存登录，等待核验"
        AccountSessionPhase.CHECKING -> "正在核验登录状态"
        AccountSessionPhase.SUSPENDED -> "登录已保留，返回前台后核验"
        AccountSessionPhase.RETRYABLE -> "读取暂未完成，登录已保留，可重试"
        AccountSessionPhase.RECONNECT_REQUIRED -> "登录未能确认，需要重新连接账号"
        AccountSessionPhase.STORAGE_ERROR -> "本地登录保存或恢复未完成，请重试或导出结果"
        AccountSessionPhase.CLEARED -> "已退出账号"
        AccountSessionPhase.FAILED -> "连接未完成，请重试"
    }
}
internal interface AccountReadConnection:Closeable { fun send(request:LocalRequest):ByteArray }
internal class VerifiedSchoolConfig internal constructor(val config:DiagnosticConfig,val baseline:DiagnosticConfig?,val issuedAt:Long,
    internal val revision:Long,val executionRequirements:io.github.ibudaoa.running.RunRuntimeRequirements=io.github.ibudaoa.running.RunRuntimeRequirements())

/** Login storage and movement lifecycle are separate. All network IO still requires foreground. */
internal class ForegroundAccountSession(private val clock:()->Long={System.nanoTime()/1_000_000}) {
    private val mutable=MutableStateFlow(AccountSessionSnapshot())
    val state=mutable.asStateFlow()
    private var generation=0L
    private val cancelledCapture = java.util.concurrent.atomic.AtomicLong(0L)
    fun invalidateCapture(ticket: Long) { cancelledCapture.accumulateAndGet(ticket, ::maxOf) }
    private var material:ByteArray?=null
    private var pending:ByteArray?=null
    private var returnDeadline=0L
    private var awaitingReturn=false
    private var foreground=false
    private var active:Closeable?=null
    private var storage:AccountSessionStorage?=null
    private var loaded=false
    private var credentialRevision=0L
    private val protocolCandidates=mutableListOf<ByteArray>()
    private var pendingRotation:ByteArray?=null
    private var pendingRevision=0L
    private var verifiedSchool:VerifiedSchoolConfig?=null
    @Synchronized fun verifiedSchool(nowSeconds:Long):VerifiedSchoolConfig? = verifiedSchool?.takeIf { isCurrent(it,nowSeconds) }
    @Synchronized fun isCurrent(value:VerifiedSchoolConfig,nowSeconds:Long):Boolean = foreground && verifiedSchool===value &&
        credentialRevision==value.revision
    @Synchronized fun acknowledgeSchool(value:VerifiedSchoolConfig,nowSeconds:Long) {
        if(isCurrent(value,nowSeconds)) {
            val reviewed=LivePreparation.review(value.config,value.config,
                value.config.config["school_id"]?.jsonPrimitive?.content,Instant.ofEpochSecond(nowSeconds))
            mutable.value=mutable.value.copy(preparation=reviewed,lastPreparation=reviewed,preparationCurrent=true)
        }
    }
    private fun wipeCandidates() { protocolCandidates.forEach { it.fill(0) };protocolCandidates.clear() }
    private fun flags() {
        mutable.value=mutable.value.copy(protocolReady=material?.let(AccountProtocol::ready)==true,
            refreshAvailable=material?.let(AccountProtocol::hasRefresh)==true,
            refreshState=material?.takeIf(AccountProtocol::hasRefresh)?.let { AccountRefreshState.valueOf(AccountProtocol.refreshState(it)) } ?: AccountRefreshState.MISSING)
    }
    private fun commit(bytes:ByteArray) {
        storage?.save(bytes)
        material?.fill(0);material=bytes.clone();credentialRevision++;loaded=true;verifiedSchool=null
        mutable.value=mutable.value.copy(credentialsPersisted=storage!=null)
        mutable.value=mutable.value.copy(preparationCurrent=false)
        flags()
    }

    @Synchronized fun attachStorage(value:AccountSessionStorage) { if(storage==null) storage=value }
    @Synchronized fun restore() {
        pendingRotation?.let { next ->
            if(pendingRevision==credentialRevision) try {
                commit(next);next.fill(0);pendingRotation=null
                mutable.value=mutable.value.copy(phase=AccountSessionPhase.RESTORED,refreshOutcome=AccountRefreshOutcome.NOT_ATTEMPTED)
            } catch(_:Exception) { return }
        }
        if(loaded || mutable.value.phase==AccountSessionPhase.CONNECTING || material!=null) return
        val store=storage ?: return
        var plain:ByteArray?=null
        try {
            plain=store.load()
            if(plain!=null) {
                val request=try { AccountReadProbe.restoreFromStorage(plain) }
                    catch(_:Exception) { throw AccountStorageInvalid() }
                request.body.fill(0)
                material=plain;plain=null
                mutable.value=AccountSessionSnapshot(AccountSessionPhase.RESTORED,credentialsPersisted=true,restoredFromStorage=true)
                flags()
            }
            loaded=true
        } catch(_:AccountStorageInvalid) {
            // Keep ciphertext for explicit replacement/logout; never silently reinterpret it.
            loaded=true
            mutable.value=AccountSessionSnapshot(AccountSessionPhase.RECONNECT_REQUIRED,AccountSessionEnd.STORAGE_INVALID,credentialsPersisted=true)
        } catch(_:Exception) {
            mutable.value=AccountSessionSnapshot(AccountSessionPhase.STORAGE_ERROR,AccountSessionEnd.STORAGE_UNAVAILABLE,credentialsPersisted=true)
        } finally { plain?.fill(0) }
    }
    @Synchronized internal fun runAccountId():Long? {
        if(!foreground || material==null || !AccountProtocol.ready(material!!)) return null
        return AccountProtocol.runParameters(material!!)["uid"]?.toLongOrNull()?.takeIf { it>0 }
    }
    internal fun runRequest(connection:AccountReadConnection,endpoint:RunEndpoint,context:io.github.ibudaoa.running.RunRequestContext,
        fields:Map<String,String>,now:Long,nonce:Int):ByteArray {
        val request=try { synchronized(this) {
            check(foreground && active==null && runAccountId()==context.account)
            RunProtocol.request(material!!,endpoint,context,fields,now,nonce).also { active=connection }
        } } catch(e:Exception) { runCatching { connection.close() };throw e }
        return try { connection.send(request) } finally {
            request.body.fill(0);runCatching { connection.close() }
            synchronized(this) { if(active===connection)active=null }
        }
    }
    internal fun <T> runStorageIo(account:Long,connection:Closeable,operation:()->T):T {
        try { synchronized(this) { check(foreground && active==null && runAccountId()==account);active=connection } }
        catch(e:Exception) { runCatching { connection.close() };throw e }
        return try { operation() } finally {
            runCatching { connection.close() };synchronized(this) { if(active===connection)active=null }
        }
    }

    @Synchronized fun begin():Long {
        verifiedSchool=null
        generation++;credentialRevision++;cancelRead();pending?.fill(0);pending=null;wipeCandidates()
        pendingRotation?.fill(0);pendingRotation=null
        returnDeadline=clock()+120_000;awaitingReturn=true
        mutable.value=mutable.value.copy(phase=AccountSessionPhase.CONNECTING,end=null,acquisition=null,
            preparation=LivePreparationSnapshot(),lastPreparation=null,preparationCurrent=false)
        return generation
    }
    @Synchronized fun accept(ticket:Long,request:LocalRequest):Boolean {
        if(ticket!=generation || ticket<=cancelledCapture.get() || mutable.value.phase!=AccountSessionPhase.CONNECTING || clock()>=returnDeadline) return false
        pending?.fill(0)
        pending=AccountReadProbe.projectForStorage(request)
        return true
    }
    @Synchronized fun acquisition(ticket:Long,result:AccountReadResult) {
        if(ticket!=generation || ticket<=cancelledCapture.get()) return
        if(!result.readAccepted || pending==null) { failCapture(result);return }
        try {
            material?.let { old -> val inherited=AccountProtocol.inherit(old,pending!!);pending!!.fill(0);pending=inherited }
            protocolCandidates.forEach { context ->
                AccountProtocol.merge(pending!!,context)?.let { merged -> pending!!.fill(0);pending=merged }
            }
            commit(pending!!);pending!!.fill(0);pending=null;wipeCandidates()
            mutable.value=mutable.value.copy(phase=if(foreground)AccountSessionPhase.READY else AccountSessionPhase.SUSPENDED,
                end=null,acquisition=result,lastRead=null,credentialsPersisted=storage!=null,restoredFromStorage=false,
                preparation=LivePreparationSnapshot(),refreshOutcome=AccountRefreshOutcome.NOT_ATTEMPTED,
                lastPreparation=null,preparationCurrent=false,protocolCaptureProblem=if(mutable.value.protocolReady)ProtocolCaptureProblem.NONE else mutable.value.protocolCaptureProblem)
            awaitingReturn=false
        } catch(_:Exception) {
            pending?.fill(0);pending=null;awaitingReturn=false;wipeCandidates()
            mutable.value=mutable.value.copy(phase=if(material!=null)AccountSessionPhase.RETRYABLE else AccountSessionPhase.STORAGE_ERROR,
                end=AccountSessionEnd.STORAGE_UNAVAILABLE,acquisition=result)
        }
    }
    @Synchronized fun offerProtocol(ticket:Long,context:ByteArray) {
        if(ticket!=generation || ticket<=cancelledCapture.get()) return
        val current=material
        if(current!=null) {
            val merged=runCatching { AccountProtocol.merge(current,context) }.getOrNull()
            if(merged!=null) try { commit(merged);mutable.value=mutable.value.copy(protocolCaptureProblem=ProtocolCaptureProblem.NONE) }
                catch(_:Exception) { mutable.value=mutable.value.copy(refreshOutcome=AccountRefreshOutcome.STORAGE_FAILED,protocolCaptureProblem=ProtocolCaptureProblem.STORAGE) }
                finally { merged.fill(0) }
        }
        if(protocolCandidates.size>=2) protocolCandidates.removeAt(0).fill(0)
        protocolCandidates.add(context.clone())
    }
    @Synchronized fun protocolProblem(ticket:Long,problem:ProtocolCaptureProblem) {
        if(ticket==generation) mutable.value=mutable.value.copy(protocolCaptureProblem=problem)
    }
    private fun failCapture(result:AccountReadResult?=mutable.value.acquisition,end:AccountSessionEnd=AccountSessionEnd.CAPTURE_FAILED) {
        generation++;pending?.fill(0);pending=null;awaitingReturn=false;wipeCandidates()
        mutable.value=mutable.value.copy(phase=if(material!=null)AccountSessionPhase.RESTORED else AccountSessionPhase.FAILED,
            end=end,acquisition=result)
    }
    @Synchronized fun captureReady(ticket: Long): Boolean = ticket == generation && ticket > cancelledCapture.get() && clock() < returnDeadline &&
        mutable.value.acquisition?.readAccepted == true && mutable.value.credentialsPersisted &&
        mutable.value.protocolReady && material != null

    @Synchronized fun captureSchoolMatches(ticket: Long, config: DiagnosticConfig): Boolean {
        if (!captureReady(ticket)) return false
        val school = runCatching { Json.parseToJsonElement(material!!.toString(Charsets.UTF_8)).jsonObject
            .getValue("parameters").jsonObject["school_id"]?.jsonPrimitive?.content }.getOrNull()
        return school != null && config.config["school_id"]?.jsonPrimitive?.content == school
    }

    @Synchronized fun captureContextMatches(ticket: Long, context: ByteArray): Boolean {
        if (!captureReady(ticket)) return false
        val merged = runCatching { AccountProtocol.merge(material!!,context) }.getOrNull() ?: return false
        merged.fill(0)
        return true
    }

    /** Restricted to this explicit temporary acquisition, not a general background account read. */
    fun readCaptureSchool(ticket: Long, transport: AccountReadConnection, now: Long, nonce: Int): DiagnosticConfig? {
        val working = synchronized(this) { if (!captureReady(ticket)) null else material!!.clone() }
            ?: return null.also { transport.close() }
        try {
            val request = AccountProtocol.request(working, ReadOnlyPolicy.BEFORE_RUN, now, nonce)
            val response = try { transport.send(request) } finally { request.body.fill(0) }
            val config = try { BeforeRunDecoder.decode(response) } finally { response.fill(0) }
            return config.takeIf { captureSchoolMatches(ticket, it) }
        } finally { working.fill(0); transport.close() }
    }

    @Synchronized fun captureEnded(ticket:Long) {
        if(ticket!=generation) return
        wipeCandidates()
        if(mutable.value.phase==AccountSessionPhase.CONNECTING) failCapture()
    }
    @Synchronized fun cancelConnection() {
        if(mutable.value.phase==AccountSessionPhase.CONNECTING) failCapture()
    }
    @Synchronized fun onForeground() {
        foreground=true;expire()
        if(mutable.value.phase==AccountSessionPhase.CONNECTING) awaitingReturn=false
        if(mutable.value.phase==AccountSessionPhase.SUSPENDED)
            mutable.value=mutable.value.copy(phase=AccountSessionPhase.RESTORED,end=null)
    }
    @Synchronized fun onBackground() {
        verifiedSchool=null
        foreground=false
        if(mutable.value.phase==AccountSessionPhase.CONNECTING && awaitingReturn) return
        if(mutable.value.phase==AccountSessionPhase.CONNECTING) failCapture()
        generation++;cancelRead()
        if(material!=null && mutable.value.available)
            mutable.value=mutable.value.copy(phase=AccountSessionPhase.SUSPENDED,end=AccountSessionEnd.BACKGROUND,
                preparation=if(mutable.value.preparation.phase==LivePreparationPhase.READING)
                    LivePreparationSnapshot(LivePreparationPhase.INTERRUPTED) else mutable.value.preparation,preparationCurrent=false)
    }
    @Synchronized fun expire() {
        if(mutable.value.phase==AccountSessionPhase.CONNECTING && clock()>=returnDeadline)
            failCapture(end=AccountSessionEnd.EXPIRED)
    }
    @Synchronized fun clear(reason:AccountSessionEnd=AccountSessionEnd.DISCONNECTED) {
        verifiedSchool=null
        generation++;credentialRevision++;cancelRead();material?.fill(0);material=null;pending?.fill(0);pending=null;wipeCandidates()
        pendingRotation?.fill(0);pendingRotation=null
        awaitingReturn=false;loaded=true
        try {
            storage?.delete()
            mutable.value=mutable.value.copy(phase=AccountSessionPhase.CLEARED,end=reason,credentialsPersisted=false,restoredFromStorage=false,
                protocolReady=false,refreshAvailable=false,preparation=LivePreparationSnapshot(),lastPreparation=null,
                preparationCurrent=false,protocolCaptureProblem=ProtocolCaptureProblem.NONE,refreshState=AccountRefreshState.MISSING)
        } catch(_:Exception) {
            mutable.value=mutable.value.copy(phase=AccountSessionPhase.STORAGE_ERROR,end=AccountSessionEnd.STORAGE_UNAVAILABLE)
        }
    }
    private fun cancelRead() { val connection=active;active=null;runCatching { connection?.close() } }
    fun read(connection:AccountReadConnection,nowSeconds:Long,nonce:Int,savedConfig:DiagnosticConfig?=null) {
        val ticket:Long
        var revision:Long
        var working:ByteArray
        synchronized(this) {
            expire()
            if(!foreground || active!=null || material==null || mutable.value.phase !in setOf(AccountSessionPhase.RESTORED,AccountSessionPhase.READY,AccountSessionPhase.RETRYABLE)) {
                runCatching { connection.close() };return
            }
            ticket=generation;revision=credentialRevision;working=material!!.clone();active=connection
            verifiedSchool=null
            mutable.value=mutable.value.copy(phase=AccountSessionPhase.CHECKING,end=null,reads=(mutable.value.reads+1).coerceAtMost(1_000_000),
                preparation=LivePreparationSnapshot(if(AccountProtocol.ready(working))LivePreparationPhase.READING else LivePreparationPhase.NEEDS_SETUP),preparationCurrent=false)
        }
        var step=0
        val started=System.nanoTime()
        fun currentTime()=nowSeconds+(System.nanoTime()-started)/1_000_000_000
        fun nextNonce()=100_000+(nonce-100_000+step++).mod(900_000)
        fun accountRead():AccountReadResult {
            var result=AccountReadResult(AccountReadOutcome.NETWORK_ERROR)
            repeat(2) {
                synchronized(this) { if(ticket!=generation || !foreground || revision!=credentialRevision) return result }
                val request=AccountReadProbe.restoreFromStorage(working)
                result=AccountReadProbe.readRetained(request,currentTime(),nextNonce()) { body -> connection.send(request.copy(body=body)) }
                if(result.outcome!=AccountReadOutcome.NETWORK_ERROR)return result
            }
            return result
        }
        fun exchange(request:LocalRequest):ByteArray = try { connection.send(request) } finally { request.body.fill(0) }
        var result=AccountReadResult(AccountReadOutcome.NETWORK_ERROR)
        var preparation=LivePreparationSnapshot(LivePreparationPhase.NEEDS_SETUP)
        var freshConfig:DiagnosticConfig?=null
        var runtimeRequirements=io.github.ibudaoa.running.RunRuntimeRequirements()
        fun readPreparation() {
                val response=exchange(AccountProtocol.request(working,ReadOnlyPolicy.BEFORE_RUN,currentTime(),nextNonce()))
                preparation=try {
                    val fresh=BeforeRunDecoder.decode(response)
                    freshConfig=fresh
                    runtimeRequirements=io.github.ibudaoa.running.RunRuntimeRequirements.inspect(BeforeRunDecoder.decodeSuccessfulData(response))
                    val school=Json.parseToJsonElement(working.toString(Charsets.UTF_8)).jsonObject
                        .getValue("parameters").jsonObject["school_id"]?.jsonPrimitive?.content
                    LivePreparation.review(fresh,savedConfig,school,Instant.ofEpochSecond(currentTime()))
                } finally { response.fill(0) }
        }
        try {
            result=accountRead()
            if(result.readAccepted && AccountProtocol.ready(working) && AccountProtocol.refreshState(working)!="UNVERIFIED") {
                synchronized(this) { if(ticket!=generation || !foreground || revision!=credentialRevision) return }
                try { readPreparation() }
                catch(e:BeforeRunAccountException) {
                    result=AccountReadResult(if(e.problem==CaptureProblem.ACCOUNT_CHANGED) AccountReadOutcome.ACCOUNT_CHANGED else AccountReadOutcome.ACCOUNT_EXPIRED)
                }
            }
            if(result.outcome in setOf(AccountReadOutcome.ACCOUNT_EXPIRED,AccountReadOutcome.ACCOUNT_CHANGED)) {
                val canRefresh=AccountProtocol.ready(working) && AccountProtocol.hasRefresh(working) && AccountProtocol.refreshState(working)=="AVAILABLE"
                if(canRefresh) {
                    val inFlight=AccountProtocol.state(working,"IN_FLIGHT")
                    synchronized(this) {
                        if(ticket!=generation || !foreground || revision!=credentialRevision) return
                        try { commit(inFlight);revision=credentialRevision }
                        catch(e:Exception) {
                            mutable.value=mutable.value.copy(refreshOutcome=AccountRefreshOutcome.STORAGE_FAILED)
                            throw e
                        } finally { inFlight.fill(0) }
                        mutable.value=mutable.value.copy(refreshAttempts=mutable.value.refreshAttempts+1,refreshOutcome=AccountRefreshOutcome.IN_PROGRESS)
                    }
                    working.fill(0);working=synchronized(this) { material!!.clone() }
                    val response=exchange(AccountProtocol.request(working,AccountProtocol.REFRESH,currentTime(),nextNonce()))
                    val rotated=try { AccountProtocol.rotated(working,response) } finally { response.fill(0) }
                    try {
                        synchronized(this) {
                            // A completed rotation must be durably saved even if foreground was lost,
                            // but never after logout/replacement changed the credential owner.
                            if(revision!=credentialRevision) return
                            try { commit(rotated);revision=credentialRevision }
                            catch(e:Exception) {
                                pendingRotation?.fill(0);pendingRotation=rotated.clone();pendingRevision=credentialRevision
                                mutable.value=mutable.value.copy(refreshOutcome=AccountRefreshOutcome.STORAGE_FAILED)
                                throw e
                            }
                        }
                        working.fill(0);working=rotated.clone()
                    } finally { rotated.fill(0) }
                    synchronized(this) { if(ticket!=generation || !foreground || revision!=credentialRevision) return }
                    result=accountRead()
                } else synchronized(this) {
                    if(ticket==generation) mutable.value=mutable.value.copy(refreshOutcome=if(AccountProtocol.hasRefresh(working))
                        AccountRefreshOutcome.UNCONFIRMED else AccountRefreshOutcome.NOT_AVAILABLE)
                }
            }
            if(result.readAccepted && AccountProtocol.refreshState(working)=="UNVERIFIED") {
                val verified=AccountProtocol.state(working,"AVAILABLE")
                try { synchronized(this) {
                    if(revision!=credentialRevision) return
                    try { commit(verified);revision=credentialRevision }
                    catch(e:Exception) {
                        pendingRotation?.fill(0);pendingRotation=verified.clone();pendingRevision=credentialRevision
                        mutable.value=mutable.value.copy(refreshOutcome=AccountRefreshOutcome.STORAGE_FAILED)
                        throw e
                    }
                    mutable.value=mutable.value.copy(refreshOutcome=AccountRefreshOutcome.VERIFIED)
                };working.fill(0);working=verified.clone() } finally { verified.fill(0) }
            }
            if(result.readAccepted && AccountProtocol.ready(working)) {
                synchronized(this) { if(ticket!=generation || !foreground || revision!=credentialRevision) return }
                if(freshConfig==null) readPreparation()
            }
        } catch(e:BeforeRunAccountException) {
            result=AccountReadResult(if(e.problem==CaptureProblem.ACCOUNT_CHANGED)AccountReadOutcome.ACCOUNT_CHANGED else AccountReadOutcome.ACCOUNT_EXPIRED)
            preparation=LivePreparationSnapshot(LivePreparationPhase.FAILED)
            synchronized(this) { if(revision==credentialRevision && mutable.value.refreshOutcome==AccountRefreshOutcome.IN_PROGRESS)
                mutable.value=mutable.value.copy(refreshOutcome=AccountRefreshOutcome.REJECTED) }
        } catch(_:Exception) {
            preparation=LivePreparationSnapshot(LivePreparationPhase.FAILED)
            synchronized(this) { if(revision==credentialRevision && mutable.value.refreshOutcome==AccountRefreshOutcome.IN_PROGRESS)
                mutable.value=mutable.value.copy(refreshOutcome=AccountRefreshOutcome.UNCONFIRMED) }
        } finally {
            working.fill(0);runCatching { connection.close() }
            synchronized(this) { if(active===connection) {
                active=null
                if(ticket==generation && mutable.value.phase==AccountSessionPhase.CHECKING)
                    mutable.value=mutable.value.copy(phase=AccountSessionPhase.RESTORED)
            } }
        }
        synchronized(this) {
            if(ticket!=generation || !foreground || revision!=credentialRevision) return
            if(result.readAccepted && freshConfig!=null && preparation.phase in setOf(LivePreparationPhase.MATCHED,LivePreparationPhase.CHANGED))
                verifiedSchool=VerifiedSchoolConfig(freshConfig!!,savedConfig,currentTime(),revision,runtimeRequirements)
            active=null
            mutable.value=mutable.value.copy(lastRead=result,preparation=preparation,lastPreparation=preparation,
                preparationCurrent=preparation.phase in setOf(LivePreparationPhase.MATCHED,LivePreparationPhase.CHANGED),phase=when {
                mutable.value.refreshOutcome==AccountRefreshOutcome.STORAGE_FAILED -> AccountSessionPhase.STORAGE_ERROR
                result.readAccepted -> AccountSessionPhase.READY
                result.outcome in setOf(AccountReadOutcome.ACCOUNT_EXPIRED,AccountReadOutcome.ACCOUNT_CHANGED,
                    AccountReadOutcome.ACCOUNT_MISMATCH,AccountReadOutcome.SIGNATURE_MISMATCH,AccountReadOutcome.UNSUPPORTED_REQUEST) -> AccountSessionPhase.RECONNECT_REQUIRED
                else -> AccountSessionPhase.RETRYABLE
            },end=if(result.readAccepted)null else AccountSessionEnd.ACCOUNT_FAILURE)
        }
    }

}

internal object AccountSessionRuntime {
    val session=ForegroundAccountSession()
    fun initialize(context:Context) {
        session.attachStorage(AndroidAccountStorage(context.applicationContext))
        session.restore()
    }
}
object AccountSessionReport {
    fun summary(snapshot:AccountSessionSnapshot):JsonObject=buildJsonObject {
        put("phase",snapshot.phase.name);put("memory_available",snapshot.available)
        put("credentials_persisted",snapshot.credentialsPersisted);put("restored_from_storage",snapshot.restoredFromStorage)
        put("configuration_owner_verified",snapshot.preparationCurrent && snapshot.preparation.phase==LivePreparationPhase.MATCHED)
        put("encrypted_protocol_ready",snapshot.protocolReady);put("refresh_material_available",snapshot.refreshAvailable)
        put("automatic_refresh_supported",snapshot.protocolReady && snapshot.refreshAvailable)
        put("refresh_state",snapshot.refreshState.name)
        put("refresh_attempts",snapshot.refreshAttempts.coerceIn(0,1_000_000));put("refresh_outcome",snapshot.refreshOutcome.name)
        put("protocol_capture_problem",snapshot.protocolCaptureProblem.name)
        put("live_preparation",JsonObject(LivePreparation.report(snapshot.lastPreparation ?: snapshot.preparation)+mapOf(
            "current" to JsonPrimitive(snapshot.preparationCurrent),"current_phase" to JsonPrimitive(snapshot.preparation.phase.name))))
        put("foreground_reads",snapshot.reads.coerceIn(0,1_000_000))
        snapshot.end?.let { put("end",it.name) }
        fun result(key:String,value:AccountReadResult?) { value?.let {
            put(key,buildJsonObject {
                put("outcome",it.outcome.name);put("independent_requests",it.independentRequests)
                put("signature_verified",it.originalSignatureVerified);put("account_matched",it.accountMatched)
                it.reason?.let { reason -> put("reason",reason.name) }
            })
        } }
        result("acquisition",snapshot.acquisition);result("last_read",snapshot.lastRead)
    }
}
