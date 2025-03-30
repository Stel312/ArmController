package com.example.wearimu

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
    private var bluetoothSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null // Keep the output stream
    private val APP_UUID = UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66")
    private lateinit var requestPermissionLauncher: ActivityResultLauncher<Array<String>>

    // State to hold sensor data
    private var accData by mutableStateOf("Acc: X=0.00, Y=0.00, Z=0.00")
    private var gyroData by mutableStateOf("Gyro: X=0.00, Y=0.00, Z=0.00")

    private val sensorEventListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                accData = "Acc: X=${String.format("%.2f", x)}, Y=${String.format("%.2f", y)}, Z=${String.format("%.2f", z)}"
                //sendSensorData("$x,$y,$z,acc") //remove from here
                sendData("$x,$y,$z,acc") //use new sendData
            } else if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                gyroData = "Gyro: X=${String.format("%.2f", x)}, Y=${String.format("%.2f", y)}, Z=${String.format("%.2f", z)}"
                //sendSensorData("$x,$y,$z,gyro")  //remove from here
                sendData("$x,$y,$z,gyro") //use new sendData
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

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
            WearApp(accData = accData, gyroData = gyroData)
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
            )  // Use fastest
        }
        gyroscope?.let {
            sensorManager.registerListener(
                sensorEventListener,
                it,
                SensorManager.SENSOR_DELAY_FASTEST
            ) // Use fastest
        }
    }

    private fun unregisterSensors() {
        sensorManager.unregisterListener(sensorEventListener)
    }

    private fun sendSensorData(data: String) { //remove this
        GlobalScope.launch(Dispatchers.IO) {
            try {
                bluetoothSocket?.let {
                    val outputStream: OutputStream = it.outputStream
                    outputStream.write((data + "\n").toByteArray())
                }
            } catch (e: IOException) {
                Log.e("MainActivity", "Error sending data: ${e.message}")
            }
        }
    }

    private fun sendData(data: String) { //new sendData
        try {
            outputStream?.write((data + "\n").toByteArray())
        } catch (e: IOException) {
            Log.e("MainActivity", "Error sending data: ${e.message}")
        }
    }

    private fun startBluetoothServer() {
        if (!checkBluetoothPermissions()) return
        GlobalScope.launch(Dispatchers.IO) {
            val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
            try {
                val serverSocket: BluetoothServerSocket =
                    bluetoothAdapter.listenUsingRfcommWithServiceRecord("IMU_Data", APP_UUID)
                bluetoothSocket = serverSocket.accept()
                bluetoothSocket?.let{ //get outputStream
                    outputStream = it.outputStream
                }
                withContext(Dispatchers.Main) {
                    Log.d("MainActivity", "Bluetooth connection established.")
                }
                //serverSocket.close()  // Keep the server socket open
            } catch (e: IOException) {
                withContext(Dispatchers.Main) {
                    Log.e("MainActivity", "Bluetooth server error: ${e.message}")
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
    fun WearApp(accData: String, gyroData: String) {
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
                    text = "Sending IMU Data",
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
            }
        }
    }
}
