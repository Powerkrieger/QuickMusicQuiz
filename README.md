# QuickMusicQuiz

A Kotlin + Jetpack Compose Android app. Play a short clip from a Spotify playlist and guess the song.

---

## Setup (required before first build)

### 1. Spotify Developer Dashboard
Go to https://developer.spotify.com/dashboard and create an app (or open your existing one).

Under **Settings → Edit**, add:
- **Redirect URI:** `quickmusicquiz://callback`
- **Android Package Name:** `com.example.quickmusicquiz`
- **Android Package Fingerprint (SHA-1):** `3A:72:CE:FB:A5:96:66:48:DC:69:81:B6:96:B6:0C:4E:76:E7:99:00`
  *(This is the debug keystore fingerprint. Use your release keystore SHA-1 for production builds.)*

### 2. Fill in your Client ID
In `SpotifyAuthManager.kt`:
```kotlin
const val CLIENT_ID = "your_client_id_here"
```

### 3. Set your playlist ID
In `MainViewModel.kt`:
```kotlin
private val playlistId = "your_playlist_id_here"
```
Find the ID in the Spotify share URL: `https://open.spotify.com/playlist/THIS_PART`

### 4. Add the Spotify App Remote AAR
Download `spotify-app-remote-release-0.8.0.aar` from:
https://github.com/spotify/android-sdk/releases

Place it in `app/libs/`.

---

## How it works

1. **Connect** — log in with your Spotify Premium account via PKCE OAuth
2. **Configure** — choose clip duration (5 or 10 seconds) and start position (beginning or random)
3. **Play** — the app plays a random clip from the playlist via the Spotify app on your device
4. **Guess** — a countdown gives you time to identify the song
5. **Reveal** — the answer (song name, artist, release year) is shown and the song resumes

---

## Game flow & screens

```
NotConnected → [login] → Connected (config screen)
    → [Start Round] → LoadingTrack → Playing
        → [clip ends] → CountingDown → [0s] → Revealed (answer shown, song resumes)
```

### Playing screen
- Clip countdown timer
- ⏸ Pause / ▶ Resume — pauses both the timer and Spotify playback
- Skip to Answer → — immediately reveals the answer (no countdown)

### CountingDown screen
- Answer hidden, 10-second timer running
- ⏸ Pause Countdown / ▶ Resume Countdown — pauses the timer only
- Reveal Answer — shows the answer immediately and resumes playback
- *(At 0s, answer is auto-revealed and playback auto-resumes)*

### Answer screen (after reveal)
- Song name, artist, release year
- ⏸ Pause / ▶ Resume — controls Spotify playback
- Start from Beginning — restarts the track
- Next Round → — immediately plays a new random clip

---

## Architecture

Deliberately simple — no DI, no navigation framework, no layering.

| File | Purpose |
|------|---------|
| `MainActivity.kt` | Single activity; all Compose UI screens; App Remote lifecycle |
| `MainViewModel.kt` | `GameState` sealed class; round logic; timer; pause state |
| `SpotifyAuthManager.kt` | PKCE OAuth flow; token storage in SharedPreferences |
| `SpotifyPlaybackManager.kt` | App Remote SDK (playback + PlayerState); album year via Web API |

### Spotify integrations
- **App Remote SDK** — controls the Spotify app over IPC; provides track name/artist/duration via `PlayerState`. No Web API needed for playlist/track data.
- **PKCE Web API token** — used only to fetch the album release year from `/v1/albums/{id}`.

### State management
- `GameState` sealed class drives all UI (collected as `StateFlow` in Compose)
- `_isPaused` — gates the `tickSecond()` timer loop
- `_isPlaybackPaused` — drives the pause button label; updated by every action that touches Spotify playback

---

## Known issues / TODOs

- Playlist ID and Client ID are hardcoded — could be made configurable in settings
- SHA-1 fingerprint above is for debug builds only

## AI - Notice

- Code co-authored by Claude Code
- Initial Prompt for Claude co-authored by GPT-5.2
