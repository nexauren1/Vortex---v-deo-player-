package com.nexauren.vortex.playback

import androidx.media3.cast.CastPlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var localPlayer: ExoPlayer? = null

    override fun onCreate() {
        super.onCreate()

        val exoPlayer = ExoPlayer.Builder(this).build()
        localPlayer = exoPlayer

        // CastPlayer delegates to ExoPlayer locally and to a Cast receiver
        // when a Cast session is selected from the output switcher.
        val castPlayer = CastPlayer.Builder(this)
            .setLocalPlayer(exoPlayer)
            .build()

        mediaSession = MediaSession.Builder(this, castPlayer).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
        localPlayer?.release()
        localPlayer = null
        super.onDestroy()
    }
}
