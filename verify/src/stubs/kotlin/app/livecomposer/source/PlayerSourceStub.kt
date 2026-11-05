package app.livecomposer.source

import android.content.Context
import android.view.Surface
import app.livecomposer.mix.AudioMixer

/**
 * 仅供 verify/ 编译检查使用的替身：真实实现依赖 Media3（AndroidX，云端拿不到），由 CI 的 Android 构建编译。
 * 公开 API 必须与 app/src/main/java/app/livecomposer/source/PlayerSource.kt 保持一致。
 */
@Suppress("UNUSED_PARAMETER")
class PlayerSource(
    context: Context,
    val id: String,
    uri: String,
    live: Boolean,
    loop: Boolean,
    mixer: AudioMixer,
    volume: Float,
    listener: Listener,
) {
    enum class State(val label: String) {
        IDLE("未开始"), CONNECTING("连接中"), BUFFERING("缓冲中"), PLAYING("播放中"),
        RECONNECTING("重连中"), ENDED("已播完"), ERROR("播放失败"),
    }

    interface Listener {
        fun onStateChanged(state: State, message: String?)
        fun onVideoSize(width: Int, height: Int)
    }

    var state = State.IDLE; private set
    var message: String? = null; private set
    fun attach(s: Surface) {}
    fun setVolume(v: Float) {}
    fun pause() {}
    fun setLoop(value: Boolean) {}
    fun setMonitor(v: Float) {}
    fun resume() {}
    fun release() {}
}
