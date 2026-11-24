// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.windows.platform

import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinBase
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Keeps the Windows display and system awake while legal multi-way course study is active.
 *
 * Important Windows API contract: `Kernel32.SetThreadExecutionState` is **thread-affine**
 * (per OS thread). Therefore every enable, periodic refresh, and release call MUST execute on
 * the same dedicated single daemon thread so the continuous execution state is reliably held
 * and cleanly cleared.
 */
object WindowsKeepAwake {
    private val lock = Any()
    private var active: Boolean = false
    private var heartbeatFuture: ScheduledFuture<*>? = null

    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "caochaoxing-windows-keepawake").apply {
            isDaemon = true
        }
    }

    /** Injectable applier for unit tests; defaults to Win32 `Kernel32.SetThreadExecutionState`. */
    @Volatile
    internal var stateApplier: (enabled: Boolean) -> Unit = { enabled ->
        if (WindowsPaths.isWindows) {
            runCatching {
                val flags = if (enabled) {
                    WinBase.ES_CONTINUOUS or WinBase.ES_SYSTEM_REQUIRED or WinBase.ES_DISPLAY_REQUIRED
                } else {
                    WinBase.ES_CONTINUOUS
                }
                Kernel32.INSTANCE.SetThreadExecutionState(flags)
            }
        }
    }

    val isActive: Boolean
        get() = synchronized(lock) { active }

    fun setActive(enabled: Boolean) {
        synchronized(lock) {
            if (active == enabled) return
            active = enabled
            heartbeatFuture?.cancel(false)
            heartbeatFuture = null
            if (enabled) {
                executor.execute { stateApplier(true) }
                // Refresh periodically on the exact same OS thread to keep display timers reset.
                heartbeatFuture = executor.scheduleAtFixedRate(
                    {
                        if (isActive) {
                            stateApplier(true)
                        }
                    },
                    45L,
                    45L,
                    TimeUnit.SECONDS,
                )
            } else {
                executor.execute { stateApplier(false) }
            }
        }
    }

    fun release() {
        setActive(false)
    }

    /** Waits for pending keep-awake tasks on the dedicated thread (used in tests and clean exit). */
    internal fun flushForTesting() {
        executor.submit {}.get(5, TimeUnit.SECONDS)
    }
}
