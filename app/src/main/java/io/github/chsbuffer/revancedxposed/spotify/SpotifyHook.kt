package io.github.chsbuffer.revancedxposed.spotify

import android.app.Application
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import io.github.chsbuffer.revancedxposed.BaseHook
import io.github.chsbuffer.revancedxposed.injectHostClassLoaderToSelf
import io.github.chsbuffer.revancedxposed.spotify.misc.UnlockPremium
import io.github.chsbuffer.revancedxposed.spotify.misc.privacy.SanitizeSharingLinks
import io.github.chsbuffer.revancedxposed.spotify.misc.widgets.FixThirdPartyLaunchersWidgets
import app.revanced.extension.shared.Logger

class SpotifyHook(app: Application, lpparam: LoadPackageParam) : BaseHook(app, lpparam) {
    override val hooks = arrayOf(
        ::Extension,
        ::SanitizeSharingLinks,
        ::UnlockPremium,
        ::FixThirdPartyLaunchersWidgets,
        ::AdSkipper
    )

    // Mute state
    private var originalVolume = -1
    private var isMuted = false
    private var isMutedByTimer = false  // true = muted by end-of-track backup timer

    // Backup timer (Mutify-style): fires at the end of every real track
    private val handler = Handler(Looper.getMainLooper())
    private var timerMuteRunnable: Runnable? = null
    private var lastDuration = -1L  // duration of the currently playing non-ad track

    /**
     * Returns true if the given metadata is a confirmed Spotify ad URI.
     * Only the unambiguous `:ad:` URI scheme is checked here — no heuristics.
     * Everything else is handled by the "confirmed real music" gate at unmute time.
     */
    private fun isDefiniteAd(metadata: MediaMetadata): Boolean {
        val mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID) ?: return false
        return mediaId.contains(":ad:")
    }

    /**
     * Returns true only when metadata positively identifies real music content.
     *
     * Every legitimate Spotify track (song, podcast episode, audiobook chapter)
     * always has a non-blank ARTIST field populated by Spotify internally.
     * DAI (Dynamic Ad Insertion) ads and third-party programmatic audio ads
     * injected server-side (common in DE, IT, FR, etc.) do NOT populate ARTIST —
     * so they fall into neither category and the mute holds until a real track arrives.
     *
     * This mirrors exactly how Mutify works: Mutify stays muted between tracks because
     * Spotify suppresses broadcasts for ads. Here, we stay muted for ambiguous metadata
     * for the same reason — we only unmute on confirmed real content.
     */
    private fun isConfirmedRealMusic(metadata: MediaMetadata): Boolean {
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
        return !artist.isNullOrBlank()
    }

    private fun mute(am: AudioManager, cause: String) {
        val vol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (!isMuted && vol > 0) {
            Logger.printDebug { "AdSkipper: Muting — $cause" }
            originalVolume = vol
            isMuted = true
            am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        }
    }

    private fun unmute(am: AudioManager) {
        if (isMuted && originalVolume != -1) {
            Logger.printDebug { "AdSkipper: Unmuting — restoring volume $originalVolume" }
            am.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
            isMuted = false
            isMutedByTimer = false
            originalVolume = -1
        }
    }

    private fun cancelTimerMute() {
        timerMuteRunnable?.let { handler.removeCallbacks(it) }
        timerMuteRunnable = null
    }

    /**
     * Arms a backup mute that fires when the current track ends.
     * Mirrors the Mutify approach: any ad that slips past the metadata checks
     * will still be muted because the device is silenced before it starts playing.
     * Cancelled and rescheduled on every setPlaybackState (pause, seek, resume).
     */
    private fun scheduleTimerMute(am: AudioManager, durationMs: Long, positionMs: Long) {
        cancelTimerMute()
        val delay = durationMs - positionMs
        // Sanity bounds: skip if already past the end, or duration is unrealistically long (>30 min)
        if (delay <= 0L || delay > 30 * 60 * 1000L) return
        val r = Runnable {
            timerMuteRunnable = null
            isMutedByTimer = true
            mute(am, "end-of-track timer backup fired")
        }
        timerMuteRunnable = r
        handler.postDelayed(r, delay)
        Logger.printDebug { "AdSkipper: Timer backup armed in ${delay}ms" }
    }

    /**
     * AdSkipper — three-tier ad blocking:
     *
     * Tier 1 — setMetadata, definite ad (":ad:" URI):
     *   Immediately mutes. Catches standard ads in most countries.
     *
     * Tier 2 — setMetadata, confirmed real music (has artist):
     *   Unmutes and arms the end-of-track backup timer.
     *   Ambiguous metadata (no ":ad:" but also no artist) does NOTHING —
     *   the mute holds. This is the Mutify principle: stay muted until
     *   you can positively confirm real content.
     *
     * Tier 3 — end-of-track backup timer (Mutify-style):
     *   Fires at the end of every confirmed real track. Any ad — regardless
     *   of its URI format or metadata — is caught because the device is
     *   already muted before it starts playing. setPlaybackState keeps the
     *   timer accurate through pauses and seeks.
     */
    fun AdSkipper() {
        val am = app.getSystemService(AudioManager::class.java)!!

        // --- Hook 1: setMetadata — primary detection + timer arming ---
        runCatching {
            XposedHelpers.findAndHookMethod(
                MediaSession::class.java.name,
                classLoader,
                "setMetadata",
                MediaMetadata::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val metadata = param.args[0] as? MediaMetadata ?: return

                            when {
                                isDefiniteAd(metadata) -> {
                                    // Tier 1: definite ad URI — mute immediately
                                    cancelTimerMute()
                                    val mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
                                    mute(am, "definite ad URI (ID=$mediaId)")
                                }
                                isConfirmedRealMusic(metadata) -> {
                                    // Tier 2: confirmed real music — unmute and arm the backup timer
                                    cancelTimerMute()
                                    unmute(am)
                                    val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
                                    if (duration > 0) {
                                        lastDuration = duration
                                        scheduleTimerMute(am, duration, 0L)
                                    }
                                }
                                else -> {
                                    // Ambiguous metadata (no ":ad:", no artist):
                                    // could be a DAI/regional ad — do NOT unmute.
                                    // The backup timer mute from the previous track holds.
                                    Logger.printDebug { "AdSkipper: Ambiguous metadata — staying muted" }
                                }
                            }
                        } catch (e: Throwable) {
                            Logger.printDebug { "AdSkipper setMetadata error: ${e.message}" }
                        }
                    }
                }
            )
        }.onFailure { Logger.printDebug { "AdSkipper setMetadata hook failed: ${it.message}" } }

        // --- Hook 2: setPlaybackState — keep timer accurate on pause / seek / resume ---
        runCatching {
            XposedHelpers.findAndHookMethod(
                MediaSession::class.java.name,
                classLoader,
                "setPlaybackState",
                PlaybackState::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val state = param.args[0] as? PlaybackState ?: return
                            val dur = lastDuration
                            if (dur <= 0) return
                            when (state.state) {
                                PlaybackState.STATE_PLAYING -> {
                                    // Reschedule with real current position (handles resume after pause + seeks)
                                    scheduleTimerMute(am, dur, state.position)
                                }
                                PlaybackState.STATE_PAUSED,
                                PlaybackState.STATE_STOPPED -> {
                                    // Suspend timer while paused — rescheduled on next STATE_PLAYING
                                    cancelTimerMute()
                                }
                            }
                        } catch (e: Throwable) {
                            Logger.printDebug { "AdSkipper setPlaybackState error: ${e.message}" }
                        }
                    }
                }
            )
        }.onFailure { Logger.printDebug { "AdSkipper setPlaybackState hook failed: ${it.message}" } }
    }

    fun Extension() {
        // load stubbed spotify classes
        injectHostClassLoaderToSelf(this::class.java.classLoader!!, classLoader)
    }
}
