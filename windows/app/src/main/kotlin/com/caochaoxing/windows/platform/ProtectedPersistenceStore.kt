// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.windows.platform

import com.caochaoxing.core.model.ChaoxingModelDefaults
import com.caochaoxing.core.store.ChaoxingAppStateSnapshot
import com.caochaoxing.core.store.FilePersistenceStore
import com.caochaoxing.core.store.PersistenceStore
import com.sun.jna.platform.win32.Crypt32Util
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption

/**
 * Abstraction for platform-backed byte protection (Windows DPAPI on Windows, injectable in tests).
 */
interface DataProtectionBackend {
    val label: String
    fun protect(plaintext: ByteArray): ByteArray
    fun unprotect(ciphertext: ByteArray): ByteArray
}

/**
 * Current-user Windows DPAPI protection backed by `Crypt32.CryptProtectData` / `CryptUnprotectData`.
 */
object WindowsDpapiBackend : DataProtectionBackend {
    override val label: String = "Windows DPAPI (当前用户加密)"

    override fun protect(plaintext: ByteArray): ByteArray =
        try {
            Crypt32Util.cryptProtectData(plaintext)
        } catch (t: Throwable) {
            throw IOException("Windows DPAPI 加密失败：${t.message ?: t.javaClass.simpleName}", t)
        }

    override fun unprotect(ciphertext: ByteArray): ByteArray =
        try {
            Crypt32Util.cryptUnprotectData(ciphertext)
        } catch (t: Throwable) {
            throw IOException("Windows DPAPI 解密失败：${t.message ?: t.javaClass.simpleName}", t)
        }
}

/**
 * Encrypted local persistence store with magic-header validation, legacy `state.json` migration,
 * anti-silent-overwrite checks, and non-destructive recovery archiving.
 */
class ProtectedPersistenceStore(
    private val encryptedFile: File,
    private val legacyJsonFile: File? = null,
    private val backend: DataProtectionBackend = WindowsDpapiBackend,
) : PersistenceStore {
    private var lastSnapshot: ChaoxingAppStateSnapshot? = null

    val storageDescription: String
        get() = "${backend.label} · ${encryptedFile.absolutePath}"

    override suspend fun load(): ChaoxingAppStateSnapshot = withStoreLock {
        loadUnlocked().also { lastSnapshot = it }
    }

    override suspend fun save(snapshot: ChaoxingAppStateSnapshot) = withStoreLock {
        val current = loadUnlocked()
        if (current != snapshot && lastSnapshot != null && current != lastSnapshot) {
            throw IOException("另一个窗口已修改本地数据。请重新打开客户端后再试。")
        }
        if (current != snapshot || !encryptedFile.exists()) writeUnlocked(snapshot)
        lastSnapshot = snapshot
    }

    override suspend fun archiveAndReset(): File = withStoreLock {
        archiveAndResetUnlocked().also { lastSnapshot = ChaoxingAppStateSnapshot() }
    }

    private suspend fun <T> withStoreLock(operation: () -> T): T = processMutex.withLock {
        withContext(Dispatchers.IO) {
            val path = encryptedFile.toPath().toAbsolutePath()
            Files.createDirectories(path.parent)
            // The state file itself gets atomically replaced, so lock a stable sibling.
            val lockPath = path.resolveSibling("${path.fileName}.lock")
            FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                channel.lock().use { operation() }
            }
        }
    }

    private fun loadUnlocked(): ChaoxingAppStateSnapshot {
        val path = encryptedFile.toPath().toAbsolutePath()
        if (Files.isDirectory(path)) {
            throw IOException("状态路径是目录而非文件: $path")
        }
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            val raw = Files.readAllBytes(path)
            return decodeProtectedBytes(raw)
        }

        // One-time migration from legacy plaintext state.json if present.
        val legacyPath = legacyJsonFile?.toPath()?.toAbsolutePath()
        if (legacyPath != null && Files.exists(legacyPath, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(legacyPath)) {
            val legacyBytes = Files.readAllBytes(legacyPath)
            val snapshot = ChaoxingAppStateSnapshot.decodedBackupData(legacyBytes)
            writeUnlocked(snapshot)
            runCatching { Files.deleteIfExists(legacyPath) }
            return snapshot
        }

        return ChaoxingAppStateSnapshot()
    }

    private fun archiveAndResetUnlocked(): File {
        val targetPath = encryptedFile.toPath().toAbsolutePath()
        if (Files.isDirectory(targetPath)) {
            throw IOException("状态路径是目录而非文件: $targetPath")
        }
        val parentDir = targetPath.parent
            ?: throw IOException("无法解析父目录: $targetPath")
        Files.createDirectories(parentDir)

        val hasEncryptedFile = Files.exists(targetPath, LinkOption.NOFOLLOW_LINKS)
        val legacyPath = legacyJsonFile?.toPath()?.toAbsolutePath()
        val backupPath = if (hasEncryptedFile) {
            val backup = parentDir.resolve("state.recovery-${ChaoxingModelDefaults.newUuidString()}.dpapi")
            Files.copy(targetPath, backup)
            backup
        } else {
            if (legacyPath != null && Files.exists(legacyPath, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(legacyPath)) {
                val backup = parentDir.resolve("state.recovery-${ChaoxingModelDefaults.newUuidString()}.json")
                Files.copy(legacyPath, backup)
                backup
            } else {
                throw IOException("未找到需要归档的状态文件: $targetPath")
            }
        }

        writeUnlocked(ChaoxingAppStateSnapshot())
        if (!hasEncryptedFile && legacyPath != null) Files.deleteIfExists(legacyPath)
        return backupPath.toFile()
    }

    private fun writeUnlocked(snapshot: ChaoxingAppStateSnapshot) {
        val targetPath = encryptedFile.toPath().toAbsolutePath()
        val parentDir = targetPath.parent
            ?: throw IOException("无法解析父目录: $targetPath")
        Files.createDirectories(parentDir)

        val plaintext = snapshot.encodedBackupData(includeAccounts = true)
        val ciphertext = backend.protect(plaintext)
        val envelope = ByteArray(MAGIC_HEADER.size + ciphertext.size)
        MAGIC_HEADER.copyInto(envelope, destinationOffset = 0)
        ciphertext.copyInto(envelope, destinationOffset = MAGIC_HEADER.size)

        val tempPath = Files.createTempFile(parentDir, ".${targetPath.fileName}.", ".tmp")
        try {
            Files.write(tempPath, envelope)
            moveWithWindowsRetry(tempPath, targetPath)
        } finally {
            runCatching { Files.deleteIfExists(tempPath) }
        }
    }

    /**
     * Performs atomic replacement with short backoff retries for transient Windows file locks
     * (e.g., Windows Defender or Search Indexer briefly touching the file during save).
     */
    private fun moveWithWindowsRetry(source: java.nio.file.Path, target: java.nio.file.Path) {
        var lastError: IOException? = null
        for (attempt in 0 until 5) {
            try {
                try {
                    Files.move(
                        source,
                        target,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(
                        source,
                        target,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }
                return
            } catch (e: IOException) {
                lastError = e
                if (attempt < 4) {
                    try {
                        Thread.sleep(25L * (attempt + 1))
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw e
                    }
                }
            }
        }
        throw lastError ?: IOException("无法写入目标状态文件: $target")
    }

    private fun decodeProtectedBytes(raw: ByteArray): ChaoxingAppStateSnapshot {
        if (raw.size <= MAGIC_HEADER.size) {
            throw IOException("加密状态文件为空或已截断")
        }
        for (i in MAGIC_HEADER.indices) {
            if (raw[i] != MAGIC_HEADER[i]) {
                throw IOException("加密状态文件格式头不匹配")
            }
        }
        val ciphertext = raw.copyOfRange(MAGIC_HEADER.size, raw.size)
        val plaintext = backend.unprotect(ciphertext)
        return ChaoxingAppStateSnapshot.decodedBackupData(plaintext)
    }

    companion object {
        private val processMutex = Mutex()
        private val MAGIC_HEADER = byteArrayOf('C'.code.toByte(), 'X'.code.toByte(), 'W'.code.toByte(), '1'.code.toByte())

        /**
         * Creates the default store for the current desktop OS:
         * - Windows: DPAPI-protected `%LOCALAPPDATA%\Caochaoxing\state.dpapi`
         * - Non-Windows host (development/testing): `FilePersistenceStore` at `state.json`
         */
        fun createDefault(appDir: File = WindowsPaths.resolveAppDataDir()): Pair<PersistenceStore, String> {
            return if (WindowsPaths.isWindows) {
                val store = ProtectedPersistenceStore(
                    encryptedFile = WindowsPaths.defaultProtectedStateFile(appDir),
                    legacyJsonFile = WindowsPaths.defaultLegacyJsonStateFile(appDir),
                    backend = WindowsDpapiBackend,
                )
                store to store.storageDescription
            } else {
                val jsonFile = WindowsPaths.defaultLegacyJsonStateFile(appDir)
                FilePersistenceStore(jsonFile) to "本地 JSON 存储 · ${jsonFile.absolutePath}"
            }
        }
    }
}
