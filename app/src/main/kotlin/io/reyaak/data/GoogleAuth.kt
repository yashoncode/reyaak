package io.reyaak.data

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import io.reyaak.core.tools.Gmail
import io.reyaak.core.tools.GmailAuth
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Gmail access, through Play Services' Authorization API.
 *
 * No client secret, no refresh token, and nothing written to disk: once the
 * user has granted the scope, `authorize()` returns a fresh access token with
 * no UI, so a token is fetched per call and thrown away. That is why there is
 * no token in [io.reyaak.core.tools.ToolConfig] to encrypt or expire, and why
 * signing out is just forgetting the address.
 *
 * The app needs an OAuth client ID of type Android, registered against this
 * package name and signing certificate, in a Google Cloud project with the
 * Gmail API enabled. There is nothing to put in the code for it: Play Services
 * matches the caller by package and signature.
 */
class GoogleAuth(private val context: Context) : GmailAuth {

    private val request: AuthorizationRequest = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(Gmail.SCOPE)))
        .build()

    /**
     * A token when the grant already exists, null when it does not.
     *
     * Null is not an error here: it is what the background agent sees when
     * consent has been revoked, and the tool turns it into a sentence asking
     * the user to sign in again rather than throwing on a thread with no
     * Activity to show a dialog on.
     */
    override suspend fun accessToken(): String? = authorize()?.accessToken

    /**
     * @return the result, whose `pendingIntent` is non-null exactly when Play
     *   Services wants to show a consent screen. Failures resolve to null: the
     *   caller has one recovery either way, which is to ask again later.
     */
    suspend fun authorize(): AuthorizationResult? = suspendCancellableCoroutine { cont ->
        Identity.getAuthorizationClient(context).authorize(request)
            .addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { cont.resume(null) }
    }

    /** The token carried back by the consent screen this class launched. */
    fun tokenFrom(data: Intent?): String? = data?.let {
        runCatching {
            Identity.getAuthorizationClient(context)
                .getAuthorizationResultFromIntent(it)
                .accessToken
        }.getOrNull()
    }
}
