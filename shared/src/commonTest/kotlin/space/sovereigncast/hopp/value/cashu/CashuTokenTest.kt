package space.sovereigncast.hopp.value.cashu

import space.sovereigncast.hopp.crypto.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The NUT-00 token vectors (cashubtc/nuts, tests/00-tests.md). */
class CashuTokenTest {
    private val V3 =
        "cashuAeyJ0b2tlbiI6W3sibWludCI6Imh0dHBzOi8vODMzMy5zcGFjZTozMzM4IiwicHJvb2ZzIjpbeyJhbW91bnQiOjIsImlkIjoiMDA5YTFmMjkzMjUzZTQxZSIsInNlY3JldCI6IjQwNzkxNWJjMjEyYmU2MWE3N2UzZTZkMmFlYjRjNzI3OTgwYmRhNTFjZDA2YTZhZmMyOWUyODYxNzY4YTc4MzciLCJDIjoiMDJiYzkwOTc5OTdkODFhZmIyY2M3MzQ2YjVlNDM0NWE5MzQ2YmQyYTUwNmViNzk1ODU5OGE3MmYwY2Y4NTE2M2VhIn0seyJhbW91bnQiOjgsImlkIjoiMDA5YTFmMjkzMjUzZTQxZSIsInNlY3JldCI6ImZlMTUxMDkzMTRlNjFkNzc1NmIwZjhlZTBmMjNhNjI0YWNhYTNmNGUwNDJmNjE0MzNjNzI4YzcwNTdiOTMxYmUiLCJDIjoiMDI5ZThlNTA1MGI4OTBhN2Q2YzA5NjhkYjE2YmMxZDVkNWZhMDQwZWExZGUyODRmNmVjNjlkNjEyOTlmNjcxMDU5In1dfV0sInVuaXQiOiJzYXQiLCJtZW1vIjoiVGhhbmsgeW91LiJ9"
    private val BADPREFIX =
        "casshuAeyJ0b2tlbiI6W3sibWludCI6Imh0dHBzOi8vODMzMy5zcGFjZTozMzM4IiwicHJvb2ZzIjpbeyJhbW91bnQiOjIsImlkIjoiMDA5YTFmMjkzMjUzZTQxZSIsInNlY3JldCI6IjQwNzkxNWJjMjEyYmU2MWE3N2UzZTZkMmFlYjRjNzI3OTgwYmRhNTFjZDA2YTZhZmMyOWUyODYxNzY4YTc4MzciLCJDIjoiMDJiYzkwOTc5OTdkODFhZmIyY2M3MzQ2YjVlNDM0NWE5MzQ2YmQyYTUwNmViNzk1ODU5OGE3MmYwY2Y4NTE2M2VhIn0seyJhbW91bnQiOjgsImlkIjoiMDA5YTFmMjkzMjUzZTQxZSIsInNlY3JldCI6ImZlMTUxMDkzMTRlNjFkNzc1NmIwZjhlZTBmMjNhNjI0YWNhYTNmNGUwNDJmNjE0MzNjNzI4YzcwNTdiOTMxYmUiLCJDIjoiMDI5ZThlNTA1MGI4OTBhN2Q2YzA5NjhkYjE2YmMxZDVkNWZhMDQwZWExZGUyODRmNmVjNjlkNjEyOTlmNjcxMDU5In1dfV0sInVuaXQiOiJzYXQiLCJtZW1vIjoiVGhhbmsgeW91LiJ9"
    private val NOPREFIX =
        "eyJ0b2tlbiI6W3sibWludCI6Imh0dHBzOi8vODMzMy5zcGFjZTozMzM4IiwicHJvb2ZzIjpbeyJhbW91bnQiOjIsImlkIjoiMDA5YTFmMjkzMjUzZTQxZSIsInNlY3JldCI6IjQwNzkxNWJjMjEyYmU2MWE3N2UzZTZkMmFlYjRjNzI3OTgwYmRhNTFjZDA2YTZhZmMyOWUyODYxNzY4YTc4MzciLCJDIjoiMDJiYzkwOTc5OTdkODFhZmIyY2M3MzQ2YjVlNDM0NWE5MzQ2YmQyYTUwNmViNzk1ODU5OGE3MmYwY2Y4NTE2M2VhIn0seyJhbW91bnQiOjgsImlkIjoiMDA5YTFmMjkzMjUzZTQxZSIsInNlY3JldCI6ImZlMTUxMDkzMTRlNjFkNzc1NmIwZjhlZTBmMjNhNjI0YWNhYTNmNGUwNDJmNjE0MzNjNzI4YzcwNTdiOTMxYmUiLCJDIjoiMDI5ZThlNTA1MGI4OTBhN2Q2YzA5NjhkYjE2YmMxZDVkNWZhMDQwZWExZGUyODRmNmVjNjlkNjEyOTlmNjcxMDU5In1dfV0sInVuaXQiOiJzYXQiLCJtZW1vIjoiVGhhbmsgeW91LiJ9"
    private val V3PAD =
        "cashuAeyJ0b2tlbiI6W3sibWludCI6Imh0dHBzOi8vODMzMy5zcGFjZTozMzM4IiwicHJvb2ZzIjpbeyJhbW91bnQiOjIsImlkIjoiMDA5YTFmMjkzMjUzZTQxZSIsInNlY3JldCI6IjQwNzkxNWJjMjEyYmU2MWE3N2UzZTZkMmFlYjRjNzI3OTgwYmRhNTFjZDA2YTZhZmMyOWUyODYxNzY4YTc4MzciLCJDIjoiMDJiYzkwOTc5OTdkODFhZmIyY2M3MzQ2YjVlNDM0NWE5MzQ2YmQyYTUwNmViNzk1ODU5OGE3MmYwY2Y4NTE2M2VhIn0seyJhbW91bnQiOjgsImlkIjoiMDA5YTFmMjkzMjUzZTQxZSIsInNlY3JldCI6ImZlMTUxMDkzMTRlNjFkNzc1NmIwZjhlZTBmMjNhNjI0YWNhYTNmNGUwNDJmNjE0MzNjNzI4YzcwNTdiOTMxYmUiLCJDIjoiMDI5ZThlNTA1MGI4OTBhN2Q2YzA5NjhkYjE2YmMxZDVkNWZhMDQwZWExZGUyODRmNmVjNjlkNjEyOTlmNjcxMDU5In1dfV0sInVuaXQiOiJzYXQiLCJtZW1vIjoiVGhhbmsgeW91IHZlcnkgbXVjaC4ifQ=="
    private val V3NOPAD =
        "cashuAeyJ0b2tlbiI6W3sibWludCI6Imh0dHBzOi8vODMzMy5zcGFjZTozMzM4IiwicHJvb2ZzIjpbeyJhbW91bnQiOjIsImlkIjoiMDA5YTFmMjkzMjUzZTQxZSIsInNlY3JldCI6IjQwNzkxNWJjMjEyYmU2MWE3N2UzZTZkMmFlYjRjNzI3OTgwYmRhNTFjZDA2YTZhZmMyOWUyODYxNzY4YTc4MzciLCJDIjoiMDJiYzkwOTc5OTdkODFhZmIyY2M3MzQ2YjVlNDM0NWE5MzQ2YmQyYTUwNmViNzk1ODU5OGE3MmYwY2Y4NTE2M2VhIn0seyJhbW91bnQiOjgsImlkIjoiMDA5YTFmMjkzMjUzZTQxZSIsInNlY3JldCI6ImZlMTUxMDkzMTRlNjFkNzc1NmIwZjhlZTBmMjNhNjI0YWNhYTNmNGUwNDJmNjE0MzNjNzI4YzcwNTdiOTMxYmUiLCJDIjoiMDI5ZThlNTA1MGI4OTBhN2Q2YzA5NjhkYjE2YmMxZDVkNWZhMDQwZWExZGUyODRmNmVjNjlkNjEyOTlmNjcxMDU5In1dfV0sInVuaXQiOiJzYXQiLCJtZW1vIjoiVGhhbmsgeW91IHZlcnkgbXVjaC4ifQ"
    private val V4ONE =
        "cashuBpGF0gaJhaUgArSaMTR9YJmFwgaNhYQFhc3hAOWE2ZGJiODQ3YmQyMzJiYTc2ZGIwZGYxOTcyMTZiMjlkM2I4Y2MxNDU1M2NkMjc4MjdmYzFjYzk0MmZlZGI0ZWFjWCEDhhhUP_trhpXfStS6vN6So0qWvc2X3O4NfM-Y1HISZ5JhZGlUaGFuayB5b3VhbXVodHRwOi8vbG9jYWxob3N0OjMzMzhhdWNzYXQ="
    private val V4MULTI =
        "cashuBo2F0gqJhaUgA_9SLj17PgGFwgaNhYQFhc3hAYWNjMTI0MzVlN2I4NDg0YzNjZjE4NTAxNDkyMThhZjkwZjcxNmE1MmJmNGE1ZWQzNDdlNDhlY2MxM2Y3NzM4OGFjWCECRFODGd5IXVW-07KaZCvuWHk3WrnnpiDhHki6SCQh88-iYWlIAK0mjE0fWCZhcIKjYWECYXN4QDEzMjNkM2Q0NzA3YTU4YWQyZTIzYWRhNGU5ZjFmNDlmNWE1YjRhYzdiNzA4ZWIwZDYxZjczOGY0ODMwN2U4ZWVhY1ghAjRWqhENhLSsdHrr2Cw7AFrKUL9Ffr1XN6RBT6w659lNo2FhAWFzeEA1NmJjYmNiYjdjYzY0MDZiM2ZhNWQ1N2QyMTc0ZjRlZmY4YjQ0MDJiMTc2OTI2ZDNhNTdkM2MzZGNiYjU5ZDU3YWNYIQJzEpxXGeWZN5qXSmJjY8MzxWyvwObQGr5G1YCCgHicY2FtdWh0dHA6Ly9sb2NhbGhvc3Q6MzMzOGF1Y3NhdA"
    private val RAW =
        "6372617742a4617481a261694800ad268c4d1f5826617081a3616101617378403961366462623834376264323332626137366462306466313937323136623239643362386363313435353363643237383237666331636339343266656462346561635821038618543ffb6b8695df4ad4babcde92a34a96bdcd97dcee0d7ccf98d4721267926164695468616e6b20796f75616d75687474703a2f2f6c6f63616c686f73743a33333338617563736174"

    private val v3Proofs = listOf(
        Proof(2, "009a1f293253e41e", "407915bc212be61a77e3e6d2aeb4c727980bda51cd06a6afc29e2861768a7837", "02bc9097997d81afb2cc7346b5e4345a9346bd2a506eb7958598a72f0cf85163ea"),
        Proof(8, "009a1f293253e41e", "fe15109314e61d7756b0f8ee0f23a624acaa3f4e042f61433c728c7057b931be", "029e8e5050b890a7d6c0968db16bc1d5d5fa040ea1de284f6ec69d61299f671059"),
    )

    private val v4One = CashuToken(
        mint = "http://localhost:3338",
        proofs = listOf(Proof(1, "00ad268c4d1f5826", "9a6dbb847bd232ba76db0df197216b29d3b8cc14553cd27827fc1cc942fedb4e", "038618543ffb6b8695df4ad4babcde92a34a96bdcd97dcee0d7ccf98d472126792")),
        unit = "sat",
        memo = "Thank you",
    )

    @Test
    fun v3RoundTrip() {
        val token = CashuTokens.decode(V3)
        assertEquals(CashuToken("https://8333.space:3338", v3Proofs, "sat", "Thank you."), token)
        assertEquals(10, token.amount)
        assertEquals(V3.trimEnd('='), CashuTokens.encodeV3(token))
    }

    @Test
    fun v3WithAndWithoutPadding() {
        assertEquals(CashuTokens.decode(V3PAD), CashuTokens.decode(V3NOPAD))
        assertEquals("Thank you very much.", CashuTokens.decode(V3PAD).memo)
    }

    @Test
    fun v3Malformed() {
        assertFailsWith<IllegalArgumentException> { CashuTokens.decode(BADPREFIX) }
        assertFailsWith<IllegalArgumentException> { CashuTokens.decode(NOPREFIX) }
    }

    @Test
    fun v4SingleKeyset() {
        assertEquals(v4One, CashuTokens.decode(V4ONE))
        assertEquals(V4ONE.trimEnd('='), CashuTokens.encodeV4(v4One))
    }

    @Test
    fun v4RawBytes() {
        assertEquals(RAW.removePrefix("6372617742"), Cbor.encode(CashuTokens.v4Map(v4One)).toHex())
    }

    @Test
    fun v4MultipleKeysets() {
        val token = CashuTokens.decode(V4MULTI)
        assertEquals("http://localhost:3338", token.mint)
        assertNull(token.memo)
        assertEquals(listOf("00ffd48b8f5ecf80", "00ad268c4d1f5826", "00ad268c4d1f5826"), token.proofs.map { it.id })
        assertEquals(listOf(1L, 2L, 1L), token.proofs.map { it.amount })
        assertEquals("0273129c5719e599379a974a626363c333c56cafc0e6d01abe46d5808280789c63", token.proofs[2].c)
        assertEquals(V4MULTI, CashuTokens.encodeV4(token))
    }

    @Test
    fun v4KeepsDleqAndWitness() {
        val dleq = ProofDleq("b31e58ac6527f34975ffab13e70a48b6d2b0d35abc4b03f0151f09ee1a9763d4", "8fbae004c59e754d71df67e392b6ae4e29293113ddc2ec86592a0431d16306d8", "a6d13fcd7a18442e6076f5e1e7c887ad5de40a019824bdfa9fe740d302e8d861")
        val token = v4One.copy(proofs = listOf(v4One.proofs.single().copy(dleq = dleq, witness = "{}")))
        assertEquals(token, CashuTokens.decode(CashuTokens.encodeV4(token)))
    }

    @Test
    fun rejectsNonsense() {
        for (bad in listOf("", "cashu", "cashuC" + V4ONE.substring(6), "cashuB!!!!", "cashuBoA", "cashuA" + "e30")) {
            assertFailsWith<IllegalArgumentException>(bad) { CashuTokens.decode(bad) }
        }
    }

    @Test
    fun cborScalars() {
        assertEquals("1903e8", Cbor.encode(1000L).toHex())
        assertEquals("3863", Cbor.encode(-100L).toHex())
        assertEquals(-100L, Cbor.decode(Cbor.encode(-100L)))
        assertEquals(4294967296L, Cbor.decode(Cbor.encode(4294967296L)))
        assertFailsWith<IllegalArgumentException> { Cbor.decode(byteArrayOf(0x5a, 0x7f, 0, 0, 0)) } // 2 GB byte string, 0 bytes given
        assertFailsWith<IllegalArgumentException> { Cbor.decode(byteArrayOf(0x9f.toByte())) } // indefinite array
    }
}
