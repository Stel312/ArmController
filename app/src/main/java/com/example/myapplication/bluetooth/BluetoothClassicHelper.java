package com.example.myapplication.bluetooth;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.UUID;

public class BluetoothClassicHelper {

    private static final String TAG = "BluetoothClassicHelper";
    private static final UUID APP_UUID = UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66");

    private final Context context;
    private final BluetoothAdapter bluetoothAdapter;
    private BluetoothSocket watchSocket;
    private boolean isConnectedToWatch = false;
    private WatchDataCallback watchDataCallback; // Callback for Watch data processing

    // For Bluetooth Classic device discovery
    private BroadcastReceiver classicDiscoveryReceiver;
    private DeviceCallback classicDiscoveryCallback;
    private boolean isClassicScanning = false;

    private final Handler mainHandler; // Handler for UI updates on the main thread

    // --- Interfaces for Callbacks ---

    // Callback for Bluetooth Classic device discovery
    public interface DeviceCallback {
        void onDeviceFound(BluetoothDevice device);
    }

    // Callback for data received from the Watch
    public interface WatchDataCallback {
        void onDataReceived(String data);
        void onConnectionStatus(boolean isConnected, String message);
        void onReadError(String message);
    }

    public BluetoothClassicHelper(Context context, BluetoothAdapter bluetoothAdapter) {
        this.context = context;
        this.bluetoothAdapter = bluetoothAdapter;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void setWatchDataCallback(WatchDataCallback callback) {
        this.watchDataCallback = callback;
    }

    public boolean isConnectedToWatch() {
        return isConnectedToWatch;
    }

    // Connects to a Bluetooth Classic device (e.g., the Watch)
    public void connectToWatch(String deviceAddress) {
        if (bluetoothAdapter == null) {
            logAndToast("Bluetooth not supported.", true);
            if (watchDataCallback != null) watchDataCallback.onConnectionStatus(false, "Bluetooth not supported.");
            return;
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            logAndToast("Bluetooth connect permission not granted for Watch RFCOMM.", true);
            if (watchDataCallback != null) watchDataCallback.onConnectionStatus(false, "Permission not granted.");
            return;
        }

        BluetoothDevice device = bluetoothAdapter.getRemoteDevice(deviceAddress);
        if (device == null) {
            logAndToast("Watch device not found. Unable to connect.", true);
            if (watchDataCallback != null) watchDataCallback.onConnectionStatus(false, "Device not found.");
            return;
        }

        new Thread(() -> {
            try {
                if (bluetoothAdapter.isDiscovering()) {
                    bluetoothAdapter.cancelDiscovery();
                }

                watchSocket = device.createRfcommSocketToServiceRecord(APP_UUID);
                watchSocket.connect();
                Log.d(TAG, "Connected to watch: " + device.getName());
                isConnectedToWatch = true;
                logAndToast("Connected to Watch", false);
                if (watchDataCallback != null) watchDataCallback.onConnectionStatus(true, "Connected.");
                startReceivingWatchData(); // Start listening for data immediately after connection
            } catch (IOException e) {
                Log.e(TAG, "Failed to connect to watch (" + deviceAddress + "): " + e.getMessage());
                isConnectedToWatch = false;
                logAndToast("Failed to connect to Watch: " + e.getMessage(), true);
                if (watchDataCallback != null) watchDataCallback.onConnectionStatus(false, "Connection failed: " + e.getMessage());
                try {
                    if (watchSocket != null) {
                        watchSocket.close();
                    }
                } catch (IOException closeException) {
                    Log.e(TAG, "Could not close watch socket after connection failure: " + closeException.getMessage());
                }
            }
        }).start();
    }

    // Starts a new thread to receive data from the connected Watch
    private void startReceivingWatchData() {
        if (isConnectedToWatch && watchSocket != null) {
            new Thread(this::receiveWatchData).start();
        } else {
            Log.w(TAG, "Cannot start receiving watch data: Not connected to watch or socket is null.");
            if (watchDataCallback != null) watchDataCallback.onReadError("Not connected to Watch.");
        }
    }

    // Data reception loop for the Watch
    private void receiveWatchData() {
        try {
            InputStream inputStream = watchSocket.getInputStream();
            BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream));
            String receivedData;
            while (isConnectedToWatch && (receivedData = bufferedReader.readLine()) != null) {
                final String data = receivedData;
                mainHandler.post(() -> {
                    if (watchDataCallback != null) {
                        watchDataCallback.onDataReceived(data);
                    }
                });
            }
            Log.d(TAG, "Watch data reception loop ended.");
        } catch (IOException e) {
            Log.e(TAG, "Error receiving data from watch: " + e.getMessage());
            mainHandler.post(() -> {
                if (watchDataCallback != null) {
                    watchDataCallback.onReadError("Error reading data: " + e.getMessage());
                }
                disconnectFromWatch(); // Disconnect on read error
            });
        } finally {
            Log.d(TAG, "receiveWatchData thread finished.");
        }
    }

    // Disconnects from the Bluetooth Classic device
    public void disconnectFromWatch() {
        if (watchSocket != null) {
            try {
                watchSocket.close();
                Log.d(TAG, "Disconnected from Watch RFCOMM.");
                isConnectedToWatch = false;
                logAndToast("Disconnected from Watch", false);
                if (watchDataCallback != null) watchDataCallback.onConnectionStatus(false, "Disconnected.");
            } catch (IOException e) {
                Log.e(TAG, "Error closing Watch RFCOMM socket: " + e.getMessage());
            } finally {
                watchSocket = null;
            }
        }
    }

    // Starts Bluetooth Classic device discovery
    public void startDiscovery(DeviceCallback callback) {
        if (bluetoothAdapter == null) {
            Log.e(TAG, "Bluetooth not supported for discovery.");
            return;
        }
        if (!bluetoothAdapter.isEnabled()) {
            Log.e(TAG, "Bluetooth is not enabled for discovery.");
            return;
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "BLUETOOTH_SCAN permission denied for classic discovery.");
            return;
        }
        if (isClassicScanning) {
            Log.w(TAG, "Already scanning for classic devices.");
            return;
        }

        this.classicDiscoveryCallback = callback;
        if (classicDiscoveryReceiver != null) {
            try {
                context.unregisterReceiver(classicDiscoveryReceiver);
            } catch (IllegalArgumentException e) {
                // Receiver was not registered, ignore
            }
        }

        classicDiscoveryReceiver = new BroadcastReceiver() {
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                    BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (device != null && classicDiscoveryCallback != null) {
                        classicDiscoveryCallback.onDeviceFound(device);
                    }
                }
            }
        };
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        context.registerReceiver(classicDiscoveryReceiver, filter);

        isClassicScanning = bluetoothAdapter.startDiscovery();
        if (isClassicScanning) {
            Log.d(TAG, "Started Bluetooth Classic discovery.");
        } else {
            Log.e(TAG, "Failed to start Bluetooth Classic discovery.");
            context.unregisterReceiver(classicDiscoveryReceiver);
            classicDiscoveryReceiver = null;
        }
    }

    // Cancels Bluetooth Classic device discovery
    public void cancelDiscovery() {
        if (bluetoothAdapter == null) {
            return;
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "BLUETOOTH_SCAN permission denied for cancel classic discovery.");
            return;
        }

        if (isClassicScanning) {
            bluetoothAdapter.cancelDiscovery();
            isClassicScanning = false;
            Log.d(TAG, "Cancelled Bluetooth Classic discovery.");
            if (classicDiscoveryReceiver != null) {
                context.unregisterReceiver(classicDiscoveryReceiver);
                classicDiscoveryReceiver = null;
            }
        }
    }

    private void logAndToast(String message, boolean isError) {
        if (isError) {
            Log.e(TAG, message);
        } else {
            Log.i(TAG, message);
        }
        mainHandler.post(() -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show());
    }
}
