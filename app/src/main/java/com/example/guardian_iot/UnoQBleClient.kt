package com.example.guardian_iot

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** BLE GATT client for the UNO Q sensor peripheral. Internet is not required for readings. */
internal class UnoQBleClient(
    context: Context,
    private val onReading: (JSONObject) -> Unit,
    private val onStatus: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private var scanner = manager.adapter?.bluetoothLeScanner
    private var scanCallback: ScanCallback? = null
    private var gatt: BluetoothGatt? = null
    private var characteristics = emptyList<BluetoothGattCharacteristic>()
    private var descriptorIndex = 0
    private var scanning = false
    private var active = false
    @Volatile var isConnected: Boolean = false
        private set

    private var bpm: Int? = null
    private var spo2: Int? = null
    private var falls: Long? = null
    private val scanTimeoutTask = Runnable {
        if (active && scanning) {
            stopScan()
            retry("Pulsera no encontrada; verifica que el UNO Q esté encendido y cerca.")
        }
    }

    fun start() {
        if (active) return
        active = true
        handler.post { beginScan() }
    }

    fun stop() {
        active = false
        isConnected = false
        handler.removeCallbacksAndMessages(null)
        stopScan()
        characteristics = emptyList()
        bpm = null; spo2 = null; falls = null
        runCatching { gatt?.disconnect(); gatt?.close() }
        gatt = null
    }

    private fun hasScanPermission(): Boolean = if (Build.VERSION.SDK_INT >= 31) {
        appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    } else appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun beginScan() {
        if (!active || isConnected || gatt != null || scanning) return
        if (!hasScanPermission()) {
            publishStatus("Permite dispositivos cercanos para conectar la pulsera por Bluetooth.")
            return
        }
        val adapter = manager.adapter
        if (adapter == null || !adapter.isEnabled) {
            publishStatus("Activa Bluetooth para buscar la pulsera UNO Q.")
            handler.postDelayed({ beginScan() }, RETRY_DELAY_MS)
            return
        }
        scanner = adapter.bluetoothLeScanner
        val activeScanner = scanner
        if (activeScanner == null) {
            publishStatus("Este teléfono no admite la búsqueda BLE.")
            handler.postDelayed({ beginScan() }, RETRY_DELAY_MS)
            return
        }
        publishStatus("Buscando la pulsera UNO Q por Bluetooth…")
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (!active || gatt != null) return
                stopScan()
                publishStatus("Pulsera encontrada; conectando…")
                gatt = runCatching {
                    result.device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                }.getOrNull()
                if (gatt == null) retry("No se pudo abrir la conexión BLE.")
            }

            override fun onScanFailed(errorCode: Int) {
                stopScan()
                retry("No se pudo buscar la pulsera (BLE $errorCode).")
            }
        }
        scanCallback = callback
        scanning = true
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        runCatching { activeScanner.startScan(filters, settings, callback) }
            .onFailure { stopScan(); retry("No se pudo iniciar Bluetooth: ${it.javaClass.simpleName}.") }
        handler.removeCallbacks(scanTimeoutTask)
        handler.postDelayed(scanTimeoutTask, SCAN_WINDOW_MS)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(connectedGatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                disconnectAndRetry(connectedGatt, "La conexión BLE de la pulsera se interrumpió.")
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    gatt = connectedGatt
                    val started = runCatching { connectedGatt.discoverServices() }.getOrDefault(false)
                    if (!started) disconnectAndRetry(connectedGatt, "No se pudieron consultar los servicios BLE.")
                }
                BluetoothProfile.STATE_DISCONNECTED -> disconnectAndRetry(connectedGatt, "Pulsera desconectada; buscando de nuevo…")
            }
        }

        override fun onServicesDiscovered(connectedGatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                disconnectAndRetry(connectedGatt, "No se pudieron leer los servicios de la pulsera.")
                return
            }
            val service = connectedGatt.getService(SERVICE_UUID)
            val pulse = service?.getCharacteristic(PULSE_UUID)
            val oxygen = service?.getCharacteristic(SPO2_UUID)
            val fallCount = service?.getCharacteristic(FALLS_UUID)
            if (pulse == null || oxygen == null || fallCount == null) {
                disconnectAndRetry(connectedGatt, "El UNO Q no ofrece el servicio Tzinda esperado.")
                return
            }
            characteristics = listOf(pulse, oxygen, fallCount)
            descriptorIndex = 0
            enableNextNotification(connectedGatt)
        }

        override fun onDescriptorWrite(connectedGatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                disconnectAndRetry(connectedGatt, "No se pudo activar el envío de lecturas BLE.")
                return
            }
            descriptorIndex++
            enableNextNotification(connectedGatt)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(connectedGatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            acceptNotification(characteristic, characteristic.value ?: ByteArray(0))
        }

        override fun onCharacteristicChanged(
            connectedGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            acceptNotification(characteristic, value)
        }
    }

    private fun enableNextNotification(connectedGatt: BluetoothGatt) {
        val characteristic = characteristics.getOrNull(descriptorIndex)
        if (characteristic == null) {
            isConnected = true
            publishStatus("Pulsera UNO Q conectada por Bluetooth; recibiendo lecturas.")
            return
        }
        if (!connectedGatt.setCharacteristicNotification(characteristic, true)) {
            disconnectAndRetry(connectedGatt, "Android rechazó las notificaciones de la pulsera.")
            return
        }
        val descriptor = characteristic.getDescriptor(CLIENT_CONFIGURATION_UUID)
        if (descriptor == null) {
            disconnectAndRetry(connectedGatt, "Falta la configuración BLE de notificaciones.")
            return
        }
        val result = if (Build.VERSION.SDK_INT >= 33) {
            connectedGatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            if (connectedGatt.writeDescriptor(descriptor)) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE
        }
        if (result != BluetoothGatt.GATT_SUCCESS) disconnectAndRetry(connectedGatt, "No se pudo activar una notificación BLE.")
    }

    private fun acceptNotification(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        when (characteristic.uuid) {
            PULSE_UUID -> if (value.size >= 2) bpm = unsigned16(value)
            SPO2_UUID -> if (value.isNotEmpty()) spo2 = value[0].toInt() and 0xFF
            FALLS_UUID -> if (value.size >= 4) falls = unsigned32(value)
        }
        val pulse = bpm ?: return
        val oxygen = spo2 ?: return
        val count = falls ?: return
        val capturedAt = System.currentTimeMillis()
        onReading(
            JSONObject()
                .put("source", "arduino_ble")
                .put("bpm", pulse)
                .put("spo2", oxygen)
                .put("caidas", count)
                .put("captured_at_ms", capturedAt),
        )
    }

    private fun disconnectAndRetry(connectedGatt: BluetoothGatt, message: String) {
        if (gatt === connectedGatt) gatt = null
        isConnected = false
        characteristics = emptyList()
        bpm = null; spo2 = null; falls = null
        runCatching { connectedGatt.disconnect(); connectedGatt.close() }
        retry(message)
    }

    private fun retry(message: String) {
        if (!active) return
        publishStatus(message)
        handler.removeCallbacks(retryTask)
        handler.postDelayed(retryTask, RETRY_DELAY_MS)
    }

    private val retryTask = Runnable { beginScan() }

    private fun stopScan() {
        handler.removeCallbacks(scanTimeoutTask)
        val callback = scanCallback
        if (callback != null) runCatching { scanner?.stopScan(callback) }
        scanCallback = null
        scanning = false
    }

    private fun publishStatus(status: String) = handler.post { onStatus(status) }

    private fun unsigned16(value: ByteArray): Int =
        ByteBuffer.wrap(value, 0, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF

    private fun unsigned32(value: ByteArray): Long =
        ByteBuffer.wrap(value, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFF_FFFFL

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("6b9f0001-6e2a-4f72-a11d-545a696e6461")
        val PULSE_UUID: UUID = UUID.fromString("6b9f0002-6e2a-4f72-a11d-545a696e6461")
        val SPO2_UUID: UUID = UUID.fromString("6b9f0003-6e2a-4f72-a11d-545a696e6461")
        val FALLS_UUID: UUID = UUID.fromString("6b9f0004-6e2a-4f72-a11d-545a696e6461")
        private val CLIENT_CONFIGURATION_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val SCAN_WINDOW_MS = 20_000L
        private const val RETRY_DELAY_MS = 3_000L
    }
}
