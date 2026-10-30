package io.github.ibudaoa.maps

/** Counts and enums only: no coordinates, raw SDK errors, timestamps or device/network identifiers. */
enum class LocationCheckStatus { STARTING, NO_CALLBACK, ACCEPTED, SDK_ERROR, REJECTED, EXPIRED, PERMISSION_REQUIRED, SYSTEM_LOCATION_OFF, INITIALIZATION_FAILED }
enum class LocationAccuracyBand { UNKNOWN, UP_TO_10M, UP_TO_25M, UP_TO_50M, OVER_50M }
enum class LocationCoordinateFrame { UNKNOWN, GCJ02, WGS84, OTHER }
data class LocationDiagnostics(
    val status: LocationCheckStatus,
    val finePermission: Boolean,
    val systemLocationEnabled: Boolean?,
    val callbacks: Int = 0,
    val accepted: Int = 0,
    val sdkErrors: Int = 0,
    val lastSdkCode: Int? = null,
    val lastRejection: FixRejection? = null,
    val accuracyBand: LocationAccuracyBand = LocationAccuracyBand.UNKNOWN,
    val coordinateFrame: LocationCoordinateFrame = LocationCoordinateFrame.UNKNOWN,
) {
    val label: String get() = when (status) {
        LocationCheckStatus.STARTING -> "正在获取前台位置"
        LocationCheckStatus.NO_CALLBACK -> "定位服务尚未返回结果"
        LocationCheckStatus.ACCEPTED -> "当前位置 · 前台定位"
        LocationCheckStatus.SDK_ERROR -> when (lastSdkCode) {
            4 -> "定位网络连接失败（4）"
            7 -> "定位服务授权未通过（7）"
            9,10 -> "定位服务初始化失败（${lastSdkCode}）"
            12,20 -> "定位权限不足（${lastSdkCode}）"
            13 -> "暂未获得网络或 GPS 定位（13）"
            14 -> "GPS 卫星信号不足（14）"
            15 -> "SDK 拒绝了模拟定位（15）"
            18,19 -> "网络定位条件不足（${lastSdkCode}）"
            else -> "定位服务暂不可用（${lastSdkCode ?: "未知"}）"
        }
        LocationCheckStatus.REJECTED -> lastRejection?.label ?: "定位数据暂不可用"
        LocationCheckStatus.EXPIRED -> "定位已过期，等待更新"
        LocationCheckStatus.PERMISSION_REQUIRED -> "需要前台精确定位权限"
        LocationCheckStatus.SYSTEM_LOCATION_OFF -> "系统定位已关闭"
        LocationCheckStatus.INITIALIZATION_FAILED -> "定位初始化失败，请导出检查结果"
    }
}

/** Keeps a concrete failure visible until a new SDK result replaces it. */
internal class LocationHealthTracker(fine: Boolean, enabled: Boolean?) {
    var value = LocationDiagnostics(when {
        !fine -> LocationCheckStatus.PERMISSION_REQUIRED
        enabled == false -> LocationCheckStatus.SYSTEM_LOCATION_OFF
        else -> LocationCheckStatus.STARTING
    },fine,enabled)
        private set
    private fun increment(value: Int) = (value+1).coerceAtMost(1_000_000)
    fun sdkError(code: Int?) {
        value=value.copy(status=LocationCheckStatus.SDK_ERROR,callbacks=increment(value.callbacks),
            sdkErrors=increment(value.sdkErrors),lastSdkCode=code?.takeIf { it in 0..1000 },lastRejection=null)
    }
    fun sample(frame: LocationCoordinateFrame, accuracy: Float, rejection: FixRejection?) {
        val band=when {
            !accuracy.isFinite() || accuracy <= 0 -> LocationAccuracyBand.UNKNOWN
            accuracy <= 10 -> LocationAccuracyBand.UP_TO_10M
            accuracy <= 25 -> LocationAccuracyBand.UP_TO_25M
            accuracy <= 50 -> LocationAccuracyBand.UP_TO_50M
            else -> LocationAccuracyBand.OVER_50M
        }
        value=value.copy(status=if (rejection == null) LocationCheckStatus.ACCEPTED else LocationCheckStatus.REJECTED,
            callbacks=increment(value.callbacks),accepted=if (rejection == null) increment(value.accepted) else value.accepted,
            lastSdkCode=0,lastRejection=rejection,accuracyBand=band,coordinateFrame=frame)
    }
    fun noCallback() {
        if (value.status == LocationCheckStatus.STARTING && value.callbacks == 0)
            value=value.copy(status=LocationCheckStatus.NO_CALLBACK)
    }
    fun expired() {
        if (value.status == LocationCheckStatus.ACCEPTED) value=value.copy(status=LocationCheckStatus.EXPIRED,lastRejection=FixRejection.EXPIRED)
    }
    fun initializationFailed() { value=value.copy(status=LocationCheckStatus.INITIALIZATION_FAILED) }
}
