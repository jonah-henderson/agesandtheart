package co.voik.agesandtheart.server

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * A Source RCON client, which is how a check talks to a running server.
 *
 * **Why this rather than the log**: `DedicatedServer.runCommand` is `prepareForCommand()`,
 * `executeBlocking(…)`, `getCommandResponse()`. So the server runs the command *on its own thread*, waits
 * for it to finish, and hands back a buffer holding that command's output and nothing else — no timestamps,
 * no other threads' lines, no need for a barrier token to know when it is done. Failures come back too,
 * since `RconConsoleSource.acceptsFailure()` is true.
 *
 * The one thing it does not do is separate the messages that went into it: `sendSystemMessage` appends to a
 * `StringBuffer` with nothing between. That is why the commands can answer as one JSON document.
 */
class Rcon(host: String, port: Int, password: String) : AutoCloseable {
    private val socket = Socket(host, port)
    private val toServer = DataOutputStream(socket.getOutputStream())
    private val fromServer = DataInputStream(socket.getInputStream())
    private var nextRequest = 1

    init {
        socket.tcpNoDelay = true
        val request = send(TYPE_AUTHENTICATE, password)
        val reply = receive()
        // A refusal comes back as request id -1, which is the whole of the protocol's error reporting.
        check(reply.requestId == request) { "the server refused the RCON password (id ${reply.requestId})" }
    }

    /** [command] run on the server thread, and everything it said back. */
    fun run(command: String): String {
        val request = send(TYPE_COMMAND, command)
        val first = receive()
        check(first.requestId == request) { "expected a reply to $request, got ${first.requestId}" }

        // **A sentinel rather than a length test.** The server splits a long answer into 4096-byte packets
        // and marks the end of neither the packet nor the run, so "a short packet was the last one" is wrong
        // for any answer that is an exact multiple of 4096. Asking an unanswerable question afterwards is:
        // requests are served in order, so its reply cannot arrive before the real one has finished.
        //
        // Sent *after* the first reply is read, never alongside the command: the server reads one packet per
        // `read()` and hangs up on a buffer holding two, so back-to-back sends close the connection.
        val sentinel = send(TYPE_UNKNOWN, "")
        val answer = StringBuilder(first.body)
        while (true) {
            val packet = receive()
            if (packet.requestId == sentinel) return answer.toString()
            answer.append(packet.body)
        }
    }

    override fun close() {
        runCatching { socket.close() }
    }

    private fun send(type: Int, body: String): Int {
        val request = nextRequest++
        val payload = body.toByteArray(StandardCharsets.UTF_8)
        val packet = ByteBuffer.allocate(HEADER_BYTES + payload.size + TERMINATORS).order(ByteOrder.LITTLE_ENDIAN)
        packet.putInt(REQUEST_ID_BYTES + TYPE_BYTES + payload.size + TERMINATORS)
        packet.putInt(request)
        packet.putInt(type)
        packet.put(payload)
        packet.put(0)
        packet.put(0)
        toServer.write(packet.array())
        toServer.flush()
        return request
    }

    private fun receive(): Packet {
        val length = readLittleEndianInt()
        check(length >= REQUEST_ID_BYTES + TYPE_BYTES) { "an RCON packet claimed to be $length bytes" }
        val requestId = readLittleEndianInt()
        readLittleEndianInt() // the type, which tells a client nothing it did not already know
        val body = ByteArray(length - REQUEST_ID_BYTES - TYPE_BYTES - TERMINATORS)
        fromServer.readFully(body)
        fromServer.readFully(ByteArray(TERMINATORS))
        return Packet(requestId, String(body, StandardCharsets.UTF_8))
    }

    private fun readLittleEndianInt(): Int = Integer.reverseBytes(fromServer.readInt())

    private data class Packet(val requestId: Int, val body: String)

    private companion object {
        const val TYPE_COMMAND = 2
        const val TYPE_AUTHENTICATE = 3

        /** Any type the server does not know, which it answers with "Unknown request …" and runs nothing. */
        const val TYPE_UNKNOWN = 100

        const val REQUEST_ID_BYTES = 4
        const val TYPE_BYTES = 4
        const val HEADER_BYTES = 12
        const val TERMINATORS = 2
    }
}
