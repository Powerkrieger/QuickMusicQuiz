package com.example.quickmusicquiz

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.quickmusicquiz.ui.theme.QuickMusicQuizTheme

/**
 * The single Activity for the whole app.
 *
 * An Activity is Android's entry point for user interaction (a "screen owner").
 * We use one Activity + Jetpack Compose instead of multiple Activities or Fragments.
 *
 * Key lifecycle responsibilities here:
 *   • onStart / onStop  — connect / disconnect Spotify App Remote
 *   • onNewIntent       — receive the Spotify auth redirect while app is running
 *   • onCreate          — check if app was cold-started via the auth redirect
 */
class MainActivity : ComponentActivity() {

    // viewModels() creates the ViewModel the first time and returns the same instance
    // on subsequent calls (even after rotation). The ViewModel lives until the Activity
    // is permanently finished (not just rotated).
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()  // let content draw behind system bars (status bar, nav bar)

        // Handle the case where the app was NOT running when Spotify redirected back.
        // Android starts the app fresh via this intent in that case.
        handleRedirectIntent(intent)

        // setContent replaces the traditional XML layout with a Compose UI tree.
        setContent {
            QuickMusicQuizTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    QuizApp(viewModel)
                }
            }
        }
    }

    /**
     * Called when this Activity is already running (on top of the back stack) and a new
     * intent arrives. This is the normal path for the Spotify auth redirect because the
     * app will usually be in the foreground while the user logs in.
     *
     * launchMode="singleTop" in the manifest ensures this method is called instead of
     * creating a duplicate Activity instance.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleRedirectIntent(intent)
    }

    /**
     * Connect to the Spotify app when our app becomes visible.
     * onStart() is called both on first launch and when returning from background.
     */
    override fun onStart() {
        super.onStart()
        if (viewModel.authManager.isAuthenticated()) {
            viewModel.playbackManager.connect(
                onConnected = { viewModel.onAppRemoteConnected() },
                onFailure   = { error -> viewModel.onAppRemoteFailure(error) }
            )
        }
    }

    /**
     * Disconnect when the app goes to the background.
     * Holding the App Remote connection in the background wastes resources and
     * keeps the Spotify app unnecessarily active.
     */
    override fun onStop() {
        super.onStop()
        viewModel.playbackManager.disconnect()
        viewModel.onAppRemoteDisconnected()
    }

    /**
     * Checks if the intent carries the Spotify redirect URI
     * (quickmusicquiz://callback?code=...) and passes the code to the ViewModel.
     *
     * We also connect App Remote here immediately. The normal path (onStart) misses
     * the first-launch case because the token exchange hasn't completed yet when
     * onStart runs — so isAuthenticated() is still false at that point.
     * App Remote does its own auth and doesn't need the PKCE token to connect.
     */
    private fun handleRedirectIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme == "quickmusicquiz" && uri.host == "callback") {
            val code = uri.getQueryParameter("code") ?: return
            viewModel.handleAuthRedirect(code)
            viewModel.playbackManager.connect(
                onConnected = { viewModel.onAppRemoteConnected() },
                onFailure   = { error -> viewModel.onAppRemoteFailure(error) }
            )
        }
    }
}

// ─── Root Composable ─────────────────────────────────────────────────────────────────

/**
 * Observes the ViewModel's state flows and renders the appropriate screen.
 *
 * collectAsState() converts a StateFlow into Compose State, so the UI re-renders
 * automatically whenever a new value is emitted.
 */
@Composable
fun QuizApp(viewModel: MainViewModel) {
    val gameState         by viewModel.gameState.collectAsState()
    val isRemoteConnected by viewModel.isAppRemoteConnected.collectAsState()
    val authUrl           by viewModel.authUrl.collectAsState()
    val isPlaybackPaused  by viewModel.isPlaybackPaused.collectAsState()
    val isTimerPaused     by viewModel.isTimerPaused.collectAsState()
    val albumArt          by viewModel.albumArt.collectAsState()

    val context = LocalContext.current

    LaunchedEffect(authUrl) {
        val url = authUrl ?: return@LaunchedEffect
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        viewModel.onAuthUrlConsumed()
    }

    when (val state = gameState) {
        is GameState.NotConnected   -> NotConnectedScreen(onConnect = { viewModel.startAuth() })
        is GameState.Authenticating -> AuthenticatingScreen()
        is GameState.Connected      -> ConnectedScreen(
            viewModel         = viewModel,
            isRemoteConnected = isRemoteConnected,
            errorMessage      = state.errorMessage
        )
        is GameState.LoadingTrack   -> LoadingScreen()
        is GameState.Playing        -> PlayingScreen(
            state            = state,
            isPlaybackPaused = isPlaybackPaused,
            onTogglePause    = { if (isPlaybackPaused) viewModel.resumeGame() else viewModel.pauseGame() },
            onSkipToAnswer   = { viewModel.skipToAnswer() }
        )
        is GameState.CountingDown   -> CountingDownScreen(
            state                  = state,
            isTimerPaused          = isTimerPaused,
            isPlaybackPaused       = isPlaybackPaused,
            albumArt               = albumArt,
            onToggleCountdownPause = { viewModel.toggleCountdownPause() },
            onTogglePlayback       = { viewModel.togglePlayback() },
            onReveal               = { viewModel.revealAnswer() },
            onRestart              = { viewModel.restartTrack() },
            onSkip                 = { viewModel.skipToNextRound() }
        )
    }
}

// ─── Screens ─────────────────────────────────────────────────────────────────────────

@Composable
fun NotConnectedScreen(onConnect: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Quick Music Quiz", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Connect your Spotify Premium account to play.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(32.dp))
        Button(onClick = onConnect) {
            Text("Connect with Spotify")
        }
    }
}

@Composable
fun AuthenticatingScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text("Opening Spotify login…")
        }
    }
}

@Composable
fun ConnectedScreen(
    viewModel: MainViewModel,
    isRemoteConnected: Boolean,
    errorMessage: String?
) {
    // remember + mutableStateOf: local UI state that survives recomposition.
    // Initialized from the ViewModel so values persist across rounds.
    var clipDuration   by remember { mutableStateOf(viewModel.clipDurationSeconds) }
    var fromBeginning  by remember { mutableStateOf(viewModel.startFromBeginning) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Quick Music Quiz", style = MaterialTheme.typography.headlineMedium)

        Spacer(Modifier.height(32.dp))

        // ── Clip duration ─────────────────────────────────────────────────────────
        Text("Clip Duration", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            listOf(5, 10).forEach { seconds ->
                FilterChip(
                    selected = clipDuration == seconds,
                    onClick  = {
                        clipDuration = seconds
                        viewModel.setClipDuration(seconds)
                    },
                    label = { Text("$seconds seconds") }
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        // ── Start position ────────────────────────────────────────────────────────
        Text("Start Position", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            FilterChip(
                selected = fromBeginning,
                onClick  = { fromBeginning = true;  viewModel.setStartFromBeginning(true) },
                label    = { Text("Beginning") }
            )
            FilterChip(
                selected = !fromBeginning,
                onClick  = { fromBeginning = false; viewModel.setStartFromBeginning(false) },
                label    = { Text("Random") }
            )
        }

        Spacer(Modifier.height(32.dp))

        // ── Error message ─────────────────────────────────────────────────────────
        if (errorMessage != null) {
            Text(
                text  = errorMessage,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
        }

        // ── Start button ──────────────────────────────────────────────────────────
        Button(
            onClick  = { viewModel.startRound() },
            enabled  = isRemoteConnected,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Start Round")
        }

        if (!isRemoteConnected) {
            Spacer(Modifier.height(8.dp))
            Text(
                text  = "Connecting to Spotify app…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
fun LoadingScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text("Loading track…")
        }
    }
}

@Composable
fun PlayingScreen(
    state: GameState.Playing,
    isPlaybackPaused: Boolean,
    onTogglePause: () -> Unit,
    onSkipToAnswer: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("♫", style = MaterialTheme.typography.displayLarge)
        Spacer(Modifier.height(16.dp))
        Text(
            text  = if (isPlaybackPaused) "Paused" else "Clip playing",
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text  = "${state.secondsRemaining}s",
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(32.dp))
        Button(
            onClick  = onTogglePause,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isPlaybackPaused) "▶  Resume" else "⏸  Pause")
        }
        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick  = onSkipToAnswer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Skip to Answer →")
        }
    }
}

@Composable
fun CountingDownScreen(
    state: GameState.CountingDown,
    isTimerPaused: Boolean,
    isPlaybackPaused: Boolean,
    albumArt: Bitmap?,
    onToggleCountdownPause: () -> Unit,
    onTogglePlayback: () -> Unit,
    onReveal: () -> Unit,
    onRestart: () -> Unit,
    onSkip: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // ── Track reveal area ─────────────────────────────────────────────────────
        if (state.isRevealed) {
            if (albumArt != null) {
                Image(
                    bitmap             = albumArt.asImageBitmap(),
                    contentDescription = "Album art",
                    modifier           = Modifier
                        .size(200.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
                Spacer(Modifier.height(16.dp))
            }
            Text(
                text  = state.track.name,
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text  = state.track.artist,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.secondary
            )
            if (state.track.year != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text  = "${state.track.year}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        } else {
            Text("?", style = MaterialTheme.typography.displayLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                text  = "${state.secondsRemaining}s",
                style = MaterialTheme.typography.displayMedium,
                color = if (state.secondsRemaining <= 3)
                    MaterialTheme.colorScheme.error
                else
                    MaterialTheme.colorScheme.primary
            )
            Text(
                text  = "to answer",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline
            )
        }

        Spacer(Modifier.height(40.dp))

        // ── Action buttons ────────────────────────────────────────────────────────
        if (!state.isRevealed) {
            Button(
                onClick  = onToggleCountdownPause,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isTimerPaused) "▶  Resume Countdown" else "⏸  Pause Countdown")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick  = onReveal,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Reveal Answer")
            }
        }

        if (state.isRevealed) {
            Button(
                onClick  = onTogglePlayback,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isPlaybackPaused) "▶  Resume" else "⏸  Pause")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick  = onRestart,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Start from Beginning")
            }
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick  = onSkip,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Next Round →")
            }
        }
    }
}
