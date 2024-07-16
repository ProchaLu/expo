package expo.modules.video.enums

enum class PlayerEvent(val value: String) {
  PLAYING_CHANGE("playingChange"),
  STATUS_CHANGE("statusChange"),
  VOLUME_CHANGE("volumeChange"),
  SOURCE_CHANGE("sourceChange"),
  PLAYBACK_RATE_CHANGE("playbackRateChange"),
  PLAY_TO_END("playToEnd")
}
