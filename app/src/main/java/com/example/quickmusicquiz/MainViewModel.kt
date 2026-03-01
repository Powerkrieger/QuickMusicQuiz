package com.example.quickmusicquiz

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ─── Game State ──────────────────────────────────────────────────────────────────────

/**
 * Sealed class representing every possible screen/state of the quiz.
 *
 * A sealed class is like an enum where each variant can carry its own data.
 * Only the states defined here can exist — the compiler enforces exhaustiveness
 * in when-expressions, so we can never forget to handle a state in the UI.
 */
sealed class GameState {

    /** Initial state — user has not logged into Spotify yet. */
    object NotConnected : GameState()

    /** Browser has been opened for Spotify login; waiting for the redirect. */
    object Authenticating : GameState()

    /**
     * Logged in and App Remote is (being) connected.
     * User can set options and start a round.
     * [errorMessage] is shown when something went wrong (nullable = no error).
     */
    data class Connected(val errorMessage: String? = null) : GameState()

    /** Fetching the playlist's track list from the Spotify Web API. */
    object LoadingTrack : GameState()

    /**
     * A clip is currently playing.
     * [clipDurationSeconds] is the total clip length; [secondsRemaining] counts down.
     */
    data class Playing(
        val clipDurationSeconds: Int,
        val secondsRemaining: Int
    ) : GameState()

    /**
     * The clip has finished; the 10-second answer countdown is running.
     * [isRevealed] becomes true when the user presses "Reveal Answer".
     */
    data class CountingDown(
        val track: TrackInfo,
        val secondsRemaining: Int,
        val isRevealed: Boolean
    ) : GameState()
}

// ─── ViewModel ───────────────────────────────────────────────────────────────────────

/**
 * AndroidViewModel is a ViewModel that holds an Application reference.
 * We use it instead of plain ViewModel because our managers need a Context.
 *
 * ViewModel survives screen rotation — state is not lost when the device rotates.
 * It is created once per Activity and cleared when the Activity is permanently finished.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    // ─── Spotify managers ─────────────────────────────────────────────────────────────

    // Expose managers so MainActivity can call connect/disconnect in lifecycle methods.
    val authManager     = SpotifyAuthManager(application)
    val playbackManager = SpotifyPlaybackManager(application, authManager)  // authManager used for album year lookups

    // Persists clip duration, start position, and the last selected playlist across restarts.
    private val configPrefs = application.getSharedPreferences("quiz_config", Context.MODE_PRIVATE)

    // ─── Observable state (UI layer collects these) ───────────────────────────────────

    // StateFlow: a hot observable that always holds the latest value.
    // Compose's collectAsState() re-renders the UI whenever the value changes.
    private val _gameState = MutableStateFlow<GameState>(
        if (authManager.isAuthenticated()) GameState.Connected() else GameState.NotConnected
    )
    val gameState: StateFlow<GameState> = _gameState.asStateFlow()

    // Whether Spotify App Remote is currently connected.
    // Separate from gameState so the "Start Round" button can react to it independently.
    private val _isAppRemoteConnected = MutableStateFlow(false)
    val isAppRemoteConnected: StateFlow<Boolean> = _isAppRemoteConnected.asStateFlow()

    // Gates the timer in tickSecond(). Exposed so the UI can show timer pause state.
    private val _isPaused = MutableStateFlow(false)
    val isTimerPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    // Source of truth for whether Spotify is currently paused.
    private val _isPlaybackPaused = MutableStateFlow(false)
    val isPlaybackPaused: StateFlow<Boolean> = _isPlaybackPaused.asStateFlow()

    private val _albumArt = MutableStateFlow<Bitmap?>(null)
    val albumArt: StateFlow<Bitmap?> = _albumArt.asStateFlow()

    // Curated playlist catalogue shown on the config screen.
    val playlists: List<SpotifyPlaylist> = CURATED_PLAYLISTS

    private val _selectedPlaylistId = MutableStateFlow(configPrefs.getString("selected_playlist_id", null))
    val selectedPlaylistId: StateFlow<String?> = _selectedPlaylistId.asStateFlow()

    private val _customPlaylistInput = MutableStateFlow(configPrefs.getString("custom_playlist_input", "") ?: "")
    val customPlaylistInput: StateFlow<String> = _customPlaylistInput.asStateFlow()

    // One-shot URL event: MainActivity observes this and opens a browser tab.
    // Cleared by onAuthUrlConsumed() immediately after the browser is opened.
    private val _authUrl = MutableStateFlow<String?>(null)
    val authUrl: StateFlow<String?> = _authUrl.asStateFlow()

    // ─── User-selected options ────────────────────────────────────────────────────────

    var clipDurationSeconds: Int = configPrefs.getInt("clip_duration", 5)
        private set

    var startFromBeginning: Boolean = configPrefs.getBoolean("start_from_beginning", true)
        private set

    fun setClipDuration(seconds: Int) {
        clipDurationSeconds = seconds
        configPrefs.edit().putInt("clip_duration", seconds).apply()
    }

    fun setStartFromBeginning(value: Boolean) {
        startFromBeginning = value
        configPrefs.edit().putBoolean("start_from_beginning", value).apply()
    }

    /** Selects a playlist from the curated list and clears any custom URL input. */
    fun selectPlaylist(id: String) {
        _selectedPlaylistId.value = id
        _customPlaylistInput.value = ""
        configPrefs.edit()
            .putString("selected_playlist_id", id)
            .putString("custom_playlist_input", "")
            .apply()
    }

    /** Updates the custom URL/ID field. Extracts the playlist ID if the input is valid. */
    fun setCustomPlaylistInput(text: String) {
        _customPlaylistInput.value = text
        val id = extractPlaylistId(text)
        _selectedPlaylistId.value = id
        configPrefs.edit()
            .putString("custom_playlist_input", text)
            .putString("selected_playlist_id", id)
            .apply()
    }

    private fun extractPlaylistId(input: String): String? {
        val s = input.trim()
        // https://open.spotify.com/playlist/ID?si=...
        Regex("""spotify\.com/playlist/([A-Za-z0-9]+)""").find(s)
            ?.let { return it.groupValues[1] }
        // spotify:playlist:ID
        Regex("""spotify:playlist:([A-Za-z0-9]+)""").find(s)
            ?.let { return it.groupValues[1] }
        // bare ID: Spotify IDs are base-62, typically 22 chars
        if (s.matches(Regex("[A-Za-z0-9]{15,30}"))) return s
        return null
    }

    // ─── Job tracking ─────────────────────────────────────────────────────────────────

    // We keep references so we can cancel ongoing work when the user skips.
    private var roundJob:     Job? = null
    private var countdownJob: Job? = null

    // The track currently being guessed (needed for restart/reveal).
    private var currentTrack: TrackInfo? = null

    // ─── Auth ─────────────────────────────────────────────────────────────────────────

    /**
     * Triggers the PKCE auth flow by emitting a URL for the Activity to open in a browser.
     */
    fun startAuth() {
        _gameState.value = GameState.Authenticating
        _authUrl.value   = authManager.buildAuthorizationUrl()
    }

    /**
     * Called by MainActivity after Android delivers the redirect intent
     * (quickmusicquiz://callback?code=...) from the Spotify login browser.
     */
    fun handleAuthRedirect(code: String) {
        viewModelScope.launch {
            val success = authManager.exchangeCodeForToken(code)
            _gameState.value = if (success) GameState.Connected() else GameState.NotConnected
        }
    }

    /** Called by the Activity after it has opened the auth URL; clears the event. */
    fun onAuthUrlConsumed() { _authUrl.value = null }

    // ─── App Remote lifecycle callbacks (called from MainActivity) ────────────────────

    fun onAppRemoteConnected() {
        _isAppRemoteConnected.value = true
    }

    fun onAppRemoteDisconnected() {
        _isAppRemoteConnected.value = false
    }

    fun onAppRemoteFailure(error: String) {
        _isAppRemoteConnected.value = false
        _gameState.value = GameState.Connected(errorMessage = "Spotify: $error")
    }

    // ─── Round logic ──────────────────────────────────────────────────────────────────

    /**
     * Starts a new round:
     *   1. Fetches tracks from the playlist via Web API
     *   2. Picks a random track
     *   3. Plays a clip (from beginning or random position)
     *   4. Waits for the clip to finish, then pauses and starts the answer countdown
     */
    // Called from PlayingScreen — pauses both the timer and Spotify.
    fun pauseGame() {
        _isPaused.value = true
        _isPlaybackPaused.value = true
        playbackManager.pause()
    }

    // Called from PlayingScreen — resumes both the timer and Spotify.
    fun resumeGame() {
        _isPaused.value = false
        _isPlaybackPaused.value = false
        playbackManager.resume()
    }

    // Called from CountingDownScreen — pauses/resumes the answer countdown only.
    fun toggleCountdownPause() {
        _isPaused.value = !_isPaused.value
    }

    // Called from CountingDownScreen — toggles Spotify playback while answer is shown.
    fun togglePlayback() {
        if (_isPlaybackPaused.value) {
            _isPlaybackPaused.value = false
            playbackManager.resume()
        } else {
            _isPlaybackPaused.value = true
            playbackManager.pause()
        }
    }

    /**
     * Waits for one second of unpaused time.
     * Polls every 50 ms so that pausing/resuming feels near-instant.
     */
    private suspend fun tickSecond() {
        var elapsed = 0L
        while (elapsed < 1000L) {
            delay(50L)
            if (!_isPaused.value) elapsed += 50L
        }
    }

    fun startRound() {
        roundJob?.cancel()
        countdownJob?.cancel()
        _isPaused.value = false
        _isPlaybackPaused.value = false
        _albumArt.value = null

        // viewModelScope: the coroutine is cancelled automatically when the ViewModel
        // is cleared (i.e. when the Activity is permanently finished).
        roundJob = viewModelScope.launch {
            val playlistId = _selectedPlaylistId.value
            if (playlistId == null) {
                _gameState.value = GameState.Connected(errorMessage = "Please select a playlist first")
                return@launch
            }

            _gameState.value = GameState.LoadingTrack

            // Start shuffle playback of the playlist via App Remote, then read
            // the current track from PlayerState — no Web API call needed.
            playbackManager.playPlaylistShuffle(playlistId)

            val track = playbackManager.getCurrentTrack()
            if (track == null) {
                _gameState.value = GameState.Connected(
                    errorMessage = "Could not get track info — is Spotify open and playing?"
                )
                return@launch
            }

            // Fetch release year and album art in parallel — both are optional.
            val year     = playbackManager.getAlbumYear(track.albumUri)
            val albumArt = playbackManager.getTrackImage(track.imageUriRaw)
            _albumArt.value = albumArt
            currentTrack = track.copy(year = year)

            val clipDuration = clipDurationSeconds

            // Spotify already started from position 0; seek if a random start is wanted.
            if (!startFromBeginning) {
                val maxStartMs = (track.durationMs - clipDuration * 1000L).coerceAtLeast(0L)
                playbackManager.seekTo((0L..maxStartMs).random())
            }

            // Count down the clip duration so the UI can show a progress indicator
            for (s in clipDuration downTo 0) {
                _gameState.value = GameState.Playing(clipDuration, s)
                if (s == 0) break
                tickSecond()
            }

            _isPlaybackPaused.value = true
            playbackManager.pause()
            startAnswerCountdown(currentTrack!!)
        }
    }

    /**
     * Runs a 10-second countdown after the clip finishes.
     * Preserves [CountingDown.isRevealed] across ticks so a reveal mid-countdown sticks.
     */
    private fun startAnswerCountdown(track: TrackInfo) {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            for (s in 10 downTo 0) {
                // Auto-reveal at 0; otherwise preserve any mid-countdown reveal the user triggered.
                val isRevealed = s == 0 || (_gameState.value as? GameState.CountingDown)?.isRevealed ?: false
                _gameState.value = GameState.CountingDown(track, s, isRevealed)
                if (s == 0) {
                    // Auto-resume playback when the answer is revealed at countdown end.
                    _isPlaybackPaused.value = false
                    playbackManager.resume()
                    break
                }
                tickSecond()
            }
        }
    }

    // ─── User actions during CountingDown ─────────────────────────────────────────────

    /** Shows the track name and artist in the UI and resumes playback. Countdown continues. */
    fun revealAnswer() {
        val current = _gameState.value as? GameState.CountingDown ?: return
        _gameState.value = current.copy(isRevealed = true)
        _isPlaybackPaused.value = false
        playbackManager.resume()
    }

    /** Replays the current track from the very beginning. */
    fun restartTrack() {
        val track = currentTrack ?: return
        _isPlaybackPaused.value = false
        viewModelScope.launch {
            playbackManager.playTrackAt(track.uri, 0L)
        }
    }

    /** Stops the clip early and immediately shows the revealed answer. No countdown. */
    fun skipToAnswer() {
        roundJob?.cancel()
        countdownJob?.cancel()
        val track = currentTrack ?: return
        _isPaused.value = false
        _isPlaybackPaused.value = true
        playbackManager.pause()
        _gameState.value = GameState.CountingDown(track, secondsRemaining = 0, isRevealed = true)
    }

    /** Cancels everything and returns to the configuration screen. */
    fun backToMenu() {
        roundJob?.cancel()
        countdownJob?.cancel()
        _isPaused.value = false
        _isPlaybackPaused.value = false
        _albumArt.value = null
        currentTrack = null
        playbackManager.pause()
        _gameState.value = GameState.Connected()
    }

    /** Cancels the current round and immediately starts a new one. */
    fun skipToNextRound() {
        startRound()
    }

    // ─── Cleanup ──────────────────────────────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        // Disconnect App Remote when the ViewModel is destroyed (app process ends)
        playbackManager.disconnect()
    }
}

// ─── Curated playlist catalogue ───────────────────────────────────────────────────────
//
// IDs are taken from the share URL: open.spotify.com/playlist/{ID}
// Verify/update them if Spotify retires a playlist.

private fun playlist(id: String, name: String, category: String) =
    SpotifyPlaylist(id, name, category)

val CURATED_PLAYLISTS: List<SpotifyPlaylist> = listOf(

    // ── Decades ───────────────────────────────────────────────────────────────────────
    playlist("37i9dQZF1DWTJ7xPn4vNaz", "All Out 70s",   "Decades"),
    playlist("37i9dQZF1DX4UtSsGT1Sbe", "All Out 80s",   "Decades"),
    playlist("37i9dQZF1DXbTxeAdrVG2l", "All Out 90s",   "Decades"),
    playlist("37i9dQZF1DX4o1oenSJRJd", "All Out 2000s", "Decades"),
    playlist("37i9dQZF1DX5Ejj0EkURtP", "All Out 2010s", "Decades"),
    playlist("37i9dQZF1DX2kiBW15EFZJ", "All Out 2020s", "Decades"),

    // ── Pop ───────────────────────────────────────────────────────────────────────────
    playlist("37i9dQZF1DXcBWIGoYBM5M", "Today's Top Hits", "Pop"),
    playlist("37i9dQZF1DX4dyzvuaRJ0n", "mint",             "Pop"),
    playlist("37i9dQZF1DWTl4y3vgJOXW", "Pop Rising",       "Pop"),

    // ── Rock ──────────────────────────────────────────────────────────────────────────
    playlist("37i9dQZF1DWXRqgorJj26U", "Rock Classics",  "Rock"),
    playlist("37i9dQZF1DXdwmD2GFJpNs", "All New Rock",   "Rock"),

    // ── Hip-Hop ───────────────────────────────────────────────────────────────────────
    playlist("37i9dQZF1DX0XUsuxWHRQd", "Rap Caviar",      "Hip-Hop"),
    playlist("37i9dQZF1DX2vIgwty3mBs", "Most Necessary",  "Hip-Hop"),

    // ── R&B ───────────────────────────────────────────────────────────────────────────
    playlist("37i9dQZF1DX4SBhb3fqCJd", "Are & Be",       "R&B"),
    playlist("37i9dQZF1DXca6e9l1WLEF", "R&B Classics",   "R&B"),

    // ── Electronic ────────────────────────────────────────────────────────────────────
    playlist("37i9dQZF1DXaXB8fQg7xoQ", "Dance Hits",         "Electronic"),
    playlist("37i9dQZF1DX8tZsk88tuoI", "Electronic Rising",  "Electronic"),

    // ── Country ───────────────────────────────────────────────────────────────────────
    playlist("37i9dQZF1DX1lVhptIYRda", "Hot Country",     "Country"),
    playlist("37i9dQZF1DXjdUTt6GnWCp", "Country Classics","Country"),
)
