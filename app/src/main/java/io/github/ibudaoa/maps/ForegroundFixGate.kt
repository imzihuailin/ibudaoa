package io.github.ibudaoa.maps

import io.github.ibudaoa.domain.CampusCoordinate

enum class FixRejection(val label: String) {
    WRONG_FRAME("定位坐标类型尚不支持"), MOCK("定位被标记为模拟位置"),
    INVALID_COORDINATE("定位坐标数值异常"), ACCURACY("当前定位精度不足"),
    INVALID_TIME("定位时间缺失"), FUTURE_TIME("定位时间晚于设备时钟"),
    STALE_TIME("SDK 返回的位置已经过期"), INVALID_ELAPSED("定位计时状态异常"), EXPIRED("定位已过期，等待更新"),
}

/** A display-quality gate, not a school rule or proof of platform coordinate alignment. */
class ForegroundFixGate {
    private var generation = 0L
    private var active = false
    private var last: DisplayFix? = null
    var lastRejection: FixRejection? = null
        private set

    fun begin(): Long {
        generation++
        active = true
        last = null
        lastRejection = null
        return generation
    }

    fun stop() {
        generation++
        active = false
        last = null
    }

    fun invalidate() { last = null }

    fun accept(
        attempt: Long, coordinate: CampusCoordinate, accuracyMeters: Float,
        gcj02: Boolean, mock: Boolean, sdkTimeMillis: Long, wallTimeMillis: Long, elapsedMillis: Long, speedMetersPerSecond:Float?=null,
    ): DisplayFix? {
        if (!active || attempt != generation) return null
        val reason = when {
            !gcj02 -> FixRejection.WRONG_FRAME
            mock -> FixRejection.MOCK
            !coordinate.latitude.isFinite() || !coordinate.longitude.isFinite() ||
                coordinate.latitude !in -90.0..90.0 || coordinate.longitude !in -180.0..180.0 -> FixRejection.INVALID_COORDINATE
            !accuracyMeters.isFinite() || accuracyMeters <= 0 || accuracyMeters > 50f -> FixRejection.ACCURACY
            sdkTimeMillis <= 0 -> FixRejection.INVALID_TIME
            sdkTimeMillis > wallTimeMillis -> FixRejection.FUTURE_TIME
            wallTimeMillis-sdkTimeMillis > MAX_AGE_MS -> FixRejection.STALE_TIME
            elapsedMillis < 0 -> FixRejection.INVALID_ELAPSED
            else -> null
        }
        lastRejection=reason
        if (reason != null) { last=null; return null }
        return DisplayFix(coordinate, accuracyMeters, elapsedMillis,sdkTimeMillis,
            speedMetersPerSecond?.takeIf { it.isFinite() && it>=0 && it<=100 }).also { last = it }
    }

    fun current(elapsedMillis: Long): DisplayFix? = last?.takeIf {
        active && elapsedMillis >= it.receivedElapsedMillis && elapsedMillis - it.receivedElapsedMillis <= MAX_AGE_MS
    }

    fun expireIfNeeded(elapsedMillis: Long): Boolean {
        if (last == null || current(elapsedMillis) != null) return false
        last=null
        lastRejection=FixRejection.EXPIRED
        return true
    }

    companion object { const val MAX_AGE_MS = 15_000L }
}

/** Memory-only preview; deliberately cannot be implicitly converted to PlatformOrigin. */
data class DisplayFix(val coordinate: CampusCoordinate, val accuracyMeters: Float, val receivedElapsedMillis: Long,
    val sdkTimeMillis:Long?=null,val speedMetersPerSecond:Float?=null)
