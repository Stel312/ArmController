package com.example.wearimu

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.Vignette
import androidx.wear.compose.material.VignettePosition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import android.bluetooth.BluetoothAdapter  // Added import
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.pm.ServiceInfo

class ImuDataService : Service(), SensorEventListener {
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private var magnetometer: Sensor? = null
    private var linearAcceleration: Sensor? = null
    private var rotationVector: Sensor? = null
    private var bluetoothSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private val appUuid = UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66")
    private var serverSocket: BluetoothServerSocket? = null
    private var connectionJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val notificationChannelId = "IMU_SERVICE_CHANNEL"
    private val notificationId = 1
    private val restartDelay = 5000L // 5 seconds delay before restarting server

    private var isBluetoothConnected by mutableStateOf(false)
    private var isSendingData by mutableStateOf(false)

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

    // Add state to hold sensor data for display in the activity
    var accDataX by mutableStateOf(0.0f)
        private set
    var accDataY by mutableStateOf(0.0f)
        private set
    var accDataZ by mutableStateOf(0.0f)
        private set

    var gyroDataX by mutableStateOf(0.0f)
        private set
    var gyroDataY by mutableStateOf(0.0f)
        private set
    var gyroDataZ by mutableStateOf(0.0f)
        private set

    var magDataX by mutableStateOf(0.0f)
        private set
    var magDataY by mutableStateOf(0.0f)
        private set
    var magDataZ by mutableStateOf(0.0f)
        private set

    override fun onCreate() {
        super.onCreate()
        Log.d("ImuDataService", "onCreate called")
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        linearAcceleration = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        createNotificationChannel()
        registerSensors()
        startForeground(notificationId, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        startBluetoothServer()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                notificationChannelId,
                "IMU Data Service",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager =
                getSystemService(NotificationManager::class.java) as NotificationManager
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent =
            PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, notificationChannelId)
            .setContentTitle("IMU Data Service")
            .setContentText("Sending IMU data in the background")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)

        return builder.build()
    }

    private fun registerSensors() {
        linearAcceleration?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
        }
        rotationVector?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
        }
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
        }
        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
        }
        magnetometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
        }
    }

    private fun unregisterSensors() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                filteredAccX = alpha * x + (1 - alpha) * filteredAccX
                filteredAccY = alpha * y + (1 - alpha) * filteredAccY
                filteredAccZ = alpha * z + (1 - alpha) * filteredAccZ

                // Update the state variables
                accDataX = filteredAccX
                accDataY = filteredAccY
                accDataZ = filteredAccZ

                val data = "acc,$filteredAccX,$filteredAccY,$filteredAccZ"
                if (isSendingData && isBluetoothConnected && outputStream != null) {
                    sendData(data)
                }
            }
            Sensor.TYPE_GYROSCOPE -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                filteredGyroX = alpha * x + (1 - alpha) * filteredGyroX
                filteredGyroY = alpha * y + (1 - alpha) * filteredGyroY
                filteredGyroZ = alpha * z + (1 - alpha) * filteredGyroZ

                gyroDataX = filteredGyroX
                gyroDataY = filteredGyroY
                gyroDataZ = filteredGyroZ

                val data = "gyro,$filteredGyroX,$filteredGyroY,$filteredGyroZ"
                if (isSendingData && isBluetoothConnected && outputStream != null) {
                    sendData(data)
                }
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                filteredMagX = alpha * x + (1 - alpha) * filteredMagX
                filteredMagY = alpha * y + (1 - alpha) * filteredMagY
                filteredMagZ = alpha * z + (1 - alpha) * filteredMagZ

                magDataX = filteredMagX
                magDataY = filteredMagY
                magDataZ = filteredMagZ

                val data = "mag,$filteredMagX,$filteredMagY,$filteredMagZ"
                if (isSendingData && isBluetoothConnected && outputStream != null) {
                    sendData(data)
                }
            }
            Sensor.TYPE_ROTATION_VECTOR -> {
                // Handle rotation vector sensor data if needed
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                val w = event.values[3]
                val data = "rotation,$x,$y,$z,$w"
                if (isSendingData && isBluetoothConnected && outputStream != null) {
                    sendData(data)
                }
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                // Handle linear acceleration sensor data if needed
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                val data = "linear,$x,$y,$z"
                if (isSendingData && isBluetoothConnected && outputStream != null) {
                    sendData(data)
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}

    private fun sendData(data: String) {
        try {
            outputStream?.write((data + "\n").toByteArray())
        } catch (e: IOException) {
            Log.e("ImuDataService", "Error sending data: ${e.message}")
            isBluetoothConnected = false
            isSendingData = false
            closeSocketAndStreams()
            // The startBluetoothServer will handle the restart
        }
    }

    private fun startBluetoothServer() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e("ImuDataService", "Bluetooth Connect Permission Denied")
            return
        }

        connectionJob?.cancel()
        connectionJob = serviceScope.launch {
            while (isActive) {
                var currentServerSocket: BluetoothServerSocket? = null
                try {
                    if (serverSocket == null) {
                        serverSocket =
                            BluetoothAdapter.getDefaultAdapter()?.listenUsingInsecureRfcommWithServiceRecord(
                                "IMU_Data",
                                appUuid
                            )
                        withContext(Dispatchers.Main) {  // Use Dispatchers.Main
                            Log.d("ImuDataService", "Server listening for connection...")
                        }
                    }
                    currentServerSocket = serverSocket
                    val socket: BluetoothSocket? = try {
                        currentServerSocket?.accept()
                    } catch (e: IOException) {
                        if (!isActive) {
                            Log.d("ImuDataService", "Server accept operation cancelled.")
                            break
                        } else {
                            withContext(Dispatchers.Main) { // Use Dispatchers.Main
                                Log.e(
                                    "ImuDataService",
                                    "Bluetooth server accept error: ${e.message}"
                                )
                                isBluetoothConnected = false
                                isSendingData = false
                            }
                            delay(restartDelay)
                            null
                        }
                    }

                    socket?.let { connectedSocket ->
                        bluetoothSocket = connectedSocket
                        withContext(Dispatchers.Main) {  // Use Dispatchers.Main
                            Log.d("ImuDataService", "Bluetooth connection established.")
                            isBluetoothConnected = true
                        }
                        outputStream = connectedSocket.outputStream
                        isSendingData = true
                        registerSensors()

                        try {
                            val inputStream = connectedSocket.inputStream
                            val buffer = ByteArray(1024)
                            var bytesRead = -1
                            while (isActive && connectedSocket.isConnected && inputStream.read(buffer)
                                    .also { bytesRead = it } != -1
                            ) {
                                if (bytesRead > 0) {
                                    val receivedMessage =
                                        String(buffer, 0, bytesRead, Charsets.UTF_8).trim()
                                    Log.d("ImuDataService", "Received: $receivedMessage")
                                    // Handle received messages if needed
                                }
                            }
                            withContext(Dispatchers.Main) {  // Use Dispatchers.Main
                                Log.d("ImuDataService", "Bluetooth disconnected by client.")
                                isBluetoothConnected = false
                                isSendingData = false
                            }
                        } catch (e: IOException) {
                            withContext(Dispatchers.Main) { // Use Dispatchers.Main
                                Log.e(
                                    "ImuDataService",
                                    "Bluetooth connection error (read/write): ${e.message}"
                                )
                                isBluetoothConnected = false
                                isSendingData = false
                            }
                        } finally {
                            unregisterSensors()
                            closeSocketAndStreams()
                            // Start the server again after a delay
                            delay(restartDelay)
                            startBluetoothServer() // Restart the server
                        }
                    }
                } finally {
                    if (!isActive) {
                        closeServerSocket()
                        break
                    }
                }
            }
            closeServerSocket()
            withContext(Dispatchers.Main) {  // Use Dispatchers.Main
                Log.d("ImuDataService", "Bluetooth server stopped.")
            }
        }
    }

    private fun closeSocketAndStreams() {
        try {
            bluetoothSocket?.close()
        } catch (e: IOException) {
            Log.e("ImuDataService", "Error closing bluetooth socket: ${e.message}")
        } finally {
            bluetoothSocket = null
        }
        try {
            outputStream?.close()
        } catch (e: IOException) {
            Log.e("ImuDataService", "Error closing output stream: ${e.message}")
        } finally {
            outputStream = null
        }
    }

    private fun closeServerSocket() {
        try {
            serverSocket?.close()
        } catch (e: IOException) {
            Log.e("ImuDataService", "Error closing server socket: ${e.message}")
        } finally {
            serverSocket = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        connectionJob?.cancel()
        unregisterSensors()
        serviceScope.cancel()
        closeSocketAndStreams()
        closeServerSocket()
        Log.d("ImuDataService", "onDestroy")
    }

    override fun onBind(intent: Intent): IBinder? {
        return null // Not binding
    }
}

class MainActivity : ComponentActivity() {

    private lateinit var requestPermissionLauncher: ActivityResultLauncher<Array<String>>
    private var isBluetoothServiceRunning by mutableStateOf(false)

    // Use the state variables from the service
    private var accData by mutableStateOf("Acc: X=0.00, Y=0.00, Z=0.00")
    private var gyroData by mutableStateOf("Gyro: X=0.00, Y=0.00, Z=0.00")
    private var magData by mutableStateOf("Mag: X=0.00, Y=0.00, Z=0.00")
    private var isConnected by mutableStateOf(false)

    private var serviceIntent: Intent? = null

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)

        requestPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val allGranted = permissions.all { it.value }
            if (allGranted) {
                startImuDataService()
            } else {
                Log.e("MainActivity", "Bluetooth permissions denied.")
            }
        }

        if (checkBluetoothPermissions()) {
            startImuDataService()
        }

        setContent {
            val currentIsConnected = remember { mutableStateOf(false) }

            // Declare the service instance using remember
            val imuDataService = remember {
                ImuDataService().apply {
                }
            }

            LaunchedEffect(Unit) {
                while (isActive) {
                    currentIsConnected.value = isBluetoothServiceRunning
                    // Access the data from the service instance.
                    accData =
                        "Acc: X=${imuDataService.accDataX.format(2)}, Y=${imuDataService.accDataY.format(
                            2
                        )}, Z=${imuDataService.accDataZ.format(2)}"
                    gyroData =
                        "Gyro: X=${imuDataService.gyroDataX.format(2)}, Y=${imuDataService.gyroDataY.format(
                            2
                        )}, Z=${imuDataService.gyroDataZ.format(2)}"
                    magData =
                        "Mag: X=${imuDataService.magDataX.format(2)}, Y=${imuDataService.magDataY.format(
                            2
                        )}, Z=${imuDataService.accDataZ.format(2)}"

                    delay(16) // Update data at approximately 60Hz.  Adjust as needed.
                }
            }

            WearApp(
                accData = accData,
                gyroData = gyroData,
                magData = magData,
                isConnected = currentIsConnected.value
            )
        }
    }

    private fun checkBluetoothPermissions(): Boolean {
        val permissions = arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.FOREGROUND_SERVICE
        )
        val permissionsNotGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (permissionsNotGranted.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsNotGranted.toTypedArray())
            return false
        }
        return true
    }

    private fun startImuDataService() {
        serviceIntent = Intent(this, ImuDataService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        isBluetoothServiceRunning = true
    }

    private fun stopImuDataService() {
        serviceIntent?.let { stopService(it) }
        isBluetoothServiceRunning = false
    }

    override fun onDestroy() {
        super.onDestroy()
        stopImuDataService()
    }

    @Composable
    fun WearApp(accData: String, gyroData: String, magData: String, isConnected: Boolean) {
        Scaffold(
            vignette = { Vignette(vignettePosition = VignettePosition.Bottom) }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colors.background)
                    .padding(8.dp),  // Add some padding
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

                Spacer(modifier = Modifier.height(16.dp)) // Add space before the canvas

                // Add a Canvas for a simple visual representation
                Canvas(
                    modifier = Modifier
                        .size(100.dp) // Give it a fixed size
                        .padding(8.dp),
                ) {
                    val center = Offset(size.width / 2, size.height / 2)
                    val radius = size.minDimension / 2

                    // Draw a circle
                    drawCircle(
                        color = if (isConnected) Color.Green else Color.Red,
                        center = center,
                        radius = radius,
                        style = Stroke(width = 4.dp.toPx()) // Make the circle a stroke
                    )

                    // Visualize accelerometer data with a line
                    val accX =
                        accData.substringAfter("Acc: X=").substringBefore(",").toFloatOrNull() ?: 0f
                    val accY =
                        accData.substringAfter("Y=").substringBefore(",").toFloatOrNull() ?: 0f

                    if (accX != 0f && accY != 0f) {
                        val endX =
                            center.x + accX * 20 // Scale the accelerometer values for visualization
                        val endY = center.y + accY * 20

                        drawLine(
                            color = Color.Blue,
                            start = center,
                            end = Offset(endX, endY),
                            strokeWidth = 4.dp.toPx()  //Added strokeWidth
                        )
                    }
                }
            }
        }
    }

    // Extension function to format floats for display
    private fun Float.format(digits: Int) = "%.${digits}f".format(this)
}

