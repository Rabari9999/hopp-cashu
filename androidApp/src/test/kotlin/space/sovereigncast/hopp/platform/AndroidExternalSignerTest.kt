package space.sovereigncast.hopp.platform

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import space.sovereigncast.hopp.nostr.ExternalSigner
import space.sovereigncast.hopp.nostr.SignerNeedsListener
import space.sovereigncast.hopp.nostr.SignerRejected
import space.sovereigncast.hopp.nostr.npub
import space.sovereigncast.hopp.ui.TestApp

/** What a signer app's content provider answers, set per test. */
private var providerAnswer: Cursor? = null
private var providerAsked: Array<out String>? = null

/** Amber's content provider, as NIP-55 describes it. */
class FakeSignerProvider : ContentProvider() {
    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        providerAsked = projection
        return providerAnswer
    }
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}

/**
 * The Android side of signing with Amber (NIP-55, ADR 0021 stage 1b): the
 * quiet content-resolver path first, the signer's own screen through the
 * bridge activity when that is not enough.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TestApp::class)
class AndroidExternalSignerTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val signer = AndroidExternalSigner(app)
    private val pkg = "com.greenart7c3.nostrsigner"
    private val me = "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d"

    /** Runs [block] off the main thread and plays the signer app on it meanwhile. */
    private fun <T> withSignerScreen(block: suspend () -> T, answer: (Intent) -> Pair<Int, Intent?>): T = runBlocking {
        val call = GlobalScope.async(Dispatchers.IO) { block() }
        var bridgeIntent: Intent? = null
        repeat(200) {
            if (bridgeIntent == null) {
                shadowOf(android.os.Looper.getMainLooper()).idle()
                // Skip what an earlier bridge started (the signer's own intent).
                bridgeIntent = generateSequence { shadowOf(app).nextStartedActivity }
                    .firstOrNull { it.component?.className == SignerBridgeActivity::class.java.name }
            }
            if (bridgeIntent == null) Thread.sleep(10)
        }
        val bridge = Robolectric.buildActivity(SignerBridgeActivity::class.java, bridgeIntent!!).setup().get()
        val started = shadowOf(bridge).nextStartedActivityForResult
        val (code, data) = answer(started.intent)
        shadowOf(bridge).receiveResult(started.intent, code, data)
        call.await()
    }

    @Test
    fun aRememberedPermissionSignsWithoutOpeningTheSigner() = runBlocking {
        Robolectric.setupContentProvider(FakeSignerProvider::class.java, "$pkg.SIGN_EVENT")
        providerAnswer = MatrixCursor(arrayOf("result", "event")).apply { addRow(arrayOf("sig", """{"id":"x"}""")) }
        val result = signer.signEvent(pkg, """{"kind":27235}""", "x", me)
        assertEquals("""{"id":"x"}""", result.eventJson)
        assertEquals(listOf("""{"kind":27235}""", "", me), providerAsked!!.toList())
        assertEquals(null, shadowOf(app).nextStartedActivity)
    }

    @Test(expected = SignerRejected::class)
    fun aRequestAlwaysRefusedIsNotAskedAgain() {
        Robolectric.setupContentProvider(FakeSignerProvider::class.java, "$pkg.SIGN_EVENT")
        providerAnswer = MatrixCursor(arrayOf("rejected")).apply { addRow(arrayOf("true")) }
        runBlocking { signer.signEvent(pkg, "{}", "x", me) }
    }

    @Test
    fun withoutAPermissionTheSignerAsks() {
        providerAnswer = null
        val result = withSignerScreen({ signer.signEvent(pkg, """{"kind":27235}""", "the-id", me) }) { intent ->
            assertEquals("nostrsigner:{\"kind\":27235}", intent.dataString)
            assertEquals(pkg, intent.`package`)
            assertEquals("sign_event", intent.getStringExtra("type"))
            assertEquals("the-id", intent.getStringExtra("id"))
            assertEquals(me, intent.getStringExtra("current_user"))
            Activity.RESULT_OK to Intent().putExtra("result", "sig").putExtra("id", "the-id")
        }
        assertEquals("sig", result.signature)
    }

    @Test
    fun aRememberedPermissionEncryptsWithoutOpeningTheSigner() = runBlocking {
        Robolectric.setupContentProvider(FakeSignerProvider::class.java, "$pkg.NIP44_ENCRYPT")
        providerAnswer = MatrixCursor(arrayOf("result")).apply { addRow(arrayOf("sealed")) }
        assertEquals("sealed", signer.nip44(pkg, true, "plain", me, me, interactive = false))
        assertEquals(listOf("plain", me, me), providerAsked!!.toList())
        assertEquals(null, shadowOf(app).nextStartedActivity)
    }

    @Test(expected = SignerNeedsListener::class)
    fun inTheBackgroundTheSignerIsNeverOpened() {
        providerAnswer = null
        runBlocking { signer.nip44(pkg, false, "sealed", me, me, interactive = false) }
    }

    @Test
    fun withoutAPermissionTheSignerAsksToDecrypt() {
        providerAnswer = null
        val result = withSignerScreen({ signer.nip44(pkg, false, "sealed", me, me) }) { intent ->
            assertEquals("nostrsigner:sealed", intent.dataString)
            assertEquals(pkg, intent.`package`)
            assertEquals("nip44_decrypt", intent.getStringExtra("type"))
            assertEquals(me, intent.getStringExtra("pubkey"))
            assertEquals(me, intent.getStringExtra("current_user"))
            Activity.RESULT_OK to Intent().putExtra("result", "plain")
        }
        assertEquals("plain", result)
    }

    @Test
    fun loggingInGivesTheKeyAndThePackage() {
        val (pubkey, signerPackage) = withSignerScreen({ signer.login(ExternalSigner.PERMISSIONS) }) { intent ->
            assertEquals("nostrsigner:", intent.dataString)
            assertEquals("get_public_key", intent.getStringExtra("type"))
            // NIP-98, since stage 3 comments, likes, the mute list and reports; since 2026-11-04 what makes the
            // first sync key — never decryption (ADR 0021, 5).
            val signing = (ExternalSigner.SIGNED_KINDS + 30078).map { """{"type":"sign_event","kind":$it}""" } + """{"type":"nip44_encrypt"}"""
            assertEquals(signing.joinToString(",", "[", "]"), intent.getStringExtra("permissions"))
            // An npub, as some signers answer: taken as the hex key.
            Activity.RESULT_OK to Intent().putExtra("result", npub(me)).putExtra("package", pkg)
        }
        assertEquals(me, pubkey)
        assertEquals(pkg, signerPackage)
    }

    @Test
    fun noInTheSignerIsRejectedAndACrashIsNot() {
        val rejected = runCatching { withSignerScreen({ signer.login(ExternalSigner.PERMISSIONS) }) { Activity.RESULT_OK to Intent().putExtra("rejected", true) } }
        assertTrue("rejected: ${rejected.exceptionOrNull()}", rejected.exceptionOrNull() is SignerRejected)
        val crashed = runCatching { withSignerScreen({ signer.login(ExternalSigner.PERMISSIONS) }) { Activity.RESULT_CANCELED to null } }
        assertTrue("crashed: ${crashed.exceptionOrNull()}", crashed.exceptionOrNull() is space.sovereigncast.hopp.nostr.SignerUnavailable)
    }
}
