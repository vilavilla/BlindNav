package com.blindnav.app.domain.navigation

import android.content.Context
import android.location.Location
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.blindnav.app.data.db.entity.Checkpoint
import com.blindnav.app.data.db.entity.CheckpointType
import com.blindnav.app.data.sensors.LocationSensorManager
import com.blindnav.app.data.sensors.UserOrientation
import com.blindnav.app.ui.audio.PriorityAudioManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs

sealed class NavigationEvent {
    data object RerouteRequested : NavigationEvent()
}

data class GuidedNavigationState(
    val isActive: Boolean = false,
    val currentCheckpoint: Checkpoint? = null,
    val currentIndex: Int = 0,
    val totalCheckpoints: Int = 0,
    val lastDirection: TacticalNavigationEngine.TacticalDirection? = null,
    val hasArrived: Boolean = false,
    val isInCrossingMode: Boolean = false,
    val isReroutingNeeded: Boolean = false,
    val locationReliable: Boolean = false,
    val gpsAccuracyMeters: Float = Float.MAX_VALUE,
    val routeProgressMeters: Double = 0.0,
    val distanceToRouteMeters: Double = Double.MAX_VALUE,
    val crossingTargetBearing: Float? = null,
    val crossingHeadingErrorDegrees: Float? = null,
    val recordedInstruction: String? = null,
    val recordedHeadingError: Float? = null
)

class GuidedNavigationManager(
    private val context: Context,
    private val locationSensorManager: LocationSensorManager,
    private val audioManager: PriorityAudioManager
) {
    companion object {
        private const val TAG = "GuidedNavigationManager"

        private const val UPDATE_INTERVAL_MS = 1000L
        private const val MAX_LOCATION_AGE_MS = 15_000L
        private const val MAX_USABLE_ACCURACY_METERS = 15f

        private const val VOICE_PRE_WARNING_METERS = 40f
        private const val VOICE_INSTRUCTION_METERS = 8f

        private const val CROSSING_MIN_ACTIVE_MS = 4_000L
        private const val CROSSING_PASSED_PROGRESS_METERS = 10.0

        private const val REROUTE_THRESHOLD_METERS = 30f
        private const val REROUTE_HOLD_MS = 5_000L
    }

    private val tacticalEngine = TacticalNavigationEngine()
    private val vibrator: Vibrator = getVibrator(context)

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var navigationJob: Job? = null

    private val _state = MutableStateFlow(GuidedNavigationState())
    val state: StateFlow<GuidedNavigationState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<NavigationEvent>(extraBufferCapacity = 8)
    val events: Flow<NavigationEvent> = _events.asSharedFlow()

    private var checkpoints: List<Checkpoint> = emptyList()
    private var routeGeometry: List<GeoCoordinate> = emptyList()
    private var checkpointProgressMeters: List<Double> = emptyList()
    private var currentCheckpointIndex = 0

    private var lastSpokenCheckpointIndex = -1
    private var hasGivenPreWarning = false

    private var crossingEnteredTime = 0L
    private var crossingCheckpointLocation: Location? = null
    private var lastCrossingCorrectionMs = 0L

    private var offRouteStartMs: Long? = null
    private var rerouteEventSent = false
    private var recordedPlayer: RecordedRoutePlayer? = null

    fun startNavigation(
        checkpointList: List<Checkpoint>,
        geometry: List<GeoCoordinate> = checkpointList.map { GeoCoordinate(it.latitude, it.longitude) },
        recordedInstructions: Map<Int, RecordedInstruction> = emptyMap()
    ) {
        if (checkpointList.isEmpty()) {
            Log.w(TAG, "startNavigation llamado sin checkpoints")
            return
        }

        checkpoints = checkpointList.sortedBy { it.orderIndex }
        recordedPlayer = if (recordedInstructions.isEmpty()) null else RecordedRoutePlayer(
            checkpoints.map { GeoCoordinate(it.latitude, it.longitude) }, recordedInstructions, geometry)
        routeGeometry = geometry.ifEmpty {
            checkpoints.map { GeoCoordinate(it.latitude, it.longitude) }
        }
        checkpointProgressMeters = checkpoints.map { checkpoint ->
            RouteGeometry.project(
                GeoCoordinate(checkpoint.latitude, checkpoint.longitude),
                routeGeometry
            )?.progressMeters ?: 0.0
        }
        currentCheckpointIndex = 0
        lastSpokenCheckpointIndex = -1
        hasGivenPreWarning = false
        crossingEnteredTime = 0L
        crossingCheckpointLocation = null
        lastCrossingCorrectionMs = 0L
        offRouteStartMs = null
        rerouteEventSent = false

        locationSensorManager.startTracking()

        _state.update {
            it.copy(
                isActive = true,
                currentCheckpoint = checkpoints.first(),
                currentIndex = 0,
                totalCheckpoints = checkpoints.size,
                hasArrived = false,
                isInCrossingMode = false,
                isReroutingNeeded = false,
                crossingTargetBearing = null,
                crossingHeadingErrorDegrees = null,
                recordedInstruction = null,
                recordedHeadingError = null
            )
        }

        startNavigationLoop()
        audioManager.speakNavigation("Navegación iniciada.")
    }

    fun stopNavigation() {
        navigationJob?.cancel()
        locationSensorManager.stopTracking()
        _state.value = GuidedNavigationState()
    }

    fun release() {
        stopNavigation()
        scope.cancel()
    }

    private fun startNavigationLoop() {
        navigationJob?.cancel()
        navigationJob = scope.launch {
            while (_state.value.isActive) {
                updateNavigation()
                delay(UPDATE_INTERVAL_MS)
            }
        }
    }

    private suspend fun updateNavigation() {
        recordedPlayer?.let { player ->
            updateRecordedNavigation(player)
            return
        }
        val checkpoint = checkpoints.getOrNull(currentCheckpointIndex) ?: return
        val userOrientation = getCurrentOrientation() ?: return

        val locationReliable = NavigationProgressPolicy.isLocationReliable(
            timestamp = userOrientation.timestamp,
            accuracyMeters = userOrientation.accuracy,
            now = System.currentTimeMillis(),
            maxAgeMs = MAX_LOCATION_AGE_MS,
            maxAccuracyMeters = MAX_USABLE_ACCURACY_METERS
        )
        if (!locationReliable) {
            _state.update {
                it.copy(
                    locationReliable = false,
                    gpsAccuracyMeters = userOrientation.accuracy
                )
            }
            return
        }

        val userLocation = userOrientation.toLocation()
        val targetLocation = checkpoint.toLocation()
        val projection = RouteGeometry.project(
            GeoCoordinate(userOrientation.latitude, userOrientation.longitude),
            routeGeometry
        )

        val direction = tacticalEngine.calculateDirection(
            userHeading = userOrientation.bearing,
            userLocation = userLocation,
            targetLocation = targetLocation
        )

        _state.update {
            it.copy(
                lastDirection = direction,
                locationReliable = true,
                gpsAccuracyMeters = userOrientation.accuracy,
                routeProgressMeters = projection?.progressMeters ?: 0.0,
                distanceToRouteMeters = projection?.distanceToRouteMeters ?: Double.MAX_VALUE
            )
        }

        updateCrossingCameraState(
            checkpoint = checkpoint,
            userOrientation = userOrientation,
            distanceToCheckpoint = direction.distanceMeters,
            routeProgressMeters = projection?.progressMeters
        )

        handleRerouteDetection(projection)

        val checkpointProgress = checkpointProgressMeters.getOrElse(currentCheckpointIndex) { 0.0 }
        val reachedOrPassed = NavigationProgressPolicy.shouldAdvanceCheckpoint(
            distanceToCheckpointMeters = direction.distanceMeters,
            accuracyMeters = userOrientation.accuracy,
            userProgressMeters = projection?.progressMeters,
            checkpointProgressMeters = checkpointProgress,
            distanceToRouteMeters = projection?.distanceToRouteMeters
        )
        val isCrossing = checkpoint.type == CheckpointType.ZEBRA_CROSSING ||
            checkpoint.type == CheckpointType.CROSSING
        val crossingCanComplete = isCrossing && _state.value.isInCrossingMode &&
            System.currentTimeMillis() - crossingEnteredTime >= CROSSING_MIN_ACTIVE_MS &&
            projection != null &&
            projection.progressMeters >= checkpointProgress + CROSSING_PASSED_PROGRESS_METERS
        val crossingWasClearlyPassed = isCrossing && !_state.value.isInCrossingMode &&
            projection != null && projection.distanceToRouteMeters <= REROUTE_THRESHOLD_METERS &&
            projection.progressMeters >= checkpointProgress + 25.0

        // Speak the current manoeuvre before advancing; never announce the next one as "now".
        maybeSpeakInstruction(checkpoint, direction.distanceMeters)
        if ((!isCrossing && reachedOrPassed) || crossingCanComplete || crossingWasClearlyPassed) {
            handleCheckpointArrival(checkpoint)
            return
        }

    }

    private suspend fun updateRecordedNavigation(player: RecordedRoutePlayer) {
        val orientation = locationSensorManager.getCurrentOrientation()
        val result = player.update(GeoCoordinate(orientation.latitude, orientation.longitude),
            orientation.bearing, orientation.accuracy, orientation.timestamp, System.currentTimeMillis(),
            locationSensorManager.isHeadingReliable(), audioManager.isVoiceBusy())
        if (!result.reliable && result.message != null) audioManager.clearGuidance()
        result.message?.let { audioManager.speakNavigation(it) }
        val checkpoint = checkpoints.getOrNull(result.index)
        _state.update {
            it.copy(currentIndex = result.index, currentCheckpoint = checkpoint,
                locationReliable = result.reliable, gpsAccuracyMeters = orientation.accuracy,
                recordedInstruction = result.message ?: it.recordedInstruction,
                recordedHeadingError = result.headingError,
                lastDirection = checkpoint?.let { target -> tacticalEngine.calculateDirection(
                    orientation.bearing, orientation.toLocation(), target.toLocation()) },
                hasArrived = result.arrived, isActive = !result.arrived)
        }
        if (result.arrived) {
            vibrateSuccess()
            locationSensorManager.stopTracking()
            navigationJob?.cancel()
            return
        }
        if (result.reliable) handleRerouteDetection(RouteGeometry.project(
            GeoCoordinate(orientation.latitude, orientation.longitude), routeGeometry))
    }

    fun repeatInstruction() {
        audioManager.speakNavigation(recordedPlayer?.repeat()
            ?: _state.value.currentCheckpoint?.description ?: "No hay una instrucción activa.")
    }

    private fun updateCrossingCameraState(
        checkpoint: Checkpoint,
        userOrientation: UserOrientation,
        distanceToCheckpoint: Float,
        routeProgressMeters: Double?
    ) {
        val isCrossingCheckpoint = checkpoint.type == CheckpointType.ZEBRA_CROSSING ||
            checkpoint.type == CheckpointType.CROSSING

        if (!isCrossingCheckpoint) {
            if (_state.value.isInCrossingMode) {
                _state.update {
                    it.copy(
                        isInCrossingMode = false,
                        crossingTargetBearing = null,
                        crossingHeadingErrorDegrees = null
                    )
                }
                crossingCheckpointLocation = null
            }
            return
        }

        val checkpointProgress = checkpointProgressMeters.getOrElse(currentCheckpointIndex) { routeProgressMeters ?: 0.0 }
        val activationDistance = NavigationProgressPolicy.arrivalThresholdMeters(userOrientation.accuracy)
        val isNearByProgress = routeProgressMeters != null &&
            routeProgressMeters >= checkpointProgress - activationDistance &&
            routeProgressMeters <= checkpointProgress + 5.0
        if (!_state.value.isInCrossingMode &&
            (distanceToCheckpoint < activationDistance || isNearByProgress)
        ) {
            val crossingBearing = RouteGeometry.bearingAtProgress(routeGeometry, checkpointProgress + 8.0)
                ?: userOrientation.bearing
            _state.update {
                it.copy(
                    isInCrossingMode = true,
                    crossingTargetBearing = crossingBearing,
                    crossingHeadingErrorDegrees = RouteGeometry.signedHeadingError(
                        userOrientation.bearing,
                        crossingBearing
                    )
                )
            }
            crossingEnteredTime = System.currentTimeMillis()
            lastCrossingCorrectionMs = 0L
            crossingCheckpointLocation = checkpoint.toLocation()
            audioManager.speakNavigation("Paso de peatones. Mantén el rumbo indicado por la brújula y comprueba el tráfico.")
            return
        }

        if (_state.value.isInCrossingMode) {
            val target = _state.value.crossingTargetBearing ?: userOrientation.bearing
            _state.update {
                it.copy(
                    crossingHeadingErrorDegrees = RouteGeometry.signedHeadingError(
                        userOrientation.bearing,
                        target
                    )
                )
            }
            val error = RouteGeometry.signedHeadingError(userOrientation.bearing, target)
            val now = System.currentTimeMillis()
            if (abs(error) > 20f && now - lastCrossingCorrectionMs >= 3_500L) {
                audioManager.speakNavigation(
                    if (error > 0) "Corrige ligeramente a la derecha" else "Corrige ligeramente a la izquierda"
                )
                vibrate(100L)
                lastCrossingCorrectionMs = now
            }
        }
    }

    private suspend fun handleRerouteDetection(projection: RouteProjection?) {
        val crossTrackError = projection?.distanceToRouteMeters ?: Double.MAX_VALUE
        val now = System.currentTimeMillis()

        if (crossTrackError > REROUTE_THRESHOLD_METERS) {
            if (offRouteStartMs == null) {
                offRouteStartMs = now
            }

            val offRouteDuration = now - (offRouteStartMs ?: now)
            if (offRouteDuration >= REROUTE_HOLD_MS && !rerouteEventSent) {
                rerouteEventSent = true
                _state.update { it.copy(isReroutingNeeded = true) }
                _events.tryEmit(NavigationEvent.RerouteRequested)
            }
        } else {
            offRouteStartMs = null
            rerouteEventSent = false
            if (_state.value.isReroutingNeeded) {
                _state.update { it.copy(isReroutingNeeded = false) }
            }
        }
    }

    private suspend fun handleCheckpointArrival(checkpoint: Checkpoint) {
        if ((checkpoint.type == CheckpointType.ZEBRA_CROSSING || checkpoint.type == CheckpointType.CROSSING) &&
            !_state.value.isInCrossingMode
        ) {
            audioManager.speakSystem("Se recuperó el GPS después del cruce; no se pudo guiar ese paso.")
        }
        vibrate(200L)
        hasGivenPreWarning = false
        currentCheckpointIndex++

        if (currentCheckpointIndex >= checkpoints.size) {
            handleFinalArrival()
            return
        }

        val nextCheckpoint = checkpoints[currentCheckpointIndex]
        _state.update {
            it.copy(
                currentCheckpoint = nextCheckpoint,
                currentIndex = currentCheckpointIndex,
                isReroutingNeeded = false,
                isInCrossingMode = false,
                crossingTargetBearing = null,
                crossingHeadingErrorDegrees = null
            )
        }

        Log.d(TAG, "Checkpoint ${checkpoint.orderIndex} completado. Siguiente: ${nextCheckpoint.orderIndex}")
    }

    private fun handleFinalArrival() {
        audioManager.speakNavigation("Has llegado a tu destino")
        vibrateSuccess()

        _state.update {
            it.copy(
                isActive = false,
                hasArrived = true,
                isInCrossingMode = false,
                isReroutingNeeded = false
            )
        }

        navigationJob?.cancel()
        locationSensorManager.stopTracking()
    }

    private fun maybeSpeakInstruction(checkpoint: Checkpoint, distanceMeters: Float) {
        if (
            distanceMeters <= VOICE_PRE_WARNING_METERS &&
            distanceMeters > VOICE_INSTRUCTION_METERS &&
            !hasGivenPreWarning &&
            lastSpokenCheckpointIndex != currentCheckpointIndex
        ) {
            val preWarning = sanitizeInstruction(buildPedestrianInstruction(checkpoint, distanceMeters.toInt()), checkpoint.type)
            audioManager.speakNavigation("En ${distanceMeters.toInt()} metros: $preWarning")
            hasGivenPreWarning = true
            return
        }

        if (distanceMeters <= VOICE_INSTRUCTION_METERS && lastSpokenCheckpointIndex != currentCheckpointIndex) {
            val instruction = sanitizeInstruction(buildPedestrianInstruction(checkpoint, 0), checkpoint.type)
            audioManager.speakNavigation(instruction)
            lastSpokenCheckpointIndex = currentCheckpointIndex
        }
    }

    private fun buildPedestrianInstruction(checkpoint: Checkpoint, distance: Int): String {
        val description = checkpoint.description.lowercase()

        if (checkpoint.type == CheckpointType.ZEBRA_CROSSING) {
            return if (distance > 0) "Paso de cebra" else "Paso de cebra. Detente y comprueba si es seguro cruzar."
        }

        if (checkpoint.type == CheckpointType.CROSSING) {
            return if (distance > 0) "Cruce" else "Cruce. Detente y comprueba si es seguro cruzar."
        }

        if (description.contains("roundabout") || description.contains("rotonda")) {
            return "Rotonda"
        }

        if (description.contains("left") || description.contains("izquierda")) {
            return if (distance > 0) "Gira a la izquierda" else "Gira a la izquierda ahora"
        }

        if (description.contains("right") || description.contains("derecha")) {
            return if (distance > 0) "Gira a la derecha" else "Gira a la derecha ahora"
        }

        if (description.contains("arrive") || description.contains("destination") || description.contains("llegado")) {
            return "Has llegado a tu destino"
        }

        if (description.contains("continue") || description.contains("straight") || description.contains("continúa")) {
            return "Continúa recto"
        }

        return checkpoint.description
    }

    fun sanitizeInstruction(text: String, checkpointType: CheckpointType? = null): String {
        var sanitized = text

        // ============================================================
        // 1. Reemplazos directos ordenados de más específico a más genérico
        // ============================================================
        val replacements = listOf(
            // --- INGLÉS (OSRM) ---
            "You have reached your destination" to "Has llegado a tu destino",
            "You have reached" to "Has llegado",
            "You have arrived" to "Has llegado a tu destino",
            "Arrive at your destination" to "Has llegado a tu destino",
            "Arrive at" to "Has llegado a",
            "Head north" to "Dirigígete hacia el norte",
            "Head south" to "Dirigígete hacia el sur",
            "Head east" to "Dirigígete hacia el este",
            "Head west" to "Dirigígete hacia el oeste",
            "Head northeast" to "Dirigígete hacia el noreste",
            "Head northwest" to "Dirigígete hacia el noroeste",
            "Head southeast" to "Dirigígete hacia el sureste",
            "Head southwest" to "Dirigígete hacia el suroeste",
            "Turn sharp left" to "Gira bruscamente a la izquierda",
            "Turn sharp right" to "Gira bruscamente a la derecha",
            "Turn slight left" to "Gira levemente a la izquierda",
            "Turn slight right" to "Gira levemente a la derecha",
            "Turn left" to "Gira a la izquierda",
            "Turn right" to "Gira a la derecha",
            "Keep left" to "Mantén la izquierda",
            "Keep right" to "Mantén la derecha",
            "Continue straight" to "Continúa recto",
            "Go straight" to "Continúa recto",
            "Continue" to "Continúa",
            "Merge" to "Incórpora",
            "Take the exit" to "Toma la salida",
            "Exit" to "Salida",
            "Destination" to "Destino",
            "Roundabout" to "Rotonda",
            "U-turn" to "Media vuelta",
            "Uturn" to "Media vuelta",
            "Depart" to "Sal",
            "Waypoint" to "Punto intermedio",
            "via" to "por",
            "in" to "en",
            "meters" to "metros",
            "feet" to "metros",

            // --- ITALIANO (OSRM en Roma) ---
            "Sei arrivato a destinazione" to "Has llegado a tu destino",
            "Sei arrivato" to "Has llegado",
            "Svolta a sinistra" to "Gira a la izquierda",
            "Svolta a destra" to "Gira a la derecha",
            "Gira a sinistra" to "Gira a la izquierda",
            "Gira a destra" to "Gira a la derecha",
            "Tieni la sinistra" to "Mantén la izquierda",
            "Tieni la destra" to "Mantén la derecha",
            "Prosegui dritto" to "Continúa recto",
            "Continua dritto" to "Continúa recto",
            "Proseguire" to "Continúa",
            "Continua" to "Continúa",
            "Rotonda" to "Rotonda",
            "Dirigersi" to "Dirigígete",
            "Destinazione" to "Destino",
            "Uscita" to "Salida",
            "Inversione" to "Media vuelta",

            // --- FRANCÉS ---
            "Vous êtes arrivé" to "Has llegado a tu destino",
            "Tournez à gauche" to "Gira a la izquierda",
            "Tournez à droite" to "Gira a la derecha",
            "Continuez tout droit" to "Continúa recto",
            "Continuez" to "Continúa",
            "Rond-point" to "Rotonda",
            "Destination" to "Destino",
            "Sortie" to "Salida",
            "Demi-tour" to "Media vuelta"
        )

        replacements.forEach { (from, to) ->
            sanitized = sanitized.replace(from, to, ignoreCase = true)
        }

        sanitized = sanitized
            .replace("\\s+".toRegex(), " ")
            .trim()

        // ============================================================
        // 2. Detección de residuos de idioma extranjero
        // ============================================================
        val residualForeignWords = Regex(
            "\\b(turn|left|right|roundabout|reached|arrived|destination|continue|straight|" +
            "keep|merge|exit|depart|waypoint|head|uturn|" +        // inglés adicional
            "svolta|sinistra|destra|prosegui|proseguire|rotonda|dirigersi|uscita|inversione|" + // italiano
            "tournez|gauche|droite|continuez|demi.tour|sortie|rond.point)\\b",             // francés
            RegexOption.IGNORE_CASE
        )

        if (residualForeignWords.containsMatchIn(sanitized)) {
            Log.w(TAG, "Texto con idioma extranjero residual: \"$sanitized\"")
            // Generar instrucción semántica basada en el tipo de checkpoint
            return when (checkpointType) {
                CheckpointType.ZEBRA_CROSSING, CheckpointType.CROSSING -> "Cruce por delante"
                CheckpointType.TURN -> "Gira en este punto"
                CheckpointType.CORNER -> "Dobla la esquina"
                CheckpointType.ALERT -> "Precaución en este punto"
                else -> "Continúa la ruta"
            }
        }

        return sanitized
    }

    private suspend fun getCurrentOrientation(): UserOrientation? {
        return locationSensorManager.orientationFlow.firstOrNull()
    }

    private fun vibrate(durationMs: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(
                VibrationEffect.createOneShot(
                    durationMs,
                    VibrationEffect.DEFAULT_AMPLITUDE
                )
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(durationMs)
        }
    }

    private fun vibrateSuccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(
                VibrationEffect.createWaveform(
                    longArrayOf(0, 120, 80, 120, 80, 120),
                    -1
                )
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(longArrayOf(0, 120, 80, 120, 80, 120), -1)
        }
    }

    private fun getVibrator(context: Context): Vibrator {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    private fun Checkpoint.toLocation(): Location {
        return Location("checkpoint").apply {
            latitude = this@toLocation.latitude
            longitude = this@toLocation.longitude
        }
    }
}
