// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.windows

import com.caochaoxing.core.crypto.ChaoxingCaptchaSolver
import com.caochaoxing.core.crypto.ChaoxingQrCodeParser
import com.caochaoxing.core.model.ChaoxingAccountSession
import com.caochaoxing.core.model.ChaoxingLocationPreset
import com.caochaoxing.core.store.ChaoxingAppStateSnapshot
import com.caochaoxing.windows.platform.DataProtectionBackend
import com.caochaoxing.windows.platform.DesktopCaptchaImageDecoder
import com.caochaoxing.windows.platform.DesktopImageObfuscator
import com.caochaoxing.windows.platform.DesktopQrCodeDecoder
import com.caochaoxing.windows.platform.DesktopSystemTools
import com.caochaoxing.windows.platform.ProtectedPersistenceStore
import com.caochaoxing.windows.platform.WindowsKeepAwake
import com.caochaoxing.windows.platform.WindowsPaths
import com.caochaoxing.windows.ui.components.haversineDistanceMeters
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO
import kotlin.math.abs

class DesktopToolsAndStoreTest {

    private class XorTestBackend(private val key: Byte = 0x5A) : DataProtectionBackend {
        override val label: String = "TestXOR"
        override fun protect(plaintext: ByteArray): ByteArray = ByteArray(plaintext.size) { i ->
            (plaintext[i].toInt() xor key.toInt()).toByte()
        }
        override fun unprotect(ciphertext: ByteArray): ByteArray = protect(ciphertext)
    }

    @Test
    fun `mac development store is separate from the native Mac account file`(@TempDir home: Path) {
        val dir = WindowsPaths.resolveAppDataDir(osName = "Mac OS X", userHome = home.toString())
        val macFile = home.resolve("Library/Application Support/Caochaoxing/state.json").toFile()
        assertNotEquals(macFile, WindowsPaths.defaultLegacyJsonStateFile(dir))
    }

    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    @Test
    fun `Windows DPAPI store survives reopening under the current user`(@TempDir dir: Path) = runTest {
        val snapshot = ChaoxingAppStateSnapshot(
            accounts = listOf(ChaoxingAccountSession(phoneNumber = "sample-account", encryptedPassword = "sample", deviceCode = "sample-device")),
        )
        val (writer, _) = ProtectedPersistenceStore.createDefault(dir.toFile())
        writer.save(snapshot)
        val (reader, _) = ProtectedPersistenceStore.createDefault(dir.toFile())
        assertEquals(snapshot, reader.load())
        assertFalse(dir.resolve("state.json").toFile().exists())
        assertFalse(dir.resolve("state.dpapi").toFile().readText(Charsets.ISO_8859_1).contains("sample-account"))
    }

    @Test
    fun `resolveAppDataDir uses LOCALAPPDATA on Windows`() {
        val dir = WindowsPaths.resolveAppDataDir(
            osName = "Windows 11",
            localAppDataEnv = """C:\Users\Demo\AppData\Local""",
            appDataEnv = """C:\Users\Demo\AppData\Roaming""",
            userHome = """C:\Users\Demo""",
        )
        assertTrue(dir.path.endsWith("Caochaoxing"))
        assertTrue(dir.path.contains("Local"))
    }

    @Test
    fun `second window cannot overwrite changes saved by the first window`(@TempDir dir: Path) = runTest {
        val file = dir.resolve("state.dpapi").toFile()
        val first = ProtectedPersistenceStore(file, backend = XorTestBackend())
        val second = ProtectedPersistenceStore(file, backend = XorTestBackend())
        val original = first.load()
        second.load()
        val saved = original.copy(starredCourseIds = listOf(42L))
        first.save(saved)
        val stale = original.copy(customUserAgent = "other-window")
        assertTrue(runCatching { second.save(stale) }.exceptionOrNull() is IOException)
        assertEquals(saved, second.load())
        second.save(saved.copy(customUserAgent = "other-window"))
        assertEquals(listOf(42L), first.load().starredCourseIds)
    }

    @Test
    fun `protected store saves loads migrates legacy json and refuses corrupt overwrite`(@TempDir tempDir: Path) = runTest {
        val encFile = tempDir.resolve("state.dpapi").toFile()
        val legacyFile = tempDir.resolve("state.json").toFile()

        // 1. Seed legacy plaintext state.json
        val legacySnapshot = ChaoxingAppStateSnapshot(
            accounts = listOf(
                ChaoxingAccountSession(
                    phoneNumber = "13800001111",
                    encryptedPassword = "enc",
                    name = "测试同学",
                    deviceCode = "dev-1",
                    isPrimary = true,
                ),
            ),
            locationPresets = listOf(
                ChaoxingLocationPreset(name = "教学楼A", address = "东校区", latitude = 34.1234, longitude = 108.5678),
            ),
        )
        legacyFile.writeBytes(legacySnapshot.encodedBackupData(includeAccounts = true))

        val store = ProtectedPersistenceStore(
            encryptedFile = encFile,
            legacyJsonFile = legacyFile,
            backend = XorTestBackend(),
        )

        // 2. Load triggers migration into state.dpapi and removes legacy plaintext file
        val migrated = store.load()
        assertEquals(1, migrated.accounts.size)
        assertEquals("13800001111", migrated.accounts.first().phoneNumber)
        assertTrue(encFile.exists())
        assertFalse(legacyFile.exists())
        assertFalse(encFile.readText(Charsets.ISO_8859_1).contains("13800001111"))

        // 3. Corrupt the encrypted file and verify load & save both throw without overwriting
        encFile.writeBytes(byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04))
        val loadErr = runCatching { store.load() }.exceptionOrNull()
        assertTrue(loadErr is IOException)
        val saveErr = runCatching { store.save(ChaoxingAppStateSnapshot()) }.exceptionOrNull()
        assertTrue(saveErr is IOException)

        // 4. Archive and reset preserves recovery copy and resets to empty snapshot
        val archived = store.archiveAndReset()
        assertTrue(archived.exists())
        assertTrue(archived.name.startsWith("state.recovery-"))
        val clean = store.load()
        assertTrue(clean.accounts.isEmpty())
    }

    @Test
    fun `DesktopImageObfuscator outputs valid JPEG with COM block and unique digests`() {
        val img = BufferedImage(120, 90, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color(70, 120, 180)
        g.fillRect(0, 0, 120, 90)
        g.dispose()
        val rawPng = ByteArrayOutputStream().apply { ImageIO.write(img, "png", this) }.toByteArray()

        val out1 = DesktopImageObfuscator.obfuscate(rawPng)
        val out2 = DesktopImageObfuscator.obfuscate(rawPng)

        assertTrue(out1.size > 100)
        assertEquals(0xFF.toByte(), out1[0])
        assertEquals(0xD8.toByte(), out1[1])
        assertEquals(0xFF.toByte(), out1[out1.size - 2])
        assertEquals(0xD9.toByte(), out1[out1.size - 1])

        val sha1 = MessageDigest.getInstance("SHA-256").digest(out1).toList()
        val sha2 = MessageDigest.getInstance("SHA-256").digest(out2).toList()
        assertNotEquals(sha1, sha2)
    }

    @Test
    fun `DesktopQrCodeDecoder decodes generated Chaoxing QR bitmap`() {
        val payload = "SIGNIN:aid=987654&source=15&Code=987654&enc=abcdef1234567890&c=demo"
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 260, 260)
        val image = BufferedImage(matrix.width, matrix.height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                image.setRGB(x, y, if (matrix[x, y]) Color.BLACK.rgb else Color.WHITE.rgb)
            }
        }
        val bytes = ByteArrayOutputStream().apply { ImageIO.write(image, "png", this) }.toByteArray()

        val decoded = DesktopQrCodeDecoder.decode(bytes)
        assertEquals(payload, decoded)

        val parsed = ChaoxingQrCodeParser.parse(decoded!!)
        assertNotNull(parsed)
        assertEquals(987654L, parsed!!.aid)
        assertEquals("abcdef1234567890", parsed.enc)
    }

    @Test
    fun `DesktopCaptchaImageDecoder integrates with ChaoxingCaptchaSolver`() {
        val targetX = 112
        val targetY = 34
        val cutSize = 40
        val bg = BufferedImage(260, 120, BufferedImage.TYPE_INT_ARGB)
        val gBg = bg.createGraphics()
        gBg.color = Color(215, 215, 215)
        gBg.fillRect(0, 0, bg.width, bg.height)
        gBg.color = Color(25, 25, 25)
        gBg.fillRect(targetX, targetY, cutSize, cutSize)
        gBg.dispose()

        val cut = BufferedImage(cutSize + 8, cutSize + 8, BufferedImage.TYPE_INT_ARGB)
        val gCut = cut.createGraphics()
        gCut.color = Color(0, 0, 0, 0)
        gCut.fillRect(0, 0, cut.width, cut.height)
        gCut.color = Color(40, 40, 40, 255)
        gCut.fillRect(4, 4, cutSize, cutSize)
        gCut.dispose()

        val bgBytes = ByteArrayOutputStream().apply { ImageIO.write(bg, "png", this) }.toByteArray()
        val cutBytes = ByteArrayOutputStream().apply { ImageIO.write(cut, "png", this) }.toByteArray()

        val shadePix = DesktopCaptchaImageDecoder.decode(bgBytes)
        val cutoutPix = DesktopCaptchaImageDecoder.decode(cutBytes)
        assertNotNull(shadePix)
        assertNotNull(cutoutPix)

        val est = ChaoxingCaptchaSolver.estimateXPosition(shadePix!!, cutoutPix!!)
        assertNotNull(est)
        assertTrue(abs(est!! - (targetX - 4)) <= 3, "Expected estimated x near ${targetX - 4}, got $est")
    }

    @Test
    fun `haversineDistanceMeters computes accurate short range distance`() {
        // ~111 meters for 0.001 deg latitude delta
        val dist = haversineDistanceMeters(39.908823, 116.397470, 39.909823, 116.397470)
        assertTrue(dist in 105.0..118.0, "Expected ~111m, got $dist")
    }

    @Test
    fun `WindowsKeepAwake runs all SetThreadExecutionState transitions on one dedicated thread`() {
        val originalApplier = WindowsKeepAwake.stateApplier
        val calls = CopyOnWriteArrayList<Pair<Boolean, String>>()
        try {
            WindowsKeepAwake.release()
            WindowsKeepAwake.flushForTesting()
            calls.clear()

            WindowsKeepAwake.stateApplier = { enabled ->
                calls.add(enabled to Thread.currentThread().name)
            }

            // Trigger enable from caller thread A, duplicate enable (idempotent), then disable from thread B
            WindowsKeepAwake.setActive(true)
            WindowsKeepAwake.setActive(true)
            val otherThread = Thread {
                WindowsKeepAwake.setActive(false)
            }
            otherThread.start()
            otherThread.join(3000)
            WindowsKeepAwake.flushForTesting()

            assertEquals(listOf(true, false), calls.map { it.first })
            assertEquals(1, calls.map { it.second }.distinct().size, "Must stay on single OS thread")
            assertEquals("caochaoxing-windows-keepawake", calls.first().second)
            assertFalse(WindowsKeepAwake.isActive)
        } finally {
            WindowsKeepAwake.stateApplier = originalApplier
            WindowsKeepAwake.release()
            WindowsKeepAwake.flushForTesting()
        }
    }

    @Test
    fun `openExternalUrl rejects non-http schemes safely`() {
        assertTrue(DesktopSystemTools.openExternalUrl("javascript:alert(1)").isFailure)
        assertTrue(DesktopSystemTools.openExternalUrl("file:///C:/Windows/System32/calc.exe").isFailure)
        assertTrue(DesktopSystemTools.openExternalUrl("caochaoxing://sign?activeId=1").isFailure)
        assertTrue(DesktopSystemTools.openExternalUrl("https:relative-path").isFailure)
    }
}
