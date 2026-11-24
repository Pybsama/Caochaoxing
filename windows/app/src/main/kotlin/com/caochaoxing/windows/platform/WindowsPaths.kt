// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.windows.platform

import java.io.File
import java.util.Locale

/**
 * Resolves per-user application data directories across Windows and non-Windows host environments.
 */
object WindowsPaths {
    const val APP_FOLDER_NAME: String = "Caochaoxing"

    val isWindows: Boolean
        get() = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT).startsWith("windows")

    val isMacOs: Boolean
        get() = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT).contains("mac")

    fun resolveAppDataDir(
        osName: String = System.getProperty("os.name").orEmpty(),
        localAppDataEnv: String? = System.getenv("LOCALAPPDATA"),
        appDataEnv: String? = System.getenv("APPDATA"),
        userHome: String = System.getProperty("user.home").orEmpty(),
    ): File {
        val os = osName.lowercase(Locale.ROOT)
        return when {
            os.startsWith("windows") -> {
                val base = localAppDataEnv?.takeIf { it.isNotBlank() }
                    ?: appDataEnv?.takeIf { it.isNotBlank() }
                    ?: File(userHome, "AppData/Local").path
                File(base, APP_FOLDER_NAME)
            }
            os.contains("mac") || os == "darwin" -> File(userHome, "Library/Application Support/${APP_FOLDER_NAME}-WindowsDev")
            else -> {
                val xdg = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
                    ?: File(userHome, ".local/share").path
                File(xdg, APP_FOLDER_NAME)
            }
        }
    }

    fun defaultProtectedStateFile(appDir: File = resolveAppDataDir()): File =
        File(appDir, "state.dpapi")

    fun defaultLegacyJsonStateFile(appDir: File = resolveAppDataDir()): File =
        File(appDir, "state.json")
}
