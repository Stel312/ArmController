package com.example.myapplication.DataProcessing;

import static android.app.PendingIntent.getActivity;
import static java.lang.Math.clamp;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Math;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;

// It's good practice to use Android's Log for logging in an Android project
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.util.Log;

import com.example.myapplication.bluetooth.BleGattCallback;
import com.example.myapplication.bluetooth.BluetoothBLEHelper;
import com.example.myapplication.bluetooth.BluetoothClassicHelper;
import com.example.myapplication.bluetooth.WatchDataCallback;
import com.example.myapplication.control.Kinematics;
import com.example.myapplication.definitions.UUID.BluetoothUUID;
import com.example.myapplication.view.SharedImuViewModel;

/**
 * Processes raw Inertial Measurement Unit (IMU) data from different sources
 * (e.g., RFCOMM Watch connection, BLE ESP32 IMU).
 * It updates internal sensor states, performs conversions (e.g., Quaternion to Euler angles),
 * and can prepare processed data for transmission over BLE to an ESP32 microcontroller.
 *
 * This class uses the JOML (Java OpenGL Mathematics Library) for 3D math operations
 * like vector and quaternion handling.
 */
public class ImuDataProcessor implements ImuDataReadyListener, WatchDataCallback, ImuDataCallback, BleGattCallback {

    private static final String TAG = "ImuDataProcessor";

    /**
     * The MAC address of the Bluetooth Classic (RFCOMM) "Watch" device.
     */
    private static final String WATCH_DEVICE_ADDRESS = "34:E3:FB:82:92:CD";
    private boolean isBleCharacteristicsReady = false;
    private ImuSensorFusion sensorFusion;
    private final Vector3f linearAcceleration = new Vector3f();
    private final Quaternionf quaternion = new Quaternionf(); // Stores rotation from Watch (RFCOMM) or ESP32 (BLE)
    private final Vector3f eulerAngles = new Vector3f(); // Stores rotation in degrees from Watch (RFCOMM) or ESP32 (BLE)
    private short[] armAngles = {0, 0, 0}; // Stores the rotation first second and third servos of what the esp32 thinks the arm is at
    private Kinematics kinematics;
    private BluetoothClassicHelper bluetoothClassicHelper;
    private BluetoothBLEHelper bluetoothBleHelper;
    private String espImuDeviceAddress;
    private SharedImuViewModel sharedViewModel;

    private BluetoothGatt connectedGatt;

    private BluetoothGattCharacteristic linearAccelerationNotificationCharacteristic;
    private BluetoothGattCharacteristic rotationVectorNotificationCharacteristic;
    private BluetoothGattCharacteristic clawNotificationCharacteristic;

    /**
     * Constructs an `ImuDataProcessor`.
     *
     * @param context             .
     * @param bluetoothAdapter
     * @param espImuDeviceAddress
     */
    public ImuDataProcessor(Context context, BluetoothAdapter bluetoothAdapter, String espImuDeviceAddress) {
        this.sensorFusion = new ImuSensorFusion();
        this.kinematics = new Kinematics();
        this.espImuDeviceAddress = espImuDeviceAddress;
        if (bluetoothAdapter != null) {
            bluetoothBleHelper = new BluetoothBLEHelper(context, bluetoothAdapter);
            bluetoothClassicHelper = new BluetoothClassicHelper(context, bluetoothAdapter);
            // Set up the callback for data received from the Bluetooth Classic Watch
            bluetoothClassicHelper.setWatchDataCallback(this);
        }

    }
    // Add this new method to link the ViewModel
    public void setSharedViewModel(SharedImuViewModel viewModel) {
        this.sharedViewModel = viewModel;
    }
    public ImuDataProcessor getInstance(){
        return this;
    }

    @Override
    public void onRotationDataReady(byte[] rotationDataBytes) {
        // Check if BLE connection and characteristics are ready before writing
        if (connectedGatt != null && rotationVectorNotificationCharacteristic != null && isBleCharacteristicsReady) {
            bluetoothBleHelper.writeCharacteristic(connectedGatt, rotationVectorNotificationCharacteristic, rotationDataBytes);
        } else {
            Log.w(TAG, "Cannot send rotation data via BLE: " +
                    (connectedGatt == null ? "ESP IMU GATT is null" : "") +
                    (rotationVectorNotificationCharacteristic == null ? " or rotationVectorNotificationCharacteristic is null" : "") +
                    (!isBleCharacteristicsReady ? " or BLE characteristics are not yet ready" : "") + ".");
        }
    }

    @Override
    public void onLinearAccelerationDataReady(byte[] linearAccelerationDataBytes) {
        // Check if BLE connection and characteristics are ready before writing
        if (connectedGatt != null && linearAccelerationNotificationCharacteristic != null && isBleCharacteristicsReady) {
            short[] armAngles = this.getArmAngles();
            kinematics.update(linearAccelerationDataBytes, armAngles);
            armAngles = kinematics.updateArmAngles();
            this.setArmAngles(armAngles);


            bluetoothBleHelper.writeCharacteristic(connectedGatt, linearAccelerationNotificationCharacteristic, this.convertArmAnglesToBytes());
        } else {
            Log.w(TAG, "Cannot send linear acceleration data via BLE: " +
                    (connectedGatt == null ? "ESP IMU GATT is null" : "") +
                    (linearAccelerationNotificationCharacteristic == null ? " or linearAccelerationNotificationCharacteristic is null" : "") +
                    (!isBleCharacteristicsReady ? " or BLE characteristics are not yet ready" : "") + ".");
        }
    }


    public void onClawDataReady(byte[] clawDataBytes) {
        if(connectedGatt != null && linearAccelerationNotificationCharacteristic != null && isBleCharacteristicsReady) {
            bluetoothBleHelper.writeCharacteristic(connectedGatt, clawNotificationCharacteristic, clawDataBytes);
        }
    }
    /**
     * Callback indicating a change in the BLE connection state.
     * This method is part of the {@link BleGattCallback} interface.
     *
     * @param gatt The {@link BluetoothGatt} object representing the GATT client.
     * @param status Status of the GATT operation. {@link BluetoothGatt#GATT_SUCCESS} if the operation completed successfully.
     * @param newState The new connection state: {@link BluetoothProfile#STATE_CONNECTED} or {@link BluetoothProfile#STATE_DISCONNECTED}.
     */
    @Override
    public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
        connectedGatt = gatt; // Store the GATT instance
        if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
            Log.i(TAG, "BLE Connected to ESP IMU. Discovering services...");
            sharedViewModel.updateStatus("Status: Connected to ESP IMU");
            //showToast("BLE Connected to ESP IMU.");
            isBleCharacteristicsReady = false; // Reset flag until services are discovered
            // Initiate service discovery immediately upon connection
            gatt.discoverServices();
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            Log.i(TAG, "BLE Disconnected from ESP IMU.");
            sharedViewModel.updateStatus("Status: Disconnected");
            //showToast("BLE Disconnected from ESP IMU.");
            // Clear all GATT-related references
            connectedGatt = null;
            linearAccelerationNotificationCharacteristic = null;
            rotationVectorNotificationCharacteristic = null;
            isBleCharacteristicsReady = false;
        } else if (status != BluetoothGatt.GATT_SUCCESS) {
            Log.e(TAG, "BLE Connection error with ESP IMU, status: " + status);
            sharedViewModel.updateStatus("Status: Connection Error (" + status + ")");
            //showToast("BLE Connection error with ESP IMU.");
            // Clear all GATT-related references on error
            connectedGatt = null;
            linearAccelerationNotificationCharacteristic = null;
            rotationVectorNotificationCharacteristic = null;
            isBleCharacteristicsReady = false;
        }
    }

    /**
     * Callback invoked when GATT services have been discovered for the remote device.
     * This method is part of the {@link BleGattCallback} interface.
     *
     * @param gatt The {@link BluetoothGatt} object representing the GATT client.
     * @param status Status of the GATT operation. {@link BluetoothGatt#GATT_SUCCESS} if the operation completed successfully.
     */
    @Override
    public void onServicesDiscovered(BluetoothGatt gatt, int status) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            Log.d(TAG, "Services discovered for ESP IMU. Attempting to get characteristics.");

            // Get the specific IMU service using its UUID
            BluetoothGattService imuService = gatt.getService(BluetoothUUID.IMU_SERVICE_UUID);
            if (imuService != null) {
                // Get the required characteristics within the IMU service
                rotationVectorNotificationCharacteristic = imuService.getCharacteristic(BluetoothUUID.ROTATION_VECTOR_CHAR_UUID);
                linearAccelerationNotificationCharacteristic = imuService.getCharacteristic(BluetoothUUID.LINEAR_ACCELERATION_CHAR_UUID);
                clawNotificationCharacteristic = imuService.getCharacteristic(BluetoothUUID.CLAW_CHAR_UUID);



                if (rotationVectorNotificationCharacteristic != null && linearAccelerationNotificationCharacteristic != null && clawNotificationCharacteristic != null) {
                    Log.d(TAG, "Found Notification and Write Characteristics. Enabling notifications...");
                    // Enable notifications for both characteristics to receive data from ESP32
                    bluetoothBleHelper.enableCharacteristicNotifications(connectedGatt, rotationVectorNotificationCharacteristic);
                    bluetoothBleHelper.enableCharacteristicNotifications(connectedGatt, linearAccelerationNotificationCharacteristic);
                    bluetoothBleHelper.enableCharacteristicNotifications(connectedGatt, clawNotificationCharacteristic);


                    isBleCharacteristicsReady = true; // Set flag indicating characteristics are ready for use
                    sharedViewModel.updateStatus("Status: Connected, Services Ready");
                } else {
                    Log.e(TAG, "Required characteristics not found on ESP IMU. Check UUIDs and ESP32 GATT service definition.");
                    //showToast("BLE: IMU Characteristics not found on ESP32.");
                    isBleCharacteristicsReady = false;
                    sharedViewModel.updateStatus("Status: Services Not Ready");
                }
            } else {
                Log.e(TAG, "IMU Service not found on ESP32 with UUID: " + BluetoothUUID.IMU_SERVICE_UUID);
                //showToast("BLE: IMU Service not found on ESP32.");
                isBleCharacteristicsReady = false;
                sharedViewModel.updateStatus("Status: Service Missing");
            }
        } else {
            Log.e(TAG, "Service discovery failed for ESP IMU with status: " + status);
            //showToast("BLE Service discovery failed for ESP IMU.");
            isBleCharacteristicsReady = false;
            sharedViewModel.updateStatus("Status: Service Discovery Failed (" + status + ")");
        }
    }

    /**
     * This callback is defined in {@link BleGattCallback} but may be redundant
     * depending on how {@link BluetoothBLEHelper} internally manages characteristic discovery.
     * For this specific setup, `onServicesDiscovered` is the primary point for characteristic setup.
     *
     * @param linearAccelerationChar The characteristic for linear acceleration.
     * @param rotationVectorChar The characteristic for rotation vector.
     */
    @Override
    public void onCharacteristicsDiscovered(BluetoothGattCharacteristic linearAccelerationChar, BluetoothGattCharacteristic rotationVectorChar) {
        Log.d(TAG, "onCharacteristicsDiscovered callback from BluetoothBLEHelper triggered (redundant for current setup).");
        // The actual characteristics are obtained and stored in onServicesDiscovered
    }
    /**
     * Callback invoked when the BLE device has been disconnected.
     * This method is part of the {@link BleGattCallback} interface.
     * It clears GATT-related references and updates the UI status.
     */
    @Override
    public void onDisconnected() {
        Log.i(TAG, "BLE device disconnected via BleGattCallback.");
        connectedGatt = null;
        linearAccelerationNotificationCharacteristic = null;
        rotationVectorNotificationCharacteristic = null;
        isBleCharacteristicsReady = false;
        sharedViewModel.updateStatus("Status: Disconnected");
    }

    /**
     * Callback invoked when a BLE connection attempt has failed.
     * This method is part of the {@link BleGattCallback} interface.
     * It clears GATT-related references and updates the UI status.
     *
     * @param message A descriptive message about the connection failure.
     */
    @Override
    public void onConnectionFailed(String message) {
        Log.e(TAG, "BLE Connection failed: " + message);
        //showToast("BLE Connection failed: " + message);
        connectedGatt = null;
        linearAccelerationNotificationCharacteristic = null;
        rotationVectorNotificationCharacteristic = null;
        isBleCharacteristicsReady = false;
        sharedViewModel.updateStatus("Status: Connection Failed");
    }
    @Override
    public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {

    }

    /**
     * Callback reporting the result of a characteristic read operation.
     * This method is part of the {@link BleGattCallback} interface.
     * This is typically for notifications/indications, where data is pushed from the peripheral.
     *
     * @param gatt The {@link BluetoothGatt} object.
     * @param characteristic The characteristic that was changed.
     */
    @Override
    public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        byte[] value = characteristic.getValue();
        UUID charUuid = characteristic.getUuid();

        // Process the received byte array based on its characteristic UUID
        if (charUuid.equals(BluetoothUUID.ROTATION_VECTOR_CHAR_UUID)) {
            this.processRotationVectorBLE(value, this);
        } else if (charUuid.equals(BluetoothUUID.LINEAR_ACCELERATION_CHAR_UUID)) {
            this.processLinearAccelerationBLE(value, this);
        } else if(charUuid.equals(BluetoothUUID.ARM_ANGLE_UUID)){
            this.processArmAngles(value);
        }
        else {
            Log.w(TAG, "Received data from unknown BLE characteristic (notification): " + charUuid.toString());
        }
    }

    /**
     * Callback indicating the result of a characteristic write operation.
     * This method is part of the {@link BleGattCallback} interface.
     *
     * @param gatt The {@link BluetoothGatt} object.
     * @param characteristic The characteristic that was written to.
     * @param status Status of the write operation. {@link BluetoothGatt#GATT_SUCCESS} if successful.
     */
    @Override
    public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            Log.d(TAG, "Characteristic write successful: " + characteristic.getUuid().toString().substring(4, 8));
        } else {
            Log.e(TAG, "Characteristic write failed: " + characteristic.getUuid().toString().substring(4, 8) + ", status: " + status);
            //showToast("BLE write failed: " + status);
        }
    }

    @Override
    public void onDataReceived(String data) {
        // Process raw data from the Watch using ImuDataProcessor
        this.process(data, this);
    }

    @Override
    public void onConnectionStatus(boolean isConnected, String message) {
        Log.d(TAG, "Watch connection status: " + message);
        // TODO: Update UI status for watch connection if needed
    }

    @Override
    public void onReadError(String message) {
        Log.e(TAG, "Watch data read error: " + message);
        //showToast("Watch error: " + message);
    }

    @Override
    public void onNewImuData(Quaternionf rotationEuler, Vector3f linearAcceleration) {
        // Ensure fragment is added and activity is active before attempting UI updates
        sharedViewModel.updateAcceleration(linearAcceleration);
        sharedViewModel.updateRotation(rotationEuler);

    }

    @Override
    public void onIncompleteData(String rawData) {
        Log.w(TAG, "Received incomplete IMU data from Watch (via internal callback): " + rawData);
        // TODO: Consider showing a Toast for incomplete data if it's frequent and user-facing
    }

    @Override
    public void onParsingError(String rawData, String errorMessage) {
        Log.e(TAG, "Error parsing IMU data from Watch (via internal callback): " + errorMessage + " - Raw: " + rawData);
        //showToast("IMU Data Error: " + errorMessage); // Notify user of parsing errors
    }

    /**
     * Initiates a connection to the Bluetooth Classic (RFCOMM) "Watch" device
     * using the {@link BluetoothClassicHelper}.
     */
    private void connectToWatch() {
        if (bluetoothClassicHelper != null) {
            bluetoothClassicHelper.connectToWatch(WATCH_DEVICE_ADDRESS);
        } else {
            Log.e(TAG, "BluetoothClassicHelper is null, cannot connect to watch.");
            //showToast("Cannot connect to Watch (Classic helper not initialized).");
        }
    }

    /**
     * Initiates a connection to the Bluetooth Low Energy (BLE) ESP32 IMU device
     * using the {@link BluetoothBLEHelper}.
     */
    private void connectToEspImuBle() {
        if (bluetoothBleHelper != null && espImuDeviceAddress != null) {
            Log.d(TAG, "Attempting to connect to ESP IMU (BLE): " + espImuDeviceAddress);
            bluetoothBleHelper.connectBLEDevice(espImuDeviceAddress, this); // 'this' refers to ImuFragment implementing BleGattCallback

        } else {
            Log.e(TAG, "BluetoothBleHelper or ESP IMU address is null, cannot connect BLE.");
            //showToast("Cannot connect to ESP IMU (BLE).");
        }
    }

    public void connect(){
        connectToEspImuBle();
        connectToWatch();
    }

    /**
     * Disconnects from the Bluetooth Classic (RFCOMM) Watch device if connected.
     */
    private void disconnectFromWatch() {
        if (bluetoothClassicHelper != null) {
            bluetoothClassicHelper.disconnect();
        }
    }

    /**
     * Disconnects from the Bluetooth Low Energy (BLE) ESP IMU device if connected.
     * Also clears all related GATT and characteristic references.
     */
    private void disconnectFromEspImuBle() {
        if (bluetoothBleHelper != null && connectedGatt != null) {
            bluetoothBleHelper.disconnectBLEDevice(connectedGatt);
            connectedGatt = null; // Clear the GATT reference
            linearAccelerationNotificationCharacteristic = null; // Clear characteristic references
            rotationVectorNotificationCharacteristic = null;
            isBleCharacteristicsReady = false; // Reset the characteristics ready flag
        } else {
            Log.d(TAG, "No BLE Gatt connection to disconnect.");
        }
    }
    public void disconnect(){
        disconnectFromWatch();
        disconnectFromEspImuBle();
    }
    /**
     * Processes raw string data received from the RFCOMM watch connection.
     * This method parses the string, updates internal sensor states (accelerometer, gyroscope,
     * magnetometer, linear acceleration, rotation quaternion), and if a {@link ImuDataReadyListener}
     * is set, prepares linear acceleration and rotation data for BLE transmission to the ESP32.
     *
     * @param rawData The raw string data, expected in a comma-separated format like
     * "acc,1.2,3.4,5.6", "gyro,0.1,0.2,0.3", "mag,7.8,9.0,1.2",
     * "linear,1.1,2.2,3.3", or "rotation,0.1,0.2,0.3,0.9".
     * @param callback The {@link ImuDataCallback} for internal processing updates and errors.
     * This is typically the `imuInternalCallback` set via `setImuInternalCallback()`.
     */
    public void process(String rawData, ImuDataCallback callback) {
        String[] values = rawData.split(",");
        if (values.length >= 4) { // Ensure we have the minimum expected data (type, x, y, z)
            try {
                String type = values[0].trim();
                float x = Float.parseFloat(values[1].trim());
                float y = Float.parseFloat(values[2].trim());
                float z = Float.parseFloat(values[3].trim());

                boolean dataUpdated = false; // Flag to check if meaningful data was processed

                switch (type) {
                    case "linear":
                        linearAcceleration.set(x, y, z);
                        dataUpdated = true;
                        // If configured, notify listener that linear acceleration data is ready for BLE transmission
                        this.onLinearAccelerationDataReady(convertVector3fToBytes(linearAcceleration));
                        break;
                    case "rotation": // Expecting quaternion (x, y, z, w) from Watch
                        if (values.length == 5) {
                            float w = Float.parseFloat(values[4].trim());
                            quaternion.set(x, y, z, w);
                            // Convert quaternion to Euler angles (degrees) for display/fusion

                            dataUpdated = true;

                                // Sending raw quaternion is generally more precise for rotation than Euler angles
                            this.onRotationDataReady(convertQuaternionToBytes(quaternion));
                        } else if ((values.length == 4)) {


                            // Convert quaternion to Euler angles (degrees) for display/fusion
                            eulerAngles.set((x+360) %360, (y +360 )%360, (z+ 360) % 360);
                            dataUpdated = true;

                            // Alternatively, if ESP32 expects Euler angles:
                            this.onRotationDataReady(convertEulerAnglesToBytes(eulerAngles));
                        } else {
                            // If "rotation" type doesn't have 5 values (x,y,z,w), it's incomplete
                            this.onIncompleteData(rawData);
                            return;
                        }
                        break;
                    default:
                        Log.w(TAG, "Received unknown data type from Watch: " + type + " in raw data: " + rawData);
                        break;
                }

                // If any relevant data was updated, trigger the internal callback for UI updates or further processing
                if (dataUpdated) {
                    // Note: sensorFusion.update() is currently commented out.
                    // If you want to run sensor fusion on data coming from the Watch,
                    // uncomment and call it here, passing the relevant sensor readings.
                    // For example: sensorFusion.update(accelerometer, gyroscope, magnetometer);
                    this.onNewImuData(quaternion, linearAcceleration);
                }

            } catch (NumberFormatException e) {
                // Catches errors if x, y, z, or w cannot be parsed as floats
                this.onParsingError(rawData, "Error parsing numeric value from Watch: " + e.getMessage());
            } catch (Exception e) {
                // Catch any other unexpected exceptions during processing
                this.onParsingError(rawData, "Unexpected error during Watch data processing: " + e.getMessage());
            }
        } else {
            // If the raw data doesn't have enough comma-separated values
            this.onIncompleteData(rawData);
        }
    }

    /**
     * Processes raw byte array data received from a BLE ESP32 IMU for Rotation Vector.
     * This method assumes the ESP32 sends quaternion data as 4 float values (16 bytes total)
     * using little-endian byte order.
     * **Crucial:** You MUST verify this byte format and endianness with your ESP32 firmware.
     *
     * @param rawBytes The raw byte array data received from the BLE rotation characteristic.
     * Expected length is 16 bytes (4 floats).
     * @param callback The {@link ImuDataCallback} for updates, typically `imuInternalCallback`.
     */
    public void processRotationVectorBLE(byte[] rawBytes, ImuDataCallback callback) {
        if (rawBytes == null || rawBytes.length != 16) { // Expecting 4 floats (x, y, z, w) = 16 bytes
            if (callback != null) {
                callback.onIncompleteData("BLE Rotation Vector: Expected 16 bytes, got " + (rawBytes != null ? rawBytes.length : "null"));
            }
            Log.e(TAG, "processRotationVectorBLE: Invalid byte array length. Expected 16, got " + (rawBytes != null ? rawBytes.length : "null"));
            return;
        }

        try {
            // Wrap the byte array in a ByteBuffer for easy float extraction
            ByteBuffer buffer = ByteBuffer.wrap(rawBytes);
            buffer.order(ByteOrder.LITTLE_ENDIAN); // Crucial: Must match ESP32's byte order for reading

            // Extract the float components of the quaternion
            float x = buffer.getFloat();
            float y = buffer.getFloat();
            float z = buffer.getFloat();
            float w = buffer.getFloat();

            quaternion.set(x, y, z, w); // Update the internal quaternion from ESP32 data

            // Convert quaternion to Euler angles (degrees) for display/fusion


            if (callback != null) {
                // Pass the updated IMU data to the UI or other consumers.
                // Note: accelerometer, gyroscope, magnetometer, linearAcceleration are not directly updated by this BLE call,
                // so their current values are passed along.
                callback.onNewImuData(quaternion, linearAcceleration);
            }
        } catch (Exception e) {
            // Catch any parsing or unexpected errors during BLE data processing
            if (callback != null) {
                callback.onParsingError("BLE Rotation Vector (" + bytesToHex(rawBytes) + ")", "Error parsing BLE rotation data: " + e.getMessage());
            }
            Log.e(TAG, "Error parsing BLE rotation data: " + e.getMessage(), e);
        }
    }

    /**
     * Processes raw byte array data received from a BLE ESP32 IMU for Linear Acceleration.
     * This method assumes the ESP32 sends 3 float values (12 bytes total) using
     * little-endian byte order.
     * **Crucial:** You MUST verify this byte format and endianness with your ESP32 firmware.
     *
     * @param rawBytes The raw byte array data received from the BLE linear acceleration characteristic.
     * Expected length is 12 bytes (3 floats).
     * @param callback The {@link ImuDataCallback} for updates, typically `imuInternalCallback`.
     */
    public void processLinearAccelerationBLE(byte[] rawBytes, ImuDataCallback callback) {
        if (rawBytes == null || rawBytes.length != 12) { // Expecting 3 floats (x, y, z) = 12 bytes
            if (callback != null) {
                callback.onIncompleteData("BLE Linear Acceleration: Expected 12 bytes, got " + (rawBytes != null ? rawBytes.length : "null"));
            }
            Log.e(TAG, "processLinearAccelerationBLE: Invalid byte array length. Expected 12, got " + (rawBytes != null ? rawBytes.length : "null"));
            return;
        }

        try {
            // Wrap the byte array in a ByteBuffer for easy float extraction
            ByteBuffer buffer = ByteBuffer.wrap(rawBytes);
            buffer.order(ByteOrder.LITTLE_ENDIAN); // Crucial: Must match ESP32's byte order for reading

            // Extract the float components of the linear acceleration vector
            float x = buffer.getFloat();
            float y = buffer.getFloat();
            float z = buffer.getFloat();

            linearAcceleration.set(x, y, z); // Update the internal linear acceleration from ESP32 data

            if (callback != null) {
                // Pass the updated IMU data to the UI or other consumers.
                // Note: accelerometer, gyroscope, magnetometer, eulerAngles/quaternion are not directly updated by this BLE call,
                // so their current values are passed along.
                callback.onNewImuData(quaternion, linearAcceleration);
            }
        } catch (Exception e) {
            // Catch any parsing or unexpected errors during BLE data processing
            if (callback != null) {
                callback.onParsingError("BLE Linear Acceleration (" + bytesToHex(rawBytes) + ")", "Error parsing BLE linear acceleration data: " + e.getMessage());
            }
            Log.e(TAG, "Error parsing BLE linear acceleration data: " + e.getMessage(), e);
        }
    }

    public void processArmAngles(byte[] rawBytes){
        try {
            ByteBuffer buffer = ByteBuffer.wrap(rawBytes);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < this.armAngles.length; i++) {
                this.armAngles[i] = buffer.getShort();
            }
            Log.d(TAG, "Processed arm angles: " + this.armAngles[0] + ", " + this.armAngles[1] + ", " + this.armAngles[2]);
        } catch (Exception e) {
            Log.e(TAG, "Error parsing BLE arm angle data: " + e.getMessage());
        }
    }
    /**
     * Retrieves the current linear acceleration data. This value is updated by either
     * processing data from the Watch (RFCOMM) or the ESP32 (BLE).
     * @return A {@link Vector3f} representing the latest linear acceleration.
     */
    public Vector3f getLinearAcceleration() {
        return linearAcceleration; // Latest linear acceleration, could be from Watch or ESP32
    }

    /**
     * Retrieves the current rotation as a quaternion.
     * This value is updated by either processing rotation data from the Watch (RFCOMM) or the ESP32 (BLE).
     * @return A {@link Quaternionf} representing the latest rotation quaternion.
     */
    public Quaternionf getQuaternion() {
        return quaternion; // Latest quaternion, from Watch or ESP32
    }

    // --- Helper for data validation (can be adapted) ---
    /**
     * Checks if a given {@link Vector3f} contains valid (non-NaN, non-zero) sensor data.
     * Currently not directly used in the process methods, but good to keep for potential checks.
     * @param vector The {@link Vector3f} to validate.
     * @return True if the vector contains valid data, false otherwise.
     */
    private boolean isSensorDataValid(Vector3f vector) {
        return !Float.isNaN(vector.x) && !Float.isNaN(vector.y) && !Float.isNaN(vector.z) &&
                (vector.x != 0 || vector.y != 0 || vector.z != 0);
    }

    // --- Byte Conversion Methods for BLE Transmission (Android App -> ESP32) ---
    // These methods convert JOML Vector3f/Quaternionf into byte arrays suitable for BLE Characteristic Writes.


    public short[] getArmAngles() {
        return armAngles;
    }

    public void setArmAngles(short[] armAngles) {
        this.armAngles = armAngles;
    }
    public Vector3f getRoationAngles() {

        short yAngle = (short) clamp(((-eulerAngles.y + 360 + 163
        ) %360), 0, 270);
        short xAngle = (short) clamp(((eulerAngles.x + 630 +69) % 360), 0, 270);

        return new Vector3f(xAngle, yAngle, eulerAngles.z);
    }

    public byte[] convertArmAnglesToBytes() {
        ByteBuffer buffer = ByteBuffer.allocate(6);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        for (short angle : this.armAngles) {
            buffer.putShort(angle);
        }
        return buffer.array();
    }


    /**
     * Converts a {@link Vector3f} (like linear acceleration) into a 6-byte array.
     * Each float component (x, y, z) is scaled by a factor (`ACCEL_SCALE_FACTOR`)
     * and truncated to a `short` (2 bytes). This requires the ESP32 to reverse the scaling
     * by dividing by the same factor during reception.
     *
     * Example: a float value of 1.234, with `ACCEL_SCALE_FACTOR` of 1000.0f, becomes short 1234.
     * The ESP32 would then calculate 1234 / 1000.0f = 1.234.
     *
     * @param vector The {@link Vector3f} to convert.
     * @return A 6-byte array representing the vector.
     */
    public byte[] convertVector3fToBytes(Vector3f vector) {
        ByteBuffer buffer = ByteBuffer.allocate(6); // 3 shorts * 2 bytes/short
        buffer.order(ByteOrder.LITTLE_ENDIAN); // Crucial: Ensure byte order matches ESP32's expectation for writes
        // Scaling factor for linear acceleration. Adjust as needed.
        // Make sure the scaled value (e.g., vector.x * 1000) fits within a short (-32768 to 32767).
        final float ACCEL_SCALE_FACTOR = 1000.0f;
        buffer.putShort((short) (vector.x * ACCEL_SCALE_FACTOR));
        buffer.putShort((short) (vector.y * ACCEL_SCALE_FACTOR));
        buffer.putShort((short) (vector.z * ACCEL_SCALE_FACTOR));
        return buffer.array();
    }

    public byte[] convertEulerAnglesToBytes(Vector3f eulerAngles){
        ByteBuffer buffer = ByteBuffer.allocate(4);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.putShort((short )getRoationAngles().y);
        buffer.putShort((short )getRoationAngles().x);
        return buffer.array();
    }
    /**
     * Converts a {@link Quaternionf} into an 8-byte array.
     * Each float component (x, y, z, w) is scaled by a factor (`Q_SCALE_FACTOR`)
     * and truncated to a `short` (2 bytes). This is generally preferred for transmitting
     * rotation data over Euler angles due to precision and avoidance of gimbal lock issues.
     *
     * @param quaternion The {@link Quaternionf} to convert.
     * @return An 8-byte array representing the quaternion.
     */
    public byte[] convertQuaternionToBytes(Quaternionf quaternion) {
        ByteBuffer buffer = ByteBuffer.allocate(4); // 4 shorts * 2 bytes/short
        buffer.order(ByteOrder.LITTLE_ENDIAN); // Crucial: Ensure byte order matches ESP32's expectation for writes

        // Scaling factor for quaternion components (which theoretically range from -1.0 to 1.0).
        // A factor like 10000 provides good precision (e.g., 0.5000 becomes 5000).
        // Max value 1.0 * 10000 = 10000, which easily fits within a short (-32768 to 32767).
        final float Q_SCALE_FACTOR = 10000.0f;

        Vector3f eulerAngles = new Vector3f(); // [pitch, yaw, roll]
        quaternion.getEulerAnglesYXZ(eulerAngles);  // radians

        // Convert radians to degrees and apply clamping.
        float xAngle = (float) Math.toDegrees(eulerAngles.x);
        float yAngle = (float) Math.toDegrees(eulerAngles.y);
        buffer.putShort((short) (((eulerAngles.x) + 630) % 360f * Q_SCALE_FACTOR));
        buffer.putShort((short) (((eulerAngles.y) + 630) % 360f * Q_SCALE_FACTOR));
        return buffer.array();
    }

    /**
     * Helper method to convert a byte array to a hexadecimal string representation for logging.
     * @param bytes The byte array to convert.
     * @return A hexadecimal string representation of the byte array, or "null" if input is null.
     */
    private String bytesToHex(byte[] bytes) {
        if (bytes == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }
}