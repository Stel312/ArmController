package com.example.wearimu

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private lateinit var requestPermissionLauncher: ActivityResultLauncher<Array<String>>
    private var isBluetoothServiceRunning by mutableStateOf(false)

    private var isConnected: Boolean? by mutableStateOf(false)
    private var imuService: ImuDataService? by mutableStateOf(null)
    private var isBound by mutableStateOf(false)
    private var serviceIntent: Intent? = null

    // 2. Define the connection to the service
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as ImuDataService.LocalBinder
            imuService = binder.getService()
            isBound = true
            isConnected = imuService?.getBluetoothConnected()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            imuService = null
            isConnected = false
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Start and Bind the service
        startAndBindService()
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
            // Observe the data directly from the bound service
            val accX = imuService?.linearX ?: 0f
            val accY = imuService?.linearY ?: 0f
            val accZ = imuService?.linearZ ?: 0f
            val rotationX = imuService?.rotationX ?: 0f
            val rotationY = imuService?.rotationY ?: 0f
            val rotationZ = imuService?.rotationZ ?: 0f
            val rotationW = imuService?.rotationW ?: 0f

            val linearText = "Acc: X=${accX.format(2)}, Y=${accY.format(2)}, Z=${accZ.format(2)}"

            val rotationText = "Acc: X=${rotationX.format(2)}, Y=${rotationY.format(2)}, Z=${rotationZ.format(2)},W=${rotationW}"

            WearApp(
                linearText = linearText,
                rotationText = rotationText,
                isConnected = isBound
            )
        }
    }
    private fun startAndBindService() {
        val intent = Intent(this, ImuDataService::class.java)
        startForegroundService(intent) // Keeps it alive
        bindService(intent, connection, BIND_AUTO_CREATE) // Allows UI to talk to it
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
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }

    // Extension function to format floats for display
    private fun Float.format(digits: Int) = "%.${digits}f".format(this)
}