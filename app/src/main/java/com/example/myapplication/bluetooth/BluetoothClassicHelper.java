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

/**
 * A helper class for managing Bluetooth Classic (RFCOMM) connections and device discovery.
 * This class provides functionalities to connect to a specific Bluetooth device (e.g., a "Watch"),
 * receive data from it, and discover other Bluetooth Classic devices.
 *
 * <p>Permissions required:
 * <ul>
 * <li>{@link android.Manifest.permission#BLUETOOTH}</li>
 * <li>{@link android.Manifest.permission#BLUETOOTH_ADMIN}</li>
 * <li>{@link android.Manifest.permission#BLUETOOTH_CONNECT} (for Android 12+)</li>
 * <li>{@link android.Manifest.permission#BLUETOOTH_SCAN} (for Android 12+)</li>
 * <li>{@link android.Manifest.permission#ACCESS_FINE_LOCATION} (for discovery on Android 11 and below)</li>
 * </ul>
 * </p>
 */
public class BluetoothClassicHelper {

    private static final String TAG = "BluetoothClassicHelper";
    /**
     * The UUID used for RFCOMM communication. This UUID should match the UUID used by the
     * server-side (e.g., the Watch) for establishing a connection.
     */
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

    /**
     * Callback interface for Bluetooth Classic device discovery results.
     */
    public interface DeviceCallback {
        /**
         * Called when a new Bluetooth device is found during discovery.
         * @param device The discovered {@link BluetoothDevice}.
         */
        void onDeviceFound(BluetoothDevice device);
    }

    /**
     * Callback interface for receiving data from and monitoring the connection status of the "Watch" device.
     */
    public interface WatchDataCallback {
        /**
         * Called when a line of data is received from the connected Watch device.
         * @param data The received string data.
         */
        void onDataReceived(String data);

        /**
         * Called to report the current connection status to the Watch device.
         * @param isConnected True if connected, false otherwise.
         * @param message A descriptive message about the connection status.
         */
        void onConnectionStatus(boolean isConnected, String message);

        /**
         * Called when an error occurs during the data reading process from the Watch.
         * @param message A descriptive message about the read error.
         */
        void onReadError(String message);
    }

    /**
     * Constructs a new BluetoothClassicHelper.
     *
     * @param context The application context, used for Toast messages and registering receivers.
     * @param bluetoothAdapter The {@link BluetoothAdapter} instance to use for Bluetooth operations.
     */
    public BluetoothClassicHelper(Context context, BluetoothAdapter bluetoothAdapter) {
        this.context = context;
        this.bluetoothAdapter = bluetoothAdapter;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * Sets the callback interface for receiving data and connection status updates from the Watch.
     *
     * @param callback The {@link WatchDataCallback} implementation. Can be null to clear the callback.
     */
    public void setWatchDataCallback(WatchDataCallback callback) {
        this.watchDataCallback = callback;
    }

    /**
     * Checks if the helper is currently connected to the Watch device.
     *
     * @return True if connected, false otherwise.
     */
    public boolean isConnectedToWatch() {
        return isConnectedToWatch;
    }

    /**
     * Initiates a connection attempt to a specific Bluetooth Classic device (e.g., the Watch).
     * This operation is performed on a new background thread to prevent blocking the UI thread.
     * Connection status and errors are reported via the {@link WatchDataCallback}.
     *
     * @param deviceAddress The MAC address of the target Bluetooth device.
     */
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
                // Cancel any ongoing discovery as it can slow down connection
                if (bluetoothAdapter.isDiscovering()) {
                    bluetoothAdapter.cancelDiscovery();
                }

                // Create an RFCOMM socket and attempt to connect
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
                    // Ensure the socket is closed on connection failure
                    if (watchSocket != null) {
                        watchSocket.close();
                    }
                } catch (IOException closeException) {
                    Log.e(TAG, "Could not close watch socket after connection failure: " + closeException.getMessage());
                }
            }
        }).start();
    }

    /**
     * Starts a new background thread to continuously receive data from the connected Watch.
     * Data is read line by line and delivered via the {@link WatchDataCallback#onDataReceived(String)} method.
     * This method is called automatically after a successful connection.
     */
    private void startReceivingWatchData() {
        if (isConnectedToWatch && watchSocket != null) {
            new Thread(this::receiveWatchData).start();
        } else {
            Log.w(TAG, "Cannot start receiving watch data: Not connected to watch or socket is null.");
            if (watchDataCallback != null) watchDataCallback.onReadError("Not connected to Watch.");
        }
    }

    /**
     * The main loop for receiving data from the Watch device.
     * This runs in a background thread and reads data from the socket's input stream.
     * It continues as long as the connection is active and data is available.
     * Errors during reading will cause the connection to be gracefully disconnected.
     */
    private void receiveWatchData() {
        try {
            InputStream inputStream = watchSocket.getInputStream();
            BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream));
            String receivedData;
            // Read data line by line as long as the connection is active and data is available
            while (isConnectedToWatch && (receivedData = bufferedReader.readLine()) != null) {
                final String data = receivedData;
                // Post the data back to the main thread for UI updates
                mainHandler.post(() -> {
                    if (watchDataCallback != null) {
                        watchDataCallback.onDataReceived(data);
                    }
                });
            }
            Log.d(TAG, "Watch data reception loop ended normally (e.g., disconnected).");
        } catch (IOException e) {
            Log.e(TAG, "Error receiving data from watch: " + e.getMessage());
            // Report the error and attempt to disconnect
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

    /**
     * Disconnects from the currently connected Bluetooth Classic device (Watch).
     * Closes the Bluetooth socket and updates the connection status.
     * Connection status is reported via the {@link WatchDataCallback}.
     */
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
                watchSocket = null; // Ensure the socket reference is nulled out
            }
        }
    }

    /**
     * Starts Bluetooth Classic device discovery. Found devices will be reported via the
     * {@link DeviceCallback#onDeviceFound(BluetoothDevice)} method.
     *
     * <p>Requires {@link Manifest.permission#BLUETOOTH_SCAN} permission on Android 12+
     * and {@link Manifest.permission#ACCESS_FINE_LOCATION} on Android 11 and below.</p>
     *
     * @param callback The {@link DeviceCallback} implementation to receive discovered devices.
     */
    public void startDiscovery(DeviceCallback callback) {
        if (bluetoothAdapter == null) {
            Log.e(TAG, "Bluetooth not supported for discovery.");
            return;
        }
        if (!bluetoothAdapter.isEnabled()) {
            Log.e(TAG, "Bluetooth is not enabled for discovery.");
            return;
        }
        // Check for necessary permissions for Bluetooth scanning
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "BLUETOOTH_SCAN permission denied for classic discovery.");
            return;
        }
        if (isClassicScanning) {
            Log.w(TAG, "Already scanning for classic devices. Ignoring new request.");
            return;
        }

        this.classicDiscoveryCallback = callback;
        // Unregister any existing receiver to avoid issues if startDiscovery is called multiple times
        if (classicDiscoveryReceiver != null) {
            try {
                context.unregisterReceiver(classicDiscoveryReceiver);
            } catch (IllegalArgumentException e) {
                // Receiver was not registered, ignore
            }
        }

        // Register a new BroadcastReceiver to listen for ACTION_FOUND broadcasts
        classicDiscoveryReceiver = new BroadcastReceiver() {
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                    BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (device != null && classicDiscoveryCallback != null) {
                        // Pass the discovered device to the provided callback
                        classicDiscoveryCallback.onDeviceFound(device);
                    }
                }
            }
        };
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        context.registerReceiver(classicDiscoveryReceiver, filter);

        // Start the discovery process
        isClassicScanning = bluetoothAdapter.startDiscovery();
        if (isClassicScanning) {
            Log.d(TAG, "Started Bluetooth Classic discovery.");
        } else {
            Log.e(TAG, "Failed to start Bluetooth Classic discovery. Unregistering receiver.");
            // If discovery fails to start, unregister the receiver immediately
            context.unregisterReceiver(classicDiscoveryReceiver);
            classicDiscoveryReceiver = null;
        }
    }

    /**
     * Cancels any ongoing Bluetooth Classic device discovery.
     * This method also unregisters the discovery BroadcastReceiver.
     *
     * <p>Requires {@link Manifest.permission#BLUETOOTH_SCAN} permission on Android 12+.</p>
     */
    public void cancelDiscovery() {
        if (bluetoothAdapter == null) {
            Log.w(TAG, "Bluetooth adapter is null. Cannot cancel discovery.");
            return;
        }
        // Check for necessary permissions to cancel Bluetooth scanning
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "BLUETOOTH_SCAN permission denied for cancel classic discovery.");
            return;
        }

        if (isClassicScanning) {
            bluetoothAdapter.cancelDiscovery();
            isClassicScanning = false;
            Log.d(TAG, "Cancelled Bluetooth Classic discovery.");
            // Unregister the receiver to prevent memory leaks and unnecessary callbacks
            if (classicDiscoveryReceiver != null) {
                try {
                    context.unregisterReceiver(classicDiscoveryReceiver);
                } catch (IllegalArgumentException e) {
                    Log.w(TAG, "Attempted to unregister receiver that was not registered: " + e.getMessage());
                } finally {
                    classicDiscoveryReceiver = null;
                }
            }
        } else {
            Log.d(TAG, "No active classic discovery to cancel.");
        }
    }

    /**
     * Helper method to log a message and display a Toast message on the main UI thread.
     *
     * @param message The message to log and display.
     * @param isError True if the message indicates an error (logs as ERROR), false for info (logs as INFO).
     */
    private void logAndToast(String message, boolean isError) {
        if (isError) {
            Log.e(TAG, message);
        } else {
            Log.i(TAG, message);
        }
        mainHandler.post(() -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show());
    }
}