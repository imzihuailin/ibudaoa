package io.github.ibudaoa.running

import org.junit.Assert.*
import org.junit.Test
import javax.crypto.spec.SecretKeySpec

class RunRecordVerificationTest {
    private val expected=ExpectedRunRecord(45,123,6,1_700_000_000)
    private fun detail(status:Int=1,extra:String="")="""{"status":1,"data":{"id":45,"uid":123,"game_id":6,"start_time":1700000000,"type":1,"record_status":$status$extra}}""".toByteArray()
    @Test fun `only an exactly bound campus run detail with explicit validity establishes success`() {
        val result=RunRecordVerifier.decode(detail(),expected)
        assertTrue(result.identityMatched);assertTrue(result.platformValid)
        assertFalse(result.toString().contains("1700000000"));assertEquals("ExpectedRunRecord(private)",expected.toString())
    }
    @Test fun `wrong record account zone start time or free run cannot establish validity`() {
        for(change in listOf("\"id\":45" to "\"id\":46","\"uid\":123" to "\"uid\":124",
            "\"game_id\":6" to "\"game_id\":7","1700000000" to "1700000001","\"type\":1" to "\"type\":2")) {
            val bytes=detail().toString(Charsets.UTF_8).replace(change.first,change.second).toByteArray()
            assertEquals(RunRecordVerdict.MISMATCH,RunRecordVerifier.decode(bytes,expected).verdict)
        }
    }
    @Test fun `pending unknown and conflicting reason never become valid`() {
        for(status in listOf(-1,0,3,10,99)) assertFalse(RunRecordVerifier.decode(detail(status),expected).platformValid)
        assertEquals(RunRecordVerdict.PENDING,RunRecordVerifier.decode(detail(10),expected).verdict)
        assertEquals(RunRecordVerdict.INVALID,RunRecordVerifier.decode(detail(2),expected).verdict)
        val conflict=RunRecordVerifier.decode(detail(extra=",\"record_failed_reason\":\"synthetic private reason\""),expected)
        assertEquals(RunRecordVerdict.UNKNOWN,conflict.verdict);assertTrue(conflict.hasFailureReason)
        assertFalse(conflict.toString().contains("synthetic private reason"))
    }
    @Test fun `failed envelopes missing status and malformed bodies never establish validity`() {
        for(bytes in listOf("{}".toByteArray(),"not json".toByteArray(),detail().toString(Charsets.UTF_8).replace("\"status\":1","\"status\":0").toByteArray(),
            detail().toString(Charsets.UTF_8).replace(",\"record_status\":1","").toByteArray()))
            assertFalse(RunRecordVerifier.decode(bytes,expected).platformValid)
    }
    @Test fun `registration retains the exact server pair and interval without printing proof`() {
        val proof="a".repeat(32)
        val registered=RegisteredRun.decode("""{"status":1,"data":{"timestamp":1700000000,"str":"$proof","location_septal":20}}""".toByteArray())
        assertEquals(1_700_000_000,registered.startTime);assertEquals(20,registered.locationIntervalSeconds)
        assertEquals(proof,registered.proofForFinish());assertFalse(registered.toString().contains(proof))
    }
    @Test fun `partial failed or malformed registration cannot start a local run`() {
        for(data in listOf("{}","""{"timestamp":0,"str":"${"a".repeat(32)}","location_septal":20}""",
            """{"timestamp":1700000000,"str":"bad","location_septal":20}""",
            """{"timestamp":1700000000,"str":"${"a".repeat(32)}","location_septal":0}"""))
            assertTrue(runCatching { RegisteredRun.decode("""{"status":1,"data":$data}""".toByteArray()) }.isFailure)
    }
    @Test fun `run checkpoint AEAD has fresh nonces and authenticates header body and key`() {
        val key=SecretKeySpec(ByteArray(32) { 7 },"AES");val plain="synthetic registration proof and task".toByteArray()
        val first=RunTaskJournalCipher.seal(plain,key);val second=RunTaskJournalCipher.seal(plain,key)
        assertFalse(first.contentEquals(second));assertArrayEquals(plain,RunTaskJournalCipher.open(first,key))
        assertFalse(first.toString(Charsets.UTF_8).contains("registration proof"))
        for(index in listOf(0,4,16,first.lastIndex)) {
            val tampered=first.clone();tampered[index]=(tampered[index].toInt() xor 1).toByte()
            assertTrue(runCatching { RunTaskJournalCipher.open(tampered,key) }.isFailure)
        }
        assertTrue(runCatching { RunTaskJournalCipher.open(first,SecretKeySpec(ByteArray(32) { 8 },"AES")) }.isFailure)
        assertTrue(runCatching { RunTaskJournalCipher.open(ByteArray(32),key) }.isFailure)
    }
    @Test fun `private result retains a short failure reason but hides credential like content`() {
        val normal=RunRecordVerifier.inspect(detail(2,",\"record_failed_reason\":\"打卡次数不足\""),expected)
        assertEquals("打卡次数不足",normal.failureReason)
        val sensitive=RunRecordVerifier.inspect(detail(2,",\"record_failed_reason\":\"https://example.test/private?token=synthetic\""),expected)
        assertFalse(sensitive.failureReason!!.contains("example.test"))
        assertFalse(sensitive.summary.toString().contains("synthetic"))
    }

}
