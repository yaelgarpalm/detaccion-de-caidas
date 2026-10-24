package com.example.deteccioncaidas

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.common.util.concurrent.ListenableFuture
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.components.containers.Detection
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Cámara trasera con detector local de persona, puntos de postura y secuencia temporal de caída. */
internal class CameraFallController(
    private val activity: ComponentActivity,
    private val previewView: PreviewView,
    private val overlay: PoseOverlayView,
    private val onStatus: (String, String, Boolean) -> Unit,
    private val onFall: () -> Unit,
) {
    private data class PersonRegion(val bounds: RectF, val score: Float)
    private data class PersonSelection(val person: PersonRegion?, val personInsideDisplay: Boolean)

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val fallDetector = FallSequenceDetector()

    @Volatile private var active = false
    @Volatile private var loading = false
    @Volatile private var session = 0
    @Volatile private var personDetector: ObjectDetector? = null
    @Volatile private var poseLandmarker: PoseLandmarker? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var previewUseCase: Preview? = null
    private var analysisUseCase: ImageAnalysis? = null
    private var trackedBounds: RectF? = null
    private var trackedAtMs = 0L
    private var lastValidPoseAtMs = 0L
    private var lastFrameRotation = -1
    private var lastDetectorTimestamp = 0L
    private var lastUiUpdateAtMs = 0L
    private var lastUiStatus = ""

    val isActive: Boolean get() = active || loading

    fun start() {
        if (active || loading || executor.isShutdown) return
        val thisSession = ++session
        loading = true
        publishStatus("Preparando cámara", "Cargando los modelos locales de persona y postura…", true)
        executor.execute {
            try {
                if (poseLandmarker == null) {
                    poseLandmarker = PoseLandmarker.createFromOptions(
                        activity.applicationContext,
                        PoseLandmarker.PoseLandmarkerOptions.builder()
                            .setBaseOptions(BaseOptions.builder().setModelAssetPath(POSE_MODEL).build())
                            .setRunningMode(RunningMode.IMAGE)
                            .setNumPoses(1)
                            .setMinPoseDetectionConfidence(0.45f)
                            .setMinPosePresenceConfidence(0.45f)
                            .setMinTrackingConfidence(0.45f)
                            .build(),
                    )
                }
                if (personDetector == null) {
                    personDetector = ObjectDetector.createFromOptions(
                        activity.applicationContext,
                        ObjectDetector.ObjectDetectorOptions.builder()
                            .setBaseOptions(BaseOptions.builder().setModelAssetPath(PERSON_MODEL).build())
                            .setRunningMode(RunningMode.VIDEO)
                            .setCategoryAllowlist(DISPLAY_AND_PERSON_LABELS)
                            .setScoreThreshold(0.30f)
                            .setMaxResults(20)
                            .build(),
                    )
                }
                activity.runOnUiThread {
                    if (thisSession == session && loading && !activity.isFinishing) bindCamera(thisSession)
                }
            } catch (error: Exception) {
                Log.e(TAG, "No se pudieron iniciar los modelos de cámara", error)
                activity.runOnUiThread {
                    if (thisSession != session) return@runOnUiThread
                    loading = false
                    active = false
                    overlay.clear()
                    publishStatus("No se pudo iniciar", "No se pudieron cargar los modelos locales. Intenta de nuevo.", true)
                }
            }
        }
    }

    private fun bindCamera(thisSession: Int) {
        val providerFuture: ListenableFuture<ProcessCameraProvider> = ProcessCameraProvider.getInstance(activity)
        providerFuture.addListener({
            try {
                if (thisSession != session || !loading || activity.isFinishing) return@addListener
                val provider = providerFuture.get()
                val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
                val preview = Preview.Builder().setTargetRotation(rotation).build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .setTargetRotation(rotation)
                    .build()
                    .also { it.setAnalyzer(executor, ::analyzeFrame) }
                provider.unbindAll()
                provider.bindToLifecycle(activity, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                cameraProvider = provider
                previewUseCase = preview
                analysisUseCase = analysis
                active = true
                loading = false
                resetTracking()
                publishStatus("Cámara activa · buscando persona", "Detector y postura se analizan en este teléfono.", true)
            } catch (error: Exception) {
                Log.e(TAG, "No se pudo abrir la cámara trasera", error)
                if (thisSession == session) {
                    loading = false
                    active = false
                    publishStatus("Cámara no disponible", "Revisa el permiso de cámara y vuelve a intentarlo.", true)
                }
            }
        }, ContextCompat.getMainExecutor(activity))
    }

    fun stop() {
        session++
        loading = false
        active = false
        runCatching { cameraProvider?.unbindAll() }
        previewUseCase = null
        analysisUseCase = null
        resetTracking()
        overlay.clear()
        publishStatus("Cámara detenida", "Los sensores siguen disponibles mientras el monitor esté activo.", true)
    }

    fun shutdown() {
        stop()
        val pose = poseLandmarker
        val detector = personDetector
        poseLandmarker = null
        personDetector = null
        if (!executor.isShutdown) {
            executor.execute {
                runCatching { pose?.close() }
                runCatching { detector?.close() }
            }
            executor.shutdown()
        }
    }

    private fun analyzeFrame(proxy: ImageProxy) {
        var rawBitmap: Bitmap? = null
        var uprightBitmap: Bitmap? = null
        var personBitmap: Bitmap? = null
        var frameImage: MPImage? = null
        var personImage: MPImage? = null
        try {
            if (!active) return
            val detector = personDetector ?: return
            val landmarker = poseLandmarker ?: return
            rawBitmap = proxy.toBitmap()
            val rotation = proxy.imageInfo.rotationDegrees
            if (lastFrameRotation != -1 && lastFrameRotation != rotation) resetTracking()
            lastFrameRotation = rotation
            uprightBitmap = rotateBitmap(rawBitmap, rotation)
            val width = uprightBitmap.width
            val height = uprightBitmap.height
            frameImage = BitmapImageBuilder(uprightBitmap).build()
            val timestamp = SystemClock.uptimeMillis().coerceAtLeast(lastDetectorTimestamp + 1L)
            lastDetectorTimestamp = timestamp

            val detections = detector.detectForVideo(frameImage, timestamp).detections()
            val selection = findPerson(detections, width, height)
            val person = selection.person
            if (person == null) {
                if (timestamp - lastValidPoseAtMs > POSE_MISSING_GRACE_MS) {
                    trackedBounds = null
                    fallDetector.missingPose(timestamp)
                }
                val detail = if (selection.personInsideDisplay) {
                    "Se ignoró una figura detectada dentro de una pantalla."
                } else {
                    "Mantén a la persona completa dentro del encuadre."
                }
                publishStatus(if (selection.personInsideDisplay) "Figura en una pantalla" else "Buscando persona", detail)
                activity.runOnUiThread { if (active) overlay.clear() }
                return
            }

            val crop = makePersonCrop(person.bounds, width, height)
            personBitmap = Bitmap.createBitmap(uprightBitmap, crop.left, crop.top, crop.width(), crop.height())
            personImage = BitmapImageBuilder(personBitmap).build()
            val poses = landmarker.detect(personImage).landmarks()
            val bounds = normalizeBounds(person.bounds, width, height)
            if (poses.isNullOrEmpty()) {
                if (timestamp - lastValidPoseAtMs > POSE_MISSING_GRACE_MS) fallDetector.missingPose(timestamp)
                activity.runOnUiThread {
                    if (active) overlay.setDetection(null, width, height, bounds, person.score)
                }
                publishStatus("Persona localizada", "COCO ${percent(person.score)} · no se encontraron puntos de postura.")
                return
            }

            val points = copyLandmarks(poses.first(), crop, width, height)
            val visible = points.count { it.size >= 3 && it[2] >= 0.35f }
            lastValidPoseAtMs = timestamp
            val evaluation = fallDetector.analyze(points, bounds, timestamp)
            activity.runOnUiThread {
                if (!active) return@runOnUiThread
                overlay.setDetection(points, width, height, bounds, person.score)
                val detail = if (evaluation.title == "Persona erguida · vigilancia activa") {
                    "COCO ${percent(person.score)} · $visible puntos visibles"
                } else {
                    "COCO ${percent(person.score)} · ${evaluation.detail}"
                }
                publishStatus(evaluation.title, detail)
                if (evaluation.possibleFall) onFall()
            }
        } catch (error: Exception) {
            Log.e(TAG, "Falló el análisis de un cuadro", error)
            publishStatus("Reintentando análisis", "Se omitió un cuadro; la cámara sigue activa.")
        } finally {
            personImage?.close()
            frameImage?.close()
            personBitmap?.takeIf { !it.isRecycled }?.recycle()
            uprightBitmap?.takeIf { it !== rawBitmap && !it.isRecycled }?.recycle()
            rawBitmap?.takeIf { !it.isRecycled }?.recycle()
            proxy.close()
        }
    }

    private fun findPerson(detections: List<Detection>, width: Int, height: Int): PersonSelection {
        val displays = mutableListOf<RectF>()
        val people = mutableListOf<PersonRegion>()
        val frameArea = width.toFloat() * height
        detections.forEach { detection ->
            val category = detection.categories().firstOrNull() ?: return@forEach
            val label = category.categoryName()?.lowercase(Locale.ROOT).orEmpty()
            val bounds = clipBounds(detection.boundingBox(), width, height)
            if (bounds.width() <= 1f || bounds.height() <= 1f) return@forEach
            if (label in DISPLAY_LABELS) displays += bounds
            else if (label == "person" && category.score() >= MIN_PERSON_SCORE && bounds.width() * bounds.height() / frameArea >= MIN_PERSON_AREA) {
                people += PersonRegion(bounds, category.score())
            }
        }

        var personInsideDisplay = false
        val visiblePeople = people.filter { person ->
            val inside = displays.any { isInsideDisplay(person.bounds, it) }
            if (inside) personInsideDisplay = true
            !inside
        }
        if (visiblePeople.isEmpty()) return PersonSelection(null, personInsideDisplay)

        val previous = trackedBounds
        val trackIsFresh = previous != null && SystemClock.uptimeMillis() - trackedAtMs <= TRACK_GRACE_MS
        var best = if (trackIsFresh) visiblePeople.maxByOrNull { trackScore(it.bounds, previous!!) } else null
        if (best != null && normalizedCenterDistance(best.bounds, previous!!) > MAX_TRACK_DISTANCE) {
            resetTracking()
            best = null
        }
        best = best ?: visiblePeople.maxByOrNull { region ->
            val areaRatio = region.bounds.width() * region.bounds.height() / frameArea
            region.score * (0.5f + sqrt(areaRatio))
        }
        trackedBounds = RectF(best!!.bounds)
        trackedAtMs = SystemClock.uptimeMillis()
        return PersonSelection(best, personInsideDisplay)
    }

    private fun makePersonCrop(bounds: RectF, width: Int, height: Int): Rect {
        val padX = bounds.width() * 0.12f
        val padY = bounds.height() * 0.10f
        val left = floor((bounds.left - padX).toDouble()).toInt().coerceIn(0, width - 2)
        val top = floor((bounds.top - padY).toDouble()).toInt().coerceIn(0, height - 2)
        val right = (bounds.right + padX).toInt().coerceIn(left + 2, width)
        val bottom = (bounds.bottom + padY).toInt().coerceIn(top + 2, height)
        return Rect(left, top, right, bottom)
    }

    private fun normalizeBounds(bounds: RectF, width: Int, height: Int) = floatArrayOf(
        (bounds.left / width).coerceIn(0f, 1f),
        (bounds.top / height).coerceIn(0f, 1f),
        (bounds.right / width).coerceIn(0f, 1f),
        (bounds.bottom / height).coerceIn(0f, 1f),
    )

    private fun copyLandmarks(landmarks: List<NormalizedLandmark>, crop: Rect, width: Int, height: Int): Array<FloatArray> =
        Array(landmarks.size) { index ->
            val landmark = landmarks[index]
            val visibility = if (landmark.visibility().isPresent) landmark.visibility().get() else 1f
            floatArrayOf(
                ((crop.left + landmark.x() * crop.width()) / width).coerceIn(0f, 1f),
                ((crop.top + landmark.y() * crop.height()) / height).coerceIn(0f, 1f),
                visibility,
            )
        }

    private fun clipBounds(bounds: RectF, width: Int, height: Int) = RectF(
        bounds.left.coerceIn(0f, width.toFloat()),
        bounds.top.coerceIn(0f, height.toFloat()),
        bounds.right.coerceIn(0f, width.toFloat()),
        bounds.bottom.coerceIn(0f, height.toFloat()),
    )

    private fun isInsideDisplay(person: RectF, display: RectF): Boolean {
        if (!display.contains(person.centerX(), person.centerY())) return false
        val intersection = RectF()
        if (!intersection.setIntersect(person, display)) return false
        val area = person.width() * person.height()
        return area > 0f && intersection.width() * intersection.height() / area >= DISPLAY_OVERLAP
    }

    private fun rotateBitmap(source: Bitmap, degrees: Int): Bitmap {
        val rotation = ((degrees % 360) + 360) % 360
        if (rotation == 0) return source
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun trackScore(candidate: RectF, previous: RectF): Float =
        intersectionOverUnion(candidate, previous) * 2f - normalizedCenterDistance(candidate, previous)

    private fun normalizedCenterDistance(candidate: RectF, previous: RectF): Float {
        val scale = max(previous.width(), previous.height()).coerceAtLeast(1f)
        val dx = candidate.centerX() - previous.centerX()
        val dy = candidate.centerY() - previous.centerY()
        return sqrt(dx * dx + dy * dy) / scale
    }

    private fun intersectionOverUnion(a: RectF, b: RectF): Float {
        val overlap = RectF()
        if (!overlap.setIntersect(a, b)) return 0f
        val intersection = overlap.width() * overlap.height()
        val union = a.width() * a.height() + b.width() * b.height() - intersection
        return if (union > 0f) intersection / union else 0f
    }

    private fun resetTracking() {
        trackedBounds = null
        trackedAtMs = 0L
        lastValidPoseAtMs = 0L
        lastFrameRotation = -1
        fallDetector.reset()
    }

    private fun publishStatus(title: String, detail: String, force: Boolean = false) {
        val key = "$title\n$detail"
        val now = SystemClock.uptimeMillis()
        if (!force && (key == lastUiStatus || now - lastUiUpdateAtMs < UI_UPDATE_INTERVAL_MS)) return
        if (key == lastUiStatus) return
        lastUiStatus = key
        lastUiUpdateAtMs = now
        activity.runOnUiThread {
            if (!activity.isFinishing) onStatus(title, detail, active || loading)
        }
    }

    private fun percent(score: Float) = String.format(Locale.getDefault(), "%.0f%%", score * 100f)

    private companion object {
        const val TAG = "FallCamera"
        const val PERSON_MODEL = "efficientdet_lite0.tflite"
        const val POSE_MODEL = "pose_landmarker_full.task"
        const val POSE_MISSING_GRACE_MS = 900L
        const val TRACK_GRACE_MS = 1_200L
        const val MAX_TRACK_DISTANCE = 1.5f
        const val MIN_PERSON_SCORE = 0.40f
        const val MIN_PERSON_AREA = 0.002f
        const val DISPLAY_OVERLAP = 0.60f
        const val UI_UPDATE_INTERVAL_MS = 300L
        val DISPLAY_LABELS = setOf("tv", "laptop", "cell phone")
        val DISPLAY_AND_PERSON_LABELS = listOf("person", "tv", "laptop", "cell phone")
    }
}
