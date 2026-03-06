package com.example.focusforlife.services

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import com.example.focusforlife.core.FocusForegroundTracker
import com.example.focusforlife.core.FocusRules
import com.example.focusforlife.core.FocusTargets
import com.example.focusforlife.logging.FocusLogger

/**
 * Local VPN that hijacks DNS requests and blocks configured domains when focus rules say so.
 * All other traffic stays outside the VPN (split-tunnel) so connectivity works normally.
 */
class FocusVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnThread: Thread? = null
    private val domainUsageMonitor = DomainUsageMonitor()
    private var stopRequested = false

    private val vpnAddress = "10.255.0.2"
    private val dnsVirtualAddress = "10.255.0.1"
    private val upstreamDns = InetSocketAddress("1.1.1.1", 53)

    override fun onCreate() {
        super.onCreate()
        FocusLogger.init(this)
        FocusForegroundNotifications.ensureChannel(this)
        FocusLogger.i("VPN service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRequested = true
            FocusLogger.i("VPN stop requested via intent")
            stopVpn()
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(
            FocusForegroundNotifications.VPN_NOTIFICATION_ID,
            FocusForegroundNotifications.buildVpnNotification(this, "DNS guard running")
        )
        if (vpnThread?.isAlive == true) {
            FocusLogger.v("VPN already running; start ignored")
            return START_STICKY
        }

        vpnThread = Thread({
            try {
                runVpn()
            } catch (t: Throwable) {
                FocusLogger.e("VPN crashed", t)
            } finally {
                stopSelf()
            }
        }, "FocusVpnThread").also { it.start() }

        FocusLogger.i("VPN thread started")
        return START_STICKY
    }

    override fun onRevoke() {
        stopRequested = true
        FocusLogger.w("VPN revoked by system")
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        FocusLogger.w("VPN service destroyed")
        stopVpn()
        if (!stopRequested) {
            ServiceRestartScheduler.schedule(this, FocusVpnService::class.java)
        }
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!stopRequested) {
            FocusLogger.w("VPN task removed; scheduling restart")
            ServiceRestartScheduler.schedule(this, FocusVpnService::class.java)
        }
    }

    private fun stopVpn() {
        vpnThread?.interrupt()
        vpnThread = null
        vpnInterface?.close()
        vpnInterface = null
        runningFlag = false
        domainUsageMonitor.stop("vpn stopped")
        FocusLogger.i("VPN stopped")
    }

    private fun runVpn() {
        FocusRules.ensureFreshDay(this)
        val builder = Builder()
            .setSession("FocusForLife DNS Guard")
            .setMtu(1500)
            .addAddress(vpnAddress, 32)
            .addRoute(dnsVirtualAddress, 32)
            .addDnsServer(dnsVirtualAddress)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        val interfaceDescriptor = builder.establish() ?: throw IllegalStateException("Unable to establish VPN interface")
        vpnInterface = interfaceDescriptor
        runningFlag = true
        FocusLogger.i("VPN established")

        DatagramSocket().use { upstreamSocket ->
            protect(upstreamSocket)
            upstreamSocket.connect(upstreamDns)
            upstreamSocket.soTimeout = 1500

            FileInputStream(interfaceDescriptor.fileDescriptor).use { input ->
                FileOutputStream(interfaceDescriptor.fileDescriptor).use { output ->
                    val packetBuffer = ByteArray(32767)
                    while (!Thread.currentThread().isInterrupted) {
                        val length = try {
                            input.read(packetBuffer)
                        } catch (io: IOException) {
                            break
                        }

                        if (length <= 0) continue

                        val response = handlePacket(packetBuffer, length, upstreamSocket)
                        if (response != null) {
                            try {
                                output.write(response)
                            } catch (_: IOException) {
                                break
                            }
                        }
                    }
                }
            }
        }
    }

    private fun handlePacket(packet: ByteArray, length: Int, upstreamSocket: DatagramSocket): ByteArray? {
        if (length < 28) return null
        val version = (packet[0].toInt() shr 4) and 0xF
        if (version != 4) return null
        val headerLength = (packet[0].toInt() and 0xF) * 4
        if (headerLength < 20 || length < headerLength + 8) return null

        val protocol = packet[9].toInt() and 0xFF
        if (protocol != 17) return null // UDP only

        val destinationPort = readShort(packet, headerLength + 2)
        if (destinationPort != 53) return null

        val dnsStart = headerLength + 8
        val dnsPayloadLength = length - dnsStart
        if (dnsPayloadLength <= 12) return null

        val dnsPayload = packet.copyOfRange(dnsStart, length)
        val query = parseDnsQuery(dnsPayload) ?: return null

        val normalizedDomain = FocusTargets.normalizeDomain(query.domain)
        val domainMatch = FocusTargets.matchesBlockedDomain(normalizedDomain)
        val shouldBlock = domainMatch && FocusRules.shouldDenyAccess(applicationContext)
        FocusLogger.v("DNS request for $normalizedDomain match=$domainMatch shouldBlock=$shouldBlock")
        val dnsResponse = if (shouldBlock) {
            buildBlockedDnsResponse(dnsPayload, query)
        } else {
            forwardDnsQuery(dnsPayload, upstreamSocket) ?: buildBlockedDnsResponse(dnsPayload, query, nxdomain = true)
        } ?: return null

        return wrapUdpResponse(packet, headerLength, dnsResponse)
    }

    private fun parseDnsQuery(payload: ByteArray): DnsQuery? {
        var index = 12
        val labels = mutableListOf<String>()
        while (index < payload.size) {
            val len = payload[index].toInt() and 0xFF
            if (len == 0) {
                index += 1
                break
            }
            if (index + len >= payload.size) return null
            val label = payload.copyOfRange(index + 1, index + 1 + len)
            labels.add(String(label, Charsets.US_ASCII))
            index += len + 1
        }
        if (index + 4 > payload.size) return null
        val qType = readShort(payload, index)
        val qClass = readShort(payload, index + 2)
        val questionLength = index + 4 - 12
        return DnsQuery(
            domain = labels.joinToString("."),
            questionLength = questionLength,
            qType = qType,
            qClass = qClass
        )
    }

    private fun buildBlockedDnsResponse(
        requestPayload: ByteArray,
        query: DnsQuery,
        nxdomain: Boolean = false
    ): ByteArray? {
        val questionBytes = requestPayload.copyOfRange(12, 12 + query.questionLength)
        val header = ByteArray(12)
        System.arraycopy(requestPayload, 0, header, 0, 2) // transaction id
        header[2] = 0x81.toByte()
        header[3] = if (nxdomain) 0x83.toByte() else 0x80.toByte()
        header[4] = 0x00
        header[5] = 0x01 // QDCOUNT
        val answerCount = if (nxdomain || query.qType != 1) 0 else 1
        header[6] = 0x00
        header[7] = answerCount.toByte()
        header[8] = 0x00
        header[9] = 0x00
        header[10] = 0x00
        header[11] = 0x00

        if (answerCount == 0) {
            return header + questionBytes
        }

        val answer = ByteArray(16)
        answer[0] = 0xC0.toByte()
        answer[1] = 0x0C
        answer[2] = 0x00
        answer[3] = 0x01 // Type A
        answer[4] = 0x00
        answer[5] = 0x01 // Class IN
        answer[6] = 0x00
        answer[7] = 0x00
        answer[8] = 0x00
        answer[9] = 0x00 // TTL 0
        answer[10] = 0x00
        answer[11] = 0x04
        answer[12] = 0x00
        answer[13] = 0x00
        answer[14] = 0x00
        answer[15] = 0x00 // 0.0.0.0

        return header + questionBytes + answer
    }

    private fun forwardDnsQuery(payload: ByteArray, upstreamSocket: DatagramSocket): ByteArray? {
        return try {
            val outgoing = DatagramPacket(payload, payload.size, upstreamDns)
            upstreamSocket.send(outgoing)
            val buffer = ByteArray(1024)
            val incoming = DatagramPacket(buffer, buffer.size)
            upstreamSocket.receive(incoming)
            incoming.data.copyOf(incoming.length)
        } catch (timeout: SocketTimeoutException) {
            null
        } catch (io: IOException) {
            null
        }
    }

    private fun wrapUdpResponse(request: ByteArray, headerLength: Int, dnsPayload: ByteArray): ByteArray {
        val udpLength = dnsPayload.size + 8
        val totalLength = headerLength + udpLength
        val response = ByteArray(totalLength)

        // IPv4 header
        System.arraycopy(request, 0, response, 0, headerLength)
        response[2] = ((totalLength shr 8) and 0xFF).toByte()
        response[3] = (totalLength and 0xFF).toByte()
        response[8] = 64 // TTL
        response[9] = 17 // protocol UDP

        // swap addresses
        for (i in 0 until 4) {
            val src = request[12 + i]
            val dst = request[16 + i]
            response[12 + i] = dst
            response[16 + i] = src
        }

        response[10] = 0
        response[11] = 0
        val checksum = ipv4Checksum(response, headerLength)
        writeShort(response, 10, checksum)

        // UDP header
        val reqSrcPort = readShort(request, headerLength)
        val reqDstPort = readShort(request, headerLength + 2)
        writeShort(response, headerLength, reqDstPort)
        writeShort(response, headerLength + 2, reqSrcPort)
        writeShort(response, headerLength + 4, udpLength)
        response[headerLength + 6] = 0
        response[headerLength + 7] = 0

        // DNS payload
        System.arraycopy(dnsPayload, 0, response, headerLength + 8, dnsPayload.size)
        return response
    }

    private fun ipv4Checksum(buffer: ByteArray, length: Int): Int {
        var sum = 0L
        var i = 0
        while (i < length) {
            if (i == 10) { // skip checksum field
                i += 2
                continue
            }
            val word = readShort(buffer, i)
            sum += word
            while (sum shr 16 != 0L) {
                sum = (sum and 0xFFFF) + (sum shr 16)
            }
            i += 2
        }
        sum = sum.inv() and 0xFFFF
        return sum.toInt()
    }

    private fun readShort(buffer: ByteArray, offset: Int): Int {
        return ((buffer[offset].toInt() and 0xFF) shl 8) or (buffer[offset + 1].toInt() and 0xFF)
    }

    private fun writeShort(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = ((value shr 8) and 0xFF).toByte()
        buffer[offset + 1] = (value and 0xFF).toByte()
    }

    private fun isUserActive(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val screenOn = pm?.isInteractive ?: true
        val unlocked = km?.isKeyguardLocked?.not() ?: true
        return screenOn && unlocked
    }

    data class DnsQuery(
        val domain: String,
        val questionLength: Int,
        val qType: Int,
        val qClass: Int
    )

    private inner class DomainUsageMonitor {
        private var currentDomain: String? = null
        private var activePackage: String? = null
        private var lastTick: Long = 0L
        private var mismatchStart: Long = 0L
        private val handler = Handler(Looper.getMainLooper())
        private val tickRunnable = object : Runnable {
            override fun run() {
                val now = System.currentTimeMillis()
                val domain = currentDomain
                val pkg = activePackage
                if (domain == null || pkg == null || FocusRules.shouldDenyAccess(applicationContext) || !isUserActive()) {
                    stop("domain tick ended (inactive or blocked)")
                    return
                }
                if (!isBrowserForeground(pkg)) {
                    if (mismatchStart == 0L) mismatchStart = now
                    if (now - mismatchStart > FOREGROUND_GRACE_MS) {
                        stop("browser lost foreground")
                        return
                    }
                } else {
                    mismatchStart = 0L
                }
                val delta = now - lastTick
                if (delta > 0) {
                    FocusRules.addUsageMillis(
                        applicationContext,
                        "${FocusRules.DOMAIN_PREFIX}$domain",
                        delta
                    )
                    lastTick = now
                }
                handler.postDelayed(this, DOMAIN_USAGE_TICK_MS)
            }
        }
        private var tickerScheduled = false

        fun onDomainActivity(domain: String) {
            val pkg = FocusForegroundTracker.currentPackage()
            if (pkg == null) {
                FocusLogger.v("Domain activity ignored: no foreground package")
                return
            }
            if (!FocusTargets.browserPackageSet.contains(pkg)) {
                FocusLogger.v("Domain activity ignored: non-browser pkg=$pkg domain=$domain")
                return
            }
            if (!isUserActive()) {
                FocusLogger.v("Domain activity ignored: device inactive pkg=$pkg domain=$domain")
                return
            }
            val now = System.currentTimeMillis()
            currentDomain = domain
            activePackage = pkg
            mismatchStart = 0L
            FocusLogger.i("Domain session started: domain=$domain pkg=$pkg")
            if (!tickerScheduled) {
                lastTick = now
                tickerScheduled = true
                handler.postDelayed(tickRunnable, DOMAIN_USAGE_TICK_MS)
            }
        }

        fun stop(reason: String) {
            handler.removeCallbacksAndMessages(null)
            tickerScheduled = false
            currentDomain = null
            activePackage = null
            lastTick = 0L
            mismatchStart = 0L
            FocusLogger.i("Domain session stopped: $reason")
        }
    }

    private fun isBrowserForeground(expectedPackage: String): Boolean {
        val current = FocusForegroundTracker.currentPackage()
        return current != null && current == expectedPackage
    }

    companion object {
        const val ACTION_STOP = "com.example.focusforlife.action.STOP_VPN"

        @Volatile private var runningFlag: Boolean = false
        private const val DOMAIN_USAGE_TICK_MS = 1_000L
        private const val FOREGROUND_GRACE_MS = 3_000L

        fun isRunning(): Boolean = runningFlag
    }
}
