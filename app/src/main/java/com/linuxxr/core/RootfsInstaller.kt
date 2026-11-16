package com.linuxxr.core

import android.system.Os
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.tukaani.xz.XZInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Downloads, verifies and unpacks the pinned Linux rootfs (see [Rootfs]). */
class RootfsInstaller(private val guest: Guest, private val cacheDir: File) {

    fun interface Progress {
        fun report(message: String, fraction: Float?)
    }

    fun install(progress: Progress) {
        val archive = File(cacheDir, "rootfs.tar.xz")
        if (!archive.exists() || sha256(archive) != Rootfs.SHA256) {
            download(archive, progress)
            progress.report("Verificando SHA-256…", null)
            val actual = sha256(archive)
            if (actual != Rootfs.SHA256) {
                archive.delete()
                throw IOException("SHA-256 del rootfs no coincide: $actual")
            }
        }
        val staging = File(guest.rootfs.parentFile, "rootfs.partial")
        forceDelete(staging)
        staging.mkdirs()
        extract(archive, staging, progress)
        forceDelete(guest.rootfs)
        if (!staging.renameTo(guest.rootfs)) throw IOException("No se pudo activar el rootfs")
        guest.markInstalled()
        archive.delete()
        progress.report("Rootfs instalado", 1f)
    }

    private fun download(dest: File, progress: Progress) {
        val partial = File(dest.path + ".part")
        var conn = URL(Rootfs.URL).openConnection() as HttpURLConnection
        if (partial.exists()) conn.setRequestProperty("Range", "bytes=${partial.length()}-")
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        val resumed = conn.responseCode == HttpURLConnection.HTTP_PARTIAL
        if (!resumed && conn.responseCode != HttpURLConnection.HTTP_OK) {
            throw IOException("Descarga fallida: HTTP ${conn.responseCode}")
        }
        val offset = if (resumed) partial.length() else 0L
        val total = conn.contentLengthLong.takeIf { it > 0 }?.plus(offset)
        conn.inputStream.use { input ->
            java.io.FileOutputStream(partial, resumed).use { out ->
                val buf = ByteArray(1 shl 16)
                var done = offset
                var lastReport = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (done - lastReport > (1 shl 20)) {
                        lastReport = done
                        progress.report("Descargando Debian… ${done shr 20} MiB", total?.let { done.toFloat() / it })
                    }
                }
            }
        }
        conn.disconnect()
        if (!partial.renameTo(dest)) throw IOException("No se pudo guardar la descarga")
    }

    private fun extract(archive: File, root: File, progress: Progress) {
        val rootPath = root.canonicalPath + File.separator
        val dirModes = mutableListOf<Pair<File, Int>>()
        var count = 0
        TarArchiveInputStream(XZInputStream(archive.inputStream().buffered(1 shl 16))).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                val rel = stripTop(entry.name) ?: continue
                val out = File(root, rel)
                // Check the parent only: the entry itself may legitimately be an absolute symlink.
                val parent = out.parentFile!!.canonicalPath + File.separator
                if (!parent.startsWith(rootPath)) throw IOException("Entrada fuera del rootfs: ${entry.name}")
                // Keep owner rwx so the app's real uid can still modify everything under PRoot.
                val mode = entry.mode and 0b111_111_111
                when {
                    entry.isDirectory -> {
                        out.mkdirs()
                        dirModes += out to (mode or 0b111_000_000)
                    }
                    entry.isSymbolicLink -> {
                        out.parentFile?.mkdirs()
                        out.delete()
                        Os.symlink(entry.linkName, out.path)
                    }
                    entry.isLink -> { // hard link: Android forbids link(2) for apps, copy instead
                        val target = File(root, stripTop(entry.linkName) ?: continue)
                        out.parentFile?.mkdirs()
                        out.delete()
                        target.copyTo(out, overwrite = true)
                        Os.chmod(out.path, mode or 0b110_000_000)
                    }
                    entry.isFile -> {
                        out.parentFile?.mkdirs()
                        out.delete() // never write through a symlink left by an earlier entry
                        out.outputStream().use { tar.copyTo(it, 1 shl 16) }
                        Os.chmod(out.path, mode or 0b110_000_000)
                    }
                    else -> Unit // device nodes, fifos: provided by the /dev bind
                }
                if (++count % 500 == 0) progress.report("Extrayendo… $count archivos", null)
            }
        }
        for ((dir, mode) in dirModes.asReversed()) Os.chmod(dir.path, mode)
    }

    /**
     * Deletes a guest tree even where packages left directories read-only or unreadable to the
     * app's real uid (e.g. 0555 or 0000 dirs): restore owner rwx on the way down.
     */
    private fun forceDelete(dir: File) {
        if (!dir.exists() && !java.nio.file.Files.isSymbolicLink(dir.toPath())) return
        fun walk(f: File) {
            if (java.nio.file.Files.isSymbolicLink(f.toPath()) || !f.isDirectory) {
                f.delete()
                return
            }
            runCatching { Os.chmod(f.path, 0b111_000_000) }
            f.listFiles()?.forEach(::walk)
            f.delete()
        }
        walk(dir)
        if (dir.exists()) throw IOException("No se pudo borrar ${dir.path}")
    }

    private fun stripTop(name: String): String? {
        val clean = name.removePrefix("./")
        val prefix = Rootfs.TOP_DIR + "/"
        if (!clean.startsWith(prefix)) return null
        return clean.removePrefix(prefix).trimEnd('/').ifEmpty { null }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
