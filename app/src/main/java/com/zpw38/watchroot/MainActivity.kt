package com.zpw38.watchroot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zpw38.watchroot.adb.AdbConnectionManager
import com.zpw38.watchroot.adb.AdbShellAdapter
import com.zpw38.watchroot.adb.ConnectionMode
import com.zpw38.watchroot.adb.ConnectionSettings
import com.zpw38.watchroot.adb.Shell
import com.zpw38.watchroot.device.DeviceInfo
import com.zpw38.watchroot.root.RootExploit
import com.zpw38.watchroot.ui.RootScreen
import com.zpw38.watchroot.ui.RootUiModel
import com.zpw38.watchroot.ui.RootUiState
import com.zpw38.watchroot.ui.WatchRootTheme

/**
 * The one and only screen: connect to the watch over ADB, push the payload, run it.
 *
 * Everything that touches the device happens on a worker thread; the UI thread only ever sees
 * state updates through [model].
 */
class MainActivity : ComponentActivity() {

    private var model by mutableStateOf(RootUiModel())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val settings = ConnectionSettings(this)

        setContent {
            WatchRootTheme {
                RootScreen(
                    model = model,
                    onStart = { startExploit(settings) },
                    onRetry = { startExploit(settings) },
                )
            }
        }
    }

    /**
     * Connects, reads the device info, then runs the payload.
     *
     * The profile is selected by the kernel release the daemon reports, so an unsupported build
     * is refused before anything is written to the device.
     */
    private fun startExploit(settings: ConnectionSettings) {
        if (model.state == RootUiState.CONNECTING ||
            model.state == RootUiState.PUSHING ||
            model.state == RootUiState.RUNNING
        ) return

        Thread {
            try {
                update(RootUiState.CONNECTING, progress = 0.05f)

                val manager = AdbConnectionManager.get(this)
                val connected = manager.connect(settings.tcpHost, settings.tcpPort)
                if (!connected) {
                    fail("无法连接 ${settings.endpoint()}")
                    return@Thread
                }

                val shell = AdbShellAdapter(manager)
                val info = runCatching { DeviceInfo.fromAdb(shell) }.getOrNull()
                val release = info?.kernel.orEmpty()
                update(
                    state = RootUiState.CONNECTING,
                    deviceName = info?.deviceName.orEmpty(),
                    kernel = release,
                    endpoint = settings.endpoint(),
                    progress = 0.2f,
                )

                if (release.isBlank()) {
                    fail("无法读取内核版本")
                    return@Thread
                }

                if (!RootExploit.hasProfile(this, release)) {
                    fail("没有适配 $release 的 profile")
                    return@Thread
                }

                update(RootUiState.PUSHING, progress = 0.3f)
                val result = RootExploit.run(
                    context = this,
                    shell = shell,
                    release = release,
                    ksudPath = null,
                    onLine = { line -> appendLog(line) },
                    onProgress = { p -> update(progress = p) },
                )

                if (result.exitCode == 0) {
                    update(RootUiState.SUCCESS, progress = 1f)
                } else {
                    fail("payload 退出码 ${result.exitCode}")
                }
            } catch (e: Exception) {
                fail(e.message ?: e.toString())
            }
        }.start()
    }

    // ------------------------------------------------------------------ state plumbing

    private fun update(
        state: RootUiState = model.state,
        progress: Float = model.progress,
        deviceName: String = model.deviceName,
        kernel: String = model.kernel,
        endpoint: String = model.endpoint,
    ) {
        runOnUiThread {
            model = model.copy(
                state = state,
                progress = progress,
                deviceName = deviceName,
                kernel = kernel,
                endpoint = endpoint,
            )
        }
    }

    private fun appendLog(line: String) {
        runOnUiThread {
            model = model.copy(log = (model.log + line).takeLast(500))
        }
    }

    private fun fail(message: String) {
        runOnUiThread {
            model = model.copy(state = RootUiState.FAILED, error = message)
        }
    }
}
