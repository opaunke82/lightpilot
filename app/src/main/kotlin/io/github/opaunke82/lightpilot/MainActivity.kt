// Structure follows KarooFirefly (https://github.com/derstrassi/karoofirefly),
// Copyright (c) 2026 derstrassi, MIT License.

package io.github.opaunke82.lightpilot

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.github.opaunke82.lightpilot.data.AppSettings
import io.github.opaunke82.lightpilot.data.PreferencesRepository
import io.github.opaunke82.lightpilot.engine.EngineStatus
import io.github.opaunke82.lightpilot.engine.TimeBasedController
import io.github.opaunke82.lightpilot.log.EventLog
import io.github.opaunke82.lightpilot.ui.screens.SettingsActions
import io.github.opaunke82.lightpilot.ui.screens.SettingsScreen
import io.github.opaunke82.lightpilot.ui.theme.AppTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var repository: PreferencesRepository
    private val extension = MutableStateFlow<LightPilotExtension?>(null)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results.values.all { it }) extension.value?.setSettingsUiActive(true)
        }

    private fun ensureBlePermissions() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (perms.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            permissionLauncher.launch(perms)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureBlePermissions()
        repository = PreferencesRepository(applicationContext)

        // The extension service is started by the Karoo; wait until it is there.
        lifecycleScope.launch {
            while (LightPilotExtension.getInstance() == null) delay(500)
            extension.value = LightPilotExtension.getInstance()
            extension.value?.setSettingsUiActive(true)
        }

        fun save(settings: AppSettings) {
            lifecycleScope.launch {
                repository.updateSettings(settings)
                extension.value?.applySettings(settings)
            }
        }

        setContent {
            AppTheme {
                val settings by repository.settingsFlow.collectAsState(initial = AppSettings())
                val ext by extension.collectAsState()
                val lights = ext?.discoveredLights?.collectAsState()?.value ?: emptyList()
                val status = ext?.engine?.status?.collectAsState()?.value ?: EngineStatus()
                val lux = ext?.ambientLightSensor?.currentLux?.collectAsState()?.value ?: 0f
                val logLines by EventLog.lastLines.collectAsState()
                val exportInfo = remember { mutableStateOf<String?>(null) }

                SettingsScreen(
                    settings = settings,
                    discoveredLights = lights,
                    status = status,
                    currentLux = lux,
                    sunrise = ext?.timeController?.getSunriseTime(),
                    sunset = ext?.timeController?.getSunsetTime(),
                    sunSource = when (ext?.timeController?.source) {
                        TimeBasedController.Source.GPS -> "GPS"
                        TimeBasedController.Source.LAST_KNOWN -> "letzte bekannte Position (noch kein GPS)"
                        TimeBasedController.Source.ESTIMATED -> "geschätzt aus der Zeitzone (noch nie GPS)"
                        else -> "keine"
                    },
                    logLines = logLines,
                    exportInfo = exportInfo.value,
                    extensionReady = ext != null,
                    actions = SettingsActions(
                        save = { save(it) },
                        testMode = { id, mode -> ext?.testMode(id, mode) },
                        forgetLight = { light ->
                            if (light.protocol == io.github.opaunke82.lightpilot.data.LightProtocol.BLE) {
                                ext?.magicshineController?.disconnect(light.id)
                            }
                            save(settings.withLight(null, light.id))
                        },
                        setSimulation = { ext?.engine?.setSimulation(it) },
                        setSimulatedRide = { ext?.engine?.setSimulatedRide(it) },
                        simulateVehicle = { ext?.engine?.simulateVehicle(5) },
                        simulatePause = { manual -> ext?.engine?.simulatePause(manual) },
                        simulateResume = { ext?.engine?.simulateResume() },
                        manualCycle = { ext?.engine?.manualCycle() },
                        setManual = { ext?.engine?.setManual(it) },
                        simulateZone = { ext?.engine?.simulateZone(it) },
                        frontCycle = { ext?.engine?.frontCycle() },
                        factoryReset = {
                            ext?.factoryReset()
                            EventLog.clear()
                            exportInfo.value = null
                            Toast.makeText(this@MainActivity, "LightPilot zurückgesetzt", Toast.LENGTH_LONG).show()
                            // Fresh start of the page; the extension service was reset in place.
                            recreate()
                        },
                        exportLog = {
                            val file = EventLog.export(applicationContext)
                            val text = file?.absolutePath ?: "Export fehlgeschlagen"
                            exportInfo.value = text
                            Toast.makeText(this@MainActivity, text, Toast.LENGTH_LONG).show()
                        },
                    ),
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        extension.value?.setSettingsUiActive(true)
    }

    override fun onStop() {
        super.onStop()
        // Give the light session back to the Karoo when the page is not visible.
        extension.value?.setSettingsUiActive(false)
    }
}
