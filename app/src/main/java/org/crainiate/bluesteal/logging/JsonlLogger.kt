package org.crainiate.bluesteal.logging

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.crainiate.bluesteal.models.AdvertVariant
import org.crainiate.bluesteal.models.toHexLower
import org.crainiate.bluesteal.services.RawAdvertEvent
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

/**
 * Append-only JSONL writer for `<app-files>/AdvertLogs/<yyyy-MM-dd>.jsonl`.
 * One line per detection. Shape matches the Swift `JSONLLogger` so the iOS /
 * macOS / Android logs can be concatenated for offline analysis.
 */
class JsonlLogger(context: Context) {
    private val dir: File = (context.getExternalFilesDir(null) ?: context.filesDir)
        .resolve("AdvertLogs").apply { mkdirs() }

    private val fileDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "bluesteal.jsonl").apply { isDaemon = true }
    }

    val currentLogFile: File
        get() = dir.resolve("${fileDateFormat.format(Date())}.jsonl")

    fun append(event: RawAdvertEvent, uuid: String?, payload: ByteArray, variant: AdvertVariant) {
        val ts = Instant.ofEpochMilli(event.timestampMillis).toString()
        val record = buildJsonObject {
            put("ts", JsonPrimitive(ts))
            put("peer", JsonPrimitive(event.peripheralID.toString().uppercase()))
            put("rssi", JsonPrimitive(event.rssi))
            put("uuid", JsonPrimitive(uuid))
            put("variant", JsonPrimitive(variant.wireName))
            put("payloadHex", JsonPrimitive(payload.toHexLower()))
            put("name", JsonPrimitive(event.localName))
            put("manufacturerHex", JsonPrimitive(event.manufacturerData?.toHexLower()))
            put("serviceUUIDs", buildJsonArray {
                for (u in event.advertisedServiceUUIDs) add(JsonPrimitive(u))
            })
            put("txPower", event.txPowerLevel?.let(::JsonPrimitive) ?: JsonPrimitive(null as String?))
            put("isConnectable", event.isConnectable?.let(::JsonPrimitive) ?: JsonPrimitive(null as String?))
        }
        val line = record.toString() + "\n"
        executor.execute {
            try {
                FileOutputStream(currentLogFile, /* append = */ true).use {
                    it.write(line.toByteArray())
                }
            } catch (t: Throwable) {
                Log.w("JsonlLogger", "write failed", t)
            }
        }
    }
}
