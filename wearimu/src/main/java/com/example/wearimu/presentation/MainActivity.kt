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
import java.io.InputStream
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private var magnetometer: Sensor? = null
    private var bluetoothSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private val APP_UUID = UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66")
    private lateinit var requestPermissionLauncher: ActivityResultLauncher<Array<String>>
    private var isBluetoothConnected by mutableStateOf(false)
    private var isSendingData by mutableStateOf(false)
    private var serverSocket: BluetoothServerSocket? = null
    private var connectionJob: Job? = null

    private var accData by mutableStateOf("Acc: X=0.00, Y=0.00, Z=0.00")
    private var gyroData by mutableStateOf("Gyro: X=0.00, Y=0.00, Z=0.00")
    private var magData by mutableStateOf("Mag: X=0.00, Y=0.00, Z=0.00")

    private var filteredAccX by mutableStateOf(0.0f)
    private var filteredAccY by mutableStateOf(0.0f)
    private var filteredAccZ by mutableStateOf(0.0f)
    private var filteredGyroX by mutableStateOf(0.0f)
    private var filteredGyroY by mutableStateOf(0.0f)
    private var filteredGyroZ by mutableStateOf(0.0f)
    private var filteredMagX by mutableStateOf(0.0f)
    private var filteredMagY by mutableStateOf(0.0f)
    private var filteredMagZ by mutableStateOf(0.0f)

    private val alpha = 0.2f
    private val HEARTBEAT_INTERVAL = 5000L // Send a heartbeat every 5 seconds

    private val sensorEventListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    val x = event.values[0]
                    val y = event.values[1]
                    val z = event.values[2]
                    filteredAccX = alpha * x + (1 - alpha) * filteredAccX
                    filteredAccY = alpha * y + (1 - alpha) * filteredAccY
                    filteredAccZ = alpha * z + (1 - alpha) * filteredAccZ
                    accData = "Acc: X=${String.format("%.2f", filteredAccX)}, Y=${String.format("%.2f", filteredAccY)}, Z=${String.format("%.2f", filteredAccZ)}"
                    if (isSendingData && isBluetoothConnected && outputStream != null) {
                        sendData("$filteredAccX,$filteredAccY,$filteredAccZ,acc")
                    }
                }
                Sensor.TYPE_GYROSCOPE -> {
                    val x = event.values[0]
                    val y = event.values[1]
                    val z = event.values[2]
                    filteredGyroX = alpha * x + (1 - alpha) * filteredGyroX
                    filteredGyroY = alpha * y + (1 - alpha) * filteredGyroY
                    filteredGyroZ = alpha * z + (1 - alpha) * filteredGyroZ
                    gyroData = "Gyro: X=${String.format("%.2f", filteredGyroX)}, Y=${String.format("%.2f", filteredGyroY)}, Z=${String.format("%.2f", filteredGyroZ)}"
                    if (isSendingData && isBluetoothConnected && outputStream != null) {
                        sendData("$filteredGyroX,$filteredGyroY,$filteredGyroZ,gyro")
                    }
                }
                Sensor.TYPE_MAGNETIC_FIELD -> {
                    val x = event.values[0]
                    val y = event.values[1]
                    val z = event.values[2]
                    filteredMagX = alpha * x + (1 - alpha) * filteredMagX
                    filteredMagY = alpha * y + (1 - alpha) * filteredMagY
                    filteredMagZ = alpha * z + (1 - alpha) * filteredMagZ
                    magData = "Mag: X=${String.format("%.2f", filteredMagX)}, Y=${String.format("%.2f", filteredMagY)}, Z=${String.format("%.2f", filteredMagZ)}"
                    if (isSendingData && isBluetoothConnected && outputStream != null) {
                        sendData("$filteredMagX,$filteredMagY,$filteredMagZ,mag")
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
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

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
            WearApp(accData = accData, gyroData = gyroData, magData = magData, isConnected = isBluetoothConnected)
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
            sensorManager.registerListener(sensorEventListener, it, SensorManager.SENSOR_DELAY_FASTEST)
        }
        gyroscope?.let {
            sensorManager.registerListener(sensorEventListener, it, SensorManager.SENSOR_DELAY_FASTEST)
        }
        magnetometer?.let {
            sensorManager.registerListener(sensorEventListener, it, SensorManager.SENSOR_DELAY_FASTEST)
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
            // It's crucial to update the UI on the main thread if a send fails,
            // as it likely indicates a disconnection.
            GlobalScope.launch(Dispatchers.Main) {
                isBluetoothConnected = false
                isSendingData = false
            }
            // Attempt to close the output stream if it's still open
            try {
                outputStream?.close()
            } catch (closeException: IOException) {
                Log.e("MainActivity", "Error closing output stream: ${closeException.message}")
            }
            outputStream = null
            // Optionally, you might want to try and reconnect or restart the server here
            // if the disconnection was unexpected.
        }
    }

    private fun startBluetoothServer() {
        if (!checkBluetoothPermissions()) return

        connectionJob?.cancel()
        connectionJob = GlobalScope.launch(Dispatchers.IO) {
            val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
            var shouldContinueListening = true
            while (shouldContinueListening) {
                var currentServerSocket: BluetoothServerSocket? = null // Local variable for the current server socket
                try {
                    if (serverSocket == null) {
                        serverSocket = bluetoothAdapter.listenUsingInsecureRfcommWithServiceRecord("IMU_Data", APP_UUID)
                        withContext(Dispatchers.Main) {
                            Log.d("MainActivity", "Server listening for connection...")
                        }
                    }
                    currentServerSocket = serverSocket // Assign the potentially initialized serverSocket to the local variable
                    val socket: BluetoothSocket? = try {
                        currentServerSocket?.accept(2000) // Use the local variable here
                    } catch (e: IOException) {
                        if (connectionJob?.isCancelled == true) {
                            Log.d("MainActivity", "Server accept operation cancelled.")
                            shouldContinueListening = false
                            null
                        } else {
                            withContext(Dispatchers.Main) {
                                Log.e("MainActivity", "Bluetooth server accept error: ${e.message}")
                                isBluetoothConnected = false
                                isSendingData = false
                            }
                            delay(5000) // Wait a bit before trying to listen again
                            null
                        }
                    }

                    socket?.let { connectedSocket ->
                        bluetoothSocket = connectedSocket
                        withContext(Dispatchers.Main) {
                            Log.d("MainActivity", "Bluetooth connection established.")
                            isBluetoothConnected = true
                        }
                        outputStream = connectedSocket.outputStream
                        registerSensors()
                        isSendingData = true

                        // Start sending heartbeat
                        //val heartbeatJob = launch { sendHeartbeat() }

                        try {
                            val inputStream: InputStream = connectedSocket.inputStream
                            val buffer = ByteArray(1024)
                            var bytesRead = -1 // Initialize with a value indicating no bytes read yet
                            while (connectedSocket.isConnected && inputStream.read(buffer).also { bytesRead = it } != -1) {
                                if (bytesRead > 0) {
                                    // Process any data received from the client here if needed
                                    val receivedMessage = String(buffer, 0, bytesRead, Charsets.UTF_8).trim()
                                    Log.d("MainActivity", "Received from client: $receivedMessage")
                                    // You might want to respond to the client based on received messages
                                }
                            }
                            withContext(Dispatchers.Main) {
                                Log.d("MainActivity", "Bluetooth disconnected by client.")
                                isBluetoothConnected = false
                                isSendingData = false
                            }
                        } catch (e: IOException) {
                            withContext(Dispatchers.Main) {
                                Log.e("MainActivity", "Bluetooth connection error (read/write): ${e.message}")
                                isBluetoothConnected = false
                                isSendingData = false
                            }
                        } finally {
                            //heartbeatJob.cancel() // Stop sending heartbeat
                            unregisterSensors()
                            closeSocketAndStreams()
                        }
                    }
                } finally {
                    if (connectionJob?.isCancelled == true) {
                        closeServerSocket()
                        shouldContinueListening = false
                    }
                }
            }
            closeServerSocket()
            withContext(Dispatchers.Main) {
                Log.d("MainActivity", "Bluetooth server stopped.")
            }
        }
    }

    private fun sendHeartbeat() = GlobalScope.launch(Dispatchers.IO) {
        while (isBluetoothConnected && outputStream != null) {
            try {
                outputStream?.write("heartbeat\n".toByteArray())
                outputStream?.flush()
                delay(HEARTBEAT_INTERVAL)
            } catch (e: IOException) {
                Log.e("MainActivity", "Error sending heartbeat: ${e.message}")
                withContext(Dispatchers.Main) {
                    isBluetoothConnected = false
                    isSendingData = false
                }
                closeSocketAndStreams()
                break
            }
        }
    }

    private fun closeSocketAndStreams() {
        try {
            bluetoothSocket?.close()
        } catch (e: IOException) {
            Log.e("MainActivity", "Error closing bluetooth socket: ${e.message}")
        } finally {
            bluetoothSocket = null
        }
        try {
            outputStream?.close()
        } catch (e: IOException) {
            Log.e("MainActivity", "Error closing output stream: ${e.message}")
        } finally {
            outputStream = null
        }
    }

    private fun closeServerSocket() {
        try {
            serverSocket?.close()
        } catch (e: IOException) {
            Log.e("MainActivity", "Error closing server socket: ${e.message}")
        } finally {
            serverSocket = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterSensors()
        connectionJob?.cancel()
        closeSocketAndStreams()
        closeServerSocket()
    }

    @Composable
    fun WearApp(accData: String, gyroData: String, magData: String, isConnected: Boolean) {
        Scaffold(
            vignette = { Vignette(vignettePosition = VignettePosition.Bottom) }
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
                Text(
                    text = magData,
                    color = MaterialTheme.colors.onPrimary,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}