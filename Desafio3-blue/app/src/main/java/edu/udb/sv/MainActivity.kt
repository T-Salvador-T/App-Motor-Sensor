package edu.udb.sv

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var connectButton: Button
    private lateinit var btnLeft: Button
    private lateinit var btnRight: Button
    private lateinit var btnStop: Button
    private lateinit var btnToggleMode: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvDistance: TextView
    private lateinit var tvMotorState: TextView
    private lateinit var tvLog: TextView

    // BLE
    private lateinit var bluetoothManager: BluetoothManager
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bluetoothGatt: BluetoothGatt? = null

    // UUIDs del servicio y características
    private val SERVICE_UUID = UUID.fromString("19B10000-E8F2-537E-4F6C-D104768A1214")
    private val COMANDO_CHARACTERISTIC_UUID = UUID.fromString("19B10001-E8F2-537E-4F6C-D104768A1214")
    private val SENSOR_CHARACTERISTIC_UUID = UUID.fromString("19B10002-E8F2-537E-4F6C-D104768A1214")

    private var comandoCharacteristic: BluetoothGattCharacteristic? = null
    private var sensorCharacteristic: BluetoothGattCharacteristic? = null

    private var isConnected = false
    private var isScanning = false
    private var modoAutomatico = false // VARIABLE CRÍTICA
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        private const val REQUEST_BLUETOOTH_PERMISSIONS = 1
        private const val SCAN_PERIOD: Long = 10000
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        initializeBluetooth()
    }

    private fun initViews() {
        connectButton = findViewById(R.id.connectButton)
        btnLeft = findViewById(R.id.btnLeft)
        btnRight = findViewById(R.id.btnRight)
        btnStop = findViewById(R.id.btnStop)
        btnToggleMode = findViewById(R.id.btnToggleMode)
        tvStatus = findViewById(R.id.tvStatus)
        tvDistance = findViewById(R.id.tvDistance)
        tvMotorState = findViewById(R.id.tvMotorState)
        tvLog = findViewById(R.id.tvLog)

        setupClickListeners()
        logMessage("App BLE iniciada. Buscando 'ARDUINO'...")

        // Inicialmente deshabilitar botones de control
        btnLeft.isEnabled = false
        btnRight.isEnabled = false
        btnStop.isEnabled = false
        btnToggleMode.isEnabled = false
    }

    private fun initializeBluetooth() {
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        if (bluetoothAdapter == null) {
            logMessage("ERROR: Bluetooth no disponible")
            tvStatus.text = "Bluetooth no disponible"
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            logMessage("Bluetooth desactivado. Actívalo en ajustes.")
            tvStatus.text = "Bluetooth desactivado"
            return
        }

        checkPermissions()
    }

    private fun getRequiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN
            )
        }
    }

    private fun checkPermissions() {
        val permissions = getRequiredPermissions()
        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest, REQUEST_BLUETOOTH_PERMISSIONS)
        } else {
            logMessage("Todos los permisos concedidos")
            startScanning()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_BLUETOOTH_PERMISSIONS -> {
                if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    logMessage("Permisos concedidos. Escaneando...")
                    startScanning()
                } else {
                    logMessage("Permisos denegados. No se puede usar BLE.")
                    tvStatus.text = "Permisos denegados"
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScanning() {
        if (isScanning) return

        logMessage("Buscando dispositivo 'ARDUINO'...")
        tvStatus.text = "Buscando ARDUINO..."
        isScanning = true
        connectButton.isEnabled = false

        val leScanner = bluetoothAdapter.bluetoothLeScanner

        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                super.onScanResult(callbackType, result)
                val device = result.device
                val deviceName = device.name ?: "Sin nombre"

                logMessage("Dispositivo encontrado: $deviceName")

                if (deviceName == "ARDUINO") {
                    logMessage("¡Encontrado ARDUINO! Conectando...")
                    leScanner.stopScan(this)
                    isScanning = false
                    connectToDevice(device)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                super.onScanFailed(errorCode)
                logMessage("Error en escaneo: $errorCode")
                isScanning = false
                handler.post { connectButton.isEnabled = true }
            }
        }

        handler.postDelayed({
            if (isScanning) {
                leScanner.stopScan(scanCallback)
                isScanning = false
                handler.post {
                    connectButton.isEnabled = true
                    if (!isConnected) {
                        logMessage("No se encontró 'ARDUINO'")
                        logMessage("Verifica:")
                        logMessage("   - Arduino encendido")
                        logMessage("   - Bluetooth activado")
                        logMessage("   - Dispositivo visible")
                        tvStatus.text = "ARDUINO no encontrado"
                    }
                }
            }
        }, SCAN_PERIOD)

        leScanner.startScan(scanCallback)
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        logMessage("Conectando a ARDUINO...")
        tvStatus.text = "Conectando a ARDUINO..."

        bluetoothGatt = device.connectGatt(this, false, gattCallback)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    logMessage("¡Conectado a ARDUINO!")
                    handler.post {
                        tvStatus.text = "Conectado a ARDUINO"
                        connectButton.text = "Desconectar"
                        isConnected = true
                        connectButton.isEnabled = true

                        // Habilitar botones de control
                        btnLeft.isEnabled = true
                        btnRight.isEnabled = true
                        btnStop.isEnabled = true
                        btnToggleMode.isEnabled = true
                    }
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    logMessage("Desconectado de ARDUINO")
                    handler.post {
                        disconnect()
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            super.onServicesDiscovered(gatt, status)

            if (status == BluetoothGatt.GATT_SUCCESS) {
                logMessage("Servicios descubiertos")
                setupCharacteristics(gatt)
            } else {
                logMessage("Error descubriendo servicios: $status")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            super.onCharacteristicChanged(gatt, characteristic)

            if (characteristic.uuid == SENSOR_CHARACTERISTIC_UUID) {
                val data = characteristic.getStringValue(0)
                handler.post {
                    processSensorData(data ?: "")
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            super.onCharacteristicWrite(gatt, characteristic, status)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                logMessage("Comando enviado exitosamente")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun setupCharacteristics(gatt: BluetoothGatt) {
        val service = gatt.getService(SERVICE_UUID)
        if (service != null) {
            comandoCharacteristic = service.getCharacteristic(COMANDO_CHARACTERISTIC_UUID)
            sensorCharacteristic = service.getCharacteristic(SENSOR_CHARACTERISTIC_UUID)

            if (comandoCharacteristic != null && sensorCharacteristic != null) {
                // Activar notificaciones
                gatt.setCharacteristicNotification(sensorCharacteristic, true)
                val descriptor = sensorCharacteristic!!.getDescriptor(
                    UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
                )
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(descriptor)

                logMessage("¡Conexión completada!")
                logMessage("Ya puedes controlar el motor")

            } else {
                logMessage("No se encontraron las características")
            }
        } else {
            logMessage("No se encontró el servicio BLE")
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendCommand(command: String) {
        if (!isConnected || comandoCharacteristic == null) {
            logMessage("ERROR: No conectado a ARDUINO")
            return
        }

        comandoCharacteristic!!.value = command.toByteArray()
        bluetoothGatt?.writeCharacteristic(comandoCharacteristic)
        logMessage("Enviando: $command")
    }

    private fun processSensorData(data: String) {
        val parts = data.split(",")
        parts.forEach { part ->
            when {
                part.startsWith("DISTANCIA:") -> {
                    val distance = part.removePrefix("DISTANCIA:")
                    tvDistance.text = "$distance cm"
                }
                part.startsWith("MODO:") -> {
                    val mode = part.removePrefix("MODO:")
                    val nuevoModoAuto = mode == "AUTO"

                    // ACTUALIZAR SI HAY CAMBIO
                    if (nuevoModoAuto != modoAutomatico) {
                        modoAutomatico = nuevoModoAuto
                        btnToggleMode.text = if (modoAutomatico) "Cambiar a Manual" else "Cambiar a Automático"
                        logMessage("MODO ACTUALIZADO: ${if (modoAutomatico) "AUTOMÁTICO" else "MANUAL"}")
                    }
                }
                part.startsWith("MOTOR:") -> {
                    val motorState = part.removePrefix("MOTOR:")
                    tvMotorState.text = "$motorState"
                }
            }
        }
    }

    private fun setupClickListeners() {
        connectButton.setOnClickListener {
            if (isConnected) {
                disconnect()
            } else {
                startScanning()
            }
        }

        btnLeft.setOnClickListener {
            if (!modoAutomatico) {
                sendCommand("IZQUIERDA")
                logMessage("Comando: Girar IZQUIERDA")
            } else {
                logMessage("MODO AUTO - Cambia a Manual para controlar")
            }
        }

        btnRight.setOnClickListener {
            if (!modoAutomatico) {
                sendCommand("DERECHA")
                logMessage("Comando: Girar DERECHA")
            } else {
                logMessage("MODO AUTO - Cambia a Manual para controlar")
            }
        }

        btnStop.setOnClickListener {
            sendCommand("DETENER")
            logMessage("Comando: DETENER motor")
        }

        btnToggleMode.setOnClickListener {
            // CAMBIAR INMEDIATAMENTE EL ESTADO LOCAL
            modoAutomatico = !modoAutomatico
            btnToggleMode.text = if (modoAutomatico) "Cambiar a Manual" else "Cambiar a Automático"

            // ENVIAR COMANDO AL ARDUINO
            val comando = if (modoAutomatico) "MODO_AUTO" else "MODO_MANUAL"
            sendCommand(comando)

            logMessage(if (modoAutomatico)
                "Solicitando MODO AUTOMÁTICO..."
            else
                "Solicitando MODO MANUAL...")
        }
    }

    @SuppressLint("MissingPermission")
    private fun disconnect() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        isScanning = false

        handler.post {
            isConnected = false
            modoAutomatico = false
            connectButton.text = "Conectar BLE"
            tvStatus.text = "Desconectado"
            tvDistance.text = "-- cm"
            tvMotorState.text = "--"

            // Deshabilitar botones
            btnLeft.isEnabled = false
            btnRight.isEnabled = false
            btnStop.isEnabled = false
            btnToggleMode.isEnabled = false

            logMessage("Desconectado de ARDUINO")
        }
    }

    private fun logMessage(message: String) {
        handler.post {
            val currentText = tvLog.text.toString()
            val lines = currentText.split("\n").take(12)
            tvLog.text = "$message\n${lines.joinToString("\n")}".trim()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnect()
    }
}