package io.github.ibudaoa.maps

import com.amap.api.maps.AMapUtils
import com.amap.api.maps.model.LatLng
import io.github.ibudaoa.domain.CampusCoordinate
import io.github.ibudaoa.domain.PlatformDistance

/** Same API as the inspected client; coordinate alignment and device validation are still required. */
class AmapDistance : PlatformDistance {
    override val implementationId = "amap-11.3.100-calculateLineDistance-v1"
    override fun meters(from: CampusCoordinate, to: CampusCoordinate): Double {
        require(listOf(from, to).all {
            it.latitude.isFinite() && it.longitude.isFinite() &&
                it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0
        })
        return AMapUtils.calculateLineDistance(from.latLng(), to.latLng()).toDouble().also {
            check(it.isFinite() && it >= 0)
        }
    }
}

internal fun CampusCoordinate.latLng() = LatLng(latitude, longitude)
