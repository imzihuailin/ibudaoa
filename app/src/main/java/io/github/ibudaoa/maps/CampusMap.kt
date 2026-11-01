package io.github.ibudaoa.maps

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapsInitializer
import com.amap.api.maps.TextureMapView
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.Circle
import com.amap.api.maps.model.CircleOptions
import com.amap.api.maps.model.LatLngBounds
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.PolygonOptions
import com.amap.api.maps.model.Polyline
import com.amap.api.maps.model.PolylineOptions
import io.github.ibudaoa.roads.OriginRoadProposal
import io.github.ibudaoa.roads.ObservedRoadEdge
import io.github.ibudaoa.roads.RoadGap
import io.github.ibudaoa.roads.RoadEvidenceSnapshot
import io.github.ibudaoa.roads.FixedPointRoute
import io.github.ibudaoa.BuildConfig
import io.github.ibudaoa.diagnostics.DiagnosticConfig
import io.github.ibudaoa.domain.ConfigurationReviewer
import kotlinx.coroutines.delay

/** Candidate preview only: it neither selects points nor supplies an executable platform origin. */
@Composable
fun CampusMap(config: DiagnosticConfig?, consent: Boolean, active: Boolean, roadEvidence: RoadEvidenceSnapshot?, routeDraft: FixedPointRoute?, modifier: Modifier = Modifier, reviewGap: RoadGap? = null, reviewRoad: ObservedRoadEdge? = null, originProposal: OriginRoadProposal? = null,
    selectedIds: List<String> = emptyList(), automaticDisplay:Boolean=false,
    automaticPosition:io.github.ibudaoa.domain.CampusCoordinate?=null) {
    if (!BuildConfig.MAP_KEY_CONFIGURED || !consent || config == null) return
    val location=LocalForegroundLocation.current
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var attempt by remember { mutableIntStateOf(0) }
    var loaded by remember(config, attempt) { mutableStateOf(false) }
    var loadSlow by remember(config, attempt) { mutableStateOf(false) }
    val locationStatus=location.message
    val locationCheck=location.diagnostics
    val permission = LocalLocationPermission.current
    val host = remember(context, config, attempt) {
        if (Build.SUPPORTED_ABIS.none { it == "arm64-v8a" || it == "armeabi-v7a" }) null
        else try {
            MapsInitializer.updatePrivacyShow(context, true, true)
            MapsInitializer.updatePrivacyAgree(context, true)
            MapHost(context, config)
        } catch (_: Exception) { null } catch (_: LinkageError) { null }
    }
    LaunchedEffect(host) {
        if (host != null) {
            host.view.map.setOnMapLoadedListener { loaded = true;host.fitRoute() }
            delay(20_000)
            if (!loaded) loadSlow = true
        }
    }
    DisposableEffect(host, lifecycle) {
        if (host == null) return@DisposableEffect onDispose { }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> host.resume()
                Lifecycle.Event.ON_PAUSE -> host.pause()
                Lifecycle.Event.ON_DESTROY -> host.destroy()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) host.resume()
        onDispose { lifecycle.removeObserver(observer); host.destroy() }
    }
    LaunchedEffect(host,location.fix,active,automaticDisplay) {
        host?.showFix(location.fix.takeIf { active && !automaticDisplay })
    }
    Box(modifier.fillMaxSize()) {
        if (host != null) AndroidView(factory = { host.view }, update = { host.showRoadEvidence(roadEvidence); host.showRouteDraft(routeDraft); host.showReviewGap(reviewGap); host.showReviewRoad(reviewRoad); host.showOriginProposal(originProposal); host.showSelected(selectedIds); host.showAutomatic(automaticPosition) }, modifier = Modifier.fillMaxSize())
        Column(Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 175.dp)) {
            Text(when {
                host == null -> "地图初始化失败或设备架构不支持"
                loadSlow && !loaded -> "地图尚未加载，请检查网络和地图授权"
                !loaded -> "正在加载学校地图…"
                else -> when {
                    automaticDisplay -> "自动执行位置 · 沿本次固定路线推进"
                    routeDraft != null -> "本次计划路线"
                    roadEvidence != null -> "道路资料预览 · 本次路线尚未确定"
                    else -> "官方候选点 · 道路待核对"
                }
            }, color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.labelSmall)
            if (active && host != null && !automaticDisplay) {
                Text(locationStatus, style = MaterialTheme.typography.labelSmall)
                if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    TextButton(onClick = {
                        permission.request()
                    }) { Text("允许前台定位") }
                } else if (locationCheck?.status != LocationCheckStatus.ACCEPTED) {
                    TextButton(onClick=location.retry) { Text("立即重试定位") }
                }
            }
            if (host == null || loadSlow && !loaded) TextButton(onClick = { attempt++ }) { Text("重试地图") }
        }
    }
}

private class MapHost(context: Context, config: DiagnosticConfig) {
    val view = TextureMapView(context)
    private var resumed = false
    private var destroyed = false
    private var position: Marker? = null
    private var automaticMarker:Marker?=null
    private var shownAutomatic:io.github.ibudaoa.domain.CampusCoordinate?=null
    private var accuracy: Circle? = null
    private var shownRoadEvidence: RoadEvidenceSnapshot? = null
    private val roads = mutableListOf<Polyline>()
    private val routeLines = mutableListOf<Polyline>()
    private var shownRoute: FixedPointRoute? = null
    private var shownGap: RoadGap? = null
    private var shownReviewRoad: ObservedRoadEdge? = null
    private var reviewRoadLine: Polyline? = null
    private val gapMarkers = mutableListOf<Marker>()
    private var gapLine: Polyline? = null
    private val candidates = mutableMapOf<String, io.github.ibudaoa.domain.CampusCoordinate>()
    private var shownSelected = emptyList<String>()
    private val selectedMarkers = mutableListOf<Marker>()
    private var shownOriginProposal: OriginRoadProposal? = null
    private var originLine: Polyline? = null
    private val originMarkers = mutableListOf<Marker>()

    init {
        try {
            view.onCreate(null)
            val map = view.map
            map.uiSettings.isZoomControlsEnabled = false
            map.uiSettings.isMyLocationButtonEnabled = false
            map.isMyLocationEnabled = false // SDK automatic location is deliberately disabled.
            val review = ConfigurationReviewer.review(config)
            val coordinates = review.candidates.orEmpty().map { it.coordinate } + review.allowedBoundary.orEmpty()
            review.allowedBoundary?.let { boundary ->
                map.addPolygon(PolygonOptions().addAll(boundary.map { it.latLng() })
                    .strokeWidth(3f).strokeColor(Color.rgb(71, 113, 84)).fillColor(Color.argb(12, 71, 113, 84)))
            }
            review.candidates.orEmpty().forEach { point ->
                candidates[point.id] = point.coordinate
                map.addMarker(MarkerOptions().position(point.coordinate.latLng())
                    .title("官方候选点 ${point.id}").snippet("本次尚未分配，坐标及道路待核对")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE)))
            }
            if (coordinates.isNotEmpty()) {
                val bounds = LatLngBounds.builder().apply { coordinates.forEach { include(it.latLng()) } }.build()
                // The width/height overload avoids a zero-size viewport on first composition.
                map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 700, 700, 90))
            }
        } catch (e: Exception) { destroy(); throw e } catch (e: LinkageError) { destroy(); throw e }
    }

    fun resume() { if (!destroyed && !resumed) { view.onResume(); resumed = true } }
    fun pause() { if (!destroyed && resumed) { view.onPause(); resumed = false } }
    fun destroy() {
        if (destroyed) return
        pause()
        destroyed = true
        runCatching { view.onDestroy() }
    }
    fun showRoadEvidence(evidence: RoadEvidenceSnapshot?) {
        if (destroyed || shownRoadEvidence == evidence) return
        roads.forEach { it.remove() }; roads.clear()
        shownRoadEvidence = evidence
        var pointCount = 0
        evidence?.legs?.forEach { record -> record.leg.steps.forEach { step ->
            if (step.size >= 2 && roads.size < 1000 && pointCount + step.size <= 10_000) {
                roads.add(view.map.addPolyline(PolylineOptions().addAll(step.map { it.latLng() })
                    .color(Color.rgb(47, 121, 76)).width(5f)))
                pointCount += step.size
            }
        } }
    }
    fun showRouteDraft(route: FixedPointRoute?) {
        if (destroyed || shownRoute == route) return
        routeLines.forEach { it.remove() }; routeLines.clear()
        shownRoute = route
        route?.legs?.forEach { leg ->
            if (leg.points.size >= 2) routeLines.add(view.map.addPolyline(PolylineOptions().addAll(leg.points.map { it.latLng() })
                .color(Color.rgb(15, 84, 49)).width(10f)))
        }
        view.post { fitRoute() }
    }
    fun fitRoute() {
        if(destroyed)return
        val points=shownRoute?.legs?.flatMap { it.points }.orEmpty()
        if(points.isEmpty() || view.width<=0 || view.height<=0)return
        val bounds=LatLngBounds.builder().apply { points.forEach { include(it.latLng()) } }.build()
        runCatching { view.map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds,view.width,view.height,80)) }
    }
    fun showAutomatic(point:io.github.ibudaoa.domain.CampusCoordinate?) {
        if(destroyed || shownAutomatic==point)return
        shownAutomatic=point
        if(point==null) { automaticMarker?.remove();automaticMarker=null;return }
        if(automaticMarker==null)automaticMarker=view.map.addMarker(MarkerOptions().position(point.latLng())
            .title("自动执行位置").snippet("程序生成的路线位置").zIndex(20f)
            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)))
        else automaticMarker?.position=point.latLng()
        runCatching {
            if(!view.map.projection.visibleRegion.latLngBounds.contains(point.latLng()))
                view.map.animateCamera(CameraUpdateFactory.newLatLng(point.latLng()))
        }
    }
    fun showReviewGap(gap: RoadGap?) {
        if (destroyed || shownGap == gap) return
        shownGap = gap
        gapMarkers.forEach { it.remove() }; gapMarkers.clear()
        gapLine?.remove(); gapLine = null
        if (gap == null) return
        gapMarkers.add(view.map.addMarker(MarkerOptions().position(gap.from.latLng()).title("缺口起点")
            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))))
        gapMarkers.add(view.map.addMarker(MarkerOptions().position(gap.to.latLng()).title("缺口终点")
            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))))
        gapLine = view.map.addPolyline(PolylineOptions().add(gap.from.latLng(), gap.to.latLng())
            .color(Color.rgb(234, 102, 20)).width(12f))
        val middle = com.amap.api.maps.model.LatLng((gap.from.latitude + gap.to.latitude) / 2,
            (gap.from.longitude + gap.to.longitude) / 2)
        view.map.moveCamera(CameraUpdateFactory.newLatLngZoom(middle, 20f))
    }

    fun showReviewRoad(road: ObservedRoadEdge?) {
        if (destroyed || shownReviewRoad == road) return
        shownReviewRoad = road
        reviewRoadLine?.remove(); reviewRoadLine = null
        if (road == null || road.points.size < 2) return
        reviewRoadLine = view.map.addPolyline(PolylineOptions().addAll(road.points.map { it.latLng() })
            .color(Color.rgb(234, 102, 20)).width(10f))
        val bounds = LatLngBounds.builder().apply { road.points.forEach { include(it.latLng()) } }.build()
        runCatching { view.map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 700, 700, 120)) }
    }

    fun showSelected(ids: List<String>) {
        if (destroyed || shownSelected == ids) return
        shownSelected = ids.toList()
        selectedMarkers.forEach { it.remove() }; selectedMarkers.clear()
        ids.take(8).forEach { id -> candidates[id]?.let { coordinate ->
            selectedMarkers.add(view.map.addMarker(MarkerOptions().position(coordinate.latLng())
                .title("预览拟定点位").snippet("按本地已适配规则拟定，尚未开始任务")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_VIOLET))))
        } }
    }

    fun showOriginProposal(proposal: OriginRoadProposal?) {
        if (destroyed || shownOriginProposal == proposal) return
        shownOriginProposal = proposal
        originLine?.remove(); originLine = null
        originMarkers.forEach { it.remove() }; originMarkers.clear()
        if (proposal == null) return
        originLine = view.map.addPolyline(PolylineOptions().addAll(proposal.approach.map { it.latLng() })
            .color(Color.rgb(234,102,20)).width(10f))
        originMarkers.add(view.map.addMarker(MarkerOptions().position(proposal.origin.latLng()).title("待核对的定位起点")
            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))))
        originMarkers.add(view.map.addMarker(MarkerOptions().position(proposal.roadPoint.latLng()).title("拟接入道路")
            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE))))
        val bounds=LatLngBounds.builder().apply { proposal.approach.forEach { include(it.latLng()) } }.build()
        runCatching { view.map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds,700,700,100)) }
    }

    fun showFix(fix: DisplayFix?) {
        if (destroyed) return
        position?.remove(); accuracy?.remove()
        position = null; accuracy = null
        if (fix == null || SystemClock.elapsedRealtime() - fix.receivedElapsedMillis > ForegroundFixGate.MAX_AGE_MS) return
        position = view.map.addMarker(MarkerOptions().position(fix.coordinate.latLng()).title("当前位置")
            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)))
        accuracy = view.map.addCircle(CircleOptions().center(fix.coordinate.latLng()).radius(fix.accuracyMeters.toDouble())
            .strokeWidth(1f).strokeColor(Color.BLUE).fillColor(Color.argb(25, 0, 90, 220)))
    }
}
