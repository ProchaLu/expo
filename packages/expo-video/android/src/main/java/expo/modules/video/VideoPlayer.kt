package expo.modules.video

import android.content.ComponentName
import android.content.Context
import android.content.Context.BIND_AUTO_CREATE
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.SurfaceView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSessionService
import androidx.media3.ui.PlayerView
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.sharedobjects.SharedObject
import expo.modules.video.enums.PlayerEvent
import expo.modules.video.enums.PlayerStatus
import expo.modules.video.enums.PlayerStatus.*
import expo.modules.video.records.PlaybackError
import expo.modules.video.records.VideoSource
import expo.modules.video.records.VolumeEvent
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

// https://developer.android.com/guide/topics/media/media3/getting-started/migration-guide#improvements_in_media3
@UnstableApi
class VideoPlayer(val context: Context, appContext: AppContext, source: VideoSource?) : AutoCloseable, SharedObject(appContext) {
  // This improves the performance of playing DRM-protected content
  private var renderersFactory = DefaultRenderersFactory(context)
    .forceEnableMediaCodecAsynchronousQueueing()
  val player = ExoPlayer
    .Builder(context, renderersFactory)
    .setLooper(context.mainLooper)
    .build()

  var listeners: MutableList<WeakReference<VideoPlayerListener>> = mutableListOf()

  // We duplicate some properties of the player, because we don't want to always use the mainQueue to read them.
  var playing = false
    set(value) {
      val oldValue = field
      field = value
      if (oldValue != value) {
        sendEvent(PlayerEvent.PLAYING_CHANGE, value, oldValue)
      }
    }

  var uncommittedSource: VideoSource? = source
  private var lastLoadedSource: VideoSource? = null
    set(value) {
      val oldValue = field
      field = value
      if (oldValue != value) {
        sendEvent(PlayerEvent.SOURCE_CHANGE, value, oldValue)
      }
    }

  // Volume of the player if there was no mute applied.
  var userVolume = 1f
  var status: PlayerStatus = IDLE
  var requiresLinearPlayback = false
  var staysActiveInBackground = false
  var preservesPitch = false
    set(preservesPitch) {
      playbackParameters = applyPitchCorrection(playbackParameters)
      field = preservesPitch
    }
  var showNowPlayingNotification = true
    set(value) {
      field = value
      playbackServiceBinder?.service?.setShowNotification(value, this.player)
    }
  var duration = 0f
  var isLive = false

  private var serviceConnection: ServiceConnection
  internal var playbackServiceBinder: PlaybackServiceBinder? = null
  lateinit var timeline: Timeline

  var volume = 1f
    set(volume) {
      if (player.volume == volume) return
      player.volume = if (muted) 0f else volume
      val oldValue = field
      field = volume
      sendEvent(PlayerEvent.VOLUME_CHANGE, VolumeEvent(volume, muted), VolumeEvent(oldValue, muted))
    }

  var muted = false
    set(muted) {
      if (field == muted) return
      player.volume = if (muted) 0f else userVolume
      val oldValue = field
      field = muted
      sendEvent(PlayerEvent.VOLUME_CHANGE, VolumeEvent(volume, muted), VolumeEvent(volume, oldValue))
    }

  var playbackParameters: PlaybackParameters = PlaybackParameters.DEFAULT
    set(newPlaybackParameters) {
      val oldPlaybackParameters = field
      val pitchCorrectedPlaybackParameters = applyPitchCorrection(newPlaybackParameters)
      field = pitchCorrectedPlaybackParameters

      if (player.playbackParameters != pitchCorrectedPlaybackParameters) {
        player.playbackParameters = pitchCorrectedPlaybackParameters
      }

      if (oldPlaybackParameters.speed != newPlaybackParameters.speed) {
        sendEvent(PlayerEvent.PLAYBACK_RATE_CHANGE, newPlaybackParameters.speed, oldPlaybackParameters.speed)
      }
    }

  private val playerListener = object : Player.Listener {
    override fun onIsPlayingChanged(isPlaying: Boolean) {
      this@VideoPlayer.playing = isPlaying
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
      this@VideoPlayer.timeline = timeline
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
      this@VideoPlayer.duration = 0f
      this@VideoPlayer.isLive = false
      if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
        sendEvent(PlayerEvent.PLAY_TO_END)
      }
      super.onMediaItemTransition(mediaItem, reason)
    }

    override fun onPlaybackStateChanged(@Player.State playbackState: Int) {
      if (playbackState == Player.STATE_IDLE && player.playerError != null) {
        return
      }
      if (playbackState == Player.STATE_READY) {
        this@VideoPlayer.duration = this@VideoPlayer.player.duration / 1000f
        this@VideoPlayer.isLive = this@VideoPlayer.player.isCurrentMediaItemLive
      }
      setStatus(playerStateToPlayerStatus(playbackState), null)
      super.onPlaybackStateChanged(playbackState)
    }

    override fun onVolumeChanged(volume: Float) {
      this@VideoPlayer.volume = volume
    }

    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
      this@VideoPlayer.playbackParameters = playbackParameters
      super.onPlaybackParametersChanged(playbackParameters)
    }

    override fun onPlayerErrorChanged(error: PlaybackException?) {
      error?.let {
        this@VideoPlayer.duration = 0f
        this@VideoPlayer.isLive = false
        setStatus(ERROR, error)
      } ?: run {
        setStatus(playerStateToPlayerStatus(player.playbackState), null)
      }

      super.onPlayerErrorChanged(error)
    }
  }

  init {
    serviceConnection = object : ServiceConnection {
      override fun onServiceConnected(componentName: ComponentName, binder: IBinder) {
        playbackServiceBinder = binder as? PlaybackServiceBinder
        playbackServiceBinder?.service?.registerPlayer(player) ?: run {
          Log.w(
            "ExpoVideo",
            "Expo Video could not bind to the playback service. " +
              "This will cause issues with playback notifications and sustaining background playback."
          )
        }
      }

      override fun onServiceDisconnected(componentName: ComponentName) {
        playbackServiceBinder = null
      }

      override fun onNullBinding(componentName: ComponentName) {
        Log.w(
          "ExpoVideo",
          "Expo Video could not bind to the playback service. " +
            "This will cause issues with playback notifications and sustaining background playback."
        )
      }
    }

    appContext.reactContext?.apply {
      val intent = Intent(context, ExpoVideoPlaybackService::class.java)
      intent.action = MediaSessionService.SERVICE_INTERFACE

      startService(intent)

      val flags = if (Build.VERSION.SDK_INT >= 29) {
        BIND_AUTO_CREATE or Context.BIND_INCLUDE_CAPABILITIES
      } else {
        BIND_AUTO_CREATE
      }

      bindService(intent, serviceConnection, flags)
    }
    player.addListener(playerListener)
    VideoManager.registerVideoPlayer(this)
  }

  override fun close() {
    appContext?.reactContext?.unbindService(serviceConnection)
    playbackServiceBinder?.service?.unregisterPlayer(player)
    VideoManager.unregisterVideoPlayer(this@VideoPlayer)

    appContext?.mainQueue?.launch {
      player.removeListener(playerListener)
      player.release()
    }
    uncommittedSource = null
    lastLoadedSource = null
  }

  override fun deallocate() {
    super.deallocate()
    close()
  }

  fun changePlayerView(playerView: PlayerView) {
    player.clearVideoSurface()
    player.setVideoSurfaceView(playerView.videoSurfaceView as SurfaceView?)
    playerView.player = player
  }

  fun prepare() {
    uncommittedSource?.let { videoSource ->
      val mediaSource = videoSource.toMediaSource(context)
      player.setMediaSource(mediaSource)
      player.prepare()
      lastLoadedSource = videoSource
      uncommittedSource = null
    } ?: run {
      player.clearMediaItems()
      player.prepare()
    }
  }

  private fun applyPitchCorrection(playbackParameters: PlaybackParameters): PlaybackParameters {
    val speed = playbackParameters.speed
    val pitch = if (preservesPitch) 1f else speed
    return PlaybackParameters(speed, pitch)
  }

  private fun playerStateToPlayerStatus(@Player.State state: Int): PlayerStatus {
    return when (state) {
      Player.STATE_IDLE -> IDLE
      Player.STATE_BUFFERING -> LOADING
      Player.STATE_READY -> READY_TO_PLAY
      Player.STATE_ENDED -> {
        // When an error occurs, the player state changes to ENDED.
        if (player.playerError != null) {
          ERROR
        } else {
          IDLE
        }
      }

      else -> IDLE
    }
  }

  private fun setStatus(status: PlayerStatus, error: PlaybackException?) {
    val oldStatus = this.status
    this.status = status

    val playbackError = error?.let {
      PlaybackError(it)
    }

    if (playbackError == null && player.playbackState == Player.STATE_ENDED) {
      sendEvent(PlayerEvent.PLAY_TO_END)
    }

    if (this.status != status) {
      sendEvent(PlayerEvent.STATUS_CHANGE, status.value, oldStatus.value, playbackError)
    }
  }

  fun addListener(videoPlayerListener: VideoPlayerListener) {
    listeners.find { it.get() == videoPlayerListener } ?: run {
      listeners.add(WeakReference(videoPlayerListener))
    }
  }

  fun removeListener(videoPlayerListener: VideoPlayerListener) {
    listeners.removeAll { it.get() == videoPlayerListener }
  }

  private fun sendEvent(event: PlayerEvent, vararg args: Any?) {
    // Send the event to the JS
    emit(event.value, *args)

    // Send the event to native listeners
    when (event) {
      PlayerEvent.PLAYING_CHANGE -> {
        val isPlaying = args[0] as? Boolean
        val oldIsPlaying = args[1] as? Boolean
        isPlaying?.let {
          listeners.forEach {
            it.get()?.onIsPlayingChanged(this, isPlaying, oldIsPlaying)
          }
        }
      }
      PlayerEvent.STATUS_CHANGE -> {
        val status = args[0] as? PlayerStatus
        val oldStatus = args[1] as? PlayerStatus
        val error = args[2] as? PlaybackError
        status?.let {
          listeners.forEach {
            it.get()?.onStatusChanged(this, status, oldStatus, error)
          }
        }
      }
      PlayerEvent.VOLUME_CHANGE -> {
        val volumeEvent = args[0] as? VolumeEvent
        val oldVolumeEvent = args[0] as? VolumeEvent

        volumeEvent?.volume?.let { volume ->
          listeners.forEach {
            it.get()?.onVolumeChanged(this, volume, oldVolumeEvent?.volume)
          }
        }

        volumeEvent?.isMuted?.let { isMuted ->
          listeners.forEach {
            it.get()?.onIsMutedChanged(this, isMuted, oldVolumeEvent?.isMuted)
          }
        }
      }
      PlayerEvent.SOURCE_CHANGE -> {
        val source = args[0] as? VideoSource
        val oldSource = args[1] as? VideoSource
        listeners.forEach {
          it.get()?.onSourceChanged(this, source, oldSource)
        }
      }
      PlayerEvent.PLAYBACK_RATE_CHANGE -> {
        val playbackRate = args[0] as? Float
        val oldPlaybackRate = args[1] as? Float
        playbackRate?.let {
          listeners.forEach {
            it.get()?.onPlaybackRateChanged(this, playbackRate, oldPlaybackRate)
          }
        }
      }
      PlayerEvent.PLAY_TO_END -> {
        listeners.forEach {
          it.get()?.onPlayedToEnd(this)
        }
      }
    }
  }
}
