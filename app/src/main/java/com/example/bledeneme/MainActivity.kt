package com.example.bledeneme
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var bleDevicesListView: ListView
    private lateinit var bleDevicesAdapter: ArrayAdapter<String>
    private val bleDevices = mutableListOf<String>()



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bleDevicesListView = findViewById(R.id.bleDevicesListView)
        bleDevicesAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, bleDevices)
        bleDevicesListView.adapter = bleDevicesAdapter

        // Bluetooth adapter'ı al
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()

        // Bluetooth'un açık olduğunu kontrol et, değilse kullanıcıyı açmaya yönlendir
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth is not supported on this device", Toast.LENGTH_SHORT)
                .show()
            finish()
            return
        }

        // Bluetooth iznini kontrol et
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            // İzin varsa BLE cihazlarını tarayın
            scanDevices()
        } else {
            // İzin yoksa kullanıcıdan izin isteyin
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                PERMISSION_REQUEST_FINE_LOCATION
            )
        }


        // BLE cihazlarını tarayın
        scanDevices()
    }

    private fun scanDevices() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                    PERMISSION_REQUEST_FINE_LOCATION
                )
                return
            }
        }

        val scanner = bluetoothAdapter.bluetoothLeScanner
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(null, settings, scanCallback)

        Handler().postDelayed({
            scanner.stopScan(scanCallback)
        }, SCAN_PERIOD)
    }

    private val targetDeviceAddress = "FE:49:0A:EF:58:6B"

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.let { scanResult ->
                val device = scanResult.device
                val deviceAddress = device.address
                if (deviceAddress == targetDeviceAddress) { // Sadece hedef MAC
                    val deviceName = device.name ?: "Unknown"
                    val rssi = scanResult.rssi
                    val scanRecord = scanResult.scanRecord
                    val advertisingData = scanRecord?.bytes
                    val relevantData = advertisingData?.sliceArray(13..16)?.toList() // 15., 16. ve 17.
                    Log.d("AdvertisingData", relevantData?.toString() ?: "No relevant data found")

                    val txPower = scanRecord?.txPowerLevel
                    val localName = scanRecord?.deviceName
                    val manufacturerData = scanRecord?.manufacturerSpecificData?.toString()
                    val scanRecordBytes = scanResult.scanRecord?.bytes ?: byteArrayOf()

                    // Reklam verisi formatı
                    val advPacket = scanRecord?.bytes?.joinToString(separator = "") { byte ->
                        "%02X".format(byte)
                    }

                    val desiredLength = 62
                    val desiredData = advPacket?.substring(0, desiredLength)

                    val deviceInfo = buildString {
                        appendLine("$deviceName - $deviceAddress (RSSI: $rssi dBm)")
                        appendLine("Advertising Data: $desiredData")

                        appendLine("Relevant Data: ${relevantData?.joinToString()}")
                        appendLine("Tx Power: $txPower dBm")
                        appendLine("Local Name: $localName")
                        appendLine("Scan Record Bytes: $scanRecordBytes")
                        append("Manufacturer Data: $manufacturerData")
                    }

                    if (!bleDevices.contains(deviceInfo)) {
                        bleDevices.add(deviceInfo)
                        bleDevicesAdapter.notifyDataSetChanged()
                    }
                }
            }
        }
    }


    fun extractServiceUUIDsFromAdvertisingData(scanRecordBytes: ByteArray): List<UUID> {
        val serviceUUIDs = mutableListOf<UUID>()
        var index = 0

        while (index < scanRecordBytes.size) {
            val length = scanRecordBytes[index++].toInt() and 0xFF
            if (length == 0) break // Ulaşılan son parça, döngüyü sonlandır

            val type = scanRecordBytes[index].toInt() and 0xFF
            when (type) {
                // Servis UUID'lerini içeren bölüm
                0x02, 0x03 -> {
                    var innerIndex = 1 // Başlangıç baytını atla
                    while (innerIndex < length) {
                        val uuidBytes = scanRecordBytes.copyOfRange(index + innerIndex, index + innerIndex + 16)
                        val mostSignificantBits = (uuidBytes[1].toInt() and 0xFF) or ((uuidBytes[0].toInt() and 0xFF) shl 8)
                        val leastSignificantBits = (uuidBytes[3].toInt() and 0xFF) or ((uuidBytes[2].toInt() and 0xFF) shl 8) or
                                ((uuidBytes[5].toInt() and 0xFF) shl 16) or ((uuidBytes[4].toInt() and 0xFF) shl 24) or
                                ((uuidBytes[6].toInt() and 0xFF) shl 32) or ((uuidBytes[7].toInt() and 0xFF) shl 40) or
                                ((uuidBytes[8].toInt() and 0xFF) shl 48) or ((uuidBytes[9].toInt() and 0xFF) shl 56)
                        val uuid = UUID(mostSignificantBits.toLong(), leastSignificantBits.toLong())
                        serviceUUIDs.add(uuid)
                        innerIndex += 16 // 16 bayt (128 bit) UUID uzunluğu
                    }
                    index += length // Dış döngüde ilerleme
                }
                else -> index += length // Diğer tipler için ilerleme
            }
        }
        return serviceUUIDs
    }



    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            PERMISSION_REQUEST_FINE_LOCATION -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    scanDevices()
                } else {
                    Toast.makeText(this, "Permission denied to access location", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }


    companion object {
        private const val PERMISSION_REQUEST_FINE_LOCATION = 2
        private const val SCAN_PERIOD: Long = 100000 // 10 seconds
    }
}
