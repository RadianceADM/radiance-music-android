package io.github.radianceadm.music.share

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import androidx.core.content.ContextCompat
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The phone's own Wi-Fi network (Wi-Fi Direct), for when the two devices
 * share no Wi-Fi: the phone showing the QR makes one, the QR carries its
 * name and password, and the phone scanning it joins it straight away (no
 * pairing, no looking around). Android's always 192.168.49.1 on it.
 */
object Direct {
    const val HOST = "192.168.49.1"

    /** This device's address on the Wi-Fi it's on (or its own hotspot), not its Wi-Fi Direct one. */
    fun wifiAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.name.startsWith("p2p") && (it.name.startsWith("wlan") || it.name.startsWith("swlan") || it.name.startsWith("ap") || it.name.startsWith("softap")) }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
    }.getOrNull()

    private fun manager(context: Context) = context.getSystemService(WifiP2pManager::class.java)

    class Group(val name: String, val pass: String, private val context: Context, private val channel: WifiP2pManager.Channel) {
        fun close() {
            runCatching { manager(context).removeGroup(channel, null) }
            runCatching { channel.close() }
        }
    }

    private fun code(n: Int): String {
        val abc = "abcdefghjkmnpqrstuvwxyz23456789"
        val r = SecureRandom()
        return (1..n).map { abc[r.nextInt(abc.length)] }.joinToString("")
    }

    /** Makes the phone's own network (the Wi-Fi Direct permission must be granted). Null if the phone can't now. */
    @SuppressLint("MissingPermission")
    suspend fun open(context: Context): Group? {
        val m = manager(context) ?: return null
        val channel = m.initialize(context, Looper.getMainLooper(), null) ?: return null
        // one of ours from before, gone
        suspendCoroutine { k -> m.removeGroup(channel, object : WifiP2pManager.ActionListener { override fun onSuccess() = k.resume(Unit); override fun onFailure(r: Int) = k.resume(Unit) }) }
        val name = "DIRECT-rm-Radiance-${code(4)}"
        val pass = code(12)
        val config = WifiP2pConfig.Builder().setNetworkName(name).setPassphrase(pass).enablePersistentMode(false).setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_AUTO).build()
        val ok = suspendCoroutine { k ->
            m.createGroup(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = k.resume(true)
                override fun onFailure(reason: Int) = k.resume(false)
            })
        }
        if (!ok) { channel.close(); return null }
        return Group(name, pass, context, channel)
    }

    /** Joins the network a QR named, and waits (up to 25 s) until it's up. Null if it couldn't. */
    @SuppressLint("MissingPermission")
    suspend fun join(context: Context, name: String, pass: String): Group? {
        val m = manager(context) ?: return null
        val channel = m.initialize(context, Looper.getMainLooper(), null) ?: return null
        val config = WifiP2pConfig.Builder().setNetworkName(name).setPassphrase(pass).build().apply { wps.setup = WpsInfo.PBC }
        val up = withTimeoutOrNull(25_000) {
            suspendCancellableCoroutine { k ->
                var finished = false
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context, i: Intent) {
                        @Suppress("DEPRECATION")
                        val net = i.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                        val info = i.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO, WifiP2pInfo::class.java)
                        if (!finished && net?.isConnected == true && info?.groupFormed == true) {
                            finished = true
                            runCatching { context.unregisterReceiver(this) }
                            k.resume(true)
                        }
                    }
                }
                ContextCompat.registerReceiver(context, receiver, IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
                k.invokeOnCancellation { finished = true; runCatching { context.unregisterReceiver(receiver) } }
                m.connect(channel, config, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {}
                    override fun onFailure(reason: Int) {
                        if (!finished) { finished = true; runCatching { context.unregisterReceiver(receiver) }; k.resume(false) }
                    }
                })
            }
        } ?: false
        if (!up) { runCatching { m.cancelConnect(channel, null) }; channel.close(); return null }
        return Group(name, pass, context, channel)
    }
}
