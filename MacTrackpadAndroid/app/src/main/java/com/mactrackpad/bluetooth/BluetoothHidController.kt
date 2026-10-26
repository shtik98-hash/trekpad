package com.mactrackpad.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executors

/**
 * Керує нативним профілем Bluetooth HID (Mouse + Keyboard Composite Device).
 * Завдяки цьому Mac сприймає Android-смартфон як офіційний комбінований тачпад/клавіатуру
 * без жодних додаткових драйверів чи серверів на macOS.
 */
@SuppressLint("MissingPermission")
class BluetoothHidController(context: Context) {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var hidDevice: BluetoothHidDevice? = null
    private var hostDevice: BluetoothDevice? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _connectionState = MutableStateFlow("Відключено")
    val connectionState: StateFlow<String> = _connectionState

    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName: StateFlow<String?> = _connectedDeviceName

    private val _pairedDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val pairedDevices: StateFlow<List<BluetoothDevice>> = _pairedDevices

    private val _lastSentEvent = MutableStateFlow("Очікування жестів...")
    val lastSentEvent: StateFlow<String> = _lastSentEvent

    companion object {
        const val REPORT_ID_MOUSE: Int = 1
        const val REPORT_ID_KEYBOARD: Int = 2

        // Модифікатори клавіш macOS (HID Keyboard Modifier Byte)
        const val MOD_NONE: Byte = 0x00
        const val MOD_LCTRL: Byte = 0x01      // Control (^)
        const val MOD_LSHIFT: Byte = 0x02     // Shift (⇧)
        const val MOD_LALT: Byte = 0x04       // Option (⌥)
        const val MOD_LGUI: Byte = 0x08       // Command (⌘)

        // HID коди клавіш для системних жестів macOS
        const val KEY_NONE: Byte = 0x00
        const val KEY_A: Byte = 0x04
        const val KEY_C: Byte = 0x06
        const val KEY_D: Byte = 0x07
        const val KEY_F: Byte = 0x09
        const val KEY_H: Byte = 0x0B
        const val KEY_M: Byte = 0x10
        const val KEY_N: Byte = 0x11
        const val KEY_Q: Byte = 0x14
        const val KEY_R: Byte = 0x15
        const val KEY_V: Byte = 0x19
        const val KEY_W: Byte = 0x1A
        const val KEY_Z: Byte = 0x1D
        const val KEY_0: Byte = 0x27
        const val KEY_SPACE: Byte = 0x2C
        const val KEY_MINUS: Byte = 0x2D      // Zoom Out (⌘ -)
        const val KEY_EQUAL: Byte = 0x2E      // Zoom In (⌘ +)
        const val KEY_LBRACKET: Byte = 0x2F   // Назад у браузері/Finder (⌘ [)
        const val KEY_RBRACKET: Byte = 0x30   // Вперед у браузері/Finder (⌘ ])
        const val KEY_F3: Byte = 0x3C         // Mission Control
        const val KEY_F4: Byte = 0x3D         // Launchpad
        const val KEY_F11: Byte = 0x44        // Показати робочий стіл (Show Desktop)
        const val KEY_RIGHT: Byte = 0x4F      // Стрілка вправо (перемикання робочих столів)
        const val KEY_LEFT: Byte = 0x50       // Стрілка вліво
        const val KEY_DOWN: Byte = 0x51       // Стрілка вниз (App Exposé)
        const val KEY_UP: Byte = 0x52         // Стрілка вгору (Mission Control)

        /**
         * Комбінований USB/Bluetooth HID Report Descriptor:
         * - Report ID 1: 5-байтна миша/тачпад (Кнопки, ΔX, ΔY, Вертикальний скрол, Горизонтальний панорамний скрол AC Pan)
         * - Report ID 2: 8-байтна клавіатура для виконання жестів macOS (Mission Control, Desktops, Zoom, Rotate, Force Click)
         */
        val COMPOSITE_HID_DESCRIPTOR = byteArrayOf(
            // ================= MOUSE / TRACKPAD COLLECTION (Report ID 1) =================
            0x05.toByte(), 0x01.toByte(), // Usage Page (Generic Desktop)
            0x09.toByte(), 0x02.toByte(), // Usage (Mouse)
            0xA1.toByte(), 0x01.toByte(), // Collection (Application)
            0x85.toByte(), 0x01.toByte(), //   Report ID (1)
            0x09.toByte(), 0x01.toByte(), //   Usage (Pointer)
            0xA1.toByte(), 0x00.toByte(), //   Collection (Physical)
            // Buttons (Left, Right, Middle)
            0x05.toByte(), 0x09.toByte(), //     Usage Page (Buttons)
            0x19.toByte(), 0x01.toByte(), //     Usage Minimum (1)
            0x29.toByte(), 0x03.toByte(), //     Usage Maximum (3)
            0x15.toByte(), 0x00.toByte(), //     Logical Minimum (0)
            0x25.toByte(), 0x01.toByte(), //     Logical Maximum (1)
            0x95.toByte(), 0x03.toByte(), //     Report Count (3)
            0x75.toByte(), 0x01.toByte(), //     Report Size (1)
            0x81.toByte(), 0x02.toByte(), //     Input (Data, Variable, Absolute)
            // 5-bit padding
            0x95.toByte(), 0x01.toByte(), //     Report Count (1)
            0x75.toByte(), 0x05.toByte(), //     Report Size (5)
            0x81.toByte(), 0x03.toByte(), //     Input (Constant)
            // X, Y, Vertical Wheel
            0x05.toByte(), 0x01.toByte(), //     Usage Page (Generic Desktop)
            0x09.toByte(), 0x30.toByte(), //     Usage (X)
            0x09.toByte(), 0x31.toByte(), //     Usage (Y)
            0x09.toByte(), 0x38.toByte(), //     Usage (Wheel)
            0x15.toByte(), 0x81.toByte(), //     Logical Minimum (-127)
            0x25.toByte(), 0x7F.toByte(), //     Logical Maximum (127)
            0x75.toByte(), 0x08.toByte(), //     Report Size (8)
            0x95.toByte(), 0x03.toByte(), //     Report Count (3)
            0x81.toByte(), 0x06.toByte(), //     Input (Data, Variable, Relative)
            // Horizontal Scroll (AC Pan)
            0x05.toByte(), 0x0C.toByte(), //     Usage Page (Consumer Devices)
            0x0A.toByte(), 0x38.toByte(), 0x02.toByte(), // Usage (AC Pan)
            0x15.toByte(), 0x81.toByte(), //     Logical Minimum (-127)
            0x25.toByte(), 0x7F.toByte(), //     Logical Maximum (127)
            0x75.toByte(), 0x08.toByte(), //     Report Size (8)
            0x95.toByte(), 0x01.toByte(), //     Report Count (1)
            0x81.toByte(), 0x06.toByte(), //     Input (Data, Variable, Relative)
            0xC0.toByte(),                //   End Collection (Physical)
            0xC0.toByte(),                // End Collection (Application)

            // ================= KEYBOARD COLLECTION (Report ID 2) =================
            0x05.toByte(), 0x01.toByte(), // Usage Page (Generic Desktop)
            0x09.toByte(), 0x06.toByte(), // Usage (Keyboard)
            0xA1.toByte(), 0x01.toByte(), // Collection (Application)
            0x85.toByte(), 0x02.toByte(), //   Report ID (2)
            0x05.toByte(), 0x07.toByte(), //   Usage Page (Key Codes)
            0x19.toByte(), 0xE0.toByte(), //   Usage Minimum (224 - Left Control)
            0x29.toByte(), 0xE7.toByte(), //   Usage Maximum (231 - Right GUI/Command)
            0x15.toByte(), 0x00.toByte(), //   Logical Minimum (0)
            0x25.toByte(), 0x01.toByte(), //   Logical Maximum (1)
            0x75.toByte(), 0x01.toByte(), //   Report Size (1)
            0x95.toByte(), 0x08.toByte(), //   Report Count (8)
            0x81.toByte(), 0x02.toByte(), //   Input (Data, Variable, Absolute) - Modifier byte
            0x95.toByte(), 0x01.toByte(), //   Report Count (1)
            0x75.toByte(), 0x08.toByte(), //   Report Size (8)
            0x81.toByte(), 0x01.toByte(), //   Input (Constant) - Reserved byte
            0x95.toByte(), 0x06.toByte(), //   Report Count (6)
            0x75.toByte(), 0x08.toByte(), //   Report Size (8)
            0x15.toByte(), 0x00.toByte(), //   Logical Minimum (0)
            0x25.toByte(), 0x65.toByte(), //   Logical Maximum (101)
            0x05.toByte(), 0x07.toByte(), //   Usage Page (Key Codes)
            0x19.toByte(), 0x00.toByte(), //   Usage Minimum (0)
            0x29.toByte(), 0x65.toByte(), //   Usage Maximum (101)
            0x81.toByte(), 0x00.toByte(), //   Input (Data, Array) - 6 Key codes
            0xC0.toByte()                 // End Collection (Application)
        )
    }

    private var isDraggingButtonHeld = false

    fun initialize() {
        refreshPairedDevices()
        bluetoothAdapter?.getProfileProxy(
            /* context = */ null,
            object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile == BluetoothProfile.HID_DEVICE) {
                        hidDevice = proxy as BluetoothHidDevice
                        registerHidApp()
                    }
                }

                override fun onServiceDisconnected(profile: Int) {
                    if (profile == BluetoothProfile.HID_DEVICE) {
                        hidDevice = null
                        _connectionState.value = "Сервіс HID зупинено"
                    }
                }
            },
            BluetoothProfile.HID_DEVICE
        )
    }

    fun refreshPairedDevices() {
        val bonded = bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
        _pairedDevices.value = bonded
    }

    private fun registerHidApp() {
        val sdpSettings = BluetoothHidDeviceAppSdpSettings(
            "Mac Magic Trackpad (Android)",
            "Apple-Style Multi-Touch Bluetooth HID Trackpad",
            "Android Open HID",
            BluetoothHidDevice.SUBCLASS1_COMBO,
            COMPOSITE_HID_DESCRIPTOR
        )

        val qosSettings = BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
            800, 9, 0, 11250, BluetoothHidDeviceAppQosSettings.MAX
        )

        hidDevice?.registerApp(
            sdpSettings,
            null,
            qosSettings,
            executor,
            object : BluetoothHidDevice.Callback() {
                override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
                    _connectionState.value = if (registered) "Готовий до підключення (HID активний)" else "Помилка реєстрації HID"
                    if (registered && pluggedDevice != null) {
                        connectToMac(pluggedDevice)
                    }
                }

                override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
                    when (state) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            hostDevice = device
                            _connectedDeviceName.value = device?.name ?: "Mac"
                            _connectionState.value = "Підключено до ${device?.name ?: "Mac"}"
                        }
                        BluetoothProfile.STATE_CONNECTING -> {
                            _connectionState.value = "Підключення до ${device?.name ?: "Mac"}..."
                        }
                        BluetoothProfile.STATE_DISCONNECTED -> {
                            if (hostDevice == device) {
                                hostDevice = null
                                _connectedDeviceName.value = null
                            }
                            _connectionState.value = "Відключено"
                        }
                    }
                }
            }
        )
    }

    fun connectToMac(device: BluetoothDevice) {
        hidDevice?.connect(device)
    }

    fun disconnect() {
        hostDevice?.let { hidDevice?.disconnect(it) }
    }

    /**
     * Відправляє пакет миші/тачпада (Report ID 1)
     */
    fun sendMouseReport(
        leftButton: Boolean = isDraggingButtonHeld,
        rightButton: Boolean = false,
        middleButton: Boolean = false,
        dx: Int = 0,
        dy: Int = 0,
        verticalScroll: Int = 0,
        horizontalScroll: Int = 0
    ) {
        val device = hostDevice ?: return
        val hid = hidDevice ?: return

        var buttons = 0
        if (leftButton) buttons = buttons or 1
        if (rightButton) buttons = buttons or 2
        if (middleButton) buttons = buttons or 4

        val report = byteArrayOf(
            buttons.toByte(),
            dx.coerceIn(-127, 127).toByte(),
            dy.coerceIn(-127, 127).toByte(),
            verticalScroll.coerceIn(-127, 127).toByte(),
            horizontalScroll.coerceIn(-127, 127).toByte()
        )
        hid.sendReport(device, REPORT_ID_MOUSE, report)
    }

    /**
     * Виконує миттєвий клік (лівий, правий або середній)
     */
    fun performClick(left: Boolean = false, right: Boolean = false, middle: Boolean = false, label: String) {
        _lastSentEvent.value = label
        sendMouseReport(leftButton = left, rightButton = right, middleButton = middle)
        mainHandler.postDelayed({
            sendMouseReport(leftButton = false, rightButton = false, middleButton = false)
        }, 25)
    }

    /**
     * Подвійний клік (наприклад, для Smart Zoom або виділення слова)
     */
    fun performDoubleClick(label: String) {
        _lastSentEvent.value = label
        sendMouseReport(leftButton = true)
        mainHandler.postDelayed({ sendMouseReport(leftButton = false) }, 25)
        mainHandler.postDelayed({ sendMouseReport(leftButton = true) }, 90)
        mainHandler.postDelayed({ sendMouseReport(leftButton = false) }, 120)
    }

    /**
     * Керування утриманням кнопки миші для жесту перетягування 3 пальцями (Three-Finger Drag)
     */
    fun setThreeFingerDragState(active: Boolean) {
        if (isDraggingButtonHeld != active) {
            isDraggingButtonHeld = active
            sendMouseReport(leftButton = active)
            _lastSentEvent.value = if (active) "3 пальці: Початок перетягування (Drag)" else "3 пальці: Завершення перетягування"
        }
    }

    /**
     * Відправляє клавіатурне скорочення macOS (Report ID 2) для системних жестів Apple Trackpad
     */
    fun sendMacShortcut(modifier: Int, keyCode: Byte, gestureDescription: String) {
        val device = hostDevice
        val hid = hidDevice
        _lastSentEvent.value = gestureDescription

        if (device == null || hid == null) return

        val pressReport = byteArrayOf(
            modifier.toByte(),
            0x00,
            keyCode,
            0x00, 0x00, 0x00, 0x00, 0x00
        )
        val releaseReport = ByteArray(8) { 0x00 }

        hid.sendReport(device, REPORT_ID_KEYBOARD, pressReport)
        mainHandler.postDelayed({
            hid.sendReport(device, REPORT_ID_KEYBOARD, releaseReport)
        }, 35)
    }

    // ================= СИСТЕМНІ ЖЕСТИ APPLE MAGIC TRACKPAD =================

    /** Mission Control: свайп 3 або 4 пальцями вгору (Ctrl + Up Arrow) */
    fun triggerMissionControl() {
        sendMacShortcut(MOD_LCTRL.toInt(), KEY_UP, "Mission Control (Свайп 3/4 пальцями вгору)")
    }

    /** App Exposé: свайп 3 або 4 пальцями вниз (Ctrl + Down Arrow) */
    fun triggerAppExpose() {
        sendMacShortcut(MOD_LCTRL.toInt(), KEY_DOWN, "App Exposé (Свайп 3/4 пальцями вниз)")
    }

    /** Перемикання на попередній робочий стіл / повноекранний додаток (Ctrl + Left Arrow) */
    fun triggerPreviousSpace() {
        sendMacShortcut(MOD_LCTRL.toInt(), KEY_LEFT, "Попередній робочий стіл (Свайп 3/4 пальцями вправо)")
    }

    /** Перемикання на наступний робочий стіл / повноекранний додаток (Ctrl + Right Arrow) */
    fun triggerNextSpace() {
        sendMacShortcut(MOD_LCTRL.toInt(), KEY_RIGHT, "Наступний робочий стіл (Свайп 3/4 пальцями вліво)")
    }

    /** Launchpad: зведення 4 пальців до центру */
    fun triggerLaunchpad() {
        sendMacShortcut(MOD_NONE.toInt(), KEY_F4, "Launchpad (Щипок 4 пальцями всередину)")
    }

    /** Показати робочий стіл (Show Desktop): розведення 4 пальців назовні (Fn + F11 / Cmd + F3) */
    fun triggerShowDesktop() {
        sendMacShortcut(MOD_LGUI.toInt(), KEY_F3, "Показати Робочий стіл (Розведення 4 пальців)")
    }

    /** Force Click / Довідка та швидкий перегляд (Quick Look / Look Up): Ctrl + Cmd + D */
    fun triggerLookUpOrQuickLook() {
        sendMacShortcut(MOD_LCTRL.toInt() or MOD_LGUI.toInt(), KEY_D, "Force Touch / Довідка (Ctrl+Cmd+D)")
    }

    /** Smart Zoom (Подвійний дотик двома пальцями): скидання або збільшення масштабу (Cmd + 0 / Cmd + +) */
    private var isSmartZoomed = false
    fun triggerSmartZoom() {
        isSmartZoomed = !isSmartZoomed
        if (isSmartZoomed) {
            sendMacShortcut(MOD_LGUI.toInt(), KEY_EQUAL, "Smart Zoom: Збільшити (Подвійний тап 2 пальцями)")
        } else {
            sendMacShortcut(MOD_LGUI.toInt(), KEY_0, "Smart Zoom: 100% (Подвійний тап 2 пальцями)")
        }
    }

    /** Масштабування щипком 2 пальців (Pinch to Zoom): Cmd + Plus / Cmd + Minus */
    fun triggerPinchZoom(zoomIn: Boolean) {
        if (zoomIn) {
            sendMacShortcut(MOD_LGUI.toInt(), KEY_EQUAL, "Збільшення масштабу (Pinch In ⌘+)")
        } else {
            sendMacShortcut(MOD_LGUI.toInt(), KEY_MINUS, "Зменшення масштабу (Pinch Out ⌘-)")
        }
    }

    /** Обертання двома пальцями (Rotate): Cmd + R (у Фото/Preview) */
    fun triggerRotate(clockwise: Boolean) {
        val mod = if (clockwise) MOD_LGUI.toInt() else (MOD_LGUI.toInt() or MOD_LSHIFT.toInt())
        sendMacShortcut(mod, KEY_R, if (clockwise) "Обертання за годинниковою стрілкою (2 пальці)" else "Обертання проти годинникової стрілки (2 пальці)")
    }

    /** Навігація назад/вперед у Safari, Chrome, Finder (Cmd + [ / Cmd + ]) */
    fun triggerHistoryNavigation(back: Boolean) {
        if (back) {
            sendMacShortcut(MOD_LGUI.toInt(), KEY_LBRACKET, "Назад на попередню сторінку (⌘[)")
        } else {
            sendMacShortcut(MOD_LGUI.toInt(), KEY_RBRACKET, "Вперед на наступну сторінку (⌘])")
        }
    }

    /** Центр сповіщень (Notification Center): свайп 2 пальцями від правого краю */
    fun triggerNotificationCenter() {
        // У macOS можна призначити Ctrl+Opt+Cmd+N у Системних параметрах -> Клавіатура -> Скорочення -> Центру сповіщень
        sendMacShortcut(
            MOD_LCTRL.toInt() or MOD_LALT.toInt() or MOD_LGUI.toInt(),
            KEY_N,
            "Центр сповіщень (Свайп 2 пальцями від правого краю)"
        )
    }

    /** Spotlight Search (Cmd + Space) */
    fun triggerSpotlight() {
        sendMacShortcut(MOD_LGUI.toInt(), KEY_SPACE, "Пошук Spotlight (⌘ + Пробіл)")
    }
}
