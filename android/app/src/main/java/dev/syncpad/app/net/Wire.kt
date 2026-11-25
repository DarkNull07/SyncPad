package dev.syncpad.app.net

import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Framing and encryption. See docs/PROTOCOL.md. */
object Wire {
    const val PORT = 47800
    const val DISCOVERY_PORT = 47801
    const val CONTROL = 1
    const val SCREEN = 2
    const val FILE = 3
    const val STATE: Byte = 1
    const val BUTTON: Byte = 2
    const val UPING: Byte = 9
    const val DATA: Byte = 0x20
    const val TAG = 16
    const val MAX_FRAME = 4 * 1024 * 1024
    val MAGIC = "SPD1".toByteArray()

    fun hkdf(key: ByteArray, salt: ByteArray, info: ByteArray, length: Int = 32): ByteArray {
        fun mac(k: ByteArray, vararg parts: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(k, "HmacSHA256"))
            parts.forEach { update(it) }
            doFinal()
        }
        val prk = mac(salt, key)
        var out = ByteArray(0)
        var block = ByteArray(0)
        var i = 1
        while (out.size < length) {
            block = mac(prk, block, info, byteArrayOf(i++.toByte()))
            out += block
        }
        return out.copyOf(length)
    }

    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun unhex(s: String): ByteArray {
        require(s.length % 2 == 0)
        return ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}

class SessionKeys(psk: ByteArray, clientNonce: ByteArray, hostNonce: ByteArray) {
    private val salt = clientNonce + hostNonce
    private val make = { label: String -> Wire.hkdf(psk, salt, "syncpad1 $label".toByteArray()) }
    val c2s = make("c2s")
    val s2c = make("s2c")
    val udpC2s = make("udp c2s")
    val udpS2c = make("udp s2c")
}

/** AES-GCM with a counter nonce. One instance per direction; not thread safe. */
class Gcm(key: ByteArray, private val mode: Int) {
    private val spec = SecretKeySpec(key, "AES")
    private val cipher = Cipher.getInstance("AES/GCM/NoPadding")

    fun run(counter: Long, data: ByteArray, off: Int, len: Int, aad: ByteArray): ByteArray {
        val nonce = ByteBuffer.allocate(12).putInt(0).putLong(counter).array()
        cipher.init(mode, spec, GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(data, off, len)
    }
}

/** An encrypted, framed TCP connection. send() is thread safe; recv() is for one thread. */
class Channel(val socket: Socket, sendKey: ByteArray, recvKey: ByteArray) {
    private val tx = Gcm(sendKey, Cipher.ENCRYPT_MODE)
    private val rx = Gcm(recvKey, Cipher.DECRYPT_MODE)
    private var txN = 0L
    private var rxN = 0L
    private val input = DataInputStream(socket.getInputStream().buffered(64 * 1024))
    private val output: OutputStream = socket.getOutputStream()

    @Synchronized
    fun send(data: ByteArray, off: Int = 0, len: Int = data.size) {
        val head = ByteBuffer.allocate(4).putInt(len + Wire.TAG).array()
        val body = tx.run(txN++, data, off, len, head)
        // One write per frame, so it leaves as one segment with TCP_NODELAY.
        output.write(head + body)
        output.flush()
    }

    fun recv(): ByteArray {
        val head = ByteArray(4)
        input.readFully(head)
        val n = ByteBuffer.wrap(head).int
        if (n < Wire.TAG || n > Wire.MAX_FRAME + Wire.TAG) throw IOException("bad frame length")
        val body = ByteArray(n)
        input.readFully(body)
        try {
            return rx.run(rxN++, body, 0, n, head)
        } catch (e: GeneralSecurityException) {
            throw IOException("authentication failed", e)
        }
    }

    fun close() = runCatching { socket.close() }.let { }
}

class UdpCrypto(private val sid: Int, sendKey: ByteArray, recvKey: ByteArray) {
    private val tx = Gcm(sendKey, Cipher.ENCRYPT_MODE)
    private val rx = Gcm(recvKey, Cipher.DECRYPT_MODE)
    private var seq = 0L
    private var seen = 0L

    fun seal(data: ByteArray): ByteArray {
        val head = ByteBuffer.allocate(12).putInt(sid).putLong(++seq).array()
        return head + tx.run(seq, data, 0, data.size, head)
    }

    fun open(p: ByteArray, len: Int): ByteArray? {
        if (len < 12 + Wire.TAG) return null
        val n = ByteBuffer.wrap(p, 4, 8).long
        if (n <= seen) return null
        return try {
            rx.run(n, p, 12, len - 12, p.copyOf(12)).also { seen = n }
        } catch (_: GeneralSecurityException) {
            null
        }
    }
}

/** What a pairing link carries, and what the phone remembers about a laptop. */
data class Laptop(
    val hostId: String, val deviceId: String, val key: String, val name: String, val port: Int,
    val ips: List<String>,
) {
    companion object {
        fun parse(link: String): Laptop? = runCatching {
            val u = URI(link.trim())
            require(u.scheme == "syncpad" && u.host == "pair")
            val q = u.rawQuery.split('&').associate {
                it.substringBefore('=') to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
            }
            require(q["v"] == "1")
            val h = q.getValue("h"); val d = q.getValue("d"); val k = q.getValue("k")
            require(Wire.unhex(h).size == 8 && Wire.unhex(d).size == 8 && Wire.unhex(k).size == 32)
            Laptop(h, d, k, q["n"].orEmpty().ifBlank { "Laptop" }.take(40), q.getValue("p").toInt().also { require(it in 1..65535) },
                q["a"].orEmpty().split(',').filter { ip -> ip.matches(Regex("""\d{1,3}(\.\d{1,3}){3}""")) }.take(8))
        }.getOrNull()
    }
}
