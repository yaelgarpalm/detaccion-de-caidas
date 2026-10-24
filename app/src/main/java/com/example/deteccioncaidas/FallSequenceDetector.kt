package com.example.deteccioncaidas

import java.util.ArrayDeque
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class FallEvaluation(
    val title: String,
    val detail: String,
    val possibleFall: Boolean = false,
)

/** Temporal pose rules ported from the Flutter prototype and tuned for brief
 * detection gaps and a person who is already on the floor when monitoring starts. */
class FallSequenceDetector {
    private data class Sample(
        val timeMs: Long,
        val hipY: Float,
        val torsoAngle: Float,
        val bodyHeight: Float,
        val aspect: Float,
    )

    private val history = ArrayDeque<Sample>()
    private var candidateSince = 0L
    private var lastFallLikeAt = 0L
    private var uprightAspect: Float? = null
    private var uprightHipY: Float? = null
    private var uprightSince = 0L
    private var uprightFrames = 0
    private var fallReported = false

    fun reset() {
        history.clear()
        candidateSince = 0L
        lastFallLikeAt = 0L
        uprightAspect = null
        uprightHipY = null
        uprightSince = 0L
        uprightFrames = 0
        fallReported = false
    }

    fun missingPose(nowMs: Long): FallEvaluation {
        if (candidateSince == 0L || lastFallLikeAt == 0L || nowMs - lastFallLikeAt > MISSING_GRACE_MS) {
            candidateSince = 0L
            lastFallLikeAt = 0L
        }
        uprightFrames = 0
        uprightSince = 0L
        return FallEvaluation("Buscando persona", "Mantén a la persona completa dentro del encuadre.")
    }

    fun analyze(points: Array<FloatArray>, bounds: FloatArray, nowMs: Long): FallEvaluation {
        if (points.size < LANDMARK_COUNT || bounds.size < 4) return missingPose(nowMs)

        val shoulder = center(points, LEFT_SHOULDER, RIGHT_SHOULDER) ?: return missingPose(nowMs)
        val hip = center(points, LEFT_HIP, RIGHT_HIP) ?: return missingPose(nowMs)
        val torsoAngle = Math.toDegrees(
            atan2(kotlin.math.abs(shoulder.first - hip.first).toDouble(), kotlin.math.abs(shoulder.second - hip.second).toDouble() + 0.001),
        ).toFloat()
        val bodyWidth = max(0.01f, bounds[2] - bounds[0])
        val bodyHeight = max(0.01f, bounds[3] - bounds[1])
        val aspect = bodyWidth / bodyHeight
        val hipY = hip.second

        history.addLast(Sample(nowMs, hipY, torsoAngle, bodyHeight, aspect))
        while (history.isNotEmpty() && nowMs - history.first().timeMs > HISTORY_WINDOW_MS) history.removeFirst()

        val upright = torsoAngle < 42f && aspect < 1.05f
        if (upright) {
            uprightFrames++
            if (uprightSince == 0L) uprightSince = nowMs
            candidateSince = 0L
            lastFallLikeAt = 0L
            if (uprightFrames >= 3) {
                uprightAspect = uprightAspect?.let { it * 0.9f + aspect * 0.1f } ?: aspect
                uprightHipY = uprightHipY?.let { it * 0.9f + hipY * 0.1f } ?: hipY
            }
            if (fallReported && nowMs - uprightSince >= RECOVERED_MS) fallReported = false
            return FallEvaluation(
                if (fallReported) "Persona incorporada · revisa la alerta" else "Persona erguida · vigilancia activa",
                "PERSONA · postura en seguimiento",
            )
        }

        uprightFrames = 0
        uprightSince = 0L
        val baselineAspect = uprightAspect
        val baselineHipY = uprightHipY
        val hipDrop = baselineHipY != null && hipY - baselineHipY > HIP_DROP_THRESHOLD

        // This catches a horizontal person even when monitoring starts after the fall.
        val horizontalTorso = torsoAngle >= 70f
        val horizontalBox = aspect >= 1.12f
        val lowCompactPose = horizontalTorso && aspect >= 0.68f
        val relativeWidening = baselineAspect != null && aspect >= max(0.90f, baselineAspect * 1.25f)
        val alreadyLow = lowCompactPose || horizontalBox
        val transition = hipDrop && (torsoAngle >= 48f || relativeWidening || aspect >= 0.95f)
        val fallLike = alreadyLow || transition

        if (!fallLike) {
            if (candidateSince != 0L && nowMs - lastFallLikeAt > MISSING_GRACE_MS) {
                candidateSince = 0L
                lastFallLikeAt = 0L
            }
            return FallEvaluation("Persona localizada", "Encuadra el cuerpo completo · ${torsoAngle.toInt()}° de inclinación")
        }

        if (candidateSince == 0L || lastFallLikeAt == 0L || nowMs - lastFallLikeAt > MISSING_GRACE_MS) {
            candidateSince = nowMs
        }
        lastFallLikeAt = nowMs
        val elapsed = nowMs - candidateSince
        if (elapsed >= CONFIRMATION_MS && !fallReported) {
            fallReported = true
            return FallEvaluation("Posible caída detectada", "Postura baja sostenida · iniciando aviso", possibleFall = true)
        }
        val remaining = max(0L, CONFIRMATION_MS - elapsed) / 1000f
        return FallEvaluation(
            "Postura baja u horizontal",
            "Confirmando ${"%.1f".format(java.util.Locale.getDefault(), remaining)} s · torso ${torsoAngle.toInt()}°",
        )
    }

    private fun center(points: Array<FloatArray>, left: Int, right: Int): Pair<Float, Float>? {
        val a = points.getOrNull(left)?.takeIf { it.size >= 3 && it[2] >= LANDMARK_VISIBILITY }
        val b = points.getOrNull(right)?.takeIf { it.size >= 3 && it[2] >= LANDMARK_VISIBILITY }
        if (a == null && b == null) return null
        if (a != null && b != null) return ((a[0] + b[0]) / 2f) to ((a[1] + b[1]) / 2f)
        val single = a ?: b!!
        return single[0] to single[1]
    }

    companion object {
        private const val LANDMARK_COUNT = 33
        private const val LEFT_SHOULDER = 11
        private const val RIGHT_SHOULDER = 12
        private const val LEFT_HIP = 23
        private const val RIGHT_HIP = 24
        private const val LANDMARK_VISIBILITY = 0.25f
        private const val HIP_DROP_THRESHOLD = 0.055f
        private const val CONFIRMATION_MS = 2_000L
        private const val MISSING_GRACE_MS = 900L
        private const val HISTORY_WINDOW_MS = 12_000L
        private const val RECOVERED_MS = 2_000L
    }
}

