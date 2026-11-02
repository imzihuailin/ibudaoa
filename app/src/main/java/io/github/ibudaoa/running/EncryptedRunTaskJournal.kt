package io.github.ibudaoa.running

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Separate key, authenticated domain and backup-excluded file from the account vault. */
internal object RunTaskJournalCipher {
    const val MAX_BYTES=8_388_608
    private const val MAGIC=0x49425231
    private val aad="ibudaoa.run-task.v1".toByteArray(Charsets.US_ASCII)
    fun seal(plain:ByteArray,key:SecretKey):ByteArray {
        require(plain.size in 1..MAX_BYTES)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,key);cipher.updateAAD(aad)
        val iv=cipher.iv;require(iv.size==12)
        val payload=cipher.doFinal(plain)
        return ByteBuffer.allocate(16+payload.size).putInt(MAGIC).put(iv).put(payload).array()
    }
    fun open(sealed:ByteArray,key:SecretKey):ByteArray {
        try {
            require(sealed.size in 33..MAX_BYTES+32)
            val buffer=ByteBuffer.wrap(sealed);require(buffer.int==MAGIC)
            val iv=ByteArray(12);buffer.get(iv)
            val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,iv));cipher.updateAAD(aad)
            return cipher.doFinal(sealed,16,sealed.size-16)
        } catch(_:Exception) { throw IllegalStateException("Run checkpoint cannot be recovered") }
    }
}
internal class AndroidRunTaskJournal(private val context:Context):RunTaskJournal {
    private val file=AtomicFile(File(context.applicationContext.noBackupFilesDir,"run-task-v1.aead"))
    private fun key(create:Boolean):SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS,null) as? SecretKey)?.let { return it }
        check(create) { "Run checkpoint key unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    @Synchronized override fun load():ByteArray? {
        if(!file.baseFile.exists() && !File(file.baseFile.path+".bak").exists()) return null
        val sealed=file.openRead().use { input ->
            val buffer=ByteArray(RunTaskJournalCipher.MAX_BYTES+33)
            try {
                var offset=0
                while(offset<buffer.size) {
                    val count=input.read(buffer,offset,buffer.size-offset)
                    if(count<0) break
                    offset+=count
                }
                check(offset<=RunTaskJournalCipher.MAX_BYTES+32) { "Run checkpoint too large" }
                buffer.copyOf(offset)
            } finally { buffer.fill(0) }
        }
        return try { RunTaskJournalCipher.open(sealed,key(false)) } finally { sealed.fill(0) }
    }
    @Synchronized override fun save(bytes:ByteArray) {
        val sealed=RunTaskJournalCipher.seal(bytes,key(true))
        var output:java.io.FileOutputStream?=null
        try {
            output=file.startWrite();output.write(sealed);file.finishWrite(output);output=null
        } catch(e:Exception) { output?.let(file::failWrite);throw e }
        finally { sealed.fill(0) }
    }
    @Synchronized override fun clear() {
        file.delete()
        check(!file.baseFile.exists() && !File(file.baseFile.path+".bak").exists() && !File(file.baseFile.path+".new").exists()) {
            "Run checkpoint removal incomplete"
        }
    }
    @Synchronized override fun archiveResolved(bytes: ByteArray) {
        if (!DiagnosticMode.enabled(context)) { DiagnosticMode.clear(context); return }
        val root=kotlinx.serialization.json.Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as kotlinx.serialization.json.JsonObject
        val id=java.util.UUID.fromString((root["task"] as kotlinx.serialization.json.JsonPrimitive).content).toString()
        val dir=file.baseFile.parentFile!!
        val destination=AtomicFile(File(dir,"run-result-$id.aead"))
        val sealed=RunTaskJournalCipher.seal(bytes,key(false))
        var output:java.io.FileOutputStream?=null
        try { output=destination.startWrite();output.write(sealed);destination.finishWrite(output);output=null }
        catch(e:Exception) { output?.let(destination::failWrite);throw e }
        finally { sealed.fill(0) }
        val archives=dir.listFiles().orEmpty().filter { it.name.matches(Regex("run-result-[a-f0-9-]{36}\\.aead")) }.sortedByDescending { it.lastModified() }
        archives.filter { it.name != destination.baseFile.name }.drop(2).forEach { check(it.delete() || !it.exists()) { "Result archive rotation failed" } }
    }
    companion object { private const val ALIAS="ibudaoa.run-task.aes.v1" }
}
