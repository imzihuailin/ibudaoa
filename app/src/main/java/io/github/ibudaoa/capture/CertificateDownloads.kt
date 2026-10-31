package io.github.ibudaoa.capture

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.FileNotFoundException

/** Exports only the generated public CA; does not require a storage permission on API 29+. */
internal class CertificateDownloads(context: Context) : CertificateDownloadStorage {
    private val resolver = context.applicationContext.contentResolver
    private val prefs = context.applicationContext.getSharedPreferences("certificate-download", Context.MODE_PRIVATE)

    override fun reference(): CertificateDownload? {
        val uri = prefs.getString("uri", null) ?: return null
        val fingerprint = prefs.getString("fingerprint", null) ?: return null
        val target = ownedUri(uri) ?: return null
        if (!fingerprint.matches(Regex("[a-f0-9]{64}"))) return null
        // The user may rename or delete the exported file in Downloads between attempts.
        val name = try {
            resolver.query(target, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (_: SecurityException) { null }
        catch (_: FileNotFoundException) { null }
        return name?.let { CertificateDownload(uri, fingerprint, it) }
    }

    private fun ownedUri(value: String): Uri? = Uri.parse(value).takeIf {
        it.scheme == "content" && it.authority == MediaStore.AUTHORITY &&
            it.pathSegments.let { parts -> parts.size == 3 && parts[1] == "downloads" && parts[2].toLongOrNull() != null }
    }

    override fun read(uri: String): ByteArray? {
        val target = ownedUri(uri) ?: return null
        return try { resolver.openInputStream(target)?.use { it.readBytesBounded() } } catch (_: FileNotFoundException) { null }
        catch (_: SecurityException) { null }
    }

    override fun publish(bytes: ByteArray, fingerprint: String): CertificateDownload {
        // Recover only our own unfinished export after a process death, without touching other downloads.
        prefs.getString("pending_uri", null)?.takeIf { it != prefs.getString("uri", null) }?.let { pending ->
            ownedUri(pending)?.let { resolver.delete(it, null, null) }
            check(prefs.edit().remove("pending_uri").commit())
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME)
            put(MediaStore.Downloads.MIME_TYPE, "application/x-x509-ca-cert")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Certificate download unavailable")
        try {
            check(prefs.edit().putString("pending_uri", uri.toString()).commit())
            resolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: error("Certificate download unavailable")
            check(read(uri.toString())?.contentEquals(bytes) == true) { "Certificate download incomplete" }
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) == 1)
            // MediaStore may append a suffix on name collision; always show the actual picker name.
            val name = resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use {
                check(it.moveToFirst()); it.getString(0)
            } ?: error("Certificate download unavailable")
            return CertificateDownload(uri.toString(), fingerprint, name)
        } catch (error: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }

    override fun remember(file: CertificateDownload) {
        check(prefs.edit().putString("uri", file.uri).putString("fingerprint", file.fingerprint).putString("name", file.fileName).remove("pending_uri").commit())
    }
    override fun remove(uri: String) {
        ownedUri(uri)?.let { resolver.delete(it, null, null) }
        if (prefs.getString("pending_uri", null) == uri) check(prefs.edit().remove("pending_uri").commit())
    }

    private fun java.io.InputStream.readBytesBounded(): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = read(buffer)
            if (count < 0) return out.toByteArray()
            if (out.size() + count > 65_536) return null
            out.write(buffer, 0, count)
        }
    }

    companion object { const val FILE_NAME = "ibudaoa-连接证书.cer" }
}
