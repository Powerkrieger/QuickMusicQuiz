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
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.resume

/** Lightweight holder for a curated Spotify playlist entry. */
data class SpotifyPlaylist(val id: String, val name: String, val category: String)

/** Lightweight data holder for a Spotify track. */
data class TrackInfo(
    val uri: String,
    val name: String,
    val artist: String,
    val durationMs: Long,
    val imageUriRaw: String = "", // used to fetch album art via App Remote imagesApi
    val year: Int? = null         // always null — Web API year lookup removed (403 without extended quota)
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
    private val context: Context
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
                            uri         = track.uri ?: "",
                            name        = track.name ?: "Unknown",
                            artist      = track.artist?.name ?: "Unknown",
                            durationMs  = track.duration,
                            imageUriRaw = track.imageUri?.raw ?: ""
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
     * Fetches the release year of a track via the iTunes Search API.
     *
     * No auth required — completely free and doesn't count against Spotify quota.
     * Searches by artist + track name and reads the releaseDate from the first result.
     * Returns null silently on failure — the year is optional in the UI.
     */
    suspend fun getTrackYear(trackName: String, artist: String): Int? = withContext(Dispatchers.IO) {
        try {
            val query = URLEncoder.encode("$artist $trackName", "UTF-8")
            val connection = URL("https://itunes.apple.com/search?term=$query&entity=song&limit=5")
                .openConnection() as HttpsURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout    = 10_000

            if (connection.responseCode != 200) return@withContext null
            val json    = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val results = json.optJSONArray("results") ?: return@withContext null
            if (results.length() == 0) return@withContext null

            // releaseDate format: "2005-01-01T08:00:00Z" — take the year part
            results.getJSONObject(0).optString("releaseDate").split("-").firstOrNull()?.toIntOrNull()
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
