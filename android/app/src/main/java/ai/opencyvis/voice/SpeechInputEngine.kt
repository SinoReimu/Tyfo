package ai.opencyvis.voice

interface SpeechInputEngine {
    interface Listener {
        fun onReady()
        fun onPartialResult(text: String)
        fun onFinalResult(text: String)
        fun onError(message: String)
        fun onEnd()

        /** 麦克风音量，0..1，约 10 次 / 秒；给聆听动画用，不关心的可以不实现。 */
        fun onAudioLevel(level: Float) {}
    }

    fun startListening(listener: Listener)
    fun stopListening()
    fun destroy()
}
