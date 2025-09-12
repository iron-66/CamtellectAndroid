// File: app/src/main/java/com/example/camtellect/oww/OpenWakeWordEngine.kt
package com.example.camtellect.oww

import ai.onnxruntime.*
import android.content.Context
import android.media.*
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import kotlin.math.max


class OpenWakeWordEngine(
    private val context: Context,
    private val wakeModelAsset: String = "oww/what_is_this_.onnx",
    private val threshold: Float = 0.015f,
    private val smoothWindow: Int = 3,
    private val onWake: () -> Unit
) {

    // ====== Константы аудио/мел/эмбеддер ======
    private val TAG = "OWW"
    private val sampleRate = 16_000
    private val CHUNK_MS = 80
    private val CHUNK_SAMPLES = sampleRate * CHUNK_MS / 1000

    private val MEL_HOP = 160
    private val MEL_WIN = 512
    private val MEL_OVERLAP = MEL_WIN - MEL_HOP
    private val MEL_BANDS = 32
    private val MEL_FRAMES_FOR_EMBED = 76
    private val EMB_DIM = 96
    private var SEQ_LEN = 16

    // ====== Служебные поля ======
    private var job: Job? = null
    private var audioRecord: AudioRecord? = null

    // ONNX
    private lateinit var env: OrtEnvironment
    private lateinit var melSession: OrtSession
    private lateinit var embedSession: OrtSession
    private lateinit var kwSession: OrtSession

    // Перекрытие по PCM для корректного подсчёта мел-кадров на границах чанков
    private val pcmOverlap = FloatArray(MEL_OVERLAP)
    private var pcmOverlapFilled = 0

    // Очередь PCM для накопления ровно по 1280 семплов
    private val pcmQueue = ArrayDeque<Float>(CHUNK_SAMPLES * 2)

    // Буфер мел-кадров (храним последние ~ 160 кадров, с запасом)
    private val MEL_RING_CAP_FRAMES = 192
    private val melRing = FloatArray(MEL_RING_CAP_FRAMES * MEL_BANDS)
    private var melWriteIdx = 0 // в кадрах
    private var melCount = 0    // сколько кадров реально накоплено

    // Буфер эмбеддингов
    private var embRing = FloatArray(SEQ_LEN * EMB_DIM)
    private var embWriteIdx = 0 // в эмбеддингах
    private var embCount = 0

    // Сглаживание скоров
    private val lastScores = ArrayDeque<Float>(smoothWindow)
    private var lastFireAt = 0L
    private val cooldownMs = 600L

    // ====== Публичные методы ======
    fun start(): Boolean {
        if (job != null) return true
        if (!hasRecordPermission()) {
            Log.w(TAG, "RECORD_AUDIO not granted; start() skipped")
            return false
        }

        try {
            // 1) Готовим ONNX
            env = OrtEnvironment.getEnvironment()
            melSession = env.createSession(extractAsset("oww/melspectrogram.onnx"), OrtSession.SessionOptions())
            embedSession = env.createSession(extractAsset("oww/embedding_model.onnx"), OrtSession.SessionOptions())
            kwSession = env.createSession(extractAsset(wakeModelAsset), OrtSession.SessionOptions())

            logIo("mel", melSession)
            logIo("embed", embedSession)
            logIo("kw", kwSession)

            // Подтягиваем SEQ_LEN из формы входа классификатора: ожидаем [1, S, 96]
            SEQ_LEN = (kwSession.inputInfo[kwSession.inputNames.first()]?.info as? TensorInfo)
                ?.shape?.let { shp ->
                    if (shp.size >= 3 && (shp[2].toInt() == EMB_DIM || shp[2] < 0) && shp[1] > 0) shp[1].toInt() else 16
                } ?: 16
            embRing = FloatArray(SEQ_LEN * EMB_DIM)

            // 2) Настраиваем AudioRecord
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val wantBuf = max(minBuf, CHUNK_SAMPLES * 4)

            audioRecord = buildAudioRecord(wantBuf)
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord not initialized (state=${audioRecord?.state})")
                audioRecord?.release(); audioRecord = null
                return false
            }
            audioRecord?.startRecording()

            // 3) Запускаем корутину обработки
            job = CoroutineScope(Dispatchers.Default + SupervisorJob()).launch {
                val shortBuf = ShortArray(CHUNK_SAMPLES) // читаем блоками; точный размер не критичен
                while (isActive) {
                    val read = audioRecord?.read(shortBuf, 0, shortBuf.size, AudioRecord.READ_BLOCKING) ?: 0
                    if (read <= 0) continue

                    // short -> float [-1..1]
                    for (i in 0 until read) {
                        pcmQueue.addLast((shortBuf[i] / 32768.0f).coerceIn(-1f, 1f))
                    }

                    // Обрабатываем ровно по 1280 семплов
                    while (pcmQueue.size >= CHUNK_SAMPLES) {
                        // Сформировать вход для mel: [overlap(352)] + [новые 1280] => 1632 семпла
                        val combinedCount = MEL_OVERLAP + CHUNK_SAMPLES
                        val melInput = FloatArray(combinedCount)
                        // 1) Перекрытие (с прошлого шага) — до первого раза нули
                        if (pcmOverlapFilled == MEL_OVERLAP) {
                            System.arraycopy(pcmOverlap, 0, melInput, 0, MEL_OVERLAP)
                        } else {
                            // до прогрева перекрытия оставляем нули
                        }
                        // 2) Новые 1280 семплов
                        for (i in 0 until CHUNK_SAMPLES) {
                            melInput[MEL_OVERLAP + i] = pcmQueue.removeFirst()
                        }

                        // Обновить перекрытие (последние 352 семпла)
                        System.arraycopy(
                            melInput, combinedCount - MEL_OVERLAP,
                            pcmOverlap, 0, MEL_OVERLAP
                        )
                        pcmOverlapFilled = MEL_OVERLAP

                        // ---- MEL → 8 кадров по 32 полосы ----
                        val melOut = runMel(melInput) // плоский массив длиной (frames * 32)
                        val frames = melOut.size / MEL_BANDS
                        if (frames <= 0) continue

                        // Нормализация (x/10)+2, как требует OWW перед эмбеддером
                        for (i in melOut.indices) melOut[i] = (melOut[i] / 10.0f) + 2.0f

                        // Положить мел-кадры в кольцевой буфер
                        pushMelFrames(melOut, frames)

                        // Как только есть 76 кадров — считаем один эмбеддинг по "последним 76"
                        if (melCount >= MEL_FRAMES_FOR_EMBED) {
                            val melWindow = lastMelWindow(MEL_FRAMES_FOR_EMBED) // size = 76*32
                            val emb = runEmbed(melWindow) // size = 96
                            if (emb.size == EMB_DIM) {
                                pushEmbedding(emb)
                                // Когда накопили SEQ_LEN эмбеддингов — запускаем классификатор
                                if (embCount >= SEQ_LEN) {
                                    val seq = lastEmbWindow(SEQ_LEN) // size = SEQ_LEN*96
                                    val score = runKeyword(seq).coerceIn(0f, 1f)
                                    smoothAndMaybeFire(score)
                                }
                            }
                        }
                    } // while(pcmQueue.size >= 1280)
                } // while(isActive)
            }

            Log.i(TAG, "OpenWakeWordEngine started (SEQ_LEN=$SEQ_LEN)")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "start() error: ${e.message}", e)
            stop()
            return false
        }
    }

    fun stop() {
        job?.cancel()
        job = null

        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null

        try { kwSession.close() } catch (_: Exception) {}
        try { embedSession.close() } catch (_: Exception) {}
        try { melSession.close() } catch (_: Exception) {}
    }

    // ====== Внутренности ======

    private fun hasRecordPermission(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun buildAudioRecord(bufBytes: Int): AudioRecord? {
        // Сначала VOICE_RECOGNITION (AEC/NS на некоторых девайсах), затем фоллбек на MIC
        fun build(src: Int): AudioRecord = AudioRecord.Builder()
            .setAudioSource(src)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufBytes)
            .build()

        return try {
            build(MediaRecorder.AudioSource.VOICE_RECOGNITION).also {
                if (it.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("VR not init")
            }
        } catch (_: Exception) {
            try {
                val ar = build(MediaRecorder.AudioSource.MIC)
                if (ar.state != AudioRecord.STATE_INITIALIZED) null else ar
            } catch (e: Exception) {
                Log.e(TAG, "AudioRecord MIC failed: ${e.message}")
                null
            }
        }
    }

    private fun runMel(audio: FloatArray): FloatArray {
        // Вход: [1, N] (N = 1632)
        val inName = melSession.inputNames.first()
        val inShape = longArrayOf(1, audio.size.toLong())
        val inTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(audio), inShape)
        val out = melSession.run(mapOf(inName to inTensor))
        // Выход обычно [1,1,T,32] или [1,T,32,1] → flatten даст T*32 элементов
        val flat = flattenOnnx(out[0].value)
        out.close(); inTensor.close()
        return flat
    }

    private fun runEmbed(melWindow: FloatArray): FloatArray {
        // Вход: [1, 76, 32, 1]
        val inName = embedSession.inputNames.first()
        val shape = longArrayOf(1, MEL_FRAMES_FOR_EMBED.toLong(), MEL_BANDS.toLong(), 1)
        val t = OnnxTensor.createTensor(env, FloatBuffer.wrap(melWindow), shape)
        val out = embedSession.run(mapOf(inName to t))
        val flat = flattenOnnx(out[0].value) // обычно [1,1,1,96] → 96
        out.close(); t.close()
        return flat
    }

    private fun runKeyword(seq: FloatArray): Float {
        // Вход: [1, SEQ_LEN, 96]
        val inName = kwSession.inputNames.first()
        val shape = longArrayOf(1, SEQ_LEN.toLong(), EMB_DIM.toLong())
        val t = OnnxTensor.createTensor(env, FloatBuffer.wrap(seq), shape)
        val out = kwSession.run(mapOf(inName to t))
        val flat = flattenOnnx(out[0].value) // ожидаем 1 значение
        out.close(); t.close()
        return if (flat.isNotEmpty()) flat[0] else 0f
    }

    private fun pushMelFrames(mel: FloatArray, frames: Int) {
        // mel: [frames * 32], построчно (временные кадры подряд)
        for (f in 0 until frames) {
            val srcOff = f * MEL_BANDS
            val dstFrame = melWriteIdx % MEL_RING_CAP_FRAMES
            val dstOff = dstFrame * MEL_BANDS
            System.arraycopy(mel, srcOff, melRing, dstOff, MEL_BANDS)
            melWriteIdx = (melWriteIdx + 1) % MEL_RING_CAP_FRAMES
            if (melCount < MEL_RING_CAP_FRAMES) melCount++
        }
    }

    private fun lastMelWindow(nFrames: Int): FloatArray {
        val out = FloatArray(nFrames * MEL_BANDS)
        val startFrame = ((melWriteIdx - nFrames) + MEL_RING_CAP_FRAMES) % MEL_RING_CAP_FRAMES
        val tail = MEL_RING_CAP_FRAMES - startFrame
        return if (startFrame + nFrames <= MEL_RING_CAP_FRAMES) {
            // непрерывно
            System.arraycopy(melRing, startFrame * MEL_BANDS, out, 0, nFrames * MEL_BANDS)
            out
        } else {
            // разрыв
            val firstPart = tail * MEL_BANDS
            System.arraycopy(melRing, startFrame * MEL_BANDS, out, 0, firstPart)
            val remain = (nFrames - tail) * MEL_BANDS
            System.arraycopy(melRing, 0, out, firstPart, remain)
            out
        }
    }

    private fun pushEmbedding(emb: FloatArray) {
        val dst = (embWriteIdx % SEQ_LEN) * EMB_DIM
        System.arraycopy(emb, 0, embRing, dst, EMB_DIM)
        embWriteIdx = (embWriteIdx + 1) % SEQ_LEN
        if (embCount < SEQ_LEN) embCount++
    }

    private fun lastEmbWindow(n: Int): FloatArray {
        val out = FloatArray(n * EMB_DIM)
        val start = ((embWriteIdx - n) + SEQ_LEN) % SEQ_LEN
        val tail = SEQ_LEN - start
        return if (start + n <= SEQ_LEN) {
            System.arraycopy(embRing, start * EMB_DIM, out, 0, n * EMB_DIM)
            out
        } else {
            val firstPart = tail * EMB_DIM
            System.arraycopy(embRing, start * EMB_DIM, out, 0, firstPart)
            val remain = (n - tail) * EMB_DIM
            System.arraycopy(embRing, 0, out, firstPart, remain)
            out
        }
    }

    private fun smoothAndMaybeFire(score: Float) {
        if (lastScores.size == smoothWindow) lastScores.removeFirst()
        lastScores.addLast(score)
        val avg = lastScores.average().toFloat()

        Log.d(TAG, "score=${"%.3f".format(score)} avg=${"%.3f".format(avg)} thr=$threshold")

        val now = System.currentTimeMillis()
        if (avg >= threshold && now - lastFireAt > cooldownMs) {
            lastScores.clear()
            lastFireAt = now
            CoroutineScope(Dispatchers.Main).launch { onWake() }
        }
    }

    // === Утилиты ===

    private fun extractAsset(name: String): String {
        val out = File(context.filesDir, name)
        out.parentFile?.mkdirs()
        if (!out.exists()) {
            context.assets.open(name).use { input ->
                FileOutputStream(out).use { output -> input.copyTo(output) }
            }
        }
        return out.absolutePath
    }

    private fun logIo(tag: String, s: OrtSession) {
        val ins = s.inputNames.joinToString { n ->
            val ti = s.inputInfo[n]?.info as? TensorInfo
            "$n:${ti?.type} ${ti?.shape?.contentToString()}"
        }
        val outs = s.outputNames.joinToString { n ->
            val ti = s.outputInfo[n]?.info as? TensorInfo
            "$n:${ti?.type} ${ti?.shape?.contentToString()}"
        }
        Log.i(TAG, "$tag IN=[$ins] OUT=[$outs]")
    }

    @Suppress("UNCHECKED_CAST")
    private fun flattenOnnx(any: Any?): FloatArray {
        return when (any) {
            is FloatArray -> any
            is Array<*> -> {
                val list = ArrayList<Float>()
                fun rec(x: Any?) {
                    when (x) {
                        is FloatArray -> list.addAll(x.asList())
                        is Array<*> -> x.forEach { rec(it) }
                    }
                }
                rec(any)
                list.toFloatArray()
            }
            else -> FloatArray(0)
        }
    }
}
