package io.github.ibudaoa.maps

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.amap.api.services.core.AMapException
import com.amap.api.services.core.LatLonPoint
import com.amap.api.services.core.ServiceSettings
import com.amap.api.services.route.*
import io.github.ibudaoa.BuildConfig
import io.github.ibudaoa.domain.CampusCoordinate
import io.github.ibudaoa.roads.CandidateRoadRequest
import io.github.ibudaoa.roads.RoadGap
import io.github.ibudaoa.roads.WalkingLeg
import io.github.ibudaoa.roads.WalkingReply
import io.github.ibudaoa.roads.WalkingRoadReviewer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

enum class RoadProbeFailure { NOT_ENABLED, NOT_FOREGROUND, SDK_FAILURE, AUTHORIZATION, QUOTA, NETWORK, MISMATCHED_QUERY, EMPTY_RESULT, TOO_COMPLEX }
class RoadProbeException(val reason: RoadProbeFailure) : Exception(reason.name)

/** Candidate-to-candidate read only; does not collect or transmit user GPS. */
class AmapWalkingRoadSource(private val context: Context) {
    suspend fun probe(request: CandidateRoadRequest, consent: Boolean, lifecycle: Lifecycle): WalkingReply =
        WalkingReply(request, WalkingRoadReviewer.SOURCE, search(request.from, request.to, consent, lifecycle))

    suspend fun probeGap(gap: RoadGap, consent: Boolean, lifecycle: Lifecycle): List<WalkingLeg> =
        search(gap.from, gap.to, consent, lifecycle)
    /** User-requested access guidance; sends the current foreground position to the map provider. */
    suspend fun probeAccess(from:CampusCoordinate,to:CampusCoordinate,consent:Boolean,lifecycle:Lifecycle):List<WalkingLeg> =
        search(from,to,consent,lifecycle)

    private suspend fun search(from: CampusCoordinate, to: CampusCoordinate, consent: Boolean, lifecycle: Lifecycle): List<WalkingLeg> =
        withContext(Dispatchers.Main.immediate) {
            if (!BuildConfig.MAP_KEY_CONFIGURED || !consent) throw RoadProbeException(RoadProbeFailure.NOT_ENABLED)
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                throw RoadProbeException(RoadProbeFailure.NOT_FOREGROUND)
            withTimeout(15_000) {
                suspendCancellableCoroutine { continuation ->
                    val once = AtomicBoolean(false)
                    val handler = Handler(Looper.getMainLooper())
                    var search: RouteSearchV2? = null
                    var observer: LifecycleEventObserver? = null
                    fun detach() {
                        runCatching { search?.setRouteSearchListener(null) }
                        observer?.let(lifecycle::removeObserver)
                    }
                    fun finish(reply: List<WalkingLeg>? = null, failure: Exception? = null) {
                        if (!once.compareAndSet(false, true)) return
                        detach()
                        if (!continuation.isActive) return
                        if (failure != null) continuation.resumeWithException(failure)
                        else continuation.resume(reply!!)
                    }
                    continuation.invokeOnCancellation {
                        once.set(true)
                        // The SDK has no cancellation API; detach and ignore any in-flight network callback.
                        handler.post { detach() }
                    }
                    try {
                        observer = LifecycleEventObserver { _, _ ->
                            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                                finish(failure = CancellationException("Road preview left foreground"))
                        }.also(lifecycle::addObserver)
                        ServiceSettings.updatePrivacyShow(context, true, true)
                        ServiceSettings.updatePrivacyAgree(context, true)
                        ServiceSettings.getInstance().protocol = ServiceSettings.HTTPS
                        val client = RouteSearchV2(context.applicationContext)
                        search = client
                        run {
                            client.setRouteSearchListener(object : RouteSearchV2.OnRouteSearchListener {
                                override fun onWalkRouteSearched(result: WalkRouteResultV2?, code: Int) {
                                    handler.post {
                                        if (once.get() || !continuation.isActive) return@post
                                        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                                            finish(failure = CancellationException("Road preview left foreground"))
                                            return@post
                                        }
                                        try { finish(reply = AmapWalkingReplyMapper.mapPoints(from, to, result, code)) }
                                        catch (e: RoadProbeException) { finish(failure = e) }
                                        catch (_: Exception) { finish(failure = RoadProbeException(RoadProbeFailure.SDK_FAILURE)) }
                                    }
                                }
                                override fun onDriveRouteSearched(result: DriveRouteResultV2?, code: Int) = Unit
                                override fun onBusRouteSearched(result: BusRouteResultV2?, code: Int) = Unit
                                override fun onRideRouteSearched(result: RideRouteResultV2?, code: Int) = Unit
                            })
                            val query = RouteSearchV2.WalkRouteQuery(RouteSearchV2.FromAndTo(
                                LatLonPoint(from.latitude, from.longitude),
                                LatLonPoint(to.latitude, to.longitude),
                            )).apply {
                                isIndoor = false
                                alternativeRoute = 1
                                showFields = RouteSearchV2.ShowFields.POLINE or RouteSearchV2.ShowFields.NAVI
                            }
                            client.calculateWalkRouteAsyn(query)
                        }
                    } catch (e: AMapException) { finish(failure = RoadProbeException(roadFailure(e.errorCode))) }
                    catch (_: Exception) { finish(failure = RoadProbeException(RoadProbeFailure.SDK_FAILURE)) }
                }
            }
        }
}

/** Pure mapping keeps raw SDK text, names, instructions and addresses out of app diagnostics. */
internal object AmapWalkingReplyMapper {
    fun map(request: CandidateRoadRequest, result: WalkRouteResultV2?, code: Int): WalkingReply =
        WalkingReply(request, WalkingRoadReviewer.SOURCE, mapPoints(request.from, request.to, result, code))

    fun mapPoints(from: CampusCoordinate, to: CampusCoordinate, result: WalkRouteResultV2?, code: Int): List<WalkingLeg> {
        if (code != AMapException.CODE_AMAP_SUCCESS) throw RoadProbeException(roadFailure(code))
        if (result == null) throw RoadProbeException(RoadProbeFailure.SDK_FAILURE)
        val query = result.walkQuery?.fromAndTo ?: throw RoadProbeException(RoadProbeFailure.MISMATCHED_QUERY)
        if (query.from?.latitude != from.latitude || query.from?.longitude != from.longitude ||
            query.to?.latitude != to.latitude || query.to?.longitude != to.longitude)
            throw RoadProbeException(RoadProbeFailure.MISMATCHED_QUERY)
        val paths = result.paths?.takeIf { it.isNotEmpty() } ?: throw RoadProbeException(RoadProbeFailure.EMPTY_RESULT)
        if (paths.size > 3) throw RoadProbeException(RoadProbeFailure.TOO_COMPLEX)
        var count = 0
        val mapped = paths.map { path ->
            val steps = path.steps ?: emptyList()
            if (steps.size > 2048) throw RoadProbeException(RoadProbeFailure.TOO_COMPLEX)
            WalkingLeg(path.distance.toDouble(), steps.map { step ->
                val points = step.polyline ?: emptyList()
                if (points.size > 20_000 || count + points.size > 20_000) throw RoadProbeException(RoadProbeFailure.TOO_COMPLEX)
                count += points.size
                points.map { CampusCoordinate(it.latitude, it.longitude) }
            })
        }
        return mapped
    }
}

internal fun roadFailure(code: Int): RoadProbeFailure = when (code) {
    AMapException.CODE_AMAP_INVALID_USER_KEY, AMapException.CODE_AMAP_INVALID_USER_IP,
    AMapException.CODE_AMAP_INVALID_USER_DOMAIN, AMapException.CODE_AMAP_INVALID_USER_SCODE,
    AMapException.CODE_AMAP_USERKEY_PLAT_NOMATCH, AMapException.CODE_AMAP_INSUFFICIENT_PRIVILEGES,
    AMapException.CODE_AMAP_USER_KEY_RECYCLED -> RoadProbeFailure.AUTHORIZATION
    AMapException.CODE_AMAP_DAILY_QUERY_OVER_LIMIT, AMapException.CODE_AMAP_ACCESS_TOO_FREQUENT,
    AMapException.CODE_AMAP_IP_QUERY_OVER_LIMIT -> RoadProbeFailure.QUOTA
    AMapException.CODE_AMAP_CLIENT_SOCKET_TIMEOUT_EXCEPTION, AMapException.CODE_AMAP_CLIENT_NETWORK_EXCEPTION,
    AMapException.CODE_AMAP_CLIENT_IO_EXCEPTION -> RoadProbeFailure.NETWORK
    else -> RoadProbeFailure.SDK_FAILURE
}
