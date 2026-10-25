package space.sovereigncast.hopp.platform

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import space.sovereigncast.hopp.nostr.ExternalSigner
import space.sovereigncast.hopp.nostr.SignerPermission
import space.sovereigncast.hopp.nostr.SignerNeedsListener
import space.sovereigncast.hopp.nostr.SignerRejected
import space.sovereigncast.hopp.nostr.SignerResult
import space.sovereigncast.hopp.nostr.SignerUnavailable
import space.sovereigncast.hopp.nostr.npubToHex
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * A signer app on the phone, as NIP-55 describes it (Amber and others).
 * Signing asks the content resolver first — in the background, for what the
 * listener allowed once — and opens the signer only when that is not enough.
 */
class AndroidExternalSigner(private val context: Context) : ExternalSigner {
    override fun available(): Boolean =
        context.packageManager.queryIntentActivities(Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:")), 0).isNotEmpty()

    override suspend fun login(permissions: List<SignerPermission>): Pair<String, String> {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:")).apply {
            putExtra("type", "get_public_key")
            putExtra("permissions", ExternalSigner.permissionsJson(permissions))
        }
        val data = SignerBridgeActivity.request(context, intent)
        val result = data.getStringExtra("result")?.trim() ?: throw SignerUnavailable("no public key")
        // NIP-55 says hex; some signers have answered with an npub.
        val pubkey = (if (result.startsWith("npub1")) npubToHex(result) else result)?.lowercase()
            ?.takeIf { it.length == 64 && it.all { c -> c in "0123456789abcdef" } } ?: throw SignerUnavailable("not a public key")
        val signerPackage = data.getStringExtra("package")?.takeIf { it.isNotBlank() } ?: throw SignerUnavailable("no package")
        return pubkey to signerPackage
    }

    override suspend fun signEvent(signerPackage: String, eventJson: String, eventId: String, currentUser: String, interactive: Boolean): SignerResult {
        quietly(signerPackage, eventJson, currentUser)?.let { return it }
        if (!interactive) throw SignerNeedsListener()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:$eventJson")).apply {
            `package` = signerPackage
            putExtra("type", "sign_event")
            putExtra("id", eventId)
            putExtra("current_user", currentUser)
        }
        val data = SignerBridgeActivity.request(context, intent)
        return SignerResult(data.getStringExtra("event"), data.getStringExtra("result"))
    }

    override suspend fun nip44(signerPackage: String, encrypt: Boolean, text: String, peer: String, currentUser: String, interactive: Boolean): String {
        val type = if (encrypt) "nip44_encrypt" else "nip44_decrypt"
        ask(signerPackage, type.uppercase(), arrayOf(text, peer, currentUser))?.get("result")?.let { return it }
        if (!interactive) throw SignerNeedsListener()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:$text")).apply {
            `package` = signerPackage
            putExtra("type", type)
            putExtra("pubkey", peer)
            putExtra("current_user", currentUser)
        }
        return SignerBridgeActivity.request(context, intent).getStringExtra("result") ?: throw SignerUnavailable("the signer answered nothing")
    }

    /** The background answer, or null when the signer wants to ask the listener. */
    private suspend fun quietly(signerPackage: String, eventJson: String, currentUser: String): SignerResult? =
        ask(signerPackage, "SIGN_EVENT", arrayOf(eventJson, "", currentUser))?.let { c ->
            SignerResult(c["event"], c["result"]).takeIf { it.eventJson != null || it.signature != null }
        }

    /** The first row the signer's content provider gives for [method], or null when it
     *  would rather ask the listener (or is not there). */
    private suspend fun ask(signerPackage: String, method: String, args: Array<String>): Map<String, String?>? = withContext(Dispatchers.IO) {
        val cursor = runCatching {
            context.contentResolver.query(Uri.parse("content://$signerPackage.$method"), args, null, null, null)
        }.getOrNull() ?: return@withContext null
        cursor.use { c ->
            // Always refused there: no point opening the signer to be refused again (NIP-55).
            if (c.getColumnIndex("rejected") >= 0) throw SignerRejected()
            if (!c.moveToFirst()) return@withContext null
            c.columnNames.associateWith { name -> c.getString(c.getColumnIndex(name)) }
        }
    }
}

/**
 * Opens the signer for one request and hands its answer back to the coroutine
 * that waits for it — a signer answers with an activity result, which only an
 * activity can receive. Shows nothing of its own.
 */
class SignerBridgeActivity : ComponentActivity() {
    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val waiting = requestId?.let(pending::remove)
        when {
            waiting == null -> Unit
            result.resultCode != Activity.RESULT_OK -> waiting.completeExceptionally(SignerUnavailable("the signer did not answer"))
            result.data == null -> waiting.completeExceptionally(SignerUnavailable("the signer answered nothing"))
            result.data!!.isRejected() -> waiting.completeExceptionally(SignerRejected())
            else -> waiting.complete(result.data!!)
        }
        finish()
    }
    private var requestId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_REQUEST)
        requestId = id
        // Recreated (rotated) while the signer is open: its answer still arrives here.
        if (savedInstanceState != null) return
        val request = id?.let(requests::remove)
        if (id == null || request == null) { finish(); return }
        try {
            launcher.launch(request)
        } catch (e: android.content.ActivityNotFoundException) {
            pending.remove(id)?.completeExceptionally(SignerUnavailable("no signer app"))
            finish()
        }
    }

    companion object {
        private const val EXTRA_REQUEST = "request"
        private val requests = ConcurrentHashMap<String, Intent>()
        private val pending = ConcurrentHashMap<String, CompletableDeferred<Intent>>()

        private fun Intent.isRejected() = getBooleanExtra("rejected", false) || getStringExtra("rejected") == "true"

        suspend fun request(context: Context, signerIntent: Intent): Intent {
            val id = UUID.randomUUID().toString()
            val answer = CompletableDeferred<Intent>()
            requests[id] = signerIntent
            pending[id] = answer
            try {
                context.startActivity(Intent(context, SignerBridgeActivity::class.java).putExtra(EXTRA_REQUEST, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return answer.await()
            } finally {
                requests.remove(id)
                pending.remove(id)
            }
        }
    }
}
