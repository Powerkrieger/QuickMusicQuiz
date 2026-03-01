package com.example.quickmusicquiz

import android.content.Context
import android.graphics.Bitmap
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.protocol.types.Image
import com.spotify.protocol.types.ImageUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.resume

/** Lightweight data holder for a Spotify track. */
data class TrackInfo(
    val uri: String,
    val name: String,
    val artist: String,
    val durationMs: Long,
    val albumUri: String = "",    // used for year lookup via Web API
    val imageUriRaw: String = "", // used to fetch album art via App Remote imagesApi
    val year: Int? = null
)

/**
 * Controls Spotify playback via the App Remote SDK.
 *
 * The App Remote SDK communicates with the Spotify app on the same device via IPC
 * (inter-process communication). It gives us both playback control AND the current
 * track's metadata (name, artist, duration) via PlayerState — so we don't need the
 * Spotify Web API for playlist fetching.
 *
 * We still use the Web API for one small thing: fetching the album release year,
 * which the App Remote SDK does not expose.
 */
class SpotifyPlaybackManager(
    private val context: Context,
    private val authManager: SpotifyAuthManager
) {

    // The live connection to the Spotify app. Null when disconnected.
    private var appRemote: SpotifyAppRemote? = null

    val isConnected: Boolean get() = appRemote?.isConnected == true

    // ─── App Remote connection ────────────────────────────────────────────────────────

    /**
     * Connects to the Spotify app running on the same device.
     *
     * Call this in Activity.onStart(). The callbacks arrive on the main thread.
     * If the user has not yet authorized this app, Spotify will show an auth dialog
     * (because showAuthView = true).
     *
*/
    fun connect(onConnected: () -> Unit, onFailure: (String) -> Unit) {
        // ConnectionParams identifies our app to the Spotify app
        val params = ConnectionParams.Builder(SpotifyAuthManager.CLIENT_ID)
            .setRedirectUri(SpotifyAuthManager.REDIRECT_URI)
            .showAuthView(true)  // show Spotify's permission dialog on first connect
            .build()

        SpotifyAppRemote.connect(context, params, object : Connector.ConnectionListener {
            override fun onConnected(remote: SpotifyAppRemote) {
                appRemote = remote
                onConnected()
            }

            override fun onFailure(throwable: Throwable) {
                appRemote = null
                onFailure(throwable.localizedMessage ?: "Unknown Spotify error")
            }
        })
    }

    /**
     * Disconnects from the Spotify app. Call this in Activity.onStop().
     * Always disconnect to avoid keeping Spotify awake when our app is in the background.
     */
    fun disconnect() {
        appRemote?.let { SpotifyAppRemote.disconnect(it) }
        appRemote = null
    }

    // ─── Playback control ─────────────────────────────────────────────────────────────

    /**
     * Starts shuffle playback of a playlist and waits briefly for Spotify to begin.
     * After this call, [getCurrentTrack] will return the randomly chosen track.
     */
    suspend fun playPlaylistShuffle(playlistId: String) {
        val remote = appRemote ?: return
        remote.playerApi.setShuffle(true)
        remote.playerApi.play("spotify:playlist:$playlistId")
        delay(800)  // give Spotify time to pick a track and start buffering
    }

    /**
     * Reads the current track from the Spotify app's PlayerState.
     *
     * This is a one-shot async call bridged into a coroutine via suspendCancellableCoroutine.
     * The App Remote SDK delivers the result via a callback on the main thread; we resume
     * the coroutine from it.
     *
     * Returns null if not connected or if no track is currently loaded.
     */
    suspend fun getCurrentTrack(): TrackInfo? = suspendCancellableCoroutine { cont ->
        val remote = appRemote
        if (remote == null) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }

        remote.playerApi.playerState
            .setResultCallback { state ->
                if (cont.isCompleted) return@setResultCallback
                val track = state.track
                if (track == null) {
                    cont.resume(null)
                } else {
                    cont.resume(
                        TrackInfo(
                            uri          = track.uri ?: "",
                            name         = track.name ?: "Unknown",
                            artist       = track.artist?.name ?: "Unknown",
                            durationMs   = track.duration,
                            albumUri     = track.album?.uri ?: "",
                            imageUriRaw  = track.imageUri?.raw ?: ""
                        )
                    )
                }
            }
            .setErrorCallback {
                if (!cont.isCompleted) cont.resume(null)
            }
    }

    /**
     * Fetches the album art bitmap via the App Remote imagesApi.
     * Uses LARGE (640×640) for display quality. Returns null on failure.
     */
    suspend fun getTrackImage(imageUriRaw: String): Bitmap? = suspendCancellableCoroutine { cont ->
        val remote = appRemote
        if (remote == null || imageUriRaw.isEmpty()) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }
        remote.imagesApi
            .getImage(ImageUri(imageUriRaw), Image.Dimension.LARGE)
            .setResultCallback { bitmap ->
                if (!cont.isCompleted) cont.resume(bitmap)
            }
            .setErrorCallback {
                if (!cont.isCompleted) cont.resume(null)
            }
    }

    /**
     * Fetches the release year of an album from the Spotify Web API.
     *
     * The albumUri comes from the App Remote PlayerState (e.g. "spotify:album:4LH4d3cOWNNsVw41Gqt2kv").
     * We extract the ID and call /v1/albums/{id} to get the release_date field.
     * Returns null silently if the call fails — the year is optional in the UI.
     */
    suspend fun getAlbumYear(albumUri: String): Int? = withContext(Dispatchers.IO) {
        if (albumUri.isEmpty()) return@withContext null
        if (!authManager.refreshTokenIfNeeded()) return@withContext null
        val token = authManager.getAccessToken() ?: return@withContext null
        val albumId = albumUri.removePrefix("spotify:album:")

        try {
            val connection = URL("https://api.spotify.com/v1/albums/$albumId")
                .openConnection() as HttpsURLConnection
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.connectTimeout = 10_000
            connection.readTimeout    = 10_000

            if (connection.responseCode != 200) return@withContext null
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            // release_date is "YYYY", "YYYY-MM", or "YYYY-MM-DD" depending on precision
            json.optString("release_date").split("-").firstOrNull()?.toIntOrNull()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Plays a specific track URI from the given position.
     * Used by restartTrack() to replay the current track from the beginning.
     *
     * There is a short delay before seeking because Spotify needs a moment to
     * buffer and start the track before it will accept a seek command.
     */
    suspend fun playTrackAt(trackUri: String, startPositionMs: Long) {
        val remote = appRemote ?: return
        remote.playerApi.play(trackUri)
        if (startPositionMs > 0L) {
            delay(700)  // give Spotify time to start before seeking
            remote.playerApi.seekTo(startPositionMs)
        }
    }

    fun seekTo(positionMs: Long) { appRemote?.playerApi?.seekTo(positionMs) }
    fun pause()  { appRemote?.playerApi?.pause() }
    fun resume() { appRemote?.playerApi?.resume() }
}
