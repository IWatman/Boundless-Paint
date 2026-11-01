package app.painter.drive

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Google authorization for the Drive app-data scope through Google Play services.
 *
 * The first request opens Google's account chooser / consent sheet; once the grant exists the same call returns
 * a cached or silently refreshed access token. The app therefore never handles refresh tokens, client secrets or
 * redirect URIs. The OAuth client used by Google is the Android client registered for this package and signing
 * certificate.
 */
class DriveAuthController(private val activity: ComponentActivity) : DriveTokenRequester {

    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val continuation = pending ?: return@registerForActivityResult
        pending = null
        val data = result.data
        if (result.resultCode != Activity.RESULT_OK || data == null) {
            continuation.resumeWithException(DriveAuthCancelledException())
            return@registerForActivityResult
        }
        runCatching { Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(data) }
            .onSuccess { authorization ->
                val token = authorization.accessToken
                if (token.isNullOrEmpty()) {
                    continuation.resumeWithException(DriveException(0, "Google returned no access token"))
                } else {
                    continuation.resume(token)
                }
            }
            .onFailure { error ->
                continuation.resumeWithException(
                    if (error is ApiException && error.statusCode == CommonStatusCodes.CANCELED) {
                        DriveAuthCancelledException()
                    } else {
                        DriveException(0, error.message ?: "Google authorization failed")
                    },
                )
            }
    }

    private var pending: CancellableContinuation<String>? = null

    override suspend fun invalidateToken(token: String) {
        // GoogleAuthUtil uses the same Play services token cache as AuthorizationClient, but unlike
        // AuthorizationClient.clearToken it exists in older Play services builds as well.
        withContext(Dispatchers.IO) {
            runCatching { GoogleAuthUtil.clearToken(activity, token) }
        }
    }

    override suspend fun requestToken(): String = suspendCancellableCoroutine { continuation ->
        pending?.resumeWithException(DriveException(0, "Another Google authorization is already running"))
        pending = continuation
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_SCOPE)))
            .build()
        Identity.getAuthorizationClient(activity)
            .authorize(request)
            .addOnSuccessListener { result ->
                if (result.hasResolution()) {
                    val intentSender = result.pendingIntent?.intentSender
                    if (intentSender == null) {
                        pending = null
                        continuation.resumeWithException(DriveException(0, "Google authorization needs a resolution"))
                    } else {
                        launcher.launch(IntentSenderRequest.Builder(intentSender).build())
                    }
                } else {
                    pending = null
                    val token = result.accessToken
                    if (token.isNullOrEmpty()) {
                        continuation.resumeWithException(DriveException(0, "Google returned no access token"))
                    } else {
                        continuation.resume(token)
                    }
                }
            }
            .addOnFailureListener { error ->
                pending = null
                continuation.resumeWithException(
                    if (error is ApiException && error.statusCode == CommonStatusCodes.CANCELED) {
                        DriveAuthCancelledException()
                    } else {
                        DriveException(0, error.message ?: "Google authorization failed")
                    },
                )
            }
        continuation.invokeOnCancellation { pending = null }
    }
}
