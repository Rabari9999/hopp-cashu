package space.sovereigncast.hopp.nostr

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
