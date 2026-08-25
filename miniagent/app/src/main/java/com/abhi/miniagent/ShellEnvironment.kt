package com.abhi.miniagent

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class ShellEnvironment(private val context: Context) {

    private val rootfsDir = File(context.filesDir, "alpine")
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    // Alpine ships real armv7 (32-bit) packages - this is the key difference from
    // arm64-only binaries like Claude Code/Codex that can't run on a 32-bit OS at all.
    private val alpineUrl =
        "https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/armv7/alpine-minirootfs-3.20.3-armv7.tar.gz"

    fun isSetUp(): Boolean = File(rootfsDir, "bin/sh").exists()

    fun setup(log: (String) -> Unit) {
        if (isSetUp()) { log("[shell] Alpine rootfs already present."); return }
        rootfsDir.mkdirs()
        log("[shell] Downloading Alpine armv7 minirootfs...")
        val tmpTar = File(context.cacheDir, "alpine-rootfs.tar.gz")
        val req = Request.Builder().url(alpineUrl).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) { log("[shell] Download failed: ${resp.code}"); return }
            resp.body?.byteStream()?.use { input ->
                FileOutputStream(tmpTar).use { out -> input.copyTo(out) }
            }
        }
        log("[shell] Extracting...")
        GzipCompressorInputStream(tmpTar.inputStream()).use { gz ->
            TarArchiveInputStream(gz).use { tar ->
                var entry = tar.nextTarEntry
                while (entry != null) {
                    val outFile = File(rootfsDir, entry.name)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else if (entry.isSymbolicLink) {
                        // best-effort: symlinks inside the rootfs are common (busybox applets).
                        // Java has no portable symlink API pre-NIO-on-Android; skip rather than
                        // risk a broken partial file. This is a known v0.1-of-phase-2 gap.
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { out -> tar.copyTo(out) }
                        if ((entry.mode and 0b001_000_000) != 0 || (entry.mode and 0b001_000) != 0) {
                            outFile.setExecutable(true, false)
                        }
                        outFile.setReadable(true, false)
                    }
                    entry = tar.nextTarEntry
                }
            }
        }
        tmpTar.delete()
        log("[shell] Alpine rootfs ready at ${rootfsDir.absolutePath}")
    }

    /**
     * Runs a shell command inside the sandboxed rootfs via proot.
     * "-0" fakes uid 0 (root) *inside this sandbox only* - it does not root the device
     * and cannot touch anything outside rootfsDir plus the explicit binds below.
     */
    fun runCommand(cmd: String, timeoutSeconds: Long = 60): String {
        if (!isSetUp()) return "ERROR: shell environment not set up yet. Tap 'Setup shell environment' first."
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val prootBin = File(nativeLibDir, "libproot.so")
        val loaderBin = File(nativeLibDir, "libprootloader.so")
        if (!prootBin.exists()) return "ERROR: proot binary missing from nativeLibraryDir."

        val tmpDir = File(context.cacheDir, "proot_tmp").apply { mkdirs() }

        val pb = ProcessBuilder(
            prootBin.absolutePath,
            "-r", rootfsDir.absolutePath,
            "-w", "/root",
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-0",
            "/bin/sh", "-c", cmd
        )
        pb.environment()["PROOT_LOADER"] = loaderBin.absolutePath
        pb.environment()["PROOT_TMP_DIR"] = tmpDir.absolutePath
        pb.redirectErrorStream(true)

        return try {
            val process = pb.start()
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return "ERROR: command timed out after ${timeoutSeconds}s"
            }
            process.inputStream.bufferedReader().readText().take(4000)
        } catch (e: Exception) {
            "ERROR running shell command: ${e.message}"
        }
    }

    companion object {
        // Substrings that trigger a confirmation prompt instead of silent execution.
        // Deliberately coarse - false positives just mean one extra tap, false negatives
        // mean something destructive ran unattended, so this errs toward asking.
        private val RISKY_PATTERNS = listOf(
            "rm -rf", "rm -r ", "mkfs", "dd if=", "dd of=", ":(){", "chmod -r 777",
            "chmod 777 /", "> /dev/", "curl ", "wget ", "| sh", "| bash", "reboot",
            "shutdown", "kill -9", "iptables", "passwd", "su ", "sudo "
        )

        fun isRisky(cmd: String): Boolean {
            val lower = cmd.lowercase()
            return RISKY_PATTERNS.any { lower.contains(it) }
        }
    }
}
