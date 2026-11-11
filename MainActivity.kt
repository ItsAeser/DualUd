package com.example.dualaudio

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private lateinit var player: DualPlayer
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        player = DualPlayer(applicationContext)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface { App(player) } } }
    }
    override fun onDestroy() { player.stop(); super.onDestroy() }
}

private fun queryDevices(am: AudioManager): List<AudioDeviceInfo> =
    am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter {
        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            (Build.VERSION.SDK_INT >= 31 && (it.type == AudioDeviceInfo.TYPE_BLE_HEADSET || it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER)) ||
            (Build.VERSION.SDK_INT >= 33 && it.type == AudioDeviceInfo.TYPE_BLE_BROADCAST)
    }

private fun AudioDeviceInfo.label() =
    "$productName · ${if (type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) "A2DP" else "LE Audio"}"

@Composable
fun rememberOutputDevices(refresh: Int): List<AudioDeviceInfo> {
    val am = LocalContext.current.getSystemService(AudioManager::class.java)
    var list by remember { mutableStateOf(queryDevices(am)) }
    LaunchedEffect(refresh) { list = queryDevices(am) }
    DisposableEffect(am) {
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(d: Array<out AudioDeviceInfo>?) { list = queryDevices(am) }
            override fun onAudioDevicesRemoved(d: Array<out AudioDeviceInfo>?) { list = queryDevices(am) }
        }
        am.registerAudioDeviceCallback(cb, null)
        onDispose { am.unregisterAudioDeviceCallback(cb) }
    }
    return list
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(player: DualPlayer) {
    val ctx = LocalContext.current
    val store = remember { PresetStore(ctx) }
    val custom = remember { mutableStateListOf<Preset>().apply { addAll(store.load()) } }
    var refresh by remember { mutableIntStateOf(0) }
    var fileName by remember { mutableStateOf("No file") }

    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) perm.launch(Manifest.permission.BLUETOOTH_CONNECT)
    }
    val devices = rememberOutputDevices(refresh)

    // Auto-assign connected devices to slots A and B, drop ones that disconnected.
    LaunchedEffect(devices) {
        for (i in 0..1) { val c = player.selected[i]; if (c != null && devices.none { it.id == c.id }) player.selected[i] = null }
        for (i in 0..1) if (player.selected[i] == null)
            player.selected[i] = devices.firstOrNull { d -> player.selected.none { it?.id == d.id } }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { fileName = uri.lastPathSegment ?: "audio"; player.start(uri) }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Dual Audio EQ", style = MaterialTheme.typography.headlineSmall)

        // Transport
        Card { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(fileName, style = MaterialTheme.typography.bodyMedium)
            var drag by remember { mutableStateOf<Float?>(null) }
            Slider(value = drag ?: player.positionMs.toFloat(),
                onValueChange = { drag = it },
                onValueChangeFinished = { drag?.let { player.seekTo(it.toLong()) }; drag = null },
                valueRange = 0f..maxOf(player.durationMs, 1L).toFloat())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { picker.launch(arrayOf("audio/*")) }) { Text("Pick file") }
                FilledTonalButton(onClick = { player.togglePause() }) { Text(if (player.isPlaying) "Pause" else "Play") }
                OutlinedButton(onClick = { player.lastUri?.let { player.start(it) } }) { Text("Restart") }
            }
            Text(player.status, style = MaterialTheme.typography.bodySmall)
            Text("Device or role changes apply on Restart.", style = MaterialTheme.typography.labelSmall)
        } }

        // Layout quick-setups
        Card { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Quick layouts", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = {
                    player.chains[0].role = Role.SATELLITE; player.chains[1].role = Role.SUB
                    apply(player.chains[0], "Satellite clarity", custom); apply(player.chains[1], "Sub boost", custom)
                }, label = { Text("Main + Sub") })
                AssistChip(onClick = {
                    player.chains[0].role = Role.LEFT; player.chains[1].role = Role.RIGHT
                }, label = { Text("Left / Right") })
                AssistChip(onClick = {
                    player.chains[0].role = Role.FULL; player.chains[1].role = Role.FULL
                }, label = { Text("Both full") })
            }
            Text("Crossover: ${player.crossoverHz.toInt()} Hz (used by Sub / Main roles)", style = MaterialTheme.typography.bodySmall)
            Slider(value = player.crossoverHz, onValueChange = { player.crossoverHz = it }, valueRange = 40f..250f)
        } }

        if (devices.isEmpty())
            Text("No connected Bluetooth audio outputs found. Connect two devices in Android Bluetooth settings.",
                color = MaterialTheme.colorScheme.error)

        for (i in 0..1) DeviceCard("Device ${"AB"[i]}", i, player, devices, custom) { name, gains ->
            custom.removeAll { it.name == name }; custom.add(Preset(name, gains)); store.save(custom)
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun apply(chain: DeviceChain, name: String, custom: List<Preset>) {
    (BUILT_IN_PRESETS + custom).firstOrNull { it.name == name }?.let { p -> p.gains.forEachIndexed { i, g -> chain.gains[i] = g } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DeviceCard(title: String, idx: Int, player: DualPlayer, devices: List<AudioDeviceInfo>,
               custom: List<Preset>, onSave: (String, List<Float>) -> Unit) {
    val chain = player.chains[idx]
    var devMenu by remember { mutableStateOf(false) }
    var presetMenu by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    Card { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)

        Box {
            OutlinedButton(onClick = { devMenu = true }, Modifier.fillMaxWidth()) {
                Text(player.selected[idx]?.label() ?: "Select device ▾")
            }
            DropdownMenu(devMenu, { devMenu = false }) {
                devices.forEach { d -> DropdownMenuItem(text = { Text(d.label()) }, onClick = { player.selected[idx] = d; devMenu = false }) }
                DropdownMenuItem(text = { Text("None") }, onClick = { player.selected[idx] = null; devMenu = false })
            }
        }

        Text("Role", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Role.values().forEach { r -> FilterChip(selected = chain.role == r, onClick = { chain.role = r }, label = { Text(r.label) }) }
        }

        Text("Volume ${(chain.volume * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
        Slider(chain.volume, { chain.volume = it }, valueRange = 0f..1.5f)
        Text("Delay ${chain.delayMs.toInt()} ms (align Bluetooth latency)", style = MaterialTheme.typography.labelMedium)
        Slider(chain.delayMs, { chain.delayMs = it }, valueRange = 0f..400f)

        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Equalizer", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Box {
                OutlinedButton(onClick = { presetMenu = true }) { Text("Preset ▾") }
                DropdownMenu(presetMenu, { presetMenu = false }) {
                    (BUILT_IN_PRESETS + custom).forEach { p ->
                        DropdownMenuItem(text = { Text(p.name) }, onClick = { apply(chain, p.name, custom); presetMenu = false })
                    }
                }
            }
            OutlinedButton(onClick = { saving = true }) { Text("Save") }
        }
        BAND_FREQS.forEachIndexed { b, f ->
            val label = if (f >= 1000) "${(f / 1000).let { if (it % 1f == 0f) it.toInt().toString() else "%.1f".format(it) }} kHz" else "${f.toInt()} Hz"
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.width(64.dp), style = MaterialTheme.typography.bodySmall)
                Slider(chain.gains[b], { chain.gains[b] = it }, Modifier.weight(1f), valueRange = -12f..12f)
                Text("%+.0f".format(chain.gains[b]), Modifier.width(32.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
    } }

    if (saving) {
        var name by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { saving = false },
            title = { Text("Save preset") },
            text = { OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true) },
            confirmButton = { TextButton(onClick = {
                if (name.isNotBlank()) onSave(name.trim(), chain.gains.toList()); saving = false
            }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { saving = false }) { Text("Cancel") } })
    }
}
