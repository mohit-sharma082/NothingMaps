package com.shantanu.nmaps

import android.app.Application
import android.hardware.GeomagneticField
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shantanu.nmaps.data.AppSettings
import com.shantanu.nmaps.data.Category
import com.shantanu.nmaps.data.Conditions
import com.shantanu.nmaps.data.Weather
import com.shantanu.nmaps.data.Fix
import com.shantanu.nmaps.data.Geocoder
import com.shantanu.nmaps.data.LatLon
import com.shantanu.nmaps.data.LocationTracker
import com.shantanu.nmaps.data.MapLayers
import com.shantanu.nmaps.data.Instructions
import com.shantanu.nmaps.data.OsmNotes
import com.shantanu.nmaps.data.Overpass
import com.shantanu.nmaps.data.Problem
import com.shantanu.nmaps.data.SpeedLimitMap
import com.shantanu.nmaps.data.Terrain
import com.shantanu.nmaps.data.VoiceCommand
import com.shantanu.nmaps.gpx.TrackStats
import com.shantanu.nmaps.data.Place
import com.shantanu.nmaps.data.PlaceStore
import com.shantanu.nmaps.data.Route
import com.shantanu.nmaps.data.Router
import com.shantanu.nmaps.data.SettingsStore
import com.shantanu.nmaps.data.Shortcut
import com.shantanu.nmaps.data.Travel
import com.shantanu.nmaps.data.onlineFlow
import com.shantanu.nmaps.data.parseCoordinates
import com.shantanu.nmaps.gpx.Gpx
import com.shantanu.nmaps.gpx.GpxPoint
import com.shantanu.nmaps.gpx.GpxWaypoint
import com.shantanu.nmaps.gpx.shareableGpx
import com.shantanu.nmaps.map.CameraCommand
import com.shantanu.nmaps.map.Follow
import com.shantanu.nmaps.nav.RouteTracker
import com.shantanu.nmaps.nav.distanceMeters
import com.shantanu.nmaps.nav.formatDistance
import com.shantanu.nmaps.nav.spokenDistance
import com.shantanu.nmaps.offline.Bounds
import com.shantanu.nmaps.offline.OfflineMaps
import com.shantanu.nmaps.photos.GeoPhoto
import com.shantanu.nmaps.photos.PhotoInfo
import com.shantanu.nmaps.photos.PhotoStore
import com.shantanu.nmaps.map.PhotoMarker
import com.shantanu.nmaps.health.HealthConnect
import com.shantanu.nmaps.offline.engine.LiveTileSource
import com.shantanu.nmaps.offline.engine.OfflineDbTileSource
import com.shantanu.nmaps.offline.engine.OfflineRouteException
import com.shantanu.nmaps.offline.engine.OfflineRouter
import com.shantanu.nmaps.offline.engine.OfflineSearch
import com.shantanu.nmaps.offline.engine.RouteAnnotator
import com.shantanu.nmaps.record.ActivityStore
import com.shantanu.nmaps.record.ActivitySummary
import com.shantanu.nmaps.record.NavInfo
import com.shantanu.nmaps.record.Recorder
import com.shantanu.nmaps.record.Sport
import com.shantanu.nmaps.record.Tracking
import com.shantanu.nmaps.sensors.Heading
import com.shantanu.nmaps.sensors.Sensors
import com.shantanu.nmaps.share.Friend
import com.shantanu.nmaps.share.ShareController
import com.shantanu.nmaps.timeline.TimelineCollector
import com.shantanu.nmaps.timeline.TimelineController
import com.shantanu.nmaps.update.Release
import com.shantanu.nmaps.update.Updater
import com.shantanu.nmaps.transit.TransitItinerary
import com.shantanu.nmaps.transit.Transitous
import com.shantanu.nmaps.transit.toRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

enum class Screen {
    Map, Search, Place, Route, Nav, Saved, Record, PickOnMap, Photo,
    Menu, Layers, Offline, OfflineSelect, Timeline, Share, Friend, Settings, Me, Report,
}

/** What search is choosing a place for: a Home/Work shortcut, or a slot in the trip being planned. */
sealed interface Pick {
    data class ForShortcut(val kind: Shortcut) : Pick
    data object Start : Pick
    data object Destination : Pick
    /** Replace stop [index], or add a new stop when null. */
    data class Stop(val index: Int?) : Pick
}

/** The trip in the directions planner. A null [start] means your live location. */
data class Plan(val start: Place? = null, val stops: List<Place> = emptyList())

const val MAX_STOPS = 5

/** Marks a route computed on the phone to honour the avoid options. */
const val OWN_ROUTE = "your settings"

/** An update found on GitHub Releases and how far along getting it is. */
data class UpdateState(
    val release: Release,
    /** 0..1 while downloading; null before and after. */
    val progress: Float? = null,
    val apk: File? = null,
    val error: String? = null,
    val dismissed: Boolean = false,
)

data class ReportState(val at: LatLon, val placeName: String?, val sending: Boolean = false, val sent: OsmNotes.Sent? = null, val error: String? = null)

data class NavState(
    val progress: RouteTracker.Progress,
    val arrived: Boolean = false,
    val rerouting: Boolean = false,
)

data class UiState(
    val screen: Screen = Screen.Map,
    val fix: Fix? = null,
    val hasLocation: Boolean = false,
    val follow: Follow = Follow.None,
    val online: Boolean = true,
    val settings: AppSettings = AppSettings(),
    /** A short confirmation or problem, shown briefly at the top. */
    val message: String? = null,

    val query: String = "",
    val results: List<Place> = emptyList(),
    val searching: Boolean = false,
    val searchError: String? = null,
    val category: Category? = null,

    val place: Place? = null,
    /** Weather and air quality at [place], once looked up. */
    val weather: Conditions? = null,
    val parking: Place? = null,
    val home: Place? = null,
    val work: Place? = null,
    /** Set while search is picking a place for something other than just viewing it. */
    val pick: Pick? = null,
    /** On the pick-on-map screen: the place under the pin, named by a reverse lookup. */
    val mapPick: Place? = null,
    /** A gallery photo opened from the map, and what's known about it. */
    val photo: GeoPhoto? = null,
    val photoInfo: PhotoInfo? = null,
    val plan: Plan = Plan(),

    val travel: Travel = Travel.Drive,
    /** The route options for the current trip; [route] is the chosen one. */
    val routes: List<Route> = emptyList(),
    val route: Route? = null,
    val routeLoading: Boolean = false,
    val routeError: String? = null,
    /** Shown above the route when no option meets the avoid settings. */
    val routeNotice: String? = null,
    /** Public-transit options when travel is Transit; [route] then holds the chosen one for the map. */
    val transit: List<TransitItinerary> = emptyList(),
    val transitIndex: Int = 0,
    /** Climb and profile for walking and cycling routes. */
    val routeElevation: TrackStats? = null,

    val nav: NavState? = null,
    val speedLimit: Int? = null,

    val saved: List<Place> = emptyList(),
    val recents: List<Place> = emptyList(),

    val hasCompass: Boolean = false,
    /** The user closed the calibration hint; don't show it again this session. */
    val calibrationDismissed: Boolean = false,

    val sport: Sport = Sport.Run,
    val activities: List<ActivitySummary> = emptyList(),
    /** Just-finished activity, shown as a summary with its track. */
    val finished: ActivitySummary? = null,
    val finishedTrack: List<LatLon> = emptyList(),

    val friend: Friend? = null,

    /** "Report a problem": where, about what, and how sending went. */
    val report: ReportState? = null,

    val update: UpdateState? = null,
    val checkingUpdate: Boolean = false,
    /** When the last update check finished, 0 when never. */
    val updateCheckedAt: Long = 0,
)

class MapViewModel(app: Application) : AndroidViewModel(app) {
    private val store = PlaceStore(app)
    private val settingsStore = SettingsStore(app)
    private val locations = LocationTracker(app)
    private val sensors = Sensors(app)
    private val activityStore = ActivityStore(app)

    val offline = OfflineMaps(app)

    // On-phone engine over the downloaded map tiles (and, for annotation, tiles fetched on demand).
    private val downloadedTiles = OfflineDbTileSource(java.io.File(app.filesDir, "mbgl-offline.db"))
    private val liveTiles = LiveTileSource(downloadedTiles)
    private val offlineSearch = OfflineSearch(downloadedTiles)
    private val offlineRouter = OfflineRouter(downloadedTiles)
    private val annotator = RouteAnnotator(liveTiles)
    private var annotateJob: Job? = null
    private var avoidJob: Job? = null
    /** Start, stops and destination of the current directions. */
    private var waypoints: List<LatLon> = emptyList()
    val share = ShareController(app, viewModelScope)
    val timeline = TimelineController(
        app, viewModelScope, { state.saved + listOfNotNull(state.home, state.work) },
        { (state.home != null) to (state.work != null) }, { state.settings.health },
    )
    val photos = PhotoStore(app)

    /** Thumbnails for the photos in view, rebuilt when the camera settles. */
    var photoMarkers by mutableStateOf<List<PhotoMarker>>(emptyList())
        private set
    private var photoJob: Job? = null

    var state by mutableStateOf(
        UiState(
            saved = store.saved(), recents = store.recents(), parking = store.parking(),
            home = store.shortcut(Shortcut.Home), work = store.shortcut(Shortcut.Work),
            hasCompass = sensors.hasCompass, activities = activityStore.list(), settings = settingsStore.load(),
        )
    )
        private set

    /** Live compass heading; kept outside [state] because it updates ~30 times a second. */
    var heading by mutableStateOf<Heading?>(null)
        private set

    /** Dark map forced by the light sensor during navigation at night or in tunnels. */
    var autoNight by mutableStateOf(false)
        private set

    /** Map rotation; kept outside [state] because it changes every frame while the map turns. */
    var bearing by mutableFloatStateOf(0f)
        private set

    /** What the camera shows, reported when it settles; used for offline downloads and nearby search. */
    var visible by mutableStateOf<Bounds?>(null)
        private set

    /** Live camera centre and zoom, for the scale bar and the coordinates readout. */
    var cameraCenter by mutableStateOf<LatLon?>(null)
        private set
    var cameraZoom by mutableFloatStateOf(15f)
        private set

    /** Weather where the map is looking, refreshed when the camera settles. */
    var mapWeather by mutableStateOf<Conditions?>(null)
        private set
    private var mapWeatherJob: Job? = null
    private var weatherJob: Job? = null

    private var speedLimits: SpeedLimitMap? = null
    private var lastReadyAreas: Set<Long> = emptySet()
    private var elevationJob: Job? = null

    private val cameraChannel = Channel<CameraCommand>(Channel.BUFFERED)
    val camera = cameraChannel.receiveAsFlow()

    @Volatile private var declination = 0f
    private var still = false
    private var foreground = false
    private var headingJob: Job? = null
    private var stillJob: Job? = null
    private var lightJob: Job? = null
    private var locationJob: Job? = null
    private var searchJob: Job? = null
    private var routeJob: Job? = null
    private var placeJob: Job? = null
    private var limitJob: Job? = null
    private var messageJob: Job? = null

    private var tracker: RouteTracker? = null
    private val announced = mutableSetOf<String>()
    private var offRouteCount = 0
    private var lastReroute = 0L
    private var lastLimitCheck = 0L

    private var ttsReady = false
    private val tts: TextToSpeech = TextToSpeech(app) { status ->
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) tts.setLanguage(Locale.getDefault())
    }

    init {
        Tracking.timelineEnabled = state.settings.timeline
        viewModelScope.launch { onlineFlow(app).collect { state = state.copy(online = it) } }
        viewModelScope.launch { Tracking.endNavRequests.collect { if (tracker != null) endNav() } }
        offline.refresh()
        if (state.settings.layers.photos) viewModelScope.launch { photos.scan() }
        // A quiet look for a newer build once per launch; the banner only shows when there is one.
        viewModelScope.launch { onlineFlow(app).collect { if (it && state.updateCheckedAt == 0L) checkForUpdate(quiet = true) } }
        // New or deleted areas change what the on-phone engine can see.
        viewModelScope.launch {
            offline.areas.collect { list ->
                val ready = list.filter { it.complete }.map { it.id }.toSet()
                if (ready != lastReadyAreas) {
                    lastReadyAreas = ready
                    offlineSearch.invalidate()
                    offlineRouter.invalidate()
                }
            }
        }
    }

    fun notify(text: String) = toast(text)

    private fun toast(text: String) {
        state = state.copy(message = text)
        messageJob?.cancel()
        messageJob = viewModelScope.launch {
            delay(3500)
            state = state.copy(message = null)
        }
    }

    private fun go(screen: Screen) {
        // Screens that frame something on the map take the camera away from following you.
        val frames = screen in setOf(Screen.Timeline, Screen.Friend, Screen.OfflineSelect)
        state = state.copy(screen = screen, follow = if (frames) Follow.None else state.follow)
    }

    // ---- location ------------------------------------------------------------------------

    fun onLocationPermission(granted: Boolean) {
        state = state.copy(hasLocation = granted)
        if (!granted) return
        if (state.settings.timeline) TimelineCollector.start(getApplication())
        startLocation()
    }

    /**
     * 1 Hz high-accuracy fixes while the app is on screen, and while navigating in the background
     * (the foreground service keeps that allowed). Otherwise GPS is released to save battery.
     */
    private fun startLocation() {
        if (locationJob != null || !state.hasLocation) return
        locationJob = viewModelScope.launch {
            var first = true
            locations.fixes().collect { raw ->
                declination = GeomagneticField(raw.lat.toFloat(), raw.lon.toFloat(), (raw.altitude ?: 0.0).toFloat(), raw.time).declination
                // Phone at rest: GPS still wanders a few metres. Hold the dot where it is, like Google Maps does.
                val prev = state.fix
                val fix = if (still && prev != null && state.screen != Screen.Nav && distanceMeters(prev.point, raw.point) < raw.accuracy) {
                    prev.copy(accuracy = raw.accuracy, time = raw.time)
                } else {
                    raw
                }
                state = state.copy(fix = fix)
                if (state.settings.timeline) timeline.record(raw)
                if (first && state.screen == Screen.Map) {
                    first = false
                    state = state.copy(follow = Follow.Flat)
                }
                if (state.screen == Screen.Nav) onNavFix(fix)
            }
        }
    }

    /** Locate button: first tap centres on you, second turns the map with the compass, third goes back to north-up. */
    fun locate() {
        if (state.fix == null) return
        val next = when {
            state.screen == Screen.Nav -> Follow.Nav
            state.follow == Follow.Flat && state.hasCompass -> Follow.Compass
            else -> Follow.Flat
        }
        state = state.copy(follow = next)
    }

    fun dismissCalibration() {
        state = state.copy(calibrationDismissed = true)
    }

    /** Sensors only run while the app is on screen (recording and sharing have their own service). */
    fun setForeground(active: Boolean) {
        foreground = active
        if (active) startLocation()
        else if (tracker == null) {
            locationJob?.cancel()
            locationJob = null
        }
        if (active) {
            if (headingJob == null) headingJob = viewModelScope.launch { sensors.heading { declination }.collect { heading = it } }
            if (stillJob == null) stillJob = viewModelScope.launch { sensors.stillness().collect { still = it } }
            share.startPolling()
        } else {
            headingJob?.cancel(); headingJob = null
            stillJob?.cancel(); stillJob = null
            still = false
            share.stopPolling()
        }
        syncLightSensor()
    }

    /** Ambient light only matters while navigating; switch to the dark map below ~15 lux, back above ~60. */
    private fun syncLightSensor() {
        val want = foreground && state.screen == Screen.Nav && state.settings.autoNight
        if (want && lightJob == null) {
            lightJob = viewModelScope.launch {
                sensors.lux().collect { lux ->
                    if (!autoNight && lux < 15f) autoNight = true
                    else if (autoNight && lux > 60f) autoNight = false
                }
            }
        } else if (!want) {
            lightJob?.cancel(); lightJob = null
            autoNight = false
        }
    }

    fun onUserPan() {
        if (state.follow != Follow.None) state = state.copy(follow = Follow.None)
    }

    fun onBearing(value: Float) {
        bearing = value
    }

    fun onCameraMove(center: LatLon, zoom: Float) {
        cameraCenter = center
        cameraZoom = zoom
    }

    fun zoom(by: Double) {
        cameraChannel.trySend(CameraCommand.ZoomBy(by))
    }

    fun onCameraIdle(bounds: Bounds) {
        visible = bounds
        if (state.screen == Screen.PickOnMap) resolveMapPick(bounds.center)
        loadMapWeather(bounds.center)
        loadPhotoMarkers(bounds)
    }

    /** Photos in view, as round thumbnails; off until zoomed in enough for them to be distinct. */
    private fun loadPhotoMarkers(bounds: Bounds) {
        photoJob?.cancel()
        if (!state.settings.layers.photos || cameraZoom < 11f) {
            if (photoMarkers.isNotEmpty()) photoMarkers = emptyList()
            return
        }
        val px = (56 * getApplication<Application>().resources.displayMetrics.density).toInt()
        photoJob = viewModelScope.launch {
            photoMarkers = photos.inBounds(bounds).mapNotNull { p -> photos.thumbnail(p, px)?.let { PhotoMarker(p.id, p.point, it) } }
        }
    }

    /** A photo thumbnail tapped on the map: show it with its details in the sheet. */
    fun openPhoto(id: Long) {
        val photo = photos.photos.firstOrNull { it.id == id } ?: return
        state = state.copy(screen = Screen.Photo, photo = photo, photoInfo = null, follow = Follow.None)
        cameraChannel.trySend(CameraCommand.FlyTo(photo.point, 16.5))
        placeJob?.cancel()
        placeJob = viewModelScope.launch {
            val info = photos.details(photo)
            if (state.photo?.id == photo.id) state = state.copy(photoInfo = info)
            val found = runCatching { Geocoder.reverse(photo.point) }.getOrNull()
            val address = listOfNotNull(found?.name, found?.address).filter { it.isNotBlank() }.joinToString(", ").ifBlank { "No address found" }
            if (state.photo?.id == photo.id) state = state.copy(photoInfo = info.copy(address = address))
        }
    }

    fun directionsToPhoto() {
        val photo = state.photo ?: return
        val name = state.photoInfo?.address?.substringBefore(",")?.takeIf { it.isNotBlank() && it != "No address found" } ?: "Photo"
        state = state.copy(place = Place(name, state.photoInfo?.address.orEmpty(), photo.lat, photo.lon), photo = null, photoInfo = null)
        directions()
    }

    /** Photos layer switched on from the layer sheet, once the gallery permission is granted. */
    fun enablePhotos() {
        setLayers(state.settings.layers.copy(photos = true))
        viewModelScope.launch {
            photos.scan()
            visible?.let { loadPhotoMarkers(it) }
            if (photos.photos.isEmpty()) toast("No photos with a location in the gallery")
        }
    }

    /** One lookup per settled view, a moment after the camera stops, and only when zoomed in enough to be somewhere. */
    private fun loadMapWeather(center: LatLon) {
        mapWeatherJob?.cancel()
        if (!state.online || cameraZoom < 7f) {
            mapWeather = null
            return
        }
        mapWeatherJob = viewModelScope.launch {
            delay(1200)
            mapWeather = runCatching { Weather.at(center) }.getOrNull()
        }
    }

    private fun loadWeather(at: LatLon) {
        weatherJob?.cancel()
        state = state.copy(weather = null)
        if (!state.online) return
        weatherJob = viewModelScope.launch {
            val c = runCatching { Weather.at(at) }.getOrNull() ?: return@launch
            if (state.place?.point == at) state = state.copy(weather = c)
        }
    }

    // ---- pick on map ---------------------------------------------------------------------

    /** "Choose on map" from the picker: the crosshair picks the spot, the sheet confirms it. */
    fun pickOnMap() {
        state = state.copy(screen = Screen.PickOnMap, follow = Follow.None, mapPick = null)
        // Name the spot already under the pin; the camera won't settle again until the user moves it.
        cameraCenter?.let(::resolveMapPick)
    }

    private fun resolveMapPick(at: LatLon) {
        val coords = "%.5f, %.5f".format(Locale.US, at.lat, at.lon)
        state = state.copy(mapPick = Place(coords, "Finding the address…", at.lat, at.lon))
        placeJob?.cancel()
        placeJob = viewModelScope.launch {
            delay(500)
            val found = runCatching { Geocoder.reverse(at) }.getOrNull()
            if (state.screen != Screen.PickOnMap || state.mapPick?.point != at) return@launch
            // No lookup (offline): the pin still works, named by its coordinates.
            state = state.copy(mapPick = found?.copy(lat = at.lat, lon = at.lon) ?: Place("Dropped pin", coords, at.lat, at.lon))
        }
    }

    fun confirmMapPick() {
        val p = state.mapPick ?: return
        placeJob?.cancel()
        state = state.copy(mapPick = null)
        if (state.pick != null) select(p) else showPlace(p)
    }

    fun resetNorth() {
        cameraChannel.trySend(CameraCommand.North)
    }

    // ---- menus and settings --------------------------------------------------------------

    fun openMenu() = go(Screen.Menu)
    fun openLayers() = go(Screen.Layers)
    fun openSettings() = go(Screen.Settings)

    fun setLayers(layers: MapLayers) {
        updateSettings(state.settings.copy(layers = layers))
        if (!layers.photos) photoMarkers = emptyList()
    }

    /** Health Connect toggle; the Settings page requests the permissions first. Off also revokes them. */
    fun setHealth(on: Boolean) {
        updateSettings(state.settings.copy(health = on))
        if (!on) viewModelScope.launch { HealthConnect.disconnect(getApplication()) }
        if (state.screen == Screen.Timeline) timeline.load()
    }

    fun updateSettings(s: AppSettings) {
        val timelineChanged = s.timeline != state.settings.timeline
        settingsStore.save(s)
        state = state.copy(settings = s)
        Tracking.timelineEnabled = s.timeline
        if (timelineChanged) {
            if (s.timeline) TimelineCollector.start(getApplication()) else TimelineCollector.stop(getApplication())
        }
        if (!s.voice) tts.stop()
        syncLightSensor()
    }

    fun toggleVoice() = updateSettings(state.settings.copy(voice = !state.settings.voice))

    // ---- search --------------------------------------------------------------------------

    fun openSearch() {
        state = state.copy(screen = Screen.Search, searchError = null)
    }

    fun openSaved() = go(Screen.Saved)

    fun setQuery(q: String) {
        state = state.copy(query = q, searchError = null, category = null)
        searchJob?.cancel()
        if (q.isBlank()) {
            state = state.copy(results = emptyList(), searching = false)
            return
        }
        // Coordinates go straight to a pin, online or not.
        parseCoordinates(q)?.let { at ->
            state = state.copy(results = listOf(Place("%.5f, %.5f".format(Locale.US, at.lat, at.lon), "Coordinates", at.lat, at.lon)), searching = false)
            return
        }
        searchJob = viewModelScope.launch {
            delay(300)
            state = state.copy(searching = true)
            state = try {
                state.copy(results = Geocoder.search(q.trim(), (state.fix?.point ?: visible?.center)), searching = false)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // No search server: look through saved places and the downloaded maps on the phone.
                val near = state.fix?.point ?: visible?.center
                val fromMaps = withContext(Dispatchers.Default) { runCatching { offlineSearch.search(q.trim(), near) }.getOrDefault(emptyList()) }
                val local = (localMatches(q) + fromMaps).distinctBy { it.key }
                state.copy(
                    searching = false, results = local,
                    searchError = when {
                        local.isNotEmpty() -> if (state.online) null else "Offline results from your saved places and downloaded maps."
                        state.online -> "Search isn't reachable. Check your connection and try again."
                        else -> "Nothing in your saved places or downloaded maps matches. Search everywhere needs a connection."
                    },
                )
            }
        }
    }

    private fun localMatches(q: String): List<Place> {
        val needle = q.trim().lowercase()
        return (state.saved + state.recents + listOfNotNull(state.parking))
            .filter { it.name.lowercase().contains(needle) || it.address.lowercase().contains(needle) }
            .distinctBy { it.key }
    }

    /**
     * Nearby places of a kind, nearest first. The map's own tiles answer at once (and offline);
     * Overpass then fills in everything within 3 km when it's reachable.
     */
    fun searchNearby(category: Category) {
        val at = state.fix?.point ?: visible?.center ?: return
        searchJob?.cancel()
        state = state.copy(category = category, query = "", searching = true, searchError = null, results = emptyList())
        searchJob = viewModelScope.launch {
            val fromTiles = CompletableDeferred<List<Place>>()
            cameraChannel.trySend(CameraCommand.QueryPois(category.subclasses, fromTiles))
            val local = (withTimeoutOrNull(1500) { fromTiles.await() } ?: emptyList()).sortedBy { distanceMeters(at, it.point) }
            state = state.copy(results = local)
            val remote = if (state.online) runCatching { Overpass.nearby(category, at) }.getOrNull() else null
            // An empty answer from Overpass shouldn't wipe what the map's own tiles already found.
            val found = (remote?.takeIf { it.isNotEmpty() } ?: local).sortedBy { distanceMeters(at, it.point) }
            state = state.copy(
                results = found,
                searching = false,
                searchError = when {
                    found.isNotEmpty() -> null
                    remote == null && state.online -> "Nearby search is busy right now. Try again in a minute."
                    remote == null -> "Nothing on the map here. Nearby search needs a connection for more."
                    else -> "Nothing tagged as ${category.label.lowercase()} within 3 km."
                },
            )
        }
    }

    fun clearNearby() {
        state = state.copy(category = null, results = emptyList())
    }

    fun clearRecents() {
        state = state.copy(recents = store.clearRecents())
    }

    // ---- places --------------------------------------------------------------------------

    /** Home/Work chip: go there if it's set, otherwise search for the address to set it. */
    fun openShortcut(kind: Shortcut) {
        val place = if (kind == Shortcut.Home) state.home else state.work
        if (place != null) select(place) else changeShortcut(kind)
    }

    /** Sets Home or Work straight from a Timeline suggestion. */
    fun setShortcutAt(kind: Shortcut, at: LatLon) {
        viewModelScope.launch {
            val found = runCatching { Geocoder.reverse(at) }.getOrNull()
            val p = Place(kind.label, listOfNotNull(found?.name, found?.address).filter { it.isNotBlank() }.joinToString(", "), at.lat, at.lon)
            store.setShortcut(kind, p)
            state = if (kind == Shortcut.Home) state.copy(home = p) else state.copy(work = p)
            toast("${kind.label} set")
        }
    }

    fun changeShortcut(kind: Shortcut) {
        pickPlace(Pick.ForShortcut(kind))
    }

    fun clearShortcut(kind: Shortcut) {
        store.clearShortcut(kind)
        state = if (kind == Shortcut.Home) state.copy(home = null) else state.copy(work = null)
        toast("${kind.label} removed")
    }

    private fun pickPlace(pick: Pick) {
        state = state.copy(pick = pick, query = "", results = emptyList(), category = null)
        openSearch()
    }

    fun select(place: Place) {
        when (val pick = state.pick) {
            is Pick.ForShortcut -> {
                val kind = pick.kind
                val named = place.copy(name = kind.label, address = listOf(place.name, place.address).filter { it.isNotBlank() }.joinToString(", "))
                store.setShortcut(kind, named)
                state = if (kind == Shortcut.Home) state.copy(home = named) else state.copy(work = named)
                state = state.copy(pick = null)
                toast("${kind.label} set")
                select(named)
                return
            }
            Pick.Start, Pick.Destination, is Pick.Stop -> {
                state = state.copy(recents = store.addRecent(place))
                fillPlan(pick, place)
                return
            }
            null -> Unit
        }
        state = state.copy(
            screen = Screen.Place, place = place, follow = Follow.None,
            recents = store.addRecent(place), route = null, routes = emptyList(), routeError = null,
        )
        cameraChannel.trySend(CameraCommand.FlyTo(place.point, 16.0))
        loadWeather(place.point)
    }

    fun onMapTap(name: String, at: LatLon) {
        if (state.screen in setOf(Screen.Nav, Screen.Route, Screen.OfflineSelect, Screen.PickOnMap)) return
        showPlace(Place(name, "", at.lat, at.lon))
    }

    fun onLongPress(at: LatLon) {
        if (state.screen in setOf(Screen.Nav, Screen.OfflineSelect, Screen.PickOnMap)) return
        showPlace(Place("Dropped pin", "%.5f, %.5f".format(Locale.US, at.lat, at.lon), at.lat, at.lon), lookupName = true)
    }

    /** Taps on the app's own markers: "friend:<topic>", "parking", "nearby:<i>". */
    fun onMarkerTap(id: String) {
        if (state.screen in setOf(Screen.Nav, Screen.PickOnMap)) return
        when {
            id == "parking" -> state.parking?.let { showPlace(it) }
            id.startsWith("friend:") -> share.friends.firstOrNull { it.link.topic == id.removePrefix("friend:") }?.let(::openFriend)
            id.startsWith("nearby:") -> state.results.getOrNull(id.removePrefix("nearby:").toInt())?.let(::select)
        }
    }

    fun onMeTap() {
        if (state.screen in setOf(Screen.Nav, Screen.PickOnMap) || state.fix == null) return
        go(Screen.Me)
    }

    /** Shows a place found on the map, then fills in its address from a reverse lookup. */
    private fun showPlace(place: Place, lookupName: Boolean = false) {
        state = state.copy(screen = Screen.Place, place = place, follow = Follow.None, route = null, routes = emptyList(), routeError = null)
        cameraChannel.trySend(CameraCommand.FlyTo(place.point, 16.5))
        loadWeather(place.point)
        placeJob?.cancel()
        placeJob = viewModelScope.launch {
            val found = runCatching { Geocoder.reverse(place.point) }.getOrNull() ?: return@launch
            if (state.place?.key != place.key) return@launch
            val address = listOf(found.name, found.address).filter { it.isNotBlank() && it != place.name }.joinToString(", ")
            state = state.copy(
                place = place.copy(
                    name = if (lookupName) found.name else place.name,
                    address = if (lookupName) found.address else address.ifEmpty { place.address },
                )
            )
        }
    }

    /** Saves [place] under [name] and [category]; saving an already-saved spot renames or recategorizes it. */
    fun savePlace(place: Place, name: String = place.name, category: String = place.category) {
        val named = place.copy(
            name = name.trim().ifBlank { place.name },
            category = category.trim().ifBlank { place.category },
        )
        val wasSaved = state.saved.any { it.key == place.key }
        state = state.copy(saved = store.save(named))
        toast(if (wasSaved) "Saved as ${named.name}" else "Saved")
    }

    fun setPlaceCategory(place: Place, category: String) {
        val updated = place.copy(category = category)
        state = state.copy(saved = store.save(updated))
    }

    fun unsave(place: Place) {
        state = state.copy(saved = store.unsave(place.key))
    }

    fun saveParking() {
        val fix = state.fix ?: return
        val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date())
        val p = Place("Parking", "Parked at $time", fix.lat, fix.lon)
        store.setParking(p)
        state = state.copy(parking = p, screen = Screen.Map)
        toast("Parking saved")
    }

    fun clearParking() {
        store.setParking(null)
        state = state.copy(parking = null, screen = Screen.Map, place = null)
    }

    /** Opens a location another app sent us as a geo: link. */
    fun openGeo(at: LatLon, label: String?) {
        leaveNav()
        if (label.isNullOrBlank()) showPlace(Place("Dropped pin", "%.5f, %.5f".format(Locale.US, at.lat, at.lon), at.lat, at.lon), lookupName = true)
        else showPlace(Place(label, "", at.lat, at.lon))
    }

    fun searchFor(text: String) {
        leaveNav()
        openSearch()
        setQuery(text)
    }

    // ---- routing -------------------------------------------------------------------------

    /** Directions from your location to the place on screen. */
    fun directions() {
        state = state.copy(screen = Screen.Route, follow = Follow.None, plan = Plan(), pick = null)
        fetchRoute()
    }

    /** The directions button on the map: open the planner and choose where to go. */
    fun openPlanner() {
        state = state.copy(screen = Screen.Route, follow = Follow.None, plan = Plan(), place = null, route = null, routes = emptyList(), routeError = null)
        pickPlace(Pick.Destination)
    }

    fun editStart() = pickPlace(Pick.Start)
    fun editDestination() = pickPlace(Pick.Destination)
    fun editStop(index: Int) = pickPlace(Pick.Stop(index))

    fun addStop() {
        if (state.plan.stops.size >= MAX_STOPS) {
            toast("Up to $MAX_STOPS stops")
            return
        }
        pickPlace(Pick.Stop(null))
    }

    fun removeStop(index: Int) {
        state = state.copy(plan = state.plan.copy(stops = state.plan.stops.filterIndexed { i, _ -> i != index }))
        fetchRoute()
    }

    fun clearStops() {
        state = state.copy(plan = state.plan.copy(stops = emptyList()))
        fetchRoute()
    }

    /** Start becomes destination and back; "your location" swaps as a fixed point taken now. */
    fun swapEnds() {
        val dest = state.place ?: return
        val start = state.plan.start ?: state.fix?.let { Place("Your location", "", it.lat, it.lon) } ?: return
        state = state.copy(place = start, plan = state.plan.copy(start = dest, stops = state.plan.stops.reversed()))
        fetchRoute()
    }

    /** Search picked "Your location" for the start. */
    fun useMyLocationAsStart() {
        state = state.copy(plan = state.plan.copy(start = null), pick = null, screen = Screen.Route)
        fetchRoute()
    }

    private fun fillPlan(pick: Pick, place: Place) {
        val plan = state.plan
        state = when (pick) {
            Pick.Start -> state.copy(plan = plan.copy(start = place))
            Pick.Destination -> state.copy(place = place)
            is Pick.Stop -> state.copy(
                plan = plan.copy(stops = if (pick.index == null) plan.stops + place else plan.stops.mapIndexed { i, p -> if (i == pick.index) place else p })
            )
            is Pick.ForShortcut -> state
        }.copy(pick = null, screen = Screen.Route, follow = Follow.None)
        fetchRoute()
    }

    fun setTravel(travel: Travel) {
        if (travel == state.travel) return
        state = state.copy(travel = travel, transit = emptyList())
        fetchRoute()
    }

    fun chooseRoute(index: Int) {
        val r = state.routes.getOrNull(index) ?: return
        state = state.copy(route = r)
        loadElevation(r)
    }

    private fun fetchRoute(fitCamera: Boolean = true) {
        routeJob?.cancel()
        annotateJob?.cancel()
        avoidJob?.cancel()
        val dest = state.place ?: run {
            state = state.copy(route = null, routes = emptyList(), routeLoading = false, routeError = null)
            return
        }
        val from = state.plan.start?.point ?: state.fix?.point
        if (from == null) {
            state = state.copy(
                route = null, routes = emptyList(), routeLoading = false,
                routeError = if (state.hasLocation) "Waiting for your location…" else "Allow location access to get directions from where you are.",
            )
            return
        }
        state = state.copy(routeLoading = true, routeError = null, routeNotice = null)
        if (!state.online) {
            routeOffline(listOf(from) + state.plan.stops.map { it.point } + dest.point, fitCamera)
            return
        }
        if (state.travel == Travel.Transit) {
            fetchTransit(from, dest.point, fitCamera)
            return
        }
        routeJob = viewModelScope.launch {
            state = try {
                waypoints = listOf(from) + state.plan.stops.map { it.point } + dest.point
                val routes = Router.routes(waypoints, state.travel)
                // Leave room for the planner card, which grows a row per stop.
                if (fitCamera) cameraChannel.trySend(CameraCommand.Fit(routes.flatMap { it.points }, topDp = 120 + 48 * (state.plan.stops.size + 2), bottomDp = 380))
                loadElevation(routes.first())
                annotate(routes)
                state.copy(routes = routes, route = routes.first(), routeLoading = false, routeNotice = null)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // The server is unreachable rather than saying "no route": try the downloaded maps.
                if (e !is java.io.IOException || e.message?.startsWith("No ") == true || e.message?.startsWith("One of") == true) {
                    state.copy(route = null, routes = emptyList(), routeLoading = false, routeError = e.message ?: "Couldn't get a route")
                } else {
                    routeOffline(listOf(from) + state.plan.stops.map { it.point } + dest.point, fitCamera)
                    state
                }
            }
        }
    }

    /** Directions computed on the phone from downloaded maps, when the routing server can't be reached. */
    private fun routeOffline(points: List<LatLon>, fitCamera: Boolean) {
        waypoints = points
        val travel = if (state.travel == Travel.Transit) Travel.Walk else state.travel
        routeJob = viewModelScope.launch {
            state = try {
                val route = withContext(Dispatchers.Default) { offlineRouter.route(points, travel, avoidSet()) }
                if (fitCamera) cameraChannel.trySend(CameraCommand.Fit(route.points, topDp = 120 + 48 * (state.plan.stops.size + 2), bottomDp = 380))
                val annotated = withContext(Dispatchers.Default) { runCatching { RouteAnnotator(downloadedTiles).annotate(route) }.getOrDefault(route) }
                state.copy(
                    routes = listOf(annotated), route = annotated, routeLoading = false, routeElevation = null,
                    routeNotice = "Offline route from your downloaded maps. Times are estimates" +
                        (if (state.travel == Travel.Transit) "; transit needs a connection, so this is walking." else "."),
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                state.copy(
                    route = null, routes = emptyList(), routeLoading = false,
                    routeError = (e as? OfflineRouteException)?.message?.let { "$it. Download this area in Offline maps to get directions without a connection." }
                        ?: "Directions need a connection or a downloaded map of this area.",
                )
            }
        }
    }

    /**
     * Reads the roads along each option from the map tiles: tolls, highways and ferries for the avoid
     * options, and flyovers and underpasses for the instructions. Runs after the routes show.
     */
    private fun annotate(routes: List<Route>) {
        annotateJob?.cancel()
        annotateJob = viewModelScope.launch {
            val annotated = runCatching {
                liveTiles.ensure(routes.flatMap { it.points }, 60.0, fetch = state.online)
                withContext(Dispatchers.Default) { routes.map { annotator.annotate(it) } }
            }.getOrNull()
            if (state.routes != routes || state.screen == Screen.Nav) return@launch
            if (annotated == null) {
                // Couldn't read road details (too far, offline): still honour the avoid options.
                applyAvoids()
                return@launch
            }
            val index = routes.indexOf(state.route).coerceAtLeast(0)
            state = state.copy(routes = annotated, route = annotated[index])
            applyAvoids()
        }
    }

    fun retryRoute() = fetchRoute()

    private fun fetchTransit(from: LatLon, to: LatLon, fitCamera: Boolean) {
        routeJob = viewModelScope.launch {
            state = try {
                val options = Transitous.plan(from, to)
                val chosen = options.first().toRoute()
                if (fitCamera) cameraChannel.trySend(CameraCommand.Fit(chosen.points, topDp = 140, bottomDp = 420))
                state.copy(
                    transit = options, transitIndex = 0, route = chosen, routes = listOf(chosen), routeLoading = false, routeElevation = null,
                    routeNotice = if (state.plan.stops.isNotEmpty()) "Transit trips go straight to the destination; stops are skipped." else null,
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                state.copy(
                    transit = emptyList(), route = null, routes = emptyList(), routeLoading = false,
                    routeError = if (e is java.io.IOException && e.message?.startsWith("No transit") == true)
                        "No timetable data covers this trip yet. Transitous only has the transit networks that publish open schedules."
                    else e.message ?: "Couldn't plan a transit trip",
                )
            }
        }
    }

    fun chooseTransit(index: Int) {
        val it = state.transit.getOrNull(index) ?: return
        val r = it.toRoute()
        state = state.copy(transitIndex = index, route = r, routes = listOf(r))
    }

    /**
     * The public routing server can't exclude road types, but it reports which ones each option
     * uses, so prefer the fastest option that avoids what the user asked to avoid.
     */
    private fun avoidSet(): Set<String> {
        val s = state.settings
        if (state.travel != Travel.Drive) return emptySet()
        return buildSet {
            if (s.avoidTolls) add("toll")
            if (s.avoidHighways) add("motorway")
            if (s.avoidFerries) add("ferry")
        }
    }

    private fun avoidNames(avoid: Set<String>) =
        avoid.mapNotNull { mapOf("toll" to "tolls", "motorway" to "highways", "ferry" to "ferries")[it] }.joinToString(" and ")

    fun setAvoid(tolls: Boolean = state.settings.avoidTolls, highways: Boolean = state.settings.avoidHighways, ferries: Boolean = state.settings.avoidFerries) {
        updateSettings(state.settings.copy(avoidTolls = tolls, avoidHighways = highways, avoidFerries = ferries))
        if (state.routes.isNotEmpty()) applyAvoids()
    }

    /**
     * The public routing server can't exclude roads, so: prefer the fastest annotated option that
     * avoids what was asked; if none does, compute one on the phone with those roads removed.
     */
    private fun applyAvoids() {
        avoidJob?.cancel()
        val avoid = avoidSet()
        val options = state.routes.filterNot { it.via == OWN_ROUTE }
        if (avoid.isEmpty()) {
            if (state.route?.via == OWN_ROUTE || state.routeNotice?.startsWith("Avoids") == true) {
                state = state.copy(routes = options, route = options.firstOrNull(), routeNotice = null)
            }
            return
        }
        options.firstOrNull { r -> r.classes.none { it in avoid } }?.let { ok ->
            state = state.copy(routes = options, route = ok, routeNotice = null)
            return
        }
        if (waypoints.size < 2) return
        state = state.copy(routeNotice = "Finding a route without ${avoidNames(avoid)}…")
        avoidJob = viewModelScope.launch {
            val own = runCatching {
                liveTiles.ensure(waypoints + options.flatMap { it.points }, 2_500.0, fetch = state.online)
                withContext(Dispatchers.Default) {
                    annotator.annotate(OfflineRouter(liveTiles).route(waypoints, Travel.Drive, avoid)).copy(via = OWN_ROUTE)
                }
            }.getOrNull()
            if (state.routes.filterNot { it.via == OWN_ROUTE } != options || state.screen == Screen.Nav) return@launch
            state = if (own != null && own.classes.none { it in avoid }) {
                state.copy(routes = options + own, route = own, routeNotice = "Avoids ${avoidNames(avoid)}. Worked out on your phone, so the time is an estimate.")
            } else {
                state.copy(routes = options, route = options.firstOrNull(), routeNotice = "No route avoids ${avoidNames(avoid)} here, so this one uses them.")
            }
        }
    }

    /** Elevation matters on foot and on a bike; fetch it in the background once a route is known. */
    private fun loadElevation(route: Route) {
        elevationJob?.cancel()
        state = state.copy(routeElevation = null)
        if (route.travel == Travel.Drive) return
        elevationJob = viewModelScope.launch {
            val stats = runCatching { Terrain.stats(route.points) }.getOrNull()
            if (state.route == route) state = state.copy(routeElevation = stats)
        }
    }

    // ---- navigation ----------------------------------------------------------------------

    fun startNav() {
        val route = state.route ?: return
        val fix = state.fix ?: return
        // Late annotation or avoid results must not swap the route under active guidance.
        annotateJob?.cancel()
        avoidJob?.cancel()
        val t = RouteTracker(route)
        tracker = t
        announced.clear()
        offRouteCount = 0
        lastLimitCheck = 0
        state = state.copy(screen = Screen.Nav, follow = Follow.Nav, nav = NavState(t.update(fix.point)), speedLimit = null)
        loadSpeedLimits(route)
        syncLightSensor()
        publishNav()
        Tracking.sync(getApplication())
        speak(route.steps.firstOrNull()?.instruction ?: "Starting route")
    }

    /** Anything that takes over the screen from outside (a link, a file) ends navigation cleanly first. */
    private fun leaveNav() {
        if (tracker != null) endNav()
    }

    fun endNav() {
        routeJob?.cancel()
        tracker = null
        tts.stop()
        limitJob?.cancel()
        Tracking.nav.value = null
        Tracking.sync(getApplication())
        state = state.copy(screen = Screen.Place, nav = null, route = null, routes = emptyList(), follow = Follow.Flat, speedLimit = null)
        syncLightSensor()
    }

    private fun onNavFix(fix: Fix) {
        val t = tracker ?: return
        val nav = state.nav ?: return
        if (nav.arrived) return
        val p = t.update(fix.point)
        val dest = state.place

        // Being near the destination only counts near the end: loops and round trips start there too.
        if (dest != null && (p.remaining < 25 || (p.remaining < 200 && distanceMeters(fix.point, dest.point) < 20))) {
            state = state.copy(nav = nav.copy(progress = p, arrived = true))
            Tracking.nav.value = null
            Tracking.sync(getApplication())
            speak("You have arrived at ${dest.name}")
            return
        }

        val away = p.offRoute > 45 + fix.accuracy
        offRouteCount = if (away) offRouteCount + 1 else 0
        if (offRouteCount >= 3 && System.currentTimeMillis() - lastReroute > 10_000) {
            reroute(fix)
            return
        }
        state = state.copy(nav = nav.copy(progress = p))
        announce(t, p)
        publishNav()
        checkSpeedLimit(fix)
    }

    /** Keeps the notification (and a live share's ETA) in step with the next turn. */
    private fun publishNav() {
        val route = state.route ?: return
        val p = state.nav?.progress ?: return
        val step = route.steps.getOrNull(p.stepIndex)
        val remaining = route.duration * (p.remaining / route.distance.coerceAtLeast(1.0))
        Tracking.nav.value = NavInfo(
            instruction = step?.instruction.orEmpty(),
            destination = state.place?.name ?: route.via,
            eta = System.currentTimeMillis() + (remaining * 1000).toLong(),
            distance = formatDistance(p.toManeuver).toString(),
            glyph = step?.let { com.shantanu.nmaps.ui.glyphFor(it) },
            progress = (1 - p.remaining / route.distance.coerceAtLeast(1.0)).toFloat().coerceIn(0f, 1f),
        )
    }

    /** One Overpass query for every posted limit along the route; then lookups are local. */
    private fun loadSpeedLimits(route: Route) {
        speedLimits = null
        limitJob?.cancel()
        if (route.travel != Travel.Drive || !state.online) return
        limitJob = viewModelScope.launch {
            speedLimits = runCatching { Overpass.speedLimitsAlong(route.points) }.getOrNull()
        }
    }

    /**
     * The posted limit where you are: from the route's limits when they loaded, otherwise asked
     * for every 30 s (OSRM doesn't return limits, OpenStreetMap has them).
     */
    private fun checkSpeedLimit(fix: Fix) {
        if (state.route?.travel != Travel.Drive) return
        speedLimits?.let { map ->
            val limit = map.at(fix.point)
            if (limit != state.speedLimit) state = state.copy(speedLimit = limit)
            return
        }
        if (!state.online) return
        val now = System.currentTimeMillis()
        if (now - lastLimitCheck < 30_000 || limitJob?.isActive == true) return
        lastLimitCheck = now
        limitJob = viewModelScope.launch {
            val limit = runCatching { Overpass.speedLimit(fix.point) }.getOrNull()
            state = state.copy(speedLimit = limit)
        }
    }

    private fun announce(t: RouteTracker, p: RouteTracker.Progress) {
        val step = t.route.steps.getOrNull(p.stepIndex) ?: return
        val (far, near) = when (t.route.travel) {
            Travel.Drive -> 700.0 to 90.0
            Travel.Cycle -> 250.0 to 40.0
            Travel.Walk, Travel.Transit -> 120.0 to 20.0
        }
        val id = "${t.hashCode()}:${p.stepIndex}"
        when {
            p.toManeuver <= near && announced.add("$id:now") -> speak(step.instruction)
            p.toManeuver in (near + 60)..far && announced.add("$id:soon") -> {
                val lanes = Instructions.laneHint(step.lanes)?.let { " $it." }.orEmpty()
                speak("In ${spokenDistance(p.toManeuver)}, ${step.instruction.replaceFirstChar { it.lowercase() }}.$lanes")
            }
        }
    }

    private fun reroute(fix: Fix) {
        val dest = state.place ?: return
        lastReroute = System.currentTimeMillis()
        offRouteCount = 0
        state = state.copy(nav = state.nav?.copy(rerouting = true))
        routeJob?.cancel()
        routeJob = viewModelScope.launch {
            try {
                // Keep the stops not yet reached: those whose leg ends after the current step.
                val old = tracker?.route
                val step = state.nav?.progress?.stepIndex ?: 0
                val ahead = state.plan.stops.filterIndexed { i, _ -> (old?.legEnds?.getOrNull(i) ?: Int.MAX_VALUE) >= step }
                val points = listOf(fix.point) + ahead.map { it.point } + dest.point
                val avoid = avoidSet()
                val route = when {
                    !state.online -> withContext(Dispatchers.Default) { offlineRouter.route(points, state.travel, avoid) }
                    // The server can't avoid roads, so a trip that avoids them reroutes on the phone too.
                    avoid.isNotEmpty() -> {
                        liveTiles.ensure(points, 2_500.0, fetch = true)
                        withContext(Dispatchers.Default) { annotator.annotate(OfflineRouter(liveTiles).route(points, state.travel, avoid)) }
                    }
                    else -> Router.route(points, state.travel)
                }
                if (ahead.size != state.plan.stops.size) state = state.copy(plan = state.plan.copy(stops = ahead))
                val t = RouteTracker(route)
                tracker = t
                announced.clear()
                state = state.copy(route = route, routes = listOf(route), nav = NavState(t.update(fix.point)))
                loadSpeedLimits(route)
                speak("Route updated. ${route.steps.getOrNull(1)?.instruction.orEmpty()}")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                state = state.copy(nav = state.nav?.copy(rerouting = false))
            }
        }
    }

    private fun speak(text: String) {
        if (ttsReady && state.settings.voice) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, text.hashCode().toString())
    }

    // ---- recording -----------------------------------------------------------------------

    fun openRecord() {
        state = state.copy(screen = Screen.Record, follow = if (state.fix != null) Follow.Flat else Follow.None)
    }

    fun setSport(sport: Sport) {
        state = state.copy(sport = sport)
    }

    /** Call after asking for activity-recognition and notification permissions; works without them. */
    fun startRecording() {
        Recorder.start(state.sport)
        Tracking.sync(getApplication())
        state = state.copy(finished = null, finishedTrack = emptyList(), follow = Follow.Flat)
    }

    fun pauseRecording() = Recorder.pause()

    fun resumeRecording() = Recorder.resume()

    fun finishRecording() {
        val activity = Recorder.finish()
        Tracking.sync(getApplication())
        activity ?: return
        // Under 10 m there's nothing worth keeping.
        if (activity.distance < 10 && activity.points.size < 2) {
            toast("Nothing recorded: you didn't move")
            return
        }
        val summary = activityStore.save(activity)
        state = state.copy(
            activities = activityStore.list(),
            finished = summary,
            finishedTrack = activity.points.map { LatLon(it.lat, it.lon) },
        )
        cameraChannel.trySend(CameraCommand.Fit(state.finishedTrack, topDp = 120, bottomDp = 420))
    }

    fun closeSummary() {
        state = state.copy(finished = null, finishedTrack = emptyList())
    }

    fun deleteActivity(summary: ActivitySummary) {
        activityStore.delete(summary.id)
        state = state.copy(activities = activityStore.list(), finished = null, finishedTrack = emptyList())
    }

    fun gpxFile(summary: ActivitySummary) = activityStore.gpx(summary)

    /** Elevation profile of a recorded activity, from the altitude stored with each point. */
    suspend fun activityStats(summary: ActivitySummary): TrackStats? = withContext(Dispatchers.Default) {
        val pts = activityStore.points(summary.id).filter { it.ele != null }.map { GpxPoint(it.lat, it.lon, it.ele, it.time) }
        if (pts.size < 2) null else TrackStats.of(pts)
    }

    // ---- offline maps --------------------------------------------------------------------

    fun openOffline() {
        offline.refresh()
        go(Screen.Offline)
    }

    fun selectOfflineArea() = go(Screen.OfflineSelect)

    /** Downloads [bounds], named after the town at its centre. */
    fun downloadArea(bounds: Bounds) {
        val pixelRatio = getApplication<Application>().resources.displayMetrics.density
        go(Screen.Offline)
        viewModelScope.launch {
            val name = runCatching { Geocoder.reverse(bounds.center) }.getOrNull()?.let { p ->
                p.address.split(", ").firstOrNull { it.isNotBlank() } ?: p.name
            } ?: "Area ${"%.2f, %.2f".format(Locale.US, bounds.center.lat, bounds.center.lon)}"
            offline.download(name, bounds, pixelRatio)
        }
    }

    fun showArea(bounds: Bounds) {
        state = state.copy(screen = Screen.Map, follow = Follow.None)
        cameraChannel.trySend(CameraCommand.Fit(listOf(LatLon(bounds.south, bounds.west), LatLon(bounds.north, bounds.east)), 100, 120))
    }

    // ---- saved places as GPX -------------------------------------------------------------

    /** Saved places as GPX waypoints, for other map apps. */
    fun exportSaved(): File? {
        if (state.saved.isEmpty()) return null
        val body = Gpx.write("Saved places", waypoints = state.saved.map { GpxWaypoint(it.name, it.address, it.lat, it.lon) })
        return shareableGpx(getApplication(), "Saved_places", body)
    }

    // ---- timeline ------------------------------------------------------------------------

    fun openTimeline() {
        go(Screen.Timeline)
        timeline.load()
    }

    fun fitTimeline() {
        val path = timeline.ui.path
        if (path.size >= 2) cameraChannel.trySend(CameraCommand.Fit(path, topDp = 80, bottomDp = 600))
    }

    // ---- sharing -------------------------------------------------------------------------

    fun openShare() = go(Screen.Share)

    fun startLiveShare(name: String, durationMs: Long): String {
        if (name != state.settings.shareName) updateSettings(state.settings.copy(shareName = name))
        return share.start(name, durationMs).link.uri
    }

    fun stopLiveShare() = share.stop(state.fix)

    fun addFriend(text: String): Boolean {
        leaveNav()
        val ok = share.add(text)
        toast(if (ok) "Following shared location" else "That isn't a Maps live-location link")
        if (ok && state.screen != Screen.Share) go(Screen.Share)
        return ok
    }

    fun openFriend(friend: Friend) {
        state = state.copy(screen = Screen.Friend, friend = friend, follow = Follow.None)
        friend.last?.let { cameraChannel.trySend(CameraCommand.FlyTo(LatLon(it.lat, it.lon), 16.0)) }
    }

    fun directionsToFriend(friend: Friend) {
        val loc = friend.last ?: return
        state = state.copy(place = Place(friend.label, "Shared location", loc.lat, loc.lon))
        directions()
    }

    fun removeFriend(friend: Friend) {
        share.remove(friend)
        go(Screen.Share)
    }

    // ---- data ----------------------------------------------------------------------------

    fun clearSearchHistory() {
        clearRecents()
        toast("Search history cleared")
    }

    suspend fun exportTimelineDay(): File? = timeline.exportDay()

    // ---- app updates ---------------------------------------------------------------------

    fun checkForUpdate(quiet: Boolean = false) {
        if (state.checkingUpdate) return
        state = state.copy(checkingUpdate = true)
        viewModelScope.launch {
            val found = try {
                Updater.check()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // updateCheckedAt stays 0, so the quiet launch check tries again when the connection comes back.
                state = state.copy(checkingUpdate = false)
                if (!quiet) toast("Couldn't reach GitHub to check for updates. Try again later.")
                return@launch
            }
            val keep = state.update?.takeIf { found != null && it.release.build == found.build }
            state = state.copy(
                checkingUpdate = false, updateCheckedAt = System.currentTimeMillis(),
                update = keep ?: found?.let { UpdateState(it) },
            )
            if (!quiet) toast(if (found != null) "Maps ${found.version} is available" else "You're on the latest build")
        }
    }

    /** Downloads the APK with progress, then opens the installer. Tapping again after a download reopens it. */
    fun installUpdate() {
        val u = state.update ?: return
        u.apk?.takeIf { it.exists() }?.let { Updater.install(getApplication(), it); return }
        if (u.progress != null) return
        state = state.copy(update = u.copy(progress = 0f, error = null, dismissed = false))
        viewModelScope.launch {
            val result = runCatching { Updater.download(getApplication(), u.release) { p -> state = state.copy(update = state.update?.copy(progress = p)) } }
            state = state.copy(update = state.update?.copy(progress = null, apk = result.getOrNull(), error = result.exceptionOrNull()?.let { "Download failed. Check your connection and try again." }))
            result.getOrNull()?.let { Updater.install(getApplication(), it) }
        }
    }

    fun dismissUpdate() {
        state = state.copy(update = state.update?.copy(dismissed = true))
    }

    // ---- report a problem ----------------------------------------------------------------

    fun openReport(at: LatLon? = null, placeName: String? = null) {
        val where = at ?: state.place?.point ?: cameraCenter ?: state.fix?.point ?: return
        // "Home" and "Work" are private labels; mappers need the place's real name.
        val name = (placeName ?: state.place?.name)?.let { n ->
            val p = state.place
            if (p != null && n == p.name && (p.key == state.home?.key || p.key == state.work?.key)) p.address.substringBefore(",").ifBlank { null } else n
        }
        state = state.copy(screen = Screen.Report, report = ReportState(where, name), follow = Follow.None)
        cameraChannel.trySend(CameraCommand.FlyTo(where, 17.0))
    }

    fun sendReport(problem: Problem, details: String) {
        val r = state.report ?: return
        if (details.isBlank()) {
            state = state.copy(report = r.copy(error = "Describe what's wrong so a mapper can fix it."))
            return
        }
        state = state.copy(report = r.copy(sending = true, error = null))
        viewModelScope.launch {
            state = try {
                val sent = OsmNotes.send(r.at, problem, details, r.placeName)
                state.copy(report = r.copy(sending = false, sent = sent))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                state.copy(report = r.copy(sending = false, error = if (state.online) "Couldn't send. Try again in a moment." else "You're offline. Send it when you're connected."))
            }
        }
    }

    // ---- voice ---------------------------------------------------------------------------

    var listening by mutableStateOf(false)
        private set

    fun startVoice() {
        listening = true
    }

    fun stopVoice() {
        listening = false
    }

    /** Acts on what was said: navigate somewhere, find something nearby, or just search for it. */
    fun onVoice(spoken: String) {
        listening = false
        when (val cmd = VoiceCommand.parse(spoken)) {
            is VoiceCommand.NavigateShortcut -> {
                val place = if (cmd.shortcut == Shortcut.Home) state.home else state.work
                if (place == null) changeShortcut(cmd.shortcut) else {
                    state = state.copy(place = place)
                    directions()
                }
            }
            is VoiceCommand.Navigate -> navigateByName(cmd.target)
            is VoiceCommand.Nearby -> {
                openSearch()
                searchNearby(cmd.category)
            }
            VoiceCommand.WhereAmI -> {
                state = state.copy(screen = Screen.Map)
                if (state.fix != null) {
                    locate()
                    go(Screen.Me)
                }
            }
            is VoiceCommand.Search -> searchFor(cmd.query)
        }
    }

    /** "Navigate to Cyber Hub": take the best match and go straight to directions. */
    private fun navigateByName(target: String) {
        val saved = (state.saved + listOfNotNull(state.home, state.work)).firstOrNull { it.name.equals(target, ignoreCase = true) }
        if (saved != null) {
            state = state.copy(place = saved)
            directions()
            return
        }
        searchFor(target)
        viewModelScope.launch {
            val found = runCatching { Geocoder.search(target, state.fix?.point ?: visible?.center) }.getOrNull()?.firstOrNull()
            if (found == null) {
                toast("Couldn't find \"$target\"")
                return@launch
            }
            state = state.copy(place = found, recents = store.addRecent(found), query = "", results = emptyList())
            directions()
        }
    }

    // ---- back ----------------------------------------------------------------------------

    /** Returns false when there is nothing left to go back from, so the system can close the app. */
    fun back(): Boolean {
        state = when (state.screen) {
            Screen.Map -> return false
            Screen.Search, Screen.Saved -> {
                val planning = state.pick.let { it == Pick.Start || it == Pick.Destination || it is Pick.Stop }
                state.copy(
                    screen = when {
                        planning && state.place != null -> Screen.Route
                        planning -> Screen.Map
                        state.place != null -> Screen.Place
                        else -> Screen.Map
                    },
                    category = null, pick = null,
                )
            }
            Screen.PickOnMap -> {
                placeJob?.cancel()
                state.copy(screen = Screen.Search, mapPick = null)
            }
            Screen.Photo -> {
                placeJob?.cancel()
                state.copy(screen = Screen.Map, photo = null, photoInfo = null)
            }
            Screen.Record -> state.copy(screen = Screen.Map, finished = null, finishedTrack = emptyList())
            Screen.Place -> state.copy(screen = Screen.Map, place = null, results = if (state.category != null) state.results else emptyList())
            Screen.Route -> {
                routeJob?.cancel()
                annotateJob?.cancel()
                avoidJob?.cancel()
                state.copy(screen = Screen.Place, route = null, routes = emptyList(), routeLoading = false, routeError = null)
            }
            Screen.Nav -> {
                endNav()
                return true
            }
            Screen.OfflineSelect -> state.copy(screen = Screen.Offline)
            Screen.Friend -> state.copy(screen = Screen.Share, friend = null)
            Screen.Report -> state.copy(screen = if (state.place != null) Screen.Place else Screen.Map, report = null)
            Screen.Menu, Screen.Layers, Screen.Offline, Screen.Timeline, Screen.Share, Screen.Settings, Screen.Me ->
                state.copy(screen = Screen.Map)
        }
        return true
    }

    override fun onCleared() {
        // The service would otherwise keep a frozen navigation notification with nobody to end it.
        if (tracker != null) {
            Tracking.nav.value = null
            Tracking.sync(getApplication())
        }
        tts.shutdown()
    }
}
