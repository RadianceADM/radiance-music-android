package io.github.radianceadm.music.feedback

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Where the app's feedback token is kept. */
interface TokenStore {
    fun get(): Saved?
    fun put(s: Saved)
    fun clear()
}

/** Kept for this run only (the tests; or a phone whose key store won't open). */
class MemoryStore(private var saved: Saved? = null) : TokenStore {
    override fun get() = saved
    override fun put(s: Saved) { saved = s }
    override fun clear() { saved = null }
}

/**
 * The token kept encrypted on the phone (EncryptedSharedPreferences, its key in
 * Android's key store). A file that can't be read any more (restored onto another
 * phone, where the key isn't) is started again: you sign in once more.
 */
class EncryptedStore(context: Context) : TokenStore {
    private val memory = MemoryStore()
    private val prefs: SharedPreferences? = runCatching { open(context) }
        .recoverCatching { context.deleteSharedPreferences(FILE); open(context) }
        .onFailure { Log.w("radiance-feedback", "can't open the token's store: kept for this run only", it) }
        .getOrNull()

    @Suppress("DEPRECATION") // still the simplest safe store on Android 10 and later; nothing replaces it in the library yet
    private fun open(context: Context): SharedPreferences {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(context, FILE, key, EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    }

    override fun get(): Saved? {
        val p = prefs ?: return memory.get()
        val token = runCatching { p.getString("token", null) }.getOrNull() ?: return null
        return Saved(token, p.getString("name", null).orEmpty(), p.getString("email", null).orEmpty())
    }

    override fun put(s: Saved) {
        memory.put(s)
        prefs?.edit()?.putString("token", s.token)?.putString("name", s.name)?.putString("email", s.email)?.apply()
    }

    override fun clear() {
        memory.clear()
        prefs?.edit()?.clear()?.apply()
    }

    private companion object {
        const val FILE = "music-account"
    }
}

/**
 * Who's signed in for sending feedback, if anyone: [me] for the screen (name and
 * email; the token stays here). Opened the first time it's needed (the key store
 * is slow to open, so not at the app's start).
 */
object Account : TokenStore {
    data class Me(val name: String, val email: String)

    private var store: TokenStore? = null
    private val _me = MutableStateFlow<Me?>(null)
    val me: StateFlow<Me?> = _me

    /** Opens the phone's store (once). Off the main thread: the key store takes a moment. */
    @Synchronized
    fun load(context: Context) {
        if (store != null) return
        use(EncryptedStore(context.applicationContext))
    }

    /** Another store (the tests: one in memory). */
    @Synchronized
    fun use(s: TokenStore) {
        store = s
        _me.value = s.get()?.let { Me(it.name, it.email) }
    }

    override fun get(): Saved? = store?.get()

    override fun put(s: Saved) {
        store?.put(s)
        _me.value = Me(s.name, s.email)
    }

    override fun clear() {
        store?.clear()
        _me.value = null
    }
}
