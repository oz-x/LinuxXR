package com.linuxxr.core

import android.content.Context
import android.net.ConnectivityManager
import android.system.Os
import java.io.File
import java.net.Inet4Address

/**
 * Pinned Debian 13 "trixie" rootfs (proot-distro build, aarch64), with XFCE on X11
 * (see provision.sh).
 */
object Rootfs {
    const val ID = "debian-trixie"
    const val URL = "https://github.com/termux/proot-distro/releases/download/v4.29.0/debian-trixie-aarch64-pd-v4.29.0.tar.xz"
    const val SHA256 = "3834a11cbc6496935760bdc20cca7e2c25724d0cd8f5e4926da8fd5ca1857918"
    const val TOP_DIR = "debian-trixie-aarch64"
}

/** Layout of LinuxXR state inside the app's private storage, and the PRoot launcher. */
class Guest(private val context: Context) {
    private val base: File = context.filesDir
    val rootfs = File(base, "rootfs")
    private val prootTmp = File(base, "proot-tmp")
    private val sysdata = File(base, "sysdata")
    private val shm = File(base, "shm")
    private val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)

    private val rootfsId = File(base, "rootfs.id")

    /** Installed and of the pinned distro (an older Arch rootfs counts as not installed). */
    val isInstalled: Boolean
        get() = File(rootfs, "usr/bin/bash").exists() && rootfsId.isFile && rootfsId.readText() == Rootfs.ID

    fun markInstalled() = rootfsId.writeText(Rootfs.ID)

    /** Host path of the VNC socket that Xvnc for display [n] creates at /run/linuxxr/display-n.sock. */
    fun displaySocket(n: Int) = File(rootfs, "run/linuxxr/display-$n.sock")

    /** True once the user granted "All files access" (needed to see the headset's storage). */
    val hasSharedStorage: Boolean
        get() = android.os.Environment.isExternalStorageManager() && File(SHARED_STORAGE).canRead()

    val agentToken: String?
        get() = File(rootfs, "etc/linuxxr/agent-token").takeIf { it.canRead() }?.readText()?.trim()

    /** Refreshes host-derived files and LinuxXR's guest scripts. Safe to call on every start. */
    fun prepare() {
        // Packages may leave / at mode 0555; the real owner (this app) needs to write there.
        Os.chmod(rootfs.path, 0b111_101_101)
        // .l2s must exist for --link2symlink (hard link emulation) to work.
        listOf(prootTmp, sysdata, shm, File(rootfs, ".l2s"), File(rootfs, "tmp"), File(rootfs, "run/linuxxr"), File(rootfs, "root"))
            .forEach { it.mkdirs() }
        repairHardLinkTargets()
        writeNetworkConfig()
        installGuestScripts()
        writeFakeProcFiles()
    }

    /**
     * Earlier versions gave PRoot a non-canonical L2S dir, so emulated hard links (gcc, perl, git…)
     * point at /data/user/0/… and are dangling inside the guest. Rewrite them once.
     */
    private fun repairHardLinkTargets() {
        val marker = File(base, ".l2s-canonical-v1")
        if (marker.exists() || !rootfs.isDirectory) return
        val wrong = File(rootfs, ".l2s").absolutePath + "/"
        val right = File(rootfs.canonicalFile, ".l2s").path + "/"
        if (wrong != right) {
            // walkFileTree (not Files.walk) so unreadable guest dirs are skipped instead of aborting.
            java.nio.file.Files.walkFileTree(rootfs.toPath(), object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                override fun visitFile(file: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                    if (attrs.isSymbolicLink) {
                        val target = runCatching { java.nio.file.Files.readSymbolicLink(file).toString() }.getOrNull()
                        if (target != null && target.startsWith(wrong)) {
                            java.nio.file.Files.delete(file)
                            Os.symlink(right + target.removePrefix(wrong), file.toString())
                        }
                    }
                    return java.nio.file.FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: java.nio.file.Path, exc: java.io.IOException) =
                    java.nio.file.FileVisitResult.CONTINUE
            })
        }
        marker.createNewFile()
    }

    private fun writeNetworkConfig() {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val dns = cm?.activeNetwork?.let { cm.getLinkProperties(it) }?.dnsServers
            ?.filterIsInstance<Inet4Address>()?.mapNotNull { it.hostAddress }
            .orEmpty()
            .ifEmpty { listOf("1.1.1.1", "8.8.8.8") }
        replaceFile(File(rootfs, "etc/resolv.conf"), dns.joinToString("") { "nameserver $it\n" })
        replaceFile(File(rootfs, "etc/hosts"), "127.0.0.1 localhost\n::1 localhost ip6-localhost\n127.0.1.1 linuxxr\n")
        replaceFile(File(rootfs, "etc/hostname"), "linuxxr\n")
    }

    private fun installGuestScripts() {
        val targets = mapOf(
            "provision.sh" to "usr/local/lib/linuxxr/provision.sh",
            "session.sh" to "usr/local/lib/linuxxr/session.sh",
            "agentd.py" to "usr/local/lib/linuxxr/agentd.py",
            "linuxxr" to "usr/local/bin/linuxxr",
            "linuxxr-gpu.sh" to "etc/profile.d/linuxxr-gpu.sh",
        )
        for ((asset, target) in targets) {
            val dest = File(rootfs, target)
            dest.parentFile?.mkdirs()
            val bytes = context.assets.open("guest/$asset").use { it.readBytes() }
            replaceFile(dest, bytes)
            Os.chmod(dest.path, if (target.startsWith("etc/")) 0b110_100_100 else 0b111_101_101)
        }
    }

    /** Android hides several /proc files from apps; tools like top, node and apt read them. */
    private fun writeFakeProcFiles() {
        val cpus = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val cpuRow = "120 0 300 12000 20 10 5 8 0 0"
        val stat = buildString {
            append("cpu  ").append(cpuRow.split(' ').joinToString(" ") { (it.toLong() * cpus).toString() }).append('\n')
            repeat(cpus) { append("cpu$it ").append(cpuRow).append('\n') }
            append("intr 0\nctxt 0\nbtime 1700000000\nprocesses 100\nprocs_running 1\nprocs_blocked 0\n")
        }
        val fakes = mapOf(
            "loadavg" to "0.12 0.07 0.02 2/165 765\n",
            "stat" to stat,
            "uptime" to "124.08 ${120 * cpus}.00\n",
            "version" to "Linux version $KERNEL_RELEASE (linuxxr@android) #1 SMP PREEMPT\n",
            "vmstat" to "nr_free_pages 1743136\npgpgin 0\npgpgout 0\npswpin 0\npswpout 0\n",
            "cap_last_cap" to "40\n",
            "max_user_watches" to "65536\n",
        )
        for ((name, content) in fakes) replaceFile(File(sysdata, name), content)
    }

    private fun procBinds(): List<String> {
        val map = mapOf(
            "loadavg" to "/proc/loadavg",
            "stat" to "/proc/stat",
            "uptime" to "/proc/uptime",
            "version" to "/proc/version",
            "vmstat" to "/proc/vmstat",
            "cap_last_cap" to "/proc/sys/kernel/cap_last_cap",
            "max_user_watches" to "/proc/sys/fs/inotify/max_user_watches",
        )
        return map.filter { (_, real) -> !isReadable(real) }
            .map { (name, real) -> "--bind=${File(sysdata, name).path}:$real" }
    }

    private fun isReadable(path: String): Boolean = try {
        File(path).inputStream().use { it.read() }
        true
    } catch (e: Exception) {
        false
    }

    /** Builds a process that runs [command] as root inside the Linux guest. */
    fun process(vararg command: String): ProcessBuilder {
        val proot = File(nativeLibDir, "libproot.so").path
        val args = mutableListOf(
            proot,
            "--kill-on-exit",
            "--link2symlink",
            "--sysvipc",
            "--ashmem-memfd",
            "-L",
            "--kernel-release=$KERNEL_RELEASE",
            "--root-id",
            "--rootfs=${rootfs.canonicalPath}",
            "--cwd=/root",
            "--bind=/dev",
            "--bind=/proc",
            "--bind=/sys",
            "--bind=/dev/urandom:/dev/random",
            "--bind=/proc/self/fd:/dev/fd",
            "--bind=/proc/self/fd/0:/dev/stdin",
            "--bind=/proc/self/fd/1:/dev/stdout",
            "--bind=/proc/self/fd/2:/dev/stderr",
            "--bind=${shm.path}:/dev/shm",
        )
        args += procBinds()
        // Quest shared storage (Downloads, Pictures, Oculus/Screenshots…) as ~/Quest and /sdcard.
        if (hasSharedStorage) {
            args += "--bind=$SHARED_STORAGE:/root/Quest"
            args += "--bind=$SHARED_STORAGE:/sdcard"
        }
        args += listOf(
            "/usr/bin/env", "-i",
            "HOME=/root", "USER=root", "LOGNAME=root", "SHELL=/bin/bash",
            "LANG=C.UTF-8", "TERM=xterm-256color", "TMPDIR=/tmp",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/bin:/usr/sbin",
        )
        args += command
        return ProcessBuilder(args).redirectErrorStream(true).apply {
            environment().apply {
                clear()
                put("PROOT_LOADER", File(nativeLibDir, "libproot-loader.so").path)
                put("PROOT_TMP_DIR", prootTmp.path)
                // Canonical (/data/data/…): PRoot only maps hard-link symlinks back into the guest
                // when their target starts with the canonical rootfs path, not /data/user/0/….
                put("PROOT_L2S_DIR", File(rootfs.canonicalFile, ".l2s").path)
                put("PATH", "/system/bin")
            }
        }
    }

    /** Replaces a guest file without following a symlink the guest may have planted there. */
    private fun replaceFile(file: File, content: String) = replaceFile(file, content.toByteArray())

    private fun replaceFile(file: File, content: ByteArray) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, ".${file.name}.linuxxr-tmp")
        tmp.delete()
        tmp.writeBytes(content)
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "No se pudo escribir $file" }
        }
    }

    companion object {
        const val KERNEL_RELEASE = "6.17.0-linuxxr"
        const val SHARED_STORAGE = "/storage/emulated/0"
    }
}
