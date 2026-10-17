package space.sovereigncast.hopp.nostr

import space.sovereigncast.hopp.crypto.hexToBytes
import space.sovereigncast.hopp.crypto.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EventTest {
    @Test
    fun signedEventsVerifyAndTamperingIsDetected() {
        val keys = KeyPair.generate()
        val event = Event.sign(keys, 1, "hello \"nostr\"\nzweite Zeile – ü", listOf(listOf("p", "abc")), 1_700_000_000)
        assertTrue(event.isValid())
        assertFalse(event.copy(content = "changed").isValid())
        assertFalse(event.copy(sig = event.sig.reversed()).isValid())
    }

    /** Code review 2026-10-17, M12: what a relay sends is checked, never trusted to be hex. */
    @Test
    fun aKeyOrSignatureThatIsNotLowercaseHexIsInvalidNotAnError() {
        val keys = KeyPair.generate()
        val event = Event.sign(keys, 1, "hi", emptyList(), 1_700_000_000)
        val notHex = "zz".repeat(32)
        assertFalse(event.copy(pubkey = notHex, id = Event.computeId(notHex, event.created_at, 1, emptyList(), "hi")).isValid())
        assertFalse(event.copy(sig = "zz".repeat(64)).isValid())
        // Signed for real, but over an uppercase key: it would not match the mute list.
        val upper = keys.publicKeyHex.uppercase()
        val id = Event.computeId(upper, event.created_at, 1, emptyList(), "hi")
        assertFalse(event.copy(pubkey = upper, id = id, sig = keys.signSchnorr(id.hexToBytes()).toHex()).isValid())
    }

    @Test
    fun theIdFollowsNip01Serialisation() {
        // Escaping: quote, backslash and newline escaped; non-ASCII written as UTF-8.
        val id = Event.computeId(
            "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798", 1, 1, emptyList(), "a\"b\\c\nd ü",
        )
        val expected = space.sovereigncast.hopp.crypto.Sha256.digest(
            "[0,\"79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798\",1,1,[],\"a\\\"b\\\\c\\nd ü\"]".encodeToByteArray(),
        )
        assertEquals(expected.toHex(), id)
    }
}
