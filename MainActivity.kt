package com.example.dualaudio

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.projection.MediaProjectionManager
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
        player = Engine.get(this)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface { App(player) } } }
    }
    override fun onDestroy() {
        if (isFinishing && !player.capturing) player.stop()
        super.onDestroy()
    }
}

private fun isBt(t: Int) =
    t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || t == AudioDeviceInfo.TYPE_HEARING_AID ||
        (Build.VERSION.SDK_INT >= 31 && (t == AudioDeviceInfo.TYPE_BLE_HEADSET || t == AudioDeviceInfo.TYPE_BLE_SPEAKER)) ||
        (Build.VERSION.SDK_INT >= 33 && t == AudioDeviceInfo.TYPE_BLE_BROADCAST)

private fun typeName(t: Int): String = when {
    t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth"
    t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth (call mode)"
    t == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone speaker"
    t == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
    t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || t == AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired"
    t == AudioDeviceInfo.TYPE_USB_DEVICE || t == AudioDeviceInfo.TYPE_USB_HEADSET -> "USB"
    t == AudioDeviceInfo.TYPE_HEARING_AID -> "Hearing aid"
    Build.VERSION.SDK_INT >= 31 && (t == AudioDeviceInfo.TYPE_BLE_HEADSET || t == AudioDeviceInfo.TYPE_BLE_SPEAKER) -> "LE Audio"
    Build.VERSION.SDK_INT >= 33 && t == AudioDeviceInfo.TYPE_BLE_BROADCAST -> "LE Broadcast"
    else -> "type $t"
}

private fun AudioDeviceInfo.label() = "${productName.ifBlank { "Unnamed" }} · ${typeName(type)}"

@Composable
fun rememberOutputDevices(refresh: Int): List<AudioDeviceInfo> {
    val am = LocalContext.current.getSystemService(AudioManager::class.java)
    var list by remember { mutableStateOf(am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()) }
    LaunchedEffect(refresh) { list = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList() }
    DisposableEffect(am) {
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(d: Array<out AudioDeviceInfo>?) { list = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList() }
            override fun onAudioDevicesRemoved(d: Array<out AudioDeviceInfo>?) { list = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList() }
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
    var mode by remember { mutableIntStateOf(2) }   // 0 = phone audio, 1 = file, 2 = EQ only

    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    LaunchedEffect(Unit) {
        val need = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) need += Manifest.permission.BLUETOOTH_CONNECT
        if (Build.VERSION.SDK_INT >= 33) need += Manifest.permission.POST_NOTIFICATIONS
        val missing = need.filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) perms.launch(missing.toTypedArray())
    }

    val raw = rememberOutputDevices(refresh)
    // One entry per physical device (A2DP preferred over call-mode), but never hide a device that only shows up as call-mode.
    val devices = raw.filter { it.type != AudioDeviceInfo.TYPE_TELEPHONY && it.type != AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
        .groupBy { it.key() }.values
        .map { g -> g.firstOrNull { it.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO } ?: g.first() }

    LaunchedEffect(devices.map { it.id }) {
        for (i in 0..1) { val c = player.selected[i]; if (c != null && devices.none { it.id == c.id }) player.selected[i] = null }
        val bt = devices.filter { isBt(it.type) }
        for (i in 0..1) if (player.selected[i] == null)
            player.selected[i] = bt.firstOrNull { d -> player.selected.none { it?.id == d.id } }
    }

    // ---- phone-audio capture flow: mic permission -> screen-capture consent -> service ----
    val mpm = remember { ctx.getSystemService(MediaProjectionManager::class.java) }
    val projLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val d = r.data
        if (r.resultCode == Activity.RESULT_OK && d != null) {
            ContextCompat.startForegroundService(ctx,
                Intent(ctx, CaptureService::class.java).putExtra("code", r.resultCode).putExtra("data", d))
        } else player.status = "Capture permission was denied."
    }
    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) projLauncher.launch(mpm.createScreenCaptureIntent())
        else player.status = "Android requires the microphone permission to capture phone audio."
    }
    fun startCaptureFlow() {
        if (Build.VERSION.SDK_INT < 29) { player.status = "Phone-audio capture needs Android 10 or newer."; return }
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            micPerm.launch(Manifest.permission.RECORD_AUDIO)
        else projLauncher.launch(mpm.createScreenCaptureIntent())
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { fileName = uri.lastPathSegment ?: "audio"; player.start(uri) }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Dual Audio EQ", style = MaterialTheme.typography.headlineSmall)

        Card { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(mode == 2, { mode = 2 }, label = { Text("EQ only") })
                FilterChip(mode == 0, { mode = 0 }, label = { Text("Phone audio") })
                FilterChip(mode == 1, { mode = 1 }, label = { Text("File") })
            }
            if (mode == 0) {
                val route = remember(devices.size, player.capturing) { player.systemRoute().joinToString { it.label() } }
                Text("Play music/video in any app (Spotify, YouTube…), then start. " +
                    "Your phone keeps playing the original sound on its current output" +
                    (if (route.isNotEmpty()) " ($route)" else "") +
                    ". That device plays it unprocessed; the OTHER device gets the processed copy (sub/EQ/delay).",
                    style = MaterialTheme.typography.bodySmall)
                Button(onClick = {
                    if (player.capturing) ctx.startService(Intent(ctx, CaptureService::class.java).setAction("STOP"))
                    else startCaptureFlow()
                }) { Text(if (player.capturing) "Stop phone audio" else "Start phone audio") }
            } else if (mode == 2) {
                Text("No screen share needed. Pick your two speakers and set an EQ for each below. " +
                    "Tap Start, then skip or restart a track in Echo. The app applies the EQ of whichever " +
                    "speaker is playing and switches automatically when you switch speakers. " +
                    "(If both play at once, Android can only apply one EQ to both.)", style = MaterialTheme.typography.bodySmall)
                Button(onClick = {
                    val i = Intent(ctx, EffectsService::class.java)
                    if (FxState.running) ctx.startService(i.setAction("STOP"))
                    else ContextCompat.startForegroundService(ctx, i)
                }) { Text(if (FxState.running) "Stop EQ" else "Start EQ") }
            } else {
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
            }
            Text(if (mode == 2) FxState.log else player.status, style = MaterialTheme.typography.bodySmall)
            Text("Device picks apply when you (re)start. EQ, role, volume and delay change live.",
                style = MaterialTheme.typography.labelSmall)
        } }

        if (mode != 2) Card { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Quick layouts", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = {
                    player.chains[0].role = Role.SATELLITE; player.chains[1].role = Role.SUB
                    applyPreset(player.chains[0], "Satellite clarity", custom); applyPreset(player.chains[1], "Sub boost", custom)
                }, label = { Text("Main + Sub") })
                AssistChip(onClick = { player.chains[0].role = Role.LEFT; player.chains[1].role = Role.RIGHT },
                    label = { Text("Left / Right") })
                AssistChip(onClick = { player.chains[0].role = Role.FULL; player.chains[1].role = Role.FULL },
                    label = { Text("Both full") })
            }
            Text("Crossover: ${player.crossoverHz.toInt()} Hz (Sub / Main roles)", style = MaterialTheme.typography.bodySmall)
            Slider(value = player.crossoverHz, onValueChange = { player.crossoverHz = it }, valueRange = 40f..250f)
        } }

        for (i in 0..1) DeviceCard("Device ${"AB"[i]}", i, player, devices, custom, eqOnly = mode == 2) { name, gains ->
            custom.removeAll { it.name == name }; custom.add(Preset(name, gains)); store.save(custom)
        }

        Card { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Outputs Android reports right now", style = MaterialTheme.typography.titleSmall)
            Text(if (raw.isEmpty()) "(none)" else raw.joinToString("\n") { "• ${it.label()} (id ${it.id}${if (it.address.isNullOrBlank()) "" else ", " + it.address})" },
                style = MaterialTheme.typography.bodySmall)
            Text("If your speaker isn't listed here, Android itself isn't exposing it. Check Bluetooth settings shows it " +
                "'Connected' with Media audio ON, and try disconnecting/reconnecting it.", style = MaterialTheme.typography.labelSmall)
            OutlinedButton(onClick = { refresh++ }) { Text("Refresh list") }
        } }
        Spacer(Modifier.height(24.dp))
    }
}

private fun applyPreset(chain: DeviceChain, name: String, custom: List<Preset>) {
    (BUILT_IN_PRESETS + custom).firstOrNull { it.name == name }?.let { p -> p.gains.forEachIndexed { i, g -> chain.gains[i] = g } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DeviceCard(title: String, idx: Int, player: DualPlayer, devices: List<AudioDeviceInfo>,
               custom: List<Preset>, eqOnly: Boolean = false, onSave: (String, List<Float>) -> Unit) {
    val chain = player.chains[idx]
    var devMenu by remember { mutableStateOf(false) }
    var presetMenu by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    Card { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {
                OutlinedButton(onClick = { devMenu = true }, Modifier.fillMaxWidth()) {
                    Text(player.selected[idx]?.label() ?: "Select device ▾", maxLines = 1)
                }
                DropdownMenu(devMenu, { devMenu = false }) {
                    devices.forEach { d -> DropdownMenuItem(text = { Text(d.label()) }, onClick = { player.selected[idx] = d; devMenu = false }) }
                    DropdownMenuItem(text = { Text("None (don't process)") }, onClick = { player.selected[idx] = null; devMenu = false })
                }
            }
            if (!eqOnly) FilledTonalButton(onClick = { player.testTone(idx) }) { Text("Test") }
        }

        if (!eqOnly) {
        Text("Role", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Role.values().forEach { r -> FilterChip(selected = chain.role == r, onClick = { chain.role = r }, label = { Text(r.label) }) }
        }

        Text("Volume ${(chain.volume * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
        Slider(chain.volume, { chain.volume = it }, valueRange = 0f..1.5f)
        Text("Delay ${chain.delayMs.toInt()} ms", style = MaterialTheme.typography.labelMedium)
        Slider(chain.delayMs, { chain.delayMs = it }, valueRange = 0f..400f)
        }

        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Equalizer", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Box {
                OutlinedButton(onClick = { presetMenu = true }) { Text("Preset ▾") }
                DropdownMenu(presetMenu, { presetMenu = false }) {
                    (BUILT_IN_PRESETS + custom).forEach { p ->
                        DropdownMenuItem(text = { Text(p.name) }, onClick = { applyPreset(chain, p.name, custom); presetMenu = false })
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
