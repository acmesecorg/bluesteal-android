package org.crainiate.bluesteal.logging

import android.content.Context
import android.os.Process
import android.util.Log
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Append-only JSONL tracer for the WebRTC pairing pipeline.
 *
 * One file per process per UTC date:
 * ```
 * <app-files>/Logs/relay-{yyyy-MM-dd}-{pid}.jsonl
 * ```
 *
 * Each line is a single JSON object with the shape:
 * ```
 * { ts, pid, instanceId, role, code, category, event, data }
 * ```
 *
 * Mirrors `bluesteal-macos/bluesteal/Logging/RelayTracer.swift` and Windows
 * `Bluesteal/Services/RelayTracer.cs` so the three traces can be diff'd line
 * for line.
 *
 * All IO happens on a single-threaded executor — callers can log from any
 * thread without coordination, and a failing write never throws.
 */
class RelayTracer(context: Context) {
    private val pid: Int = Process.myPid()
    private val instanceId: String = UUID.randomUUID().toString().take(8)
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "bluesteal.relay.tracer").apply { isDaemon = true }
    }

    private val logDir: File
    val logFile: File

    @Volatile private var role: String = "none"
    @Volatile private var code: String = ""

    init {
        val fileDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        // App-specific external storage so `adb pull <dir>` works without
        // additional permissions. Falls back to internal files dir if
        // external is unavailable (e.g. emulator with no SD card slot).
        logDir = (context.getExternalFilesDir(null) ?: context.filesDir).resolve("Logs")
        logDir.mkdirs()
        logFile = logDir.resolve("relay-$fileDate-$pid.jsonl")
    }

    val logDirectory: File get() = logDir
    val instance: String get() = instanceId

    fun setContext(role: String, code: String) {
        executor.execute {
            this.role = role
            this.code = code
        }
    }

    /**
     * Append one record. The data map may contain any JSON-serialisable value
     * (string, number, boolean, list, map, null). Anything else is coerced via
     * `toString`. The tracer must never throw into production code paths.
     */
    fun log(category: String, event: String, data: Map<String, Any?>? = null) {
        val ts = Instant.now().toString()           // ISO-8601 with fractional seconds
        val payload = data?.takeIf { it.isNotEmpty() }
        executor.execute {
            try {
                val record = buildJsonObject {
                    put("ts", JsonPrimitive(ts))
                    put("pid", JsonPrimitive(pid))
                    put("instanceId", JsonPrimitive(instanceId))
                    put("role", JsonPrimitive(role))
                    put("code", JsonPrimitive(code))
                    put("category", JsonPrimitive(category))
                    put("event", JsonPrimitive(event))
                    if (payload != null) put("data", payload.toJsonElement())
                }
                val line = record.toString() + "\n"
                FileOutputStream(logFile, /* append = */ true).use { it.write(line.toByteArray()) }
                Log.d(LOG_TAG, "[$instanceId $category] $event $record")
            } catch (t: Throwable) {
                Log.w(LOG_TAG, "tracer failed: $category.$event", t)
            }
        }
    }

    companion object {
        private const val LOG_TAG = "RelayTracer"

        private fun Map<String, Any?>.toJsonElement(): JsonElement = buildJsonObject {
            for ((k, v) in this@toJsonElement) put(k, v.toJsonElement())
        }

        private fun Any?.toJsonElement(): JsonElement = when (this) {
            null -> JsonPrimitive(null as String?)
            is JsonElement -> this
            is Boolean -> JsonPrimitive(this)
            is Number -> JsonPrimitive(this)
            is String -> JsonPrimitive(this)
            is Map<*, *> -> buildJsonObject {
                for ((k, v) in this@toJsonElement) put(k.toString(), v.toJsonElement())
            }
            is Iterable<*> -> buildJsonArray {
                for (e in this@toJsonElement) add(e.toJsonElement())
            }
            else -> JsonPrimitive(toString())
        }

        /** Convenience: the canonical ISO formatter that matches Swift's output. */
        @Suppress("unused")
        val ISO_INSTANT: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT
    }
}
