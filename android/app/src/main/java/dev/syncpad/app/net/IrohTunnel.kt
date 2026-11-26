package dev.syncpad.app.net

import computer.iroh.Connection
import computer.iroh.Endpoint
import computer.iroh.EndpointAddr
import computer.iroh.EndpointId
import computer.iroh.EndpointOptions
import computer.iroh.presetN0
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import kotlin.concurrent.thread

/**
 * Reaches a paired laptop on another network, through iroh.
 *
 * iroh dials the laptop by its id, punches a direct path through both routers
 * when it can, uses a relay when it cannot and keeps trying to go direct. Here
 * the connection is offered to [Link] as an ordinary address on loopback: each
 * TCP connection made to it becomes a stream, each UDP packet a datagram. So
 * the link speaks the usual protocol, end to end encrypted, inside iroh's.
 *
 * Pure JVM, so the tests run it against the real laptop app.
 */
class IrohTunnel(private val secret: () -> ByteArray) : Link.Tunnel {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    @Volatile private var endpoint: Endpoint? = null
    @Volatile private var conn: Connection? = null
    @Volatile private var peer = ""
    @Volatile private var udpBack: SocketAddress? = null
    private var tcp: ServerSocket? = null
    private var udp: DatagramSocket? = null

    @Volatile override var id = ""
        private set
    @Volatile var onId: (String) -> Unit = {}
    /** Why the endpoint could not start, for the status line. */
    @Volatile var error = ""
        private set

    /** Binds this phone's endpoint, so its id is known before the laptop is ever away. */
    fun start() {
        scope.launch { runCatching { bound() }.onFailure { error = it.toString() } }
    }

    private suspend fun bound(): Endpoint = endpoint ?: Endpoint.bind(EndpointOptions(preset = presetN0(), secretKey = secret(), alpns = listOf(ALPN))).also {
        endpoint = it
        id = it.id().toString()
        onId(id)
    }

    override fun open(laptop: String, timeoutMs: Long): Int? = runBlocking {
        withTimeoutOrNull(timeoutMs) { runCatching { connect(laptop) }.getOrNull() }
    }

    private suspend fun connect(laptop: String): Int = lock.withLock {
        val now = conn
        if (now != null && peer == laptop && now.closeReason() == null) return listen()
        drop()
        val e = bound()
        val c = e.connect(EndpointAddr(EndpointId.fromString(laptop), null, emptyList()), ALPN)
        conn = c
        peer = laptop
        val port = listen()
        scope.launch { datagrams(c) }
        port
    }

    /** "direct" or "relay", for the status line. */
    override fun route(): String? = runCatching { conn?.paths()?.firstOrNull { it.isSelected }?.let { if (it.isRelay) "relay" else "direct" } }.getOrNull()

    override fun close() { drop() }

    fun shutdown() {
        drop()
        runCatching { tcp?.close() }
        runCatching { udp?.close() }
        val e = endpoint
        endpoint = null
        if (e != null) runBlocking { withTimeoutOrNull(2000) { runCatching { e.shutdown() } } }
        scope.cancel()
    }

    private fun drop() {
        conn?.let { runCatching { it.close(0, ByteArray(0)) } }
        conn = null
    }

    /** The loopback address [Link] connects to. One port for TCP and UDP, like the laptop's. */
    @Synchronized
    private fun listen(): Int {
        tcp?.let { if (!it.isClosed) return it.localPort }
        val here = InetAddress.getByName(HERE)
        repeat(10) {
            val t = ServerSocket()
            t.bind(InetSocketAddress(here, 0))
            val u = runCatching { DatagramSocket(InetSocketAddress(here, t.localPort)) }.getOrNull()
            if (u == null) { t.close(); return@repeat }
            tcp = t
            udp = u
            thread(isDaemon = true, name = "syncpad-tunnel-tcp") { accept(t) }
            thread(isDaemon = true, name = "syncpad-tunnel-udp") { outgoing(u) }
            return t.localPort
        }
        error("no loopback port")
    }

    private fun accept(t: ServerSocket) {
        while (!t.isClosed) {
            val s = runCatching { t.accept() }.getOrNull() ?: return
            s.tcpNoDelay = true
            scope.launch { pipe(s) }
        }
    }

    private suspend fun pipe(s: Socket) {
        val c = conn ?: return s.close()
        try {
            val bi = c.openBi()
            val send = bi.send()
            val recv = bi.recv()
            val up = scope.launch {
                val buf = ByteArray(65536)
                val ins = s.getInputStream()
                runCatching {
                    while (true) {
                        val n = runInterruptible { ins.read(buf) }
                        if (n < 0) break
                        if (n > 0) send.writeAll(buf.copyOf(n))
                    }
                }
                runCatching { send.finish() }
            }
            runCatching {
                val out = s.getOutputStream()
                while (true) {
                    val d = recv.read(65536u)
                    if (d.isEmpty()) break
                    out.write(d)
                }
            }
            up.cancel()
        } catch (_: Exception) {
        } finally {
            runCatching { s.close() }
        }
    }

    /** Pointer packets from the link go out as datagrams: unreliable on purpose, like UDP. */
    private fun outgoing(u: DatagramSocket) {
        val buf = ByteArray(2048)
        while (!u.isClosed) {
            val p = DatagramPacket(buf, buf.size)
            runCatching { u.receive(p) }.onFailure { return }
            udpBack = p.socketAddress
            runCatching { conn?.sendDatagram(buf.copyOf(p.length)) }
        }
    }

    private suspend fun datagrams(c: Connection) {
        while (true) {
            val d = runCatching { c.readDatagram() }.getOrNull() ?: return
            val back = udpBack ?: continue
            runCatching { udp?.send(DatagramPacket(d, d.size, back)) }
        }
    }

    companion object {
        val ALPN = "syncpad/1".toByteArray()
        /** Link sees the laptop here. Not 127.0.0.1, which is the USB cable's. */
        const val HERE = "127.0.0.2"
    }
}
