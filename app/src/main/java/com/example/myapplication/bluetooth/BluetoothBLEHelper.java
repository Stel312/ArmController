package com.example.myapplication.bluetooth;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.example.myapplication.definitions.UUID.BluetoothUUID;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class BluetoothBLEHelper {

    private static final String TAG = "BluetoothHelper";

    // UUIDs for ESP32 IMU BLE Service and Characteristics
    // These must match the UUIDs defined in your ESP32 firmware
    public static final UUID IMU_SERVICE_UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb"); // Example Service UUID
    public static final UUID ROTATION_VECTOR_CHAR_UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb"); // Example for Rotation Vector (Human Interface Device service)
    public static final UUID LINEAR_ACCELERATION_CHAR_UUID = UUID.fromString("00002a38-0000-1000-8000-00805f9b34fb"); // Example for Linear Acceleration (Environmental Sensing service)
    public static final UUID CLIENT_CHARACTERISTIC_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final Context context;
    private final BluetoothAdapter bluetoothAdapter;
    private BleGattCallback fragmentGattCallback; // The fragment will implement this
    private BluetoothGatt connectedGatt;

    // Queue for GATT operations to prevent race conditions
    private final Queue<Runnable> gattOperationsQueue = new LinkedList<>();
    private boolean isProcessingQueue = false;
    private final Handler handler = new Handler(Looper.getMainLooper()); // Handler for queue processing

    private final Map<UUID, CharacteristicProfile> characteristicProfiles = new ConcurrentHashMap<>();
    // For BLE scanning
    private BluetoothLeScanner bluetoothLeScanner;
    private BleScanCallback bleScanCallback; // Callback for BLE scan results
    private boolean isBleScanning = false;

    public BluetoothBLEHelper(Context context, BluetoothAdapter bluetoothAdapter) {
        this.context = context;
        this.bluetoothAdapter = bluetoothAdapter;
        if (bluetoothAdapter != null) {
            bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
        }

        characteristicProfiles.put(BluetoothUUID.CLAW_CHAR_UUID, new CharacteristicProfile(100)); // 100ms rate (10 Hz)
        characteristicProfiles.put(BluetoothUUID.LINEAR_ACCELERATION_CHAR_UUID, new CharacteristicProfile(20)); // 100ms rate (10 Hz)
        characteristicProfiles.put(BluetoothUUID.ROTATION_VECTOR_CHAR_UUID, new CharacteristicProfile(20)); // 100ms rate (10 Hz)


    }
    private static class CharacteristicProfile {
        final long rateLimitMs; // The minimum time between sends for this characteristic.
        long lastWriteTime = 0; // The timestamp of the last queued write.

        CharacteristicProfile(long rateLimitMs) {
            this.rateLimitMs = rateLimitMs;
        }
    }
    public void connectBLEDevice(String address, BleGattCallback callback) {
        if (bluetoothAdapter == null || address == null) {
            Log.w(TAG, "BluetoothAdapter not initialized or unspecified address.");
            callback.onConnectionFailed("BluetoothAdapter not initialized or unspecified address.");
            return;
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "BLUETOOTH_CONNECT permission not granted.");
            callback.onConnectionFailed("BLUETOOTH_CONNECT permission not granted.");
            return;
        }

        this.fragmentGattCallback = callback;
        BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
        if (device == null) {
            Log.w(TAG, "Device not found. Unable to connect.");
            callback.onConnectionFailed("Device not found.");
            return;
        }

        // We want to connect directly to the GATT server and not autoConnect
        connectedGatt = device.connectGatt(context, false, gattCallback);
        Log.d(TAG, "Attempting to create a new GATT connection.");
    }

    public void disconnectBLEDevice(BluetoothGatt gatt) {
        if (bluetoothAdapter == null || gatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized or GATT is null.");
            return;
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "BLUETOOTH_CONNECT permission not granted for disconnect.");
            return;
        }
        gatt.disconnect();
        closeGatt();
    }

    public void closeGatt() {
        if (connectedGatt == null) {
            return;
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "BLUETOOTH_CONNECT permission not granted for closeGatt.");
            return;
        }
        connectedGatt.close();
        connectedGatt = null;
        gattOperationsQueue.clear();
        isProcessingQueue = false;
        Log.d(TAG, "GATT client closed and queue cleared.");
    }

    public void enableCharacteristicNotifications(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        if (gatt == null || characteristic == null) {
            Log.e(TAG, "Gatt or characteristic is null, cannot enable notifications.");
            completedOperation(); // Ensure queue processing continues even on null
            return;
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "BLUETOOTH_CONNECT permission not granted for enableCharacteristicNotifications.");
            completedOperation(); // Ensure queue processing continues
            return;
        }
        CharacteristicProfile profile = characteristicProfiles.get(characteristic.getUuid());

        // If a profile exists for this characteristic, apply rate-limiting.
        if (profile != null) {
            long currentTime = System.currentTimeMillis();

            // Check if the time since the last write is less than the defined rate limit.
            if ((currentTime - profile.lastWriteTime) < profile.rateLimitMs) {
                // It's too soon. Drop the packet and do not queue the operation.
                Log.v(TAG, "Rate limit applied for " + characteristic.getUuid() + ". Dropping packet.");
                return;
            }

            // It's time to send. Update the last write time for this profile.
            profile.lastWriteTime = currentTime;
        }
        gattOperationsQueue.add(() -> {
            Log.d(TAG, "Attempting to set characteristic notification for: " + characteristic.getUuid());
            boolean success = gatt.setCharacteristicNotification(characteristic, true);
            if (success) {
                BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID);
                if (descriptor != null) {
                    descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    boolean writeSuccess = gatt.writeDescriptor(descriptor);
                    if (!writeSuccess) {
                        Log.e(TAG, "Failed to write descriptor for characteristic: " + characteristic.getUuid());
                        completedOperation();
                    } else {
                        Log.d(TAG, "Queued descriptor write for notifications: " + characteristic.getUuid());
                    }
                } else {
                    Log.e(TAG, "CCCD descriptor not found for characteristic: " + characteristic.getUuid());
                    completedOperation();
                }
            } else {
                Log.e(TAG, "Failed to set characteristic notification for: " + characteristic.getUuid());
                completedOperation();
            }
        });
        if (!isProcessingQueue) {
            processNextOperation();
        }
    }

    public void writeCharacteristic(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] value) {
        if (gatt == null || characteristic == null) {
            Log.e(TAG, "Gatt or characteristic is null, cannot write.");
            completedOperation(); // Ensure queue processing continues on null
            return;
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "BLUETOOTH_CONNECT permission not granted for writeCharacteristic.");
            completedOperation(); // Ensure queue processing continues
            return;
        }

        if ((characteristic.getProperties() & (BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) == 0) {
            Log.e(TAG, "Characteristic " + characteristic.getUuid() + " does not support write operations.");
            completedOperation(); // Ensure queue processing continues
            return;
        }

        characteristic.setValue(value);

        gattOperationsQueue.add(() -> {
            boolean success = gatt.writeCharacteristic(characteristic);
            if (!success) {
                Log.e(TAG, "Failed to write characteristic " + characteristic.getUuid() + " to device.");
                completedOperation();
            } else {
                Log.d(TAG, "Queued characteristic write: " + characteristic.getUuid() + " with value: " + bytesToHex(value));
            }
        });
        if (!isProcessingQueue) {
            processNextOperation();
        }
    }

    private void processNextOperation() {
        if (isProcessingQueue) {
            return;
        }

        if (gattOperationsQueue.isEmpty()) {
            isProcessingQueue = false;
            Log.d(TAG, "GATT operations queue is empty.");
            return;
        }

        isProcessingQueue = true;
        Runnable operation = gattOperationsQueue.peek();
        Log.d(TAG, "Processing next GATT operation...");
        handler.post(operation);
    }

    private void completedOperation() {
        // Remove the operation that just completed
        gattOperationsQueue.poll();
        isProcessingQueue = false;
        // Schedule next operation with a small delay to avoid overwhelming the BLE stack
        handler.postDelayed(this::processNextOperation, 100);
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            super.onConnectionStateChange(gatt, status, newState);
            handler.post(() -> {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.i(TAG, "Connected to GATT client. Discovering services...");
                    if (fragmentGattCallback != null) {
                        fragmentGattCallback.onConnectionStateChange(gatt, status, newState);
                    }
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                        gatt.discoverServices();
                    } else {
                        Log.e(TAG, "BLUETOOTH_CONNECT permission not granted for discoverServices.");
                        if (fragmentGattCallback != null) {
                            fragmentGattCallback.onConnectionFailed("Permission denied for service discovery.");
                        }
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.i(TAG, "Disconnected from GATT client.");
                    if (fragmentGattCallback != null) {
                        fragmentGattCallback.onConnectionStateChange(gatt, status, newState);
                        fragmentGattCallback.onDisconnected();
                    }
                    closeGatt();
                } else {
                    Log.e(TAG, "Connection state changed: status=" + status + ", newState=" + newState);
                    if (fragmentGattCallback != null) {
                        fragmentGattCallback.onConnectionFailed("Connection state error: " + status);
                    }
                    closeGatt();
                }
            });
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            super.onServicesDiscovered(gatt, status);
            handler.post(() -> {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "Services discovered successfully.");
                    if (fragmentGattCallback != null) {
                        fragmentGattCallback.onServicesDiscovered(gatt, status);
                    }

                    BluetoothGattService imuService = gatt.getService(IMU_SERVICE_UUID);
                    if (imuService == null) {
                        Log.e(TAG, "IMU Service not found with UUID: " + IMU_SERVICE_UUID);
                        // Trigger a connection failed or specific error if the primary service is missing
                        if (fragmentGattCallback != null) {
                            fragmentGattCallback.onConnectionFailed("IMU Service not found.");
                        }
                        return; // Stop processing if service is not found
                    }

                    BluetoothGattCharacteristic rotationVectorChar = imuService.getCharacteristic(ROTATION_VECTOR_CHAR_UUID);
                    BluetoothGattCharacteristic linearAccelerationChar = imuService.getCharacteristic(LINEAR_ACCELERATION_CHAR_UUID);

                    if (rotationVectorChar != null && linearAccelerationChar != null) {
                        Log.d(TAG, "Found Rotation Vector and Linear Acceleration notification characteristics.");
                        if (fragmentGattCallback != null) {
                            fragmentGattCallback.onCharacteristicsDiscovered(linearAccelerationChar, rotationVectorChar);
                        }
                    } else {
                        Log.e(TAG, "One or more required IMU notification characteristics not found.");
                        if (fragmentGattCallback != null) {
                            fragmentGattCallback.onConnectionFailed("Required IMU notification characteristics not found.");
                        }
                    }

                } else {
                    Log.w(TAG, "onServicesDiscovered received: " + status);
                    if (fragmentGattCallback != null) {
                        fragmentGattCallback.onServicesDiscovered(gatt, status);
                    }
                }
            });
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            super.onCharacteristicRead(gatt, characteristic, status);
            handler.post(() -> {
                Log.d(TAG, "onCharacteristicRead: " + characteristic.getUuid().toString().substring(4, 8) + ", status: " + status);
                if (fragmentGattCallback != null) {
                    fragmentGattCallback.onCharacteristicRead(gatt, characteristic, status);
                }
                completedOperation();
            });
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            super.onCharacteristicWrite(gatt, characteristic, status);
            handler.post(() -> {
                Log.d(TAG, "onCharacteristicWrite: " + characteristic.getUuid().toString().substring(4, 8) + ", status: " + status);
                if (fragmentGattCallback != null) {
                    fragmentGattCallback.onCharacteristicWrite(gatt, characteristic, status);
                }
                completedOperation();
            });
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            super.onCharacteristicChanged(gatt, characteristic);
            handler.post(() -> {
                if (fragmentGattCallback != null) {
                    fragmentGattCallback.onCharacteristicChanged(gatt, characteristic);
                }
            });
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            super.onDescriptorWrite(gatt, descriptor, status);
            handler.post(() -> {
                Log.d(TAG, "onDescriptorWrite: " + descriptor.getCharacteristic().getUuid().toString().substring(4, 8) + ", status: " + status);
                completedOperation();
            });
        }
    };

    // --- BLE Scanning ---

    // Internal BLE ScanCallback
    private final ScanCallback bleScanInternalCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            super.onScanResult(callbackType, result);
            if (bleScanCallback != null && result != null && result.getDevice() != null) {
                bleScanCallback.onBleDeviceFound(result.getDevice(), result.getRssi(), result.getScanRecord() != null ? result.getScanRecord().getBytes() : null);
            }
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            super.onBatchScanResults(results);
            // Process batch results if needed, usually onScanResult is sufficient for real-time updates.
        }

        @Override
        public void onScanFailed(int errorCode) {
            super.onScanFailed(errorCode);
            Log.e(TAG, "BLE Scan failed with error code: " + errorCode);
            if (bleScanCallback != null) {
                bleScanCallback.onScanFailed(errorCode);
            }
            isBleScanning = false;
        }
    };


    // Starts BLE device scanning
    public void startBleScan(BleScanCallback callback) {
        if (bluetoothLeScanner == null) {
            Log.e(TAG, "BluetoothLeScanner not available. Check if BLE is supported.");
            if (callback != null) callback.onScanFailed(ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED);
            return;
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "BLE scan permissions (BLUETOOTH_SCAN, ACCESS_FINE_LOCATION) not granted.");
            //if (callback != null) callback.onScanFailed(ScanCallback.SCAN_FAILED_MISSING_LOCATION_PERMISSION); // Custom error code could be used
            return;
        }
        if (isBleScanning) {
            Log.w(TAG, "Already performing BLE scan.");
            return;
        }

        this.bleScanCallback = callback;
        isBleScanning = true;
        Log.d(TAG, "Starting BLE scan...");
        bluetoothLeScanner.startScan(bleScanInternalCallback);
    }

    // Stops BLE device scanning
    public void stopBleScan() {
        if (bluetoothLeScanner == null) {
            return;
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            // Log, but no need to fail. Just can't stop if permission revoked mid-scan.
            Log.e(TAG, "BLUETOOTH_SCAN permission not granted for stopBleScan. Cannot stop scan.");
            return;
        }
        if (isBleScanning) {
            Log.d(TAG, "Stopping BLE scan.");
            bluetoothLeScanner.stopScan(bleScanInternalCallback);
            isBleScanning = false;
        }
    }


    // Helper for logging byte arrays
    private static String bytesToHex(byte[] bytes) {
        if (bytes == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }
}
