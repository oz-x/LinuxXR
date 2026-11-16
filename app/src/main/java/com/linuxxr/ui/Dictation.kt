package com.linuxxr.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.sqrt

/**
 * Voice dictation for the VR keyboard. Uses the system speech recognizer when Horizon OS offers
 * one to third-party apps; otherwise runs Whisper (whisper.cpp) fully on the headset. The Whisper
 * model is downloaded once (pinned SHA-256). Callbacks arrive on the main thread.
 */
class Dictation(
    private val context: Context,
    private val onStatus: (String) -> Unit,
    private val onText: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile var active = false
        private set

    private var recognizer: SpeechRecognizer? = null
    @Volatile private var recordThread: Thread? = null
    private val worker = Executors.newSingleThreadExecutor()
    private val language = Locale.getDefault().language.ifEmpty { "auto" }

    fun start() {
        if (active) return
        active = true
        if (SpeechRecognizer.isRecognitionAvailable(context)) startSystem() else startWhisper()
    }

    fun stop() {
        active = false
        recognizer?.destroy()
        recognizer = null
        recordThread?.interrupt()
        recordThread = null
        onStatus("Dictado detenido")
    }

    fun release() {
        stop()
        worker.shutdown()
    }

    // ---- system recognizer ------------------------------------------------------------------

    private fun startSystem() {
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onPartialResults(partial: Bundle) {
                    partial.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        ?.takeIf { it.isNotBlank() }?.let(onStatus)
                }

                override fun onResults(results: Bundle) {
                    results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        ?.takeIf { it.isNotBlank() }?.let { onText(it); onStatus("✓ $it") }
                    if (active) listenSystem()
                }

                override fun onError(error: Int) {
                    when {
                        !active -> Unit
                        error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> listenSystem()
                        else -> {
                            // The service exists but refuses us: fall back to on-device Whisper.
                            Log.w(TAG, "system recognizer error $error, using Whisper")
                            recognizer?.destroy()
                            recognizer = null
                            startWhisper()
                        }
                    }
                }

                override fun onReadyForSpeech(params: Bundle?) = onStatus("Escuchando…")
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
        listenSystem()
    }

    private fun listenSystem() {
        recognizer?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        })
    }

    // ---- Whisper (offline) ------------------------------------------------------------------

    private fun startWhisper() {
        val t = Thread({
            try {
                val ctx = loadModel()
                if (active) record(ctx)
            } catch (e: InterruptedException) {
                // stopped
            } catch (e: Exception) {
                Log.e(TAG, "dictation failed", e)
                main.post {
                    active = false
                    onStatus("Error de dictado: ${e.message}")
                }
            }
        }, "dictation")
        recordThread = t
        t.start()
    }

    private fun status(s: String) = main.post { if (active) onStatus(s) }

    private fun loadModel(): Long {
        synchronized(Companion) {
            if (whisperCtx != 0L) return whisperCtx
            val file = File(context.filesDir, "models/$MODEL_NAME")
            // Verified once; the stamp avoids re-hashing the model on every start.
            val stamp = File(file.path + ".sha256")
            if (!file.isFile || !stamp.isFile || stamp.readText() != MODEL_SHA256) {
                if (!file.isFile || sha256(file) != MODEL_SHA256) download(file)
                stamp.writeText(MODEL_SHA256)
            }
            File(context.filesDir, "models").listFiles()?.filter { !it.name.startsWith(MODEL_NAME) }?.forEach { it.delete() }
            status("Cargando modelo de voz…")
            val t0 = System.nanoTime()
            whisperCtx = nativeInit(file.path)
            Log.i(TAG, "model loaded in ${(System.nanoTime() - t0) / 1_000_000} ms")
            if (whisperCtx == 0L) throw IOException("no se pudo cargar el modelo Whisper")
            return whisperCtx
        }
    }

    private fun download(dest: File) {
        dest.parentFile?.mkdirs()
        val part = File(dest.path + ".part")
        val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        if (conn.responseCode != HttpURLConnection.HTTP_OK) throw IOException("descarga del modelo: HTTP ${conn.responseCode}")
        val total = conn.contentLengthLong
        val md = MessageDigest.getInstance("SHA-256")
        conn.inputStream.use { input ->
            part.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                var done = 0L
                var last = 0L
                while (true) {
                    if (Thread.interrupted()) throw InterruptedException()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    md.update(buf, 0, n)
                    done += n
                    if (done - last > (4 shl 20)) {
                        last = done
                        status("Descargando modelo de voz (una vez)… ${done shr 20}/${total shr 20} MiB")
                    }
                }
            }
        }
        val digest = md.digest().joinToString("") { "%02x".format(it) }
        if (digest != MODEL_SHA256) {
            part.delete()
            throw IOException("SHA-256 del modelo no coincide")
        }
        if (!part.renameTo(dest)) throw IOException("no se pudo guardar el modelo")
    }

    /**
     * Records 16 kHz mono audio and cuts it into utterances with a simple energy VAD
     * (adaptive noise floor, 0.8 s of silence ends an utterance); each one is transcribed on
     * [worker] while recording continues.
     */
    @SuppressLint("MissingPermission")
    private fun record(ctx: Long) {
        val rate = 16_000
        val frame = 480 // 30 ms
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT, maxOf(minBuf, frame * 4 * 8))
        if (rec.state != AudioRecord.STATE_INITIALIZED) throw IOException("no se pudo abrir el micrófono")
        rec.startRecording()
        status("Escuchando… (habla, se escribe al hacer una pausa)")
        val buf = FloatArray(frame)
        val preRoll = ArrayDeque<FloatArray>()
        val utterance = ArrayList<FloatArray>()
        var noise = 0.005f
        var speaking = false
        var silentFrames = 0
        try {
            while (active && !Thread.currentThread().isInterrupted) {
                var got = 0
                while (got < frame) {
                    val n = rec.read(buf, got, frame - got, AudioRecord.READ_BLOCKING)
                    if (n < 0) throw IOException("error del micrófono ($n)")
                    got += n
                }
                var sum = 0f
                for (x in buf) sum += x * x
                val rms = sqrt(sum / frame)
                val voiced = rms > maxOf(0.012f, noise * 3.5f)
                if (!voiced) noise = noise * 0.98f + rms * 0.02f
                val chunk = buf.copyOf()
                if (!speaking) {
                    preRoll.addLast(chunk)
                    if (preRoll.size > 10) preRoll.removeFirst()
                    if (voiced) {
                        speaking = true
                        silentFrames = 0
                        utterance.addAll(preRoll)
                        preRoll.clear()
                        status("● …")
                    }
                } else {
                    utterance += chunk
                    silentFrames = if (voiced) 0 else silentFrames + 1
                    if (silentFrames > 26 || utterance.size > 20_000 / 30) {
                        val pcm = FloatArray(utterance.size * frame)
                        utterance.forEachIndexed { i, a -> a.copyInto(pcm, i * frame) }
                        utterance.clear()
                        speaking = false
                        transcribe(ctx, pcm)
                    }
                }
            }
        } finally {
            rec.stop()
            rec.release()
        }
    }

    private fun transcribe(ctx: Long, pcm: FloatArray) {
        status("Transcribiendo…")
        worker.execute {
            val started = System.nanoTime()
            val text = clean(String(nativeTranscribe(ctx, pcm, language), Charsets.UTF_8))
            Log.i(TAG, "transcribed ${pcm.size / 16} ms of audio in ${(System.nanoTime() - started) / 1_000_000} ms")
            main.post {
                if (text.isNotEmpty()) {
                    onText(text)
                    onStatus("✓ $text")
                } else if (active) {
                    onStatus("Escuchando…")
                }
            }
        }
    }

    /** Debug: transcribes a 16 kHz mono 16-bit WAV and logs the time taken. */
    fun benchmark(wav: File) {
        worker.execute {
            try {
                val ctx = loadModel()
                val bytes = wav.readBytes()
                val pcm = FloatArray((bytes.size - 44) / 2) {
                    ((bytes[44 + 2 * it].toInt() and 0xff) or (bytes[45 + 2 * it].toInt() shl 8)).toShort() / 32768f
                }
                val t0 = System.nanoTime()
                val text = String(nativeTranscribe(ctx, pcm, language), Charsets.UTF_8)
                Log.i(TAG, "benchmark ${pcm.size / 16} ms audio -> ${(System.nanoTime() - t0) / 1_000_000} ms: $text")
            } catch (e: Exception) {
                Log.e(TAG, "benchmark failed", e)
            }
        }
    }

    /** Drops Whisper's non-speech annotations ("[Música]", "(risas)") and silence hallucinations. */
    private fun clean(raw: String): String {
        val t = raw.replace(Regex("\\[[^]]*]|\\([^)]*\\)|\\*[^*]*\\*"), "").replace(Regex("\\s+"), " ").trim()
        if (HALLUCINATIONS.any { t.contains(it, ignoreCase = true) }) return ""
        return t
    }

    companion object {
        private const val TAG = "LinuxXR-Dictation"
        // Multilingual Whisper "base", 5-bit quantised (~57 MB): ~4x faster than "small" on the
        // Quest 3 CPU, which matters more for dictation than the last bit of accuracy.
        private const val MODEL_NAME = "ggml-base-q5_1.bin"
        private const val MODEL_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base-q5_1.bin"
        private const val MODEL_SHA256 = "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898"
        private val HALLUCINATIONS = listOf("amara.org", "subtítulos realizados", "gracias por ver el video")

        @Volatile private var whisperCtx = 0L

        private fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        @JvmStatic private external fun nativeInit(model: String): Long
        @JvmStatic private external fun nativeTranscribe(ctx: Long, pcm: FloatArray, language: String): ByteArray
        @JvmStatic external fun nativeFree(ctx: Long)

        init {
            System.loadLibrary("linuxxr_whisper")
        }
    }
}
