package com.example.quickmusicquiz

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.net.ssl.HttpsURLConnection

/**
 * Manages Spotify OAuth using Authorization Code Flow with PKCE.
 *
 * PKCE (Proof Key for Code Exchange) is the correct OAuth flow for mobile apps.
 * Because the app has no server, it cannot keep a "client secret" safe. PKCE
 * replaces the client secret with a one-time cryptographic proof:
 *
 *   1. We generate a random "code verifier" and keep it secret locally.
 *   2. We hash it (SHA-256) to get the "code challenge" and send that to Spotify.
 *   3. When we exchange the auth code for tokens, we send the original verifier.
 *   4. Spotify hashes it and checks it matches — proving we made the original request.
 */
class SpotifyAuthManager(private val context: Context) {

    companion object {
        // ─── TODO: fill in your own values ──────────────────────────────────────────
        // Create an app at https://developer.spotify.com/dashboard, then:
        //   • Copy the Client ID below
        //   • Add "quickmusicquiz://callback" as a Redirect URI in the dashboard
        const val CLIENT_ID = "dc4206d5fc80486babc6f19926a8ea7e"
        const val REDIRECT_URI = "quickmusicquiz://callback"

        // app-remote-control lets the App Remote SDK control Spotify playback.
        // We also need a valid token for Web API calls (public playlist reads need
        // no extra scope, but a token is still required).
        const val SCOPES = "app-remote-control playlist-read-private playlist-read-collaborative"
        // ─────────────────────────────────────────────────────────────────────────────

        private const val PREFS_NAME   = "spotify_auth_prefs"
        private const val KEY_ACCESS   = "access_token"
        private const val KEY_REFRESH  = "refresh_token"
        private const val KEY_EXPIRES  = "expires_at_ms"   // epoch ms when token expires
        private const val KEY_VERIFIER = "pkce_code_verifier"

        private const val TOKEN_URL = "https://accounts.spotify.com/api/token"
        // Expire 60 s early so we never use a token right at its edge
        private const val EXPIRY_BUFFER_MS = 60_000L
    }

    // SharedPreferences: Android's lightweight key-value persistent storage.
    // MODE_PRIVATE means only this app can read/write these prefs.
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ─── PKCE helpers ────────────────────────────────────────────────────────────────

    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(64)
        SecureRandom().nextBytes(bytes)
        // URL_SAFE | NO_PADDING | NO_WRAP: produces a base64url string with no = padding
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun generateCodeChallenge(verifier: String): String {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(hash, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    // ─── Build auth URL ──────────────────────────────────────────────────────────────

    /**
     * Returns the Spotify authorization URL to open in a browser.
     * Also saves the code verifier so we can use it later in [exchangeCodeForToken].
     */
    fun buildAuthorizationUrl(): String {
        val verifier = generateCodeVerifier()
        prefs.edit().putString(KEY_VERIFIER, verifier).apply()

        return Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", generateCodeChallenge(verifier))
            .appendQueryParameter("scope", SCOPES)
            .build()
            .toString()
    }

    // ─── Token exchange ──────────────────────────────────────────────────────────────

    /**
     * Exchanges the authorization code (received via redirect) for access + refresh tokens.
     * Must run off the main thread — uses Dispatchers.IO for the network call.
     */
    suspend fun exchangeCodeForToken(code: String): Boolean = withContext(Dispatchers.IO) {
        val verifier = prefs.getString(KEY_VERIFIER, null) ?: return@withContext false
        try {
            val body = buildString {
                append("client_id=").append(URLEncoder.encode(CLIENT_ID, "UTF-8"))
                append("&grant_type=authorization_code")
                append("&code=").append(URLEncoder.encode(code, "UTF-8"))
                append("&redirect_uri=").append(URLEncoder.encode(REDIRECT_URI, "UTF-8"))
                append("&code_verifier=").append(URLEncoder.encode(verifier, "UTF-8"))
            }
            val json = post(TOKEN_URL, body) ?: return@withContext false
            saveTokens(json)
            true
        } catch (e: Exception) {
            false
        }
    }

    // ─── Token refresh ───────────────────────────────────────────────────────────────

    /**
     * Refreshes the access token if it has expired (or is about to).
     * Returns true if a valid token is now available.
     */
    suspend fun refreshTokenIfNeeded(): Boolean {
        if (!isTokenExpired()) return true
        val refreshToken = prefs.getString(KEY_REFRESH, null) ?: return false

        return withContext(Dispatchers.IO) {
            try {
                val body = buildString {
                    append("client_id=").append(URLEncoder.encode(CLIENT_ID, "UTF-8"))
                    append("&grant_type=refresh_token")
                    append("&refresh_token=").append(URLEncoder.encode(refreshToken, "UTF-8"))
                }
                val json = post(TOKEN_URL, body) ?: return@withContext false
                saveTokens(json)
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    // ─── HTTP helper ─────────────────────────────────────────────────────────────────

    /** POSTs a URL-encoded body and returns the parsed JSON response, or null on error. */
    private fun post(url: String, body: String): JSONObject? {
        val connection = URL(url).openConnection() as HttpsURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        connection.doOutput = true
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000

        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        if (connection.responseCode != 200) return null
        return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
    }

    // ─── Token storage ───────────────────────────────────────────────────────────────

    private fun saveTokens(json: JSONObject) {
        val accessToken  = json.getString("access_token")
        val expiresIn    = json.getInt("expires_in")  // seconds
        // Keep old refresh token if the response doesn't include a new one
        val refreshToken = json.optString("refresh_token",
            prefs.getString(KEY_REFRESH, "") ?: "")

        prefs.edit()
            .putString(KEY_ACCESS, accessToken)
            .putString(KEY_REFRESH, refreshToken)
            .putLong(KEY_EXPIRES, System.currentTimeMillis() + expiresIn * 1000L - EXPIRY_BUFFER_MS)
            .apply()
    }

    private fun isTokenExpired(): Boolean =
        System.currentTimeMillis() >= prefs.getLong(KEY_EXPIRES, 0L)

    fun getAccessToken(): String? = prefs.getString(KEY_ACCESS, null)

    /** True if we have a non-expired access token stored. */
    fun isAuthenticated(): Boolean = !isTokenExpired() && getAccessToken() != null

    fun clearTokens() = prefs.edit().clear().apply()
}
