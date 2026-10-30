package io.github.ibudaoa.roads

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/** Includes user gap/direction decisions as represented by the current actual directed geometry. */
internal object GraphGeometryIdentity {
    fun of(graph: ObservedRoadGraph): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeUTF(graph.scope.toString()); out.writeUTF(graph.fingerprint)
            out.writeInt(graph.manualConfirmationCount); out.writeInt(graph.reviewedTwoWayRoadCount)
            out.writeInt(graph.edges.size)
            for (edge in graph.edges) {
                out.writeUTF(edge.fromId); out.writeUTF(edge.toId); out.writeDouble(edge.meters)
                out.writeInt(edge.points.size)
                for (point in edge.points) { out.writeDouble(point.latitude); out.writeDouble(point.longitude) }
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
