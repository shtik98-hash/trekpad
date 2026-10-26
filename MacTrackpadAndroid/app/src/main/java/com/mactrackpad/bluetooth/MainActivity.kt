package com.mactrackpad.bluetooth

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {

    private lateinit var hidController: BluetoothHidController
    private lateinit var gestureDetector: AppleGestureDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        hidController = BluetoothHidController(this)
        gestureDetector = AppleGestureDetector(hidController) { strong ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator.vibrate(
                    VibrationEffect.createPredefined(
                        if (strong) VibrationEffect.EFFECT_HEAVY_CLICK else VibrationEffect.EFFECT_TICK
                    )
                )
            }
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                MacTrackpadScreen(hidController, gestureDetector)
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun MacTrackpadScreen(
    hidController: BluetoothHidController,
    gestureDetector: AppleGestureDetector
) {
    val connectionState by hidController.connectionState.collectAsState()
    val connectedDevice by hidController.connectedDeviceName.collectAsState()
    val pairedDevices by hidController.pairedDevices.collectAsState()
    val lastSentEvent by hidController.lastSentEvent.collectAsState()

    var showSettingsModal by remember { mutableStateOf(false) }
    var showPairingModal by remember { mutableStateOf(false) }

    var pointerSpeed by remember { mutableFloatStateOf(gestureDetector.pointerSensitivity) }
    var naturalScroll by remember { mutableStateOf(gestureDetector.naturalScrolling) }
    var threeFingerDrag by remember { mutableStateOf(gestureDetector.threeFingerDragEnabled) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hidController.initialize()
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_ADVERTISE
                )
            )
        } else {
            hidController.initialize()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0E0F12))
            .padding(12.dp)
    ) {
        // Верхня панель стану Bluetooth та швидких клавіш macOS
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF1A1C23))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (connectedDevice != null) Color(0xFF30D158) else Color(0xFFFF9F0A))
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = connectedDevice ?: "Magic Trackpad HID",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    )
                    Text(
                        text = connectionState,
                        color = Color(0xFF98989D),
                        fontSize = 12.sp
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        hidController.refreshPairedDevices()
                        showPairingModal = true
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0A84FF)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("Mac Bluetooth", fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = { showSettingsModal = true },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("Жести", fontSize = 12.sp, color = Color.White)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Панель швидкого виклику системних дій macOS
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            QuickMacActionChip("Mission Control", Modifier.weight(1f)) { hidController.triggerMissionControl() }
            QuickMacActionChip("Launchpad", Modifier.weight(1f)) { hidController.triggerLaunchpad() }
            QuickMacActionChip("Spotlight", Modifier.weight(1f)) { hidController.triggerSpotlight() }
            QuickMacActionChip("Робочий стіл", Modifier.weight(1f)) { hidController.triggerShowDesktop() }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Головна сенсорна поверхня Apple Magic Trackpad
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xFF24262E), Color(0xFF191B20))
                    )
                )
                .border(1.5.dp, Color(0xFF3A3D4A), RoundedCornerShape(28.dp))
                .onSizeChanged { gestureDetector.setTrackpadWidth(it.width.toFloat()) }
                .pointerInteropFilter { motionEvent ->
                    gestureDetector.onTouchEvent(motionEvent)
                },
            contentAlignment = Alignment.BottomCenter
        ) {
            Column(
                modifier = Modifier.padding(bottom = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = lastSentEvent,
                    color = Color(0xFF64D2FF),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Підтримуються жести 1, 2, 3 та 4 пальцями • Force Touch (довге утримання)",
                    color = Color(0xFF636366),
                    fontSize = 11.sp
                )
            }
        }
    }

    // Модальне вікно вибору Mac для підключення
    if (showPairingModal) {
        AlertDialog(
            onDismissRequest = { showPairingModal = false },
            title = { Text("Підключення до Mac по Bluetooth") },
            text = {
                Column {
                    Text(
                        "Оберіть ваш Mac зі списку спарених Bluetooth-пристроїв. Додаток підключиться як офіційний бездротовий тачпад/клавіатура:",
                        fontSize = 13.sp,
                        color = Color(0xFFAEAEB2)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
                        items(pairedDevices) { device ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        hidController.connectToMac(device)
                                        showPairingModal = false
                                    }
                                    .padding(vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(device.name ?: "Невідомий пристрій", color = Color.White)
                                Text(device.address, color = Color.Gray, fontSize = 12.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPairingModal = false }) { Text("Закрити") }
            }
        )
    }

    // Модальне вікно налаштувань жестів Apple Trackpad
    if (showSettingsModal) {
        AlertDialog(
            onDismissRequest = { showSettingsModal = false },
            title = { Text("Налаштування жестів Apple Trackpad") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Швидкість курсора: ${"%.1f".format(pointerSpeed)}x")
                    Slider(
                        value = pointerSpeed,
                        onValueChange = {
                            pointerSpeed = it
                            gestureDetector.pointerSensitivity = it
                        },
                        valueRange = 0.6f..3.5f
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Природна прокрутка (Natural Scroll)")
                        Switch(
                            checked = naturalScroll,
                            onCheckedChange = {
                                naturalScroll = it
                                gestureDetector.naturalScrolling = it
                            }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Перетягування 3 пальцями (Three-Finger Drag)")
                        Switch(
                            checked = threeFingerDrag,
                            onCheckedChange = {
                                threeFingerDrag = it
                                gestureDetector.threeFingerDragEnabled = it
                            }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSettingsModal = false }) { Text("Готово") }
            }
        )
    }
}

@Composable
fun QuickMacActionChip(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF1C1E26))
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = label, color = Color(0xFFD1D1D6), fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}
