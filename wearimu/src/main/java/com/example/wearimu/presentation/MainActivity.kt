package com.example.wearimu

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Vignette
import androidx.wear.compose.material.VignettePosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private var magnetometer: Sensor? = null  // Add magnetometer
    private var bluetoothSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private val APP_UUID = UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66")
    private lateinit var requestPermissionLauncher: ActivityResultLauncher<Array<String>>
    private var isBluetoothConnected by mutableStateOf(false)
    private var isSendingData by mutableStateOf(false)

    // State to hold sensor data
    private var accData by mutableStateOf("Acc: X=0.00, Y=0.00, Z=0.00")
    private var gyroData by mutableStateOf("Gyro: X=0.00, Y=0.00, Z=0.00")
    private var magData by mutableStateOf("Mag: X=0.00, Y=0.00, Z=0.00") // Add magnetometer data

    private val sensorEventListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    val x = event.values[0]
                    val y = event.values[1]
                    val z = event.values[2]
                    accData = "Acc: X=${String.format("%.2f", x)}, Y=${String.format("%.2f", y)}, Z=${String.format("%.2f", z)}"
                    if (isSendingData) {
                        sendData("$x,$y,$z,acc")
                    }
                }
                Sensor.TYPE_GYROSCOPE -> {
                    val x = event.values[0]
                    val y = event.values[1]
                    val z = event.values[2]
                    gyroData = "Gyro: X=${String.format("%.2f", x)}, Y=${String.format("%.2f", y)}, Z=${String.format("%.2f", z)}"
                    if (isSendingData) {
                        sendData("$x,$y,$z,gyro")
                    }
                }
                Sensor.TYPE_MAGNETIC_FIELD -> { // Handle magnetometer data
                    val x = event.values[0]
                    val y = event.values[1]
                    val z = event.values[2]
                    magData = "Mag: X=${String.format("%.2f", x)}, Y=${String.format("%.2f", y)}, Z=${String.format("%.2f", z)}"
                    if (isSendingData) {
                        sendData("$x,$y,$z,mag")
                    }
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) // Get magnetometer

        requestPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val allGranted = permissions.all { it.value }
            if (allGranted) {
                startBluetoothServer()
                registerSensors()
            } else {
                Log.e("MainActivity", "Bluetooth permissions denied.")
            }
        }

        if (checkBluetoothPermissions()) {
            startBluetoothServer()
            registerSensors()
        }

        setContent {
            WearApp(accData = accData, gyroData = gyroData, magData = magData, isConnected = isBluetoothConnected) // Pass magData
        }
    }

    private fun checkBluetoothPermissions(): Boolean {
        val permissions = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        val permissionsNotGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsNotGranted.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsNotGranted.toTypedArray())
            return false
        }
        return true
    }

    private fun registerSensors() {
        accelerometer?.let {
            sensorManager.registerListener(
                sensorEventListener,
                it,
                SensorManager.SENSOR_DELAY_FASTEST
            )
        }
        gyroscope?.let {
            sensorManager.registerListener(
                sensorEventListener,
                it,
                SensorManager.SENSOR_DELAY_FASTEST
            )
        }
        magnetometer?.let {  // Register magnetometer
            sensorManager.registerListener(
                sensorEventListener,
                it,
                SensorManager.SENSOR_DELAY_FASTEST
            )
        }
    }

    private fun unregisterSensors() {
        sensorManager.unregisterListener(sensorEventListener)
    }

    private fun sendData(data: String) {
        try {
            outputStream?.write((data + "\n").toByteArray())
        } catch (e: IOException) {
            Log.e("MainActivity", "Error sending data: ${e.message}")
            GlobalScope.launch(Dispatchers.Main) {
                isBluetoothConnected = false
                isSendingData = false
            }
            startBluetoothServer()
        }
    }

    private fun startBluetoothServer() {
        if (!checkBluetoothPermissions()) return

        GlobalScope.launch(Dispatchers.IO) {
            val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
            var serverSocket: BluetoothServerSocket? = null
            while (true) {
                try {
                    serverSocket = bluetoothAdapter.listenUsingRfcommWithServiceRecord("IMU_Data", APP_UUID)
                    withContext(Dispatchers.Main) {
                        Log.d("MainActivity", "Server listening for connection...")
                    }
                    bluetoothSocket = serverSocket.accept()
                    withContext(Dispatchers.Main) {
                        Log.d("MainActivity", "Bluetooth connection established.")
                        isBluetoothConnected = true
                    }
                    outputStream = bluetoothSocket?.outputStream
                    registerSensors()
                    isSendingData = true

                    try {
                        val inputStream = bluetoothSocket?.inputStream
                        val buffer = ByteArray(1024)
                        while (inputStream?.read(buffer) != -1) {
                        }
                        withContext(Dispatchers.Main) {
                            Log.d("MainActivity", "Bluetooth disconnected.")
                            isBluetoothConnected = false
                            isSendingData = false
                        }
                        unregisterSensors()
                        outputStream?.close()
                        outputStream = null
                        bluetoothSocket?.close()
                        bluetoothSocket = null


                    } catch (e: IOException) {
                        withContext(Dispatchers.Main) {
                            Log.e("MainActivity", "Bluetooth connection error: ${e.message}")
                            isBluetoothConnected = false
                            isSendingData = false
                        }
                        unregisterSensors()
                        outputStream?.close()
                        outputStream = null
                        bluetoothSocket?.close()
                        bluetoothSocket = null

                    } finally {
                        serverSocket?.close()
                        withContext(Dispatchers.Main) {
                            Log.d("MainActivity", "Restarting server to listen for new connection")
                        }
                    }

                } catch (e: IOException) {
                    withContext(Dispatchers.Main) {
                        Log.e("MainActivity", "Bluetooth server error: ${e.message}")
                    }
                    try{
                        serverSocket?.close()
                    }catch(e: IOException){
                        Log.e("MainActivity", "Error closing server socket: ${e.message}")
                    }

                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterSensors()
        try {
            bluetoothSocket?.close()
            outputStream?.close()
        } catch (e: IOException) {
            Log.e("MainActivity", "Error closing socket: ${e.message}")
        }
    }

    @Composable
    fun WearApp(accData: String, gyroData: String, magData: String, isConnected: Boolean) { // Added magData and isConnected
        Scaffold(
            vignette = {
                Vignette(vignettePosition = VignettePosition.Bottom)
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colors.background),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                TimeText()
                Text(
                    modifier = Modifier.fillMaxSize(),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colors.primary,
                    text = if (isConnected) "Sending IMU Data" else "Disconnected",
                )
                Spacer(modifier = Modifier.height(8.dp))
                // Display sensor data
                Text(
                    text = accData,
                    color = MaterialTheme.colors.onPrimary,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = gyroData,
                    color = MaterialTheme.colors.onPrimary,
                    textAlign = TextAlign.Center
                )
                Text( // Display magnetometer data
                    text = magData,
                    color = MaterialTheme.colors.onPrimary,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

