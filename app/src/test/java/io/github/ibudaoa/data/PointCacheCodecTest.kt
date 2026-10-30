package io.github.ibudaoa.data

import io.github.ibudaoa.domain.CampusCoordinate
import io.github.ibudaoa.domain.PlatformOrigin
import io.github.ibudaoa.domain.PointScope
import io.github.ibudaoa.domain.PointSelectionCache
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PointCacheCodecTest {
    private val cache = PointSelectionCache(PointScope(1, 2, 3, 4, "a".repeat(64)), LocalDate.of(2026, 10, 28),
        PlatformOrigin(CampusCoordinate(30.0, 114.0)), listOf("00017", "point-b"))

    @Test fun `private cache roundtrip preserves exact ids and locked progress`() {
        val locked = cache.lockForExecution().withPlatformConfirmedPoint("00017")
        assertEquals(locked, PointCacheCodec.decode(PointCacheCodec.encode(locked)))
    }

    @Test fun `invalid identity progress origin and version cannot become valid cache`() {
        listOf(cache.copy(selectedIds = listOf("x", "x")), cache.copy(confirmedIds = setOf("00017")),
            cache.lockForExecution().copy(confirmedIds = setOf("other")),
            cache.copy(origin = PlatformOrigin(CampusCoordinate(Double.NaN, 114.0)))).forEach {
            assertTrue(runCatching { PointCacheCodec.encode(it) }.exceptionOrNull() is InvalidPointCache)
        }
        val encoded = PointCacheCodec.encode(cache)
        assertTrue(runCatching { PointCacheCodec.decode(encoded.replace("\"version\":1", "\"version\":9")) }.isFailure)
    }

    @Test fun `bad cache errors do not echo private input`() {
        val error = runCatching { PointCacheCodec.decode("PRIVATE_LOCATION_SENTINEL") }.exceptionOrNull()!!
        assertTrue(error is InvalidPointCache)
        assertFalse(error.message.orEmpty().contains("PRIVATE_LOCATION_SENTINEL"))
    }
}
