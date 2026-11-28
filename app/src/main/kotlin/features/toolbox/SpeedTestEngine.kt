// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0
//
// Ported from Bettbox (GPL-3.0) https://github.com/appshubcc/Bettbox
// core/toolbox/speedtest.go — Cloudflare provider only (延迟 / 下载 / 上传 / 出口信息).

package features.toolbox

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/** 测速阶段（connecting 已并入 latency）。 */
internal enum class SpeedTestPhase { Idle, Latency, Download, Upload, Done, Failed }

internal data class SpeedTestState(
    val phase: SpeedTestPhase = SpeedTestPhase.Idle,
    val progress: Float = 0f,
    val instantMbps: Double = 0.0,
    val latencyMs: Double = 0.0,
    val jitterMs: Double = 0.0,
    val downloadMbps: Double = 0.0,
    val uploadMbps: Double = 0.0,
    val exitIp: String = "",
    val exitLocation: String = "",
    val colo: String = "",
    val failedReason: String = "",
)

private const val Host = "https://speed.cloudflare.com"
private const val Streams = 8
private const val LatencyProbes = 20
private const val TransferBytes = 25_000_000L
private const val PhaseSeconds = 10
private const val SampleIntervalMillis = 100L
private const val ConnectTimeoutMillis = 5_000
private const val ReadTimeoutMillis = 10_000

/**
 * 跑一次完整测速：出口信息 → 延迟/抖动 → 下载 → 上传，全程通过 [onUpdate] 上报。
 * 取消由调用方的 coroutineScope 负责，异常原样抛出。
 */
internal suspend fun runSpeedTest(onUpdate: (SpeedTestState) -> Unit) {
    var state = SpeedTestState(phase = SpeedTestPhase.Latency)
    fun publish(next: SpeedTestState) {
        state = next
        onUpdate(next)
    }
    publish(state)

    runCatching { fetchExitInfo() }.getOrNull()?.let { exit ->
        publish(
            state.copy(
                exitIp = exit.ip,
                exitLocation = exit.location,
                colo = exit.colo,
            )
        )
    }

    val (latency, jitter) = measureLatency()
    publish(state.copy(latencyMs = latency, jitterMs = jitter, progress = 0.15f))

    publish(state.copy(phase = SpeedTestPhase.Download))
    val download = transferMbps(upload = false) { mbps, progress ->
        publish(state.copy(instantMbps = mbps, progress = 0.15f + 0.45f * progress))
    }
    publish(
        state.copy(downloadMbps = download, instantMbps = 0.0, progress = 0.6f)
    )

    publish(state.copy(phase = SpeedTestPhase.Upload))
    val upload = transferMbps(upload = true) { mbps, progress ->
        publish(state.copy(instantMbps = mbps, progress = 0.6f + 0.4f * progress))
    }
    publish(
        state.copy(
            phase = SpeedTestPhase.Done,
            progress = 1f,
            instantMbps = 0.0,
            uploadMbps = upload,
        )
    )
}

private data class ExitInfo(val ip: String, val location: String, val colo: String)

private suspend fun fetchExitInfo(): ExitInfo = withContext(Dispatchers.IO) {
    val map = httpText("$Host/cdn-cgi/trace")
        .lineSequence()
        .mapNotNull { line ->
            val index = line.indexOf('=')
            if (index <= 0) null else line.take(index) to line.substring(index + 1)
        }
        .toMap()
    ExitInfo(
        ip = map["ip"].orEmpty(),
        location = map["loc"].orEmpty(),
        // 与 Bettbox 一致：中国大陆机房不当作出口机房显示。
        colo = if (map["loc"] == "CN") "" else map["colo"].orEmpty().uppercase(),
    )
}

/** 20 次空请求：往返中位数 + 相邻差平均作为抖动。 */
private suspend fun measureLatency(): Pair<Double, Double> = withContext(Dispatchers.IO) {
    val samples = ArrayList<Double>(LatencyProbes)
    repeat(LatencyProbes) {
        currentCoroutineContext().ensureActive()
        val started = System.nanoTime()
        runCatching { httpText("$Host/__down?bytes=0") }
            .onSuccess { samples += (System.nanoTime() - started) / 1_000_000.0 }
    }
    check(samples.isNotEmpty()) { "延迟探测失败" }
    val sorted = samples.sorted()
    val median = sorted[sorted.size / 2]
    val jitter = samples.zipWithNext { left, right -> abs(right - left) }.average()
    median to jitter
}

/** 并发跑满 [Streams] 条流，返回整段传输的平均速率（Mbps）。 */
private suspend fun transferMbps(
    upload: Boolean,
    onProgress: (Double, Float) -> Unit,
): Double = coroutineScope {
    val total = AtomicLong(0)
    val open = ConcurrentLinkedQueue<HttpURLConnection>()
    val chunk = TransferBytes / Streams
    val startedAt = System.currentTimeMillis()
    val deadline = startedAt + PhaseSeconds * 1000L

    val sampler = launch {
        var lastBytes = 0L
        while (isActive) {
            delay(SampleIntervalMillis)
            val now = total.get()
            val mbps = (now - lastBytes) * 8.0 / 1000.0 / SampleIntervalMillis
            lastBytes = now
            onProgress(mbps, (now.toFloat() / TransferBytes).coerceIn(0f, 1f))
        }
    }

    val workers = List(Streams) {
        async(Dispatchers.IO) {
            while (System.currentTimeMillis() < deadline && total.get() < TransferBytes) {
                currentCoroutineContext().ensureActive()
                runCatching { transferOnce(upload, chunk, total, open) }
            }
        }
    }

    try {
        workers.awaitAll()
    } finally {
        sampler.cancel()
        // 取消时掐断在途连接，阻塞读才能尽快返回。
        open.forEach { connection -> runCatching { connection.disconnect() } }
        open.clear()
    }

    val measured = total.get()
    check(measured > 0) { "传输测试失败" }
    val elapsed = ((System.currentTimeMillis() - startedAt).coerceAtLeast(500)) / 1000.0
    measured * 8.0 / 1_000_000.0 / elapsed
}

private suspend fun transferOnce(
    upload: Boolean,
    chunk: Long,
    total: AtomicLong,
    open: ConcurrentLinkedQueue<HttpURLConnection>,
) {
    val url = URL(if (upload) "$Host/__up" else "$Host/__down?bytes=$chunk")
    val connection = (url.openConnection() as HttpURLConnection).apply {
        connectTimeout = ConnectTimeoutMillis
        readTimeout = ReadTimeoutMillis
        useCaches = false
        if (upload) {
            requestMethod = "POST"
            doOutput = true
            setFixedLengthStreamingMode(chunk)
        }
    }
    open += connection
    try {
        runInterruptible {
            if (upload) {
                val buffer = ByteArray(64 * 1024)
                connection.outputStream.use { output ->
                    var written = 0L
                    while (written < chunk) {
                        val size = minOf(buffer.size.toLong(), chunk - written).toInt()
                        output.write(buffer, 0, size)
                        written += size
                        total.addAndGet(size.toLong())
                    }
                }
            } else {
                val buffer = ByteArray(64 * 1024)
                connection.inputStream.use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        total.addAndGet(read.toLong())
                    }
                }
            }
        }
    } finally {
        open.remove(connection)
        runCatching { connection.disconnect() }
    }
}

private fun httpText(url: String): String {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = ConnectTimeoutMillis
        readTimeout = ReadTimeoutMillis
        useCaches = false
    }
    return try {
        connection.inputStream.bufferedReader().use { it.readText() }
    } finally {
        runCatching { connection.disconnect() }
    }
}
