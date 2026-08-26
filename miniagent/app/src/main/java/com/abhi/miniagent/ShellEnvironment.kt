package com.abhi.miniagent

import android.content.Context
import android.os.Build
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class ShellEnvironment(private val context: Context) {

    private val rootfsDir = File(context.filesDir, "alpine")

    // Alpine is now bundled into the APK at build time (see .github/workflows/build.yaml)
    // instead of downloaded at runtime - no network dependency, no flaky CDN download,
    // and both ABIs ship in the same APK so this picks whichever matches the device.
    private fun assetName(): String =
        if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) "alpine-arm64.tar.gz" else "alpine-armv7.tar.gz"

    fun isSetUp(): Boolean {
        // lstat, not File.exists() - /bin/sh is a symlink (often to an absolute path like
        // /bin/busybox), and File.exists() follows the link and stats the *target* against
        // the real device filesystem, where that absolute path doesn't exist. That made
        // this always report false even after a correct extraction. lstat only checks that
        // the symlink entry itself is on disk, which is what we actually want here - proot
        // resolves the real target correctly at execution time via its virtualized root.
        return try {
            android.system.Os.lstat(File(rootfsDir, "bin/sh").absolutePath)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun setup(log: (String) -> Unit) {
        if (isSetUp()) { log("[shell] Alpine rootfs already present."); return }
        rootfsDir.mkdirs()
        val name = assetName()
        log("[shell] Extracting bundled Alpine ($name)...")
        try {
            context.assets.open(name).use { assetStream ->
                GzipCompressorInputStream(assetStream).use { gz ->
                    TarArchiveInputStream(gz).use { tar ->
                        var entry = tar.nextTarEntry
                        while (entry != null) {
                            val outFile = File(rootfsDir, entry.name)
                            if (entry.isDirectory) {
                                outFile.mkdirs()
                            } else if (entry.isSymbolicLink) {
                                outFile.parentFile?.mkdirs()
                                try {
                                    android.system.Os.symlink(entry.linkName, outFile.absolutePath)
                                } catch (e: Exception) {
                                    // Ignore individual broken/unsupported symlinks rather than
                                    // aborting the whole extraction over one bad entry.
                                }
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
            }
            log("[shell] Alpine rootfs ready at ${rootfsDir.absolutePath}")
        } catch (e: Exception) {
            log("[shell] ERROR extracting bundled Alpine: ${e.message}. " +
                "(Was $name actually packaged into this build? Check the CI workflow.)")
        }
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
