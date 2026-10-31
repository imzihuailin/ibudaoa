package io.github.ibudaoa.capture

import io.github.ibudaoa.diagnostics.DiagnosticConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class CapturePurpose { CONFIG, ACCOUNT_READ_CHECK, ACCOUNT_SESSION }

enum class CapturePhase { IDLE, STARTING, LISTENING, CAPTURED, CANCELLED, ERROR }

enum class CaptureProblem(val userMessage: String) {
    NONE(""),
    TLS("证书握手未完成，请确认已安装本应用生成的 CA 证书。"),
    REQUEST_FORMAT("官方请求格式暂不支持，已阻止转发。"),
    NETWORK("连接暂未完成，请检查网络或返回后重试采集。"),
    ACCOUNT_CHECK("账号只读校验未完成，请导出本次结果。"),
    INVALID_RESPONSE("已收到响应，但未识别为有效跑前配置；请确认官方账号状态。"),
    ACCOUNT_EXPIRED("官方账号登录信息已失效，请在官方应用重新登录后再连接。"),
    ACCOUNT_CHANGED("官方账号会话已失效或发生变化，请在官方应用确认登录状态后再连接。"),
    CERTIFICATE("尚未确认 CA 证书安装，请返回完成授权准备。"),
    VPN("临时连接未建立，请检查官方应用、证书和 VPN 授权。"),
    TIMEOUT("采集已超时并关闭连接。请确认官方应用已登录后重试。"),
    STORAGE("配置未能保存，原有数据已保留；若配置发生变化，请先处理已确认的道路。"),
    REVOKED("VPN 授权已撤销，采集已停止。"),
    CANCELLED("临时采集已结束。"),
}

data class CaptureState(
    val generation: Long = 0,
    val purpose: CapturePurpose = CapturePurpose.CONFIG,
    val accountRead: AccountReadResult? = null,
    val phase: CapturePhase = CapturePhase.IDLE,
    val message: String = "",
    val blockedRequests: Int = 0,
    val config: DiagnosticConfig? = null,
    val officialOpened: Boolean = false,
    val problem: CaptureProblem = CaptureProblem.NONE,
    val blockedKinds: Set<BlockedRequestKind> = emptySet(),
    val profile: AccountProfilePreview? = null,
) {
    val active: Boolean get() = phase == CapturePhase.STARTING || phase == CapturePhase.LISTENING
}

/** Process-local, sanitized state only. Generation fences late callbacks after cancellation. */
object CaptureRuntime {
    private val mutableState = MutableStateFlow(CaptureState())
    val state = mutableState.asStateFlow()

    @Synchronized fun begin(purpose: CapturePurpose = CapturePurpose.CONFIG): Long {
        val generation = state.value.generation + 1
        mutableState.value = CaptureState(generation=generation,phase=CapturePhase.STARTING,message="正在准备临时只读连接…",purpose=purpose)
        return generation
    }

    fun update(generation: Long, transform: (CaptureState) -> CaptureState) {
        mutableState.update { if (it.generation == generation && it.active) transform(it) else it }
    }
}

/** Returning from the official app ends collection even when the optional materials were incomplete. */
object CaptureReturnPolicy {
    fun shouldRelease(state:CaptureState):Boolean = state.officialOpened && state.active
}
