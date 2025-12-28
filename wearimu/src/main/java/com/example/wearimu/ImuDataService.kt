package com.example.wearimu

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

class ImuDataService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
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
    private val binder = LocalBinder()

    // Add state to hold sensor data for display in the activity
    var linearX by mutableStateOf(0.0f)
        private set
    var linearY by mutableStateOf(0.0f)
        private set
    var linearZ by mutableStateOf(0.0f)
        private set

    var rotationX by mutableStateOf(0.0f)
        private set
    var rotationY by mutableStateOf(0.0f)
        private set
    var rotationZ by mutableStateOf(0.0f)
        private set
    var rotationW by mutableStateOf(0.0f)
        private set

    inner class LocalBinder : Binder() {
        fun getService(): ImuDataService = this@ImuDataService
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("ImuDataService", "onCreate called")
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        linearAcceleration = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        createNotificationChannel()
        registerSensors()
        startForeground(notificationId, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        startBluetoothServer()
    }

    fun getBluetoothConnected(): Boolean {
        return isBluetoothConnected
    }

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(
            notificationChannelId,
            "IMU Data Service",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        val manager =
            getSystemService(NotificationManager::class.java) as NotificationManager
        manager.createNotificationChannel(serviceChannel)
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
    }

    private fun unregisterSensors() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {

            Sensor.TYPE_ROTATION_VECTOR -> {
                // Handle rotation vector sensor data if needed
                rotationX = event.values[0]
                rotationY = event.values[1]
                rotationZ = event.values[2]
                rotationW = event.values[3]
                val data = "rotation,$rotationX,$rotationY,$rotationZ,$rotationW"
                if (isSendingData && isBluetoothConnected && outputStream != null) {
                    sendData(data)
                }
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                // Handle linear acceleration sensor data if needed
                linearX = event.values[0]
                linearY = event.values[1]
                linearZ = event.values[2]
                val data = "linear,$linearX,$linearY,$linearZ"
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
}