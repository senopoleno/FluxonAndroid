package io.github.p1neapplexpress.openflux.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.p1neapplexpress.openflux.event.AppEvent
import io.github.p1neapplexpress.openflux.event.EventBus
import io.github.p1neapplexpress.openflux.util.Logx
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

class NativeProcessSupervisor(private val context: Context) {

    companion object {
        private const val TAG = "NativeProcSupervisor"
        private const val STARTUP_GRACE_MS = 2_000L
        private const val NATIVE_LIB = "libp1npplydtransport.so"
    }

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var process: Process? = null
    @Volatile private var stdoutThread: Thread? = null

    private val running = AtomicBoolean(false)
    private val connected = AtomicBoolean(false)
    private val shuttingDown = AtomicBoolean(false)

    val isConnected: Boolean get() = connected.get()
    val isRunning: Boolean get() = running.get()

    fun start(transportType: String, payload: List<String>) {
        if (running.getAndSet(true)) {
            Logx.d(TAG, "already running, ignoring start")
            return
        }
        shuttingDown.set(false)
        Logx.i(TAG, "start transport=$transportType")
        spawn(transportType, payload)
    }

    fun stop() {
        Logx.i(TAG, "stop()")
        shuttingDown.set(true)
        running.set(false)
        connected.set(false)
        cleanup()
    }

    private fun spawn(transportType: String, payload: List<String>) {
        val libPath = "${context.applicationInfo.nativeLibraryDir}/$NATIVE_LIB"
        try {
            
            val isDebug = payload.contains("--debug") || Logx.isVerbose
            val cleanPayload = payload.filter { it != "--debug" }
            val cmd = buildList {
                add(libPath)
                if (isDebug) {
                    add("--debug")
                }
                addAll(cleanPayload)
            }
            Logx.i(TAG, "exec: ${cmd.joinToString(" ")}")

            val pb = ProcessBuilder(cmd)
                .directory(context.filesDir)
                .redirectErrorStream(true)
            process = pb.start()
            process!!.outputStream.close()

            stdoutThread = Thread {
                try {
                    BufferedReader(InputStreamReader(process!!.inputStream)).use { r ->
                        var line: String?
                        while (r.readLine().also { line = it } != null) {
                            val l = line ?: continue
                            if (l.isBlank()) continue
                            android.util.Log.d("NativeStdout", l)
                            EventBus.dispatch(AppEvent.LogMessage(l))
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    val exitCode = try { process?.waitFor() } catch (_: Exception) { null }
                    if (!shuttingDown.get()) {
                        Logx.e(TAG, "native process exited unexpectedly with code $exitCode")
                        connected.set(false)
                        running.set(false)
                        EventBus.dispatch(AppEvent.TransportDisconnected)
                        EventBus.dispatch(AppEvent.LogMessage("[E] Native transport process exited (code $exitCode)"))
                    }
                }
            }.apply {
                name = "NativeStdoutReader"
                isDaemon = true
                start()
            }

            handler.postDelayed({
                if (!shuttingDown.get()) {
                    if (process?.isAlive == true) {
                        connected.set(true)
                        EventBus.dispatch(AppEvent.TransportConnected)
                        Logx.i(TAG, "native process up")
                    } else {
                        Logx.e(TAG, "native process died during startup")
                        connected.set(false)
                        running.set(false)
                        EventBus.dispatch(AppEvent.TransportDisconnected)
                        EventBus.dispatch(AppEvent.LogMessage("[E] Native transport failed to start (process exited)"))
                    }
                }
            }, STARTUP_GRACE_MS)

        } catch (e: Exception) {
            Logx.e(TAG, "spawn failed", e)
            running.set(false)
            connected.set(false)
            EventBus.dispatch(AppEvent.TransportDisconnected)
            EventBus.dispatch(AppEvent.LogMessage("[E] Spawn failed: ${e.message}"))
        }
    }

    private fun cleanup() {
        stdoutThread?.interrupt()
        stdoutThread = null
        process?.let { p ->
            if (p.isAlive) p.destroy()
            handler.postDelayed({ if (p.isAlive) p.destroyForcibly() }, 1000L)
        }
        process = null
    }

}
