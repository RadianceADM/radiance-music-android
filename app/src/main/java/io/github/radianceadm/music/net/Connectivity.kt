package io.github.radianceadm.music.net

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.provider.Settings
import android.util.Log
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Online or not: the phone's network reaching the internet (as its status bar
 * shows it; a Wi-Fi still waiting for its sign-in page counts as offline).
 * Watched from the start (App), so a search, the lyrics or a save can say
 * "You're offline" rather than "Nothing found", and the app shows an Offline pill.
 */
object Connectivity {
    private val _online = MutableStateFlow(true)
    /** True while the phone's network reaches the internet. */
    val online: StateFlow<Boolean> = _online

    /** The network the phone uses now (the callback's), or null. */
    @Volatile private var now: Network? = null

    fun start(context: Context) {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching {
            // no network at all: offline from the start; else the callback says at once whether it reaches the internet
            if (cm.activeNetwork == null) _online.value = false
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                // (each new network's capabilities follow at once: online is said there)
                override fun onAvailable(network: Network) { now = network }

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    now = network
                    _online.value = reaches(caps)
                }

                // lost with nothing in its place (a new network in its place comes as onAvailable)
                override fun onLost(network: Network) {
                    if (now == null || now == network) {
                        now = null
                        _online.value = false
                    }
                }
            })
        }.onFailure { Log.w("radiance-net", "can't watch the connection", it) }
    }

    /** A network that reaches the internet (checked by the phone, not just connected). */
    fun reaches(caps: NetworkCapabilities?): Boolean =
        caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

    /** The phone's Internet panel (Wi-Fi, mobile data), to connect. */
    fun settings(context: Context) {
        runCatching { context.startActivity(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .recoverCatching { context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** For the tests' pictures: online or not, as told. */
    @VisibleForTesting
    fun pretend(online: Boolean) {
        _online.value = online
    }
}
