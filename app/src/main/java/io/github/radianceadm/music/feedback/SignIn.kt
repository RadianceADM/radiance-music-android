package io.github.radianceadm.music.feedback

import android.content.Context
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import io.github.radianceadm.music.net.fail

/** You closed Google's sign-in: nothing's wrong, nothing was sent. */
class SignInCancelled : Exception("sign-in cancelled")

/**
 * "Sign in with Google" on the phone (Credential Manager): pick your Google
 * account, and Google gives an ID token for Radiance Music's Web client
 * ([clientId], the admin's audience; no nonce), which the admin swaps for the
 * app's own token. [activity]: the screen it shows over.
 */
object GoogleSignIn {
    private const val DIDNT = "Google sign-in didn't work. Try again."

    suspend fun idToken(activity: Context, clientId: String): String {
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(GetSignInWithGoogleOption.Builder(clientId).build())
            .build()
        val result = try {
            CredentialManager.create(activity).getCredential(activity, request)
        } catch (e: GetCredentialCancellationException) {
            throw SignInCancelled()
        } catch (e: NoCredentialException) {
            fail("There's no Google account on this phone. Add one in the phone's Settings, then try again.", e.message)
        } catch (e: GetCredentialException) {
            Log.w("radiance-feedback", "Google sign-in failed", e)
            fail(DIDNT, "${e.type}: ${e.message}")
        }
        val c = result.credential
        if (c !is CustomCredential || c.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) fail(DIDNT, "not a Google ID token: ${c.type}")
        return try {
            GoogleIdTokenCredential.createFrom(c.data).idToken
        } catch (e: GoogleIdTokenParsingException) {
            fail(DIDNT, e.message)
        }
    }
}
