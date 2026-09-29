package dev.friction.spike

import androidx.datastore.core.Serializer
import java.io.InputStream
import java.io.OutputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal object FocusCodec {
    private val json = Json { encodeDefaults = true }
    fun encode(state: FocusState) = json.encodeToString(state)
    fun decode(text: String): FocusState = json.decodeFromString<FocusState>(text).also { state ->
        require(state.schema == 1) { "Unsupported saved schema" }
        require(state.interventionMs in 1_000..3_600_000 && state.resetMs in 1_000..86_400_000)
        state.rules.values.forEach {
            require(it.initialMs in 1_000..86_400_000 && it.repeatMs in 1_000..86_400_000)
            require(it.capMs == null || it.capMs in 1_000..86_400_000)
        }
        state.usage.values.forEach {
            require(it.used >= 0 && it.next >= 0 && it.daily >= 0 && it.remaining in 0..3_600_000)
        }
    }
}

internal object FocusSerializer : Serializer<FocusState> {
    override val defaultValue = FocusState()
    override suspend fun readFrom(input: InputStream): FocusState = FocusCodec.decode(input.readBytes().decodeToString())
    override suspend fun writeTo(t: FocusState, output: OutputStream) { output.write(FocusCodec.encode(t).encodeToByteArray()) }
}

internal fun migrateLegacySpike(on: Boolean, target: String, used: Long, remaining: Long,
    away: Long, checkpoint: Long, excluded: Set<String>): FocusState = FocusState(on = on).apply {
    if (target.isNotBlank() && target !in excluded) {
        rules[target] = AppRule(Policy.Interrupted)
        usage[target] = AppUsage(
            used = if (on) used.coerceIn(0, 10_000) else 0, next = 10 * 60_000,
            remaining = if (on) remaining.coerceIn(0, 10_000) else 0,
            away = (away.takeIf { it > 0 } ?: checkpoint).takeIf { it > 0 }?.let { Stamp(it, 0, -1) },
        )
    }
    this.checkpoint = checkpoint.takeIf { it > 0 }?.let { Stamp(it, 0, -1) }
}
