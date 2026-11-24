// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.windows.platform

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.Shell32
import com.sun.jna.platform.win32.WinReg
import com.sun.jna.platform.win32.WinUser
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.ref.WeakReference
import java.net.URI
import java.util.Locale
import javax.imageio.ImageIO

/**
 * Desktop OS integration helpers: owner-attached native file pickers, clipboard text/image access,
 * safe browser launching, and per-user Windows `caochaoxing://` URL scheme registration.
 */
object DesktopSystemTools {
    @Volatile
    private var ownerFrameRef: WeakReference<Frame>? = null

    fun registerOwnerWindow(frame: Frame?) {
        ownerFrameRef = frame?.let { WeakReference(it) }
    }

    private fun currentOwnerFrame(): Frame? = ownerFrameRef?.get()?.takeIf { it.isDisplayable }

    fun pickFile(
        title: String,
        allowedExtensions: Set<String> = emptySet(),
    ): File? {
        val dialog = FileDialog(currentOwnerFrame(), title, FileDialog.LOAD)
        if (allowedExtensions.isNotEmpty()) {
            val normalized = allowedExtensions.map { it.lowercase(Locale.ROOT).removePrefix(".") }.toSet()
            dialog.setFilenameFilter { _, name ->
                val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                ext in normalized
            }
            if (WindowsPaths.isWindows) {
                dialog.file = normalized.joinToString(";") { "*.$it" }
            }
        }
        dialog.isVisible = true
        val dir = dialog.directory
        val file = dialog.file
        dialog.dispose()
        if (dir == null || file == null) return null
        return File(dir, file)
    }

    fun pickSaveFile(
        title: String,
        defaultFileName: String,
    ): File? {
        val dialog = FileDialog(currentOwnerFrame(), title, FileDialog.SAVE)
        dialog.file = defaultFileName
        dialog.isVisible = true
        val dir = dialog.directory
        val file = dialog.file
        dialog.dispose()
        if (dir == null || file == null) return null
        return File(dir, file)
    }

    fun readClipboardText(): String? = runCatching {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
            (clipboard.getData(DataFlavor.stringFlavor) as? String)?.trim()
        } else {
            null
        }
    }.getOrNull()

    fun writeClipboardText(text: String): Boolean = runCatching {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(StringSelection(text), null)
        true
    }.getOrDefault(false)

    fun readClipboardImage(): BufferedImage? = runCatching {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        if (!clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor)) return null
        val awtImage = clipboard.getData(DataFlavor.imageFlavor) as? Image ?: return null
        if (awtImage is BufferedImage) return awtImage
        val w = awtImage.getWidth(null)
        val h = awtImage.getHeight(null)
        if (w <= 0 || h <= 0) return null
        val buffered = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = buffered.createGraphics()
        try {
            g.drawImage(awtImage, 0, 0, null)
        } finally {
            g.dispose()
        }
        buffered
    }.getOrNull()

    fun bufferedImageToPngBytes(image: BufferedImage): ByteArray {
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }

    fun openExternalUrl(rawUrl: String): Result<Unit> = runCatching {
        val uri = URI(rawUrl.trim())
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        require((scheme == "http" || scheme == "https") && !uri.rawAuthority.isNullOrBlank()) {
            "仅允许在浏览器中打开 http/https 链接"
        }
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(uri)
        } else if (WindowsPaths.isWindows) {
            val result = Shell32.INSTANCE.ShellExecute(
                null, "open", uri.toASCIIString(), null, null, WinUser.SW_SHOWNORMAL,
            )
            check(result.toLong() > 32) { "无法打开系统默认浏览器" }
        } else {
            throw UnsupportedOperationException("当前系统不支持自动打开浏览器")
        }
    }

    /**
     * Registers `caochaoxing://` under `HKCU\Software\Classes\caochaoxing` (no admin elevation required)
     * pointing to the packaged Windows executable (`Caochaoxing.exe`).
     */
    fun registerWindowsUrlScheme(): Result<String> {
        if (!WindowsPaths.isWindows) {
            return Result.failure(UnsupportedOperationException("仅可在 Windows 系统中注册 caochaoxing:// 协议"))
        }
        val exePath = resolveCurrentWindowsExecutablePath()
            ?: return Result.failure(
                IllegalStateException("当前处于开发模式运行，请打包安装 Caochaoxing.exe 后再注册 URL 协议"),
            )
        return runCatching {
            val user = WinReg.HKEY_CURRENT_USER
            val baseKey = """Software\Classes\caochaoxing"""
            val iconKey = """$baseKey\DefaultIcon"""
            val commandKey = """$baseKey\shell\open\command"""
            Advapi32Util.registryCreateKey(user, commandKey)
            Advapi32Util.registryCreateKey(user, iconKey)
            Advapi32Util.registrySetStringValue(user, commandKey, "", "\"$exePath\" \"%1\"")
            Advapi32Util.registrySetStringValue(user, iconKey, "", "\"$exePath\",0")
            Advapi32Util.registrySetStringValue(user, baseKey, "", "URL:Caochaoxing Protocol")
            Advapi32Util.registrySetStringValue(user, baseKey, "URL Protocol", "")
            "已注册 caochaoxing:// 协议 -> $exePath"
        }
    }

    private fun resolveCurrentWindowsExecutablePath(): String? {
        val jpackageExe = System.getProperty("jpackage.app-path")?.trim()?.takeIf { it.isNotEmpty() }
        if (jpackageExe != null && File(jpackageExe).isFile) {
            return jpackageExe
        }
        return runCatching {
            ProcessHandle.current().info().command().orElse(null)
                ?.takeIf {
                    it.endsWith(".exe", ignoreCase = true) &&
                        !it.endsWith("java.exe", ignoreCase = true) &&
                        !it.endsWith("javaw.exe", ignoreCase = true)
                }
        }.getOrNull()
    }
}
