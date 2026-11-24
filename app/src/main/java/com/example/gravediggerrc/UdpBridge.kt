package com.example.gravediggerrc

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.webkit.JavascriptInterface
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Pont UDP entre la page web et l'ESP32 (point d'accès Wi-Fi de l'ESP32).
 *
 * IMPORTANT : le réseau de l'ESP32 n'a pas Internet. Sans précaution, Android envoie
 * le trafic par les données mobiles et les paquets n'arrivent jamais. On demande donc
 * explicitement le réseau Wi-Fi et on y "attache" la socket UDP.
 *
 * La page web appelle AndroidBridge.sendUdp("T:50,S:-20,B:0100").
 * Si le réseau est occupé, seule la trame la PLUS RÉCENTE est envoyée
 * (pas d'accumulation = latence minimale).
 */
class UdpBridge(
    context: Context,
    private val host: String,
    private val port: Int,
    private val onStatus: (String) -> Unit,
    private val onRx: (String) -> Unit
) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val writer = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    private val sending = AtomicBoolean(false)
    private val latest = AtomicReference<String?>(null)

    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var address: InetAddress? = null
    @Volatile private var status: String = "Wi-Fi : démarrage…"
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun setStatus(s: String) {
        status = s
        onStatus(s)
    }

    @JavascriptInterface
    fun getStatus(): String = status

    /** Démarre la recherche du réseau Wi-Fi et ouvre la socket UDP. */
    fun start() {
        if (!running.compareAndSet(false, true)) return
        try {
            address = InetAddress.getByName(host) // adresse IP littérale : aucune résolution DNS
        } catch (e: Exception) {
            setStatus("Adresse invalide : $host")
            return
        }
        setStatus("Cherche le Wi-Fi de l'ESP32…")

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) // réseau sans Internet autorisé
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                openSocket(network)
            }

            override fun onLost(network: Network) {
                closeSocket()
                setStatus("Wi-Fi perdu — reconnecte le téléphone au réseau de l'ESP32")
            }
        }
        callback = cb
        try {
            cm.requestNetwork(request, cb)
        } catch (e: Exception) {
            setStatus("Erreur réseau : ${e.message}")
        }

        // Filet de sécurité : si Android ne signale aucun réseau Wi-Fi au bout de 3 s,
        // on ouvre quand même une socket normale (marche sur certains téléphones).
        val t = Thread {
            try {
                Thread.sleep(3000)
            } catch (e: InterruptedException) {
                return@Thread
            }
            if (running.get() && socket == null) openSocket(null)
        }
        t.isDaemon = true
        t.start()
    }

    fun stop() {
        running.set(false)
        try {
            callback?.let { cm.unregisterNetworkCallback(it) }
        } catch (e: Exception) {
            // déjà désenregistré
        }
        closeSocket()
        writer.shutdownNow()
    }

    private fun openSocket(network: Network?) {
        closeSocket()
        try {
            val s = DatagramSocket()
            if (network != null) network.bindSocket(s) // force le passage par le Wi-Fi
            s.soTimeout = 500
            socket = s
            if (network != null) {
                setStatus("Wi-Fi OK — envoi vers $host:$port")
            } else {
                setStatus("Envoi vers $host:$port (Wi-Fi non détecté, essai direct)")
            }
            startReader(s)
        } catch (e: Exception) {
            setStatus("Erreur socket UDP : ${e.message}")
        }
    }

    /** Écoute les éventuelles réponses de l'ESP32 (télémétrie). */
    private fun startReader(s: DatagramSocket) {
        val t = Thread {
            val buf = ByteArray(256)
            while (running.get() && socket === s) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    val txt = String(p.data, 0, p.length, Charsets.UTF_8).trim()
                    if (txt.isNotEmpty()) onRx(txt)
                } catch (e: SocketTimeoutException) {
                    // normal : on reboucle
                } catch (e: Exception) {
                    break
                }
            }
        }
        t.isDaemon = true
        t.start()
    }

    /** Appelé par la page web (thread de fond) : envoie une trame texte. */
    @JavascriptInterface
    fun sendUdp(message: String) {
        latest.set(message)
        if (sending.compareAndSet(false, true)) {
            try {
                writer.execute { drain() }
            } catch (e: Exception) {
                sending.set(false)
            }
        }
    }

    private fun drain() {
        try {
            while (true) {
                val m = latest.getAndSet(null) ?: break
                val s = socket ?: continue
                val a = address ?: continue
                try {
                    val bytes = m.toByteArray(Charsets.UTF_8)
                    s.send(DatagramPacket(bytes, bytes.size, a, port))
                } catch (e: Exception) {
                    // erreur ponctuelle : la trame suivante partira dans 25 ms
                }
            }
        } finally {
            sending.set(false)
        }
    }

    private fun closeSocket() {
        val s = socket
        socket = null
        try {
            s?.close()
        } catch (e: Exception) {
            // rien à faire
        }
    }
}
