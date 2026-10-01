package ai.opencyvis.assistant

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import ai.opencyvis.voice.DoubaoStreamingSpeechInputEngine
import ai.opencyvis.voice.SpeechInputEngine

/**
 * 数字助理必须带一个语音识别服务；选 OpenCyvis 当助理后，系统可能把默认识别器也切过来，
 * 所以这里用豆包流式语音识别（云端）给出真实可用的识别结果，而不是空壳。
 */
class AssistantRecognitionService : RecognitionService() {

    private var engine: DoubaoStreamingSpeechInputEngine? = null
    private var callback: Callback? = null
    private var lastPartial: String? = null

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback) {
        release()
        callback = listener
        lastPartial = null
        val e = DoubaoStreamingSpeechInputEngine(this).also { engine = it }
        e.startListening(object : SpeechInputEngine.Listener {
            override fun onReady() {
                runCatching {
                    listener.readyForSpeech(Bundle())
                    listener.beginningOfSpeech()
                }
            }

            override fun onPartialResult(text: String) {
                lastPartial = text
                runCatching { listener.partialResults(results(text)) }
            }

            override fun onFinalResult(text: String) = deliver(listener, text)

            override fun onError(message: String) {
                runCatching { listener.error(SpeechRecognizer.ERROR_CLIENT) }
                release()
            }

            override fun onEnd() = deliver(listener, null)

            override fun onAudioLevel(level: Float) {
                // RecognitionListener 的 rms 约定是 dB，粗略映射到 -2..10
                runCatching { listener.rmsChanged(-2f + 12f * level) }
            }
        })
    }

    override fun onStopListening(listener: Callback) {
        // 调用方主动结束：按目前听到的给结果
        engine?.stopListening()
        deliver(listener, lastPartial)
    }

    override fun onCancel(listener: Callback) = release()

    override fun onDestroy() {
        release()
        super.onDestroy()
    }

    private fun deliver(listener: Callback, text: String?) {
        if (callback !== listener) return
        runCatching {
            listener.endOfSpeech()
            if (text.isNullOrBlank()) listener.error(SpeechRecognizer.ERROR_NO_MATCH)
            else listener.results(results(text))
        }
        release()
    }

    private fun results(text: String) = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
    }

    private fun release() {
        callback = null
        engine?.destroy()
        engine = null
    }
}
