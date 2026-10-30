package io.github.ibudaoa.capture

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Implementations accept plaintext only at this internal boundary, never public UI state. */
internal interface AccountSessionStorage {
    fun load():ByteArray?
    fun save(plain:ByteArray)
    fun delete()
}
internal class AccountStorageInvalid:Exception("Saved account cannot be recovered")

/** Versioned AEAD envelope. File/header tampering is authenticated; never falls back to plaintext. */
internal object AccountStorageCipher {
    const val MAX_PLAIN=64*1024
    private val aad="ibudaoa.account.v1".toByteArray(Charsets.US_ASCII)
    private const val MAGIC=0x49424131
    fun encrypt(plain:ByteArray,key:SecretKey):ByteArray {
        require(plain.size in 1..MAX_PLAIN)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,key)
        val iv=cipher.iv
        require(iv.size==12)
        cipher.updateAAD(aad)
        val encrypted=cipher.doFinal(plain)
        return ByteBuffer.allocate(4+12+encrypted.size).putInt(MAGIC).put(iv).put(encrypted).array()
    }
    fun decrypt(sealed:ByteArray,key:SecretKey):ByteArray {
        try {
            require(sealed.size in (4+12+16+1)..(4+12+16+MAX_PLAIN))
            val buffer=ByteBuffer.wrap(sealed)
            require(buffer.int==MAGIC)
            val iv=ByteArray(12);buffer.get(iv)
            val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,iv));cipher.updateAAD(aad)
            return cipher.doFinal(sealed,16,sealed.size-16)
        } catch(_:GeneralSecurityException) { throw AccountStorageInvalid() }
        catch(_:IllegalArgumentException) { throw AccountStorageInvalid() }
    }
}

/** Device-local, backup-excluded ciphertext and non-exportable Android Keystore key. */
internal class AndroidAccountStorage(context:Context):AccountSessionStorage {
    private val file=AtomicFile(File(context.applicationContext.noBackupFilesDir,"account-session-v1.aead"))
    private fun key(create:Boolean):SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing=store.getKey(ALIAS,null)
        if(existing is SecretKey) return existing
        if(!create) throw AccountStorageInvalid()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    @Synchronized override fun load():ByteArray? {
        if(!file.baseFile.exists() && !File(file.baseFile.path+".bak").exists()) return null
        val sealed=file.openRead().use { input ->
            val out=SecretByteOutput()
            val buffer=ByteArray(4096)
            try {
                while(true) {
                    val count=input.read(buffer)
                    if(count<0) break
                    if(out.size()+count>AccountStorageCipher.MAX_PLAIN+32) throw AccountStorageInvalid()
                    out.write(buffer,0,count)
                }
                out.toByteArray()
            } finally { buffer.fill(0);out.wipe() }
        }
        return try { AccountStorageCipher.decrypt(sealed,key(false)) } finally { sealed.fill(0) }
    }
    @Synchronized override fun save(plain:ByteArray) {
        val sealed=AccountStorageCipher.encrypt(plain,key(true))
        var output:java.io.FileOutputStream?=null
        try {
            output=file.startWrite();output.write(sealed);file.finishWrite(output);output=null
        } catch(e:IOException) { output?.let(file::failWrite);throw e }
        finally { sealed.fill(0) }
    }
    @Synchronized override fun delete() {
        file.delete()
        if(file.baseFile.exists() || File(file.baseFile.path+".bak").exists() || File(file.baseFile.path+".new").exists())
            throw IOException("Account removal not completed")
    }
    companion object { private const val ALIAS="ibudaoa.account.aes.v1" }
}
