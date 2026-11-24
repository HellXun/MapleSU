package dev.maplesu.manager.runtime

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * `su` 线协议的一帧请求（shared by the Ultra supervised process; the wire format is
 * unchanged so already-delivered clients keep working）。
 *
 * Wire fields contain execution data only. The admission UID comes from Binder.
 */
internal data class NativeSuRequest(
    val terminals: Int,
    val targetUid: Int,
    val globalMount: Boolean,
    val window: IntArray,
    val executable: String,
    val directory: String,
    val arguments: List<String>,
    val environment: List<String>,
) {
    fun writeTo(stream: OutputStream) {
        val output = DataOutputStream(stream)
        output.writeInt(MAGIC)
        output.writeInt(terminals)
        output.writeInt(targetUid)
        output.writeInt(if (globalMount) 1 else 0)
        window.forEach(output::writeInt)
        fun text(value: String) {
            val encoded = value.toByteArray(Charsets.UTF_8)
            output.writeInt(encoded.size)
            output.write(encoded)
        }
        text(executable)
        text(directory)
        output.writeInt(arguments.size)
        arguments.forEach(::text)
        output.writeInt(environment.size)
        environment.forEach(::text)
        output.flush()
    }

    companion object {
        private const val MAGIC = 0x4d535531
        fun readFrom(stream: InputStream): NativeSuRequest {
            val input = DataInputStream(stream)
            require(input.readInt() == MAGIC) { "SU_PROTOCOL_INVALID" }
            val terminals = input.readInt().also { require(it in 0..7) }
            val uid = input.readInt().also { require(it >= 0) }
            val global = input.readInt().also { require(it in 0..1) } == 1
            val window = IntArray(4) { input.readInt().also { require(it in 0..65535) } }
            var remaining = 131_072
            fun text(): String {
                val length = input.readInt()
                require(length in 0..remaining) { "SU_FRAME_TOO_LARGE" }
                remaining -= length
                val bytes = ByteArray(length)
                input.readFully(bytes)
                // Reject malformed UTF-8 instead of silently changing the command bytes.
                val value = bytes.toString(Charsets.UTF_8)
                require(value.toByteArray(Charsets.UTF_8).contentEquals(bytes) && '\u0000' !in value)
                return value
            }
            val executable = text().also { require(it.startsWith('/')) }
            val directory = text().also { require(it.startsWith('/')) }
            val arguments = List(input.readInt().also { require(it in 1..256) }) { text() }
            require(arguments.first().isNotEmpty())
            val environment = List(input.readInt().also { require(it in 0..512) }) { text() }
            require(environment.all { it.indexOf('=') > 0 })
            return NativeSuRequest(terminals, uid, global, window, executable, directory, arguments, environment)
        }
    }
}
