package com.wowmouse

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Wow Mouse: turns the watch into a Bluetooth mouse.
 * Hand movement (gyroscope) moves the pointer, a shake or the CLICK button clicks.
 */
@SuppressLint("MissingPermission")
class MainActivity : Activity(), SensorEventListener {

    // Standard HID report descriptor: 3 buttons, relative X, relative Y, wheel (no report ID).
    private val descriptor = intArrayOf(
        0x05, 0x01, 0x09, 0x02, 0xA1, 0x01, 0x09, 0x01, 0xA1, 0x00,
        0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15, 0x00, 0x25, 0x01, 0x95, 0x03, 0x75, 0x01, 0x81, 0x02,
        0x95, 0x01, 0x75, 0x05, 0x81, 0x03,
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38, 0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x03, 0x81, 0x06,
        0xC0, 0xC0
    ).map { it.toByte() }.toByteArray()

    private val ui = Handler(Looper.getMainLooper())
    private var hid: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null

    private lateinit var statusView: TextView
    private lateinit var sm: SensorManager

    private var sensitivity = 30      // 0..100, low default on purpose
    private var flipX = false
    private var flipY = false
    private var shakeOn = true
    private var hold = false
    private var buttons = 0
    private var accX = 0f
    private var accY = 0f
    private var lastTs = 0L
    private var quietUntil = 0L
    private var lastClick = 0L

    // ---------- lifecycle ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sm = getSystemService(SENSOR_SERVICE) as SensorManager
        buildUi()
        if (hasPerms()) initHid() else requestPermissions(neededPerms(), 1)
    }

    override fun onResume() {
        super.onResume()
        val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        if (gyro == null) setStatus("This watch has no gyroscope, so aiming cannot work.")
        else sm.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME)
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onPause() {
        super.onPause()
        sm.unregisterListener(this)
        hold = false
        lastTs = 0L
    }

    override fun onDestroy() {
        super.onDestroy()
        try { hid?.unregisterApp() } catch (_: Exception) {}
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasPerms()) initHid() else setStatus("Bluetooth permission is needed. Allow it in app settings.")
    }

    // ---------- permissions + bluetooth ----------

    private fun neededPerms(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE
        ) else emptyArray()

    private fun hasPerms() = neededPerms().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun adapter(): BluetoothAdapter? =
        (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            runOnUiThread {
                setStatus(if (registered) "Ready. Pair from your computer, or tap Connect." else "Mouse is not registered.")
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            runOnUiThread {
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    host = device
                    setStatus("Connected to ${device?.name ?: device?.address}")
                } else if (state == BluetoothProfile.STATE_DISCONNECTED && device == host) {
                    host = null
                    setStatus("Disconnected. Tap Connect.")
                }
            }
        }
    }

    private fun initHid() {
        val a = adapter()
        if (a == null || !a.isEnabled) {
            setStatus("Turn Bluetooth on, then reopen the app.")
            return
        }
        a.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile != BluetoothProfile.HID_DEVICE) return
                val h = proxy as BluetoothHidDevice
                hid = h
                val sdp = BluetoothHidDeviceAppSdpSettings(
                    "Wow Mouse", "Watch air mouse", "Wow",
                    BluetoothHidDevice.SUBCLASS1_MOUSE, descriptor
                )
                val ok = h.registerApp(sdp, null, null, mainExecutor, callback)
                if (!ok) setStatus("This watch does not allow a Bluetooth mouse.")
            }

            override fun onServiceDisconnected(profile: Int) {
                hid = null
                host = null
                setStatus("Bluetooth mouse service stopped.")
            }
        }, BluetoothProfile.HID_DEVICE)
    }

    private fun makeDiscoverable() {
        val i = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
        i.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)
        startActivity(i)
    }

    private fun chooseHost() {
        val a = adapter() ?: return
        val devices = a.bondedDevices.toList()
        if (devices.isEmpty()) {
            setStatus("No paired devices yet. Make discoverable and pair from your computer first.")
            return
        }
        val names = devices.map { it.name ?: it.address }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Connect to")
            .setItems(names) { _, i -> hid?.connect(devices[i]) }
            .show()
    }

    // ---------- sending mouse reports ----------

    private fun send(dx: Int, dy: Int) {
        val h = hid ?: return
        val d = host ?: return
        h.sendReport(d, 0, byteArrayOf(buttons.toByte(), dx.toByte(), dy.toByte(), 0))
    }

    private fun click(mask: Int) {
        if (host == null) {
            setStatus("Not connected to a device yet.")
            return
        }
        buttons = buttons or mask
        send(0, 0)
        ui.postDelayed({
            buttons = buttons and mask.inv()
            send(0, 0)
        }, 50)
    }

    // ---------- sensors ----------

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> onGyro(e)
            Sensor.TYPE_ACCELEROMETER -> onAccel(e)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun onGyro(e: SensorEvent) {
        val now = e.timestamp
        if (lastTs == 0L) { lastTs = now; return }
        val dt = (now - lastTs) / 1_000_000_000f
        lastTs = now
        if (hold || host == null || SystemClock.elapsedRealtime() < quietUntil) return

        var yaw = e.values[2]     // rotation around the axis through the screen: left/right
        var pitch = e.values[0]   // rotation around the 3-9 o'clock axis: up/down
        if (abs(yaw) < 0.03f) yaw = 0f
        if (abs(pitch) < 0.03f) pitch = 0f

        val k = 100f + sensitivity * 7f       // pixels per radian
        var dx = -yaw * dt * k
        var dy = -pitch * dt * k
        if (flipX) dx = -dx
        if (flipY) dy = -dy
        accX += dx
        accY += dy

        val ix = accX.toInt().coerceIn(-127, 127)
        val iy = accY.toInt().coerceIn(-127, 127)
        if (ix != 0 || iy != 0) {
            accX -= ix
            accY -= iy
            send(ix, iy)
        }
    }

    private fun onAccel(e: SensorEvent) {
        if (!shakeOn) return
        val x = e.values[0]
        val y = e.values[1]
        val z = e.values[2]
        val delta = abs(sqrt(x * x + y * y + z * z) - 9.81f)
        val now = SystemClock.elapsedRealtime()
        if (delta > 14f && now - lastClick > 700) {
            lastClick = now
            quietUntil = now + 400   // pointer stays still while the hand settles
            click(1)
        }
    }

    // ---------- UI ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun setStatus(msg: String) {
        statusView.text = msg
    }

    private fun button(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            setOnClickListener { onClick() }
        }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(Color.BLACK)
        }

        statusView = TextView(this).apply {
            setTextColor(Color.parseColor("#E8B04A"))
            textSize = 14f
            text = "Starting..."
        }
        root.addView(statusView)

        root.addView(button("Make discoverable") { makeDiscoverable() })
        root.addView(button("Connect to device") { chooseHost() })

        root.addView(TextView(this).apply {
            setTextColor(Color.LTGRAY)
            textSize = 12f
            text = "Sensitivity (left = lower)"
        })
        root.addView(SeekBar(this).apply {
            max = 100
            progress = sensitivity
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { sensitivity = p }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })

        val flipXBtn = button("Flip left/right: OFF") {}
        flipXBtn.setOnClickListener {
            flipX = !flipX
            flipXBtn.text = "Flip left/right: " + (if (flipX) "ON" else "OFF")
        }
        root.addView(flipXBtn)

        val flipYBtn = button("Flip up/down: OFF") {}
        flipYBtn.setOnClickListener {
            flipY = !flipY
            flipYBtn.text = "Flip up/down: " + (if (flipY) "ON" else "OFF")
        }
        root.addView(flipYBtn)

        val shakeBtn = button("Shake to click: ON") {}
        shakeBtn.setOnClickListener {
            shakeOn = !shakeOn
            shakeBtn.text = "Shake to click: " + (if (shakeOn) "ON" else "OFF")
        }
        root.addView(shakeBtn)

        val clickBtn = button("CLICK") { click(1) }
        clickBtn.minimumHeight = dp(80)
        root.addView(clickBtn)

        root.addView(button("Right click") { click(2) })

        // Hold this to freeze the pointer (like lifting a mouse), then reposition your hand.
        val holdBtn = Button(this).apply {
            text = "HOLD (freeze pointer)"
            setOnTouchListener { v, ev ->
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> { hold = true; v.isPressed = true }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { hold = false; v.isPressed = false }
                }
                true
            }
        }
        root.addView(holdBtn)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(root)
        })
    }
}
