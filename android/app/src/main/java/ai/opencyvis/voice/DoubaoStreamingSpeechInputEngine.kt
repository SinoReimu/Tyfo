package ai.opencyvis.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import ai.opencyvis.R
import ai.opencyvis.config.ConfigRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * 豆包流式语音识别（云端，大模型流式语音识别 2.0 的双向流式优化版），鉴权用 Agent 同一个火山方舟 key。
 *
 * 协议：WebSocket 二进制帧 = 4 字节头 + [4 字节序号] + 4 字节负载长度 + 负载，整数都是大端。
 * 连上后先发一个带参数的完整请求（JSON），再每 200ms 发一包 16kHz / 16bit / 单声道 PCM，
 * 结束时发一包带“最后一包”标志的音频。服务端边听边回当前整段文本（result.text）。
 *
 * 判停交给服务端：开了二遍识别后，静音超过 [END_WINDOW_MS] 就判停，用非流式模型把这一句重新识别一遍、
 * 标上 definite=true，拿到它就当说完了，这时的文本也是更准的二遍结果。
 * 麦克风一开就录，连接建好之前的音频先攒着，连上再补发，开口快也不会丢字。
 */
class DoubaoStreamingSpeechInputEngine(context: Context) : SpeechInputEngine {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var session: Session? = null

    @Volatile
    private var destroyed = false

    override fun startListening(listener: SpeechInputEngine.Listener) {
        if (destroyed || session?.active == true) return
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            listener.onError(appContext.getString(R.string.toast_microphone_permission_required))
            return
        }
        val key = ConfigRepository(appContext).apiKey.trim()
        if (key.isEmpty()) {
            listener.onError(appContext.getString(R.string.asr_error_no_key))
            return
        }
        val s = Session(listener)
        session = s
        val request = Request.Builder()
            .url(URL)
            .header("X-Api-Key", key)
            .header("X-Api-Resource-Id", RESOURCE_ID)
            .header("X-Api-Connect-Id", UUID.randomUUID().toString())
            .build()
        s.ws = client.newWebSocket(request, s)
        scope.launch { s.capture() }
    }

    /** 调用方主动结束（手动点停 / 收起）：直接断开，不再回调。 */
    override fun stopListening() {
        session?.cancel()
        session = null
    }

    override fun destroy() {
        destroyed = true
        stopListening()
        scope.cancel()
    }

    private inner class Session(private val listener: SpeechInputEngine.Listener) : WebSocketListener() {
        /** 还没出结果；结束（出结果 / 出错 / 被取消）后不再回调。 */
        @Volatile
        var active = true
        var ws: WebSocket? = null
        private var connected = false
        // 连上之前录到的音频
        private val pending = ArrayList<ByteArray>()
        @Volatile
        private var lastText = ""
        private val startedAt = SystemClock.elapsedRealtime()

        @SuppressLint("MissingPermission")
        fun capture() {
            var recorder: AudioRecord? = null
            try {
                recorder = createAudioRecord()
                recorder.startRecording()
                post { listener.onReady() }
                Log.i(TAG, "mic ready in ${SystemClock.elapsedRealtime() - startedAt}ms")
                val read = ShortArray(READ_SAMPLES)
                val packet = ByteBuffer.allocate(PACKET_SAMPLES * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                while (active) {
                    val n = recorder.read(read, 0, read.size)
                    if (n < 0) throw IOException("Audio recording error $n")
                    var energy = 0.0
                    for (i in 0 until n) {
                        val v = read[i]
                        energy += (v / 32768.0) * (v / 32768.0)
                        packet.putShort(v)
                        if (!packet.hasRemaining()) {
                            sendAudio(packet.array().copyOf(), last = false)
                            packet.clear()
                        }
                    }
                    if (n > 0) {
                        val level = audioLevel(energy / n)
                        post { listener.onAudioLevel(level) }
                    }
                    val elapsed = SystemClock.elapsedRealtime() - startedAt
                    when {
                        // 一直没听到内容：当没听清
                        lastText.isEmpty() && elapsed > NO_SPEECH_TIMEOUT_MS -> finish(null)
                        elapsed > MAX_DURATION_MS -> finish(lastText)
                    }
                }
            } catch (t: Throwable) {
                if (active) {
                    Log.e(TAG, "Audio capture failed", t)
                    fail(appContext.getString(R.string.asr_error_mic))
                }
            } finally {
                runCatching { recorder?.stop() }
                recorder?.release()
            }
        }

        private fun sendAudio(pcm: ByteArray, last: Boolean) {
            synchronized(this) {
                if (!connected) {
                    pending.add(pcm)
                    return
                }
                ws?.send(frame(MSG_AUDIO, if (last) FLAG_LAST else FLAG_NONE, SERIAL_NONE, pcm))
            }
        }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "connected in ${SystemClock.elapsedRealtime() - startedAt}ms logid=${response.header("X-Tt-Logid")}")
            val params = JSONObject()
                .put("user", JSONObject().put("uid", "opencyvis"))
                .put("audio", JSONObject().put("format", "pcm").put("codec", "raw")
                    .put("rate", SAMPLE_RATE).put("bits", 16).put("channel", 1))
                .put("request", JSONObject()
                    .put("model_name", "bigmodel")
                    .put("enable_itn", true)
                    .put("enable_punc", true)
                    .put("enable_nonstream", true)
                    .put("end_window_size", END_WINDOW_MS)
                    .put("show_utterances", true))
            synchronized(this) {
                webSocket.send(frame(MSG_FULL_REQUEST, FLAG_NONE, SERIAL_JSON, params.toString().toByteArray()))
                pending.forEach { webSocket.send(frame(MSG_AUDIO, FLAG_NONE, SERIAL_NONE, it)) }
                pending.clear()
                connected = true
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (!active) return
            val data = bytes.toByteArray()
            val headerSize = (data[0].toInt() and 0x0f) * 4
            val type = (data[1].toInt() and 0xff) shr 4
            val flags = data[1].toInt() and 0x0f
            val gzip = (data[2].toInt() and 0x0f) == 1
            val buf = ByteBuffer.wrap(data, headerSize, data.size - headerSize)
            if (type == MSG_ERROR) {
                val code = buf.int
                val msg = String(ByteArray(buf.int).also { buf.get(it) }.let { if (gzip) gunzip(it) else it })
                Log.w(TAG, "server error $code: $msg")
                fail(appContext.getString(R.string.asr_error_server, code))
                return
            }
            if (type != MSG_SERVER_RESPONSE) return
            if (flags and 0b0001 != 0) buf.int // 序号
            val payload = ByteArray(buf.int).also { buf.get(it) }.let { if (gzip) gunzip(it) else it }
            val result = runCatching { JSONObject(String(payload)).optJSONObject("result") }.getOrNull()
            val text = result?.optString("text").orEmpty().trim()
            if (text.isNotEmpty() && text != lastText) {
                lastText = text
                post { if (active) listener.onPartialResult(text) }
            }
            val utterances = result?.optJSONArray("utterances")
            val definite = utterances != null &&
                (0 until utterances.length()).any { utterances.optJSONObject(it)?.optBoolean("definite") == true }
            // 服务端判停（这一句二遍识别完了）或已经是最后一包的结果：说完了
            if (definite || flags == FLAG_LAST_RESULT) finish(lastText)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!active) return
            Log.w(TAG, "websocket failure http=${response?.code} logid=${response?.header("X-Tt-Logid")}: ${t.message}")
            val code = response?.code
            fail(
                when (code) {
                    401, 403 -> appContext.getString(R.string.asr_error_auth)
                    null -> appContext.getString(R.string.asr_error_network)
                    else -> appContext.getString(R.string.asr_error_server, code)
                }
            )
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            // 服务端先断开了：有字就按听到的算，没字当没听清
            if (active) finish(lastText)
        }

        /** 说完了：告诉服务端结束、断开，交出结果（空的就当没听清）。 */
        fun finish(text: String?) {
            if (!active) return
            active = false
            synchronized(this) {
                if (connected) ws?.send(frame(MSG_AUDIO, FLAG_LAST, SERIAL_NONE, ByteArray(0)))
            }
            ws?.close(1000, null)
            Log.i(TAG, "finished in ${SystemClock.elapsedRealtime() - startedAt}ms: ${if (text.isNullOrBlank()) "no speech" else "${text.length} chars"}")
            post {
                if (text.isNullOrBlank()) listener.onEnd() else listener.onFinalResult(text)
            }
        }

        fun fail(message: String) {
            if (!active) return
            active = false
            ws?.cancel()
            post { listener.onError(message) }
        }

        fun cancel() {
            active = false
            ws?.cancel()
        }
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(): AudioRecord {
        val minBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBytes <= 0) throw IOException("Audio recording is unavailable")
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBytes.coerceAtLeast(PACKET_SAMPLES * 2 * 2),
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw IOException("Audio recording initialization failed")
        }
        return recorder
    }

    /** 均方能量换成 0..1 的响度：-55 dBFS 以下算安静，-15 dBFS 以上算满格。 */
    private fun audioLevel(meanSquare: Double): Float {
        if (meanSquare <= 1e-10) return 0f
        val db = 10.0 * kotlin.math.log10(meanSquare)
        return ((db + 55.0) / 40.0).coerceIn(0.0, 1.0).toFloat()
    }

    private fun post(block: () -> Unit) {
        mainHandler.post(block)
    }

    private companion object {
        const val TAG = "DoubaoAsr"
        const val URL = "wss://openspeech.bytedance.com/api/v3/plan/sauc/bigmodel_async"
        /** 豆包流式语音识别模型 2.0（小时版）。 */
        const val RESOURCE_ID = "volc.seedasr.sauc.duration"
        const val SAMPLE_RATE = 16000
        /** 每次读 100ms（音量动画更跟手），攒满 200ms 发一包（服务端建议的包大小）。 */
        const val READ_SAMPLES = 1600
        const val PACKET_SAMPLES = 3200
        /** 静音多久算说完；比服务端默认的 800ms 稍长，句中停顿一下不会被截断。 */
        const val END_WINDOW_MS = 1000
        const val NO_SPEECH_TIMEOUT_MS = 7_000L
        const val MAX_DURATION_MS = 45_000L

        const val MSG_FULL_REQUEST = 0b0001
        const val MSG_AUDIO = 0b0010
        const val MSG_SERVER_RESPONSE = 0b1001
        const val MSG_ERROR = 0b1111
        const val FLAG_NONE = 0b0000
        const val FLAG_LAST = 0b0010
        const val FLAG_LAST_RESULT = 0b0011
        const val SERIAL_NONE = 0b0000
        const val SERIAL_JSON = 0b0001

        val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build()
        }

        /** 协议版本 1、头 4 字节、不压缩。 */
        fun frame(type: Int, flags: Int, serialization: Int, payload: ByteArray): ByteString {
            val buf = ByteBuffer.allocate(8 + payload.size)
            buf.put(0x11.toByte())
            buf.put(((type shl 4) or flags).toByte())
            buf.put((serialization shl 4).toByte())
            buf.put(0)
            buf.putInt(payload.size)
            buf.put(payload)
            return buf.array().toByteString()
        }

        fun gunzip(bytes: ByteArray): ByteArray = GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
    }
}
