package com.example.myapplication.imu;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Math;
import org.joml.Matrix3f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

// It's good practice to use Android's Log for logging in an Android project
import android.util.Log;

public class ImuDataProcessor {

    private static final String TAG = "ImuDataProcessor";

    private final ImuSensorFusion sensorFusion;
    private final Vector3f accelerometer = new Vector3f();
    private final Vector3f gyroscope = new Vector3f();
    private final Vector3f magnetometer = new Vector3f();
    private final Vector3f eulerAngles = new Vector3f(); // Using Vector3f for Euler angles (degrees)
    private final Vector3f linearAcceleration = new Vector3f();
    private final Quaternionf quaternion = new Quaternionf(); // Stores rotation from Watch (RFCOMM)

    // New listener interface to signal when data is ready for BLE transmission FROM Android App TO ESP32
    public interface ImuDataReadyListener {
        void onRotationDataReady(byte[] rotationDataBytes);
        void onLinearAccelerationDataReady(byte[] linearAccelerationDataBytes);
        // Add other data types if you plan to send them
    }

    // Callback for internal processing updates or UI updates
    // This interface is used for data coming FROM the watch (RFCOMM) OR FROM the ESP32 (BLE)
    public interface ImuDataCallback {
        void onNewImuData(Vector3f accelerometer, Vector3f gyroscope, Vector3f magnetometer, Vector3f rotationEuler, Vector3f linearAcceleration);
        void onIncompleteData(String rawData);
        void onParsingError(String rawData, String errorMessage);
    }

    private ImuDataReadyListener dataReadyListener; // Listener to notify when data is ready for BLE sending
    private ImuDataCallback imuInternalCallback; // Internal callback for UI updates etc.

    public ImuDataProcessor(ImuSensorFusion sensorFusion) {
        this.sensorFusion = sensorFusion;
    }

    /**
     * Sets the listener that will be notified when processed IMU data (from the Watch)
     * is ready to be sent over BLE to the ESP32.
     * @param listener The implementation of ImuDataReadyListener.
     */
    public void setDataReadyListener(ImuDataReadyListener listener) {
        this.dataReadyListener = listener;
    }

    /**
     * Returns the currently set internal callback for IMU data updates.
     * This is useful for passing this callback into the `process` or `processBLE` methods.
     * @return The ImuDataCallback instance.
     */
    public ImuDataCallback getImuInternalCallback() {
        return imuInternalCallback;
    }

    /**
     * Sets the internal callback that will receive processed IMU data, typically for UI updates.
     * This callback handles data from both the Watch (RFCOMM) and ESP32 (BLE).
     * @param callback The implementation of ImuDataCallback.
     */
    public void setImuInternalCallback(ImuDataCallback callback) {
        this.imuInternalCallback = callback;
    }

    /**
     * Processes raw string data received from the RFCOMM watch connection.
     * Updates internal sensor states and, if configured, prepares data for BLE transmission
     * to the ESP32.
     *
     * @param rawData The raw string data (e.g., "acc,1.2,3.4,5.6", "rotation,0.1,0.2,0.3,0.9")
     * @param callback The callback for internal processing updates and errors. This is usually
     * `imuInternalCallback` set via `setImuInternalCallback()`.
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
                    case "acc":
                        accelerometer.set(x, y, z);
                        dataUpdated = true;
                        break;
                    case "gyro":
                        gyroscope.set(x, y, z);
                        dataUpdated = true;
                        break;
                    case "mag":
                        magnetometer.set(x, y, z);
                        dataUpdated = true;
                        break;
                    case "linear":
                        linearAcceleration.set(x, y, z);
                        dataUpdated = true;
                        // Data ready for BLE transmission to ESP32
                        if (dataReadyListener != null) {
                            dataReadyListener.onLinearAccelerationDataReady(convertVector3fToBytes(linearAcceleration));
                        }
                        break;
                    case "rotation": // Expecting quaternion (x, y, z, w) from Watch
                        if (values.length == 5) {
                            float w = Float.parseFloat(values[4].trim());
                            quaternion.set(x, y, z, w);
                            // Convert quaternion to Euler angles (degrees) for display/fusion
                            Matrix3f matrix = new Matrix3f();
                            quaternion.get(matrix);
                            // getEulerAnglesZYX is common, consider if XYZ or another order is preferred for your display
                            eulerAngles.set(matrix.getEulerAnglesZYX(new Vector3f()));
                            eulerAngles.mul((float) (180.0f / Math.PI)); // Convert radians to degrees
                            dataUpdated = true;

                            // Data ready for BLE transmission to ESP32
                            if (dataReadyListener != null) {
                                // Sending raw quaternion is generally more precise for rotation
                                dataReadyListener.onRotationDataReady(convertQuaternionToBytes(quaternion));
                                // Alternatively, send Euler angles:
                                // dataReadyListener.onRotationDataReady(convertEulerAnglesToBytes(eulerAngles));
                            }
                        } else {
                            callback.onIncompleteData(rawData);
                            return;
                        }
                        break;
                    default:
                        Log.w(TAG, "Received unknown data type from Watch: " + type);
                        break;
                }

                // If any relevant data was updated, trigger the internal callback for UI updates
                if (dataUpdated && imuInternalCallback != null) {
                    // Consider if sensorFusion.update() should be called here or earlier
                    // sensorFusion.update(accelerometer, gyroscope);
                    imuInternalCallback.onNewImuData(accelerometer, gyroscope, magnetometer, eulerAngles, linearAcceleration);
                }

            } catch (NumberFormatException e) {
                callback.onParsingError(rawData, "Error parsing numeric value from Watch: " + e.getMessage());
            }
        } else {
            callback.onIncompleteData(rawData);
        }
    }

    /**
     * Processes raw byte array data received from the BLE ESP IMU for Rotation Vector.
     * This method assumes the ESP IMU sends quaternion data as 4 floats (16 bytes),
     * using little-endian byte order.
     * You MUST verify this byte format with your ESP32 firmware.
     *
     * @param rawBytes The raw byte array data from the BLE rotation characteristic.
     * @param callback The callback for updates, typically `imuInternalCallback`.
     */
    public void processRotationVectorBLE(byte[] rawBytes, ImuDataCallback callback) {
        if (rawBytes.length != 16) { // Expecting 4 floats (x, y, z, w) = 16 bytes
            if (callback != null) {
                callback.onIncompleteData("BLE Rotation Vector: Expected 16 bytes, got " + rawBytes.length);
            }
            Log.e(TAG, "processRotationVectorBLE: Invalid byte array length. Expected 16, got " + rawBytes.length);
            return;
        }

        try {
            ByteBuffer buffer = ByteBuffer.wrap(rawBytes);
            buffer.order(ByteOrder.LITTLE_ENDIAN); // Crucial: Must match ESP32's byte order

            float x = buffer.getFloat();
            float y = buffer.getFloat();
            float z = buffer.getFloat();
            float w = buffer.getFloat();

            quaternion.set(x, y, z, w); // Update the internal quaternion from ESP32 data

            // Convert quaternion to Euler angles (degrees) for display/fusion
            Matrix3f matrix = new Matrix3f();
            quaternion.get(matrix);
            eulerAngles.set(matrix.getEulerAnglesZYX(new Vector3f())); // Assuming ZYX order for Euler
            eulerAngles.mul((float) (180.0f / Math.PI)); // Convert radians to degrees

            if (callback != null) {
                // Pass the updated quaternion (implicitly via getter) and Euler angles to the UI
                callback.onNewImuData(accelerometer, gyroscope, magnetometer, eulerAngles, linearAcceleration);
            }
        } catch (Exception e) {
            if (callback != null) {
                callback.onParsingError("BLE Rotation Vector", "Error parsing BLE rotation data: " + e.getMessage());
            }
            Log.e(TAG, "Error parsing BLE rotation data: " + e.getMessage(), e);
        }
    }

    /**
     * Processes raw byte array data received from the BLE ESP IMU for Linear Acceleration.
     * This method assumes the ESP IMU sends 3 floats (12 bytes), using little-endian byte order.
     * You MUST verify this byte format with your ESP32 firmware.
     *
     * @param rawBytes The raw byte array data from the BLE linear acceleration characteristic.
     * @param callback The callback for updates, typically `imuInternalCallback`.
     */
    public void processLinearAccelerationBLE(byte[] rawBytes, ImuDataCallback callback) {
        if (rawBytes.length != 12) { // Expecting 3 floats (x, y, z) = 12 bytes
            if (callback != null) {
                callback.onIncompleteData("BLE Linear Acceleration: Expected 12 bytes, got " + rawBytes.length);
            }
            Log.e(TAG, "processLinearAccelerationBLE: Invalid byte array length. Expected 12, got " + rawBytes.length);
            return;
        }

        try {
            ByteBuffer buffer = ByteBuffer.wrap(rawBytes);
            buffer.order(ByteOrder.LITTLE_ENDIAN); // Crucial: Must match ESP32's byte order

            float x = buffer.getFloat();
            float y = buffer.getFloat();
            float z = buffer.getFloat();

            linearAcceleration.set(x, y, z); // Update the internal linear acceleration from ESP32 data

            if (callback != null) {
                // Pass the updated linear acceleration (implicitly via getter) to the UI
                callback.onNewImuData(accelerometer, gyroscope, magnetometer, eulerAngles, linearAcceleration);
            }
        } catch (Exception e) {
            if (callback != null) {
                callback.onParsingError("BLE Linear Acceleration", "Error parsing BLE linear acceleration data: " + e.getMessage());
            }
            Log.e(TAG, "Error parsing BLE linear acceleration data: " + e.getMessage(), e);
        }
    }

    // --- Getters for current sensor states ---
    // These getters provide access to the latest processed sensor data, regardless of its source (Watch or ESP32)
    public Vector3f getFilteredAccelerometer() {
        return sensorFusion.getFilteredAccelerometer(); // Assuming sensor fusion is applied
    }

    public Vector3f getCorrectedGyroscope() {
        return sensorFusion.getCorrectedGyroscope(); // Assuming sensor fusion is applied
    }

    public Vector3f getMagnetometer() {
        return magnetometer; // Direct value
    }

    public Vector3f getLinearAcceleration() {
        return linearAcceleration; // Latest linear acceleration, could be from Watch or ESP32
    }

    public Vector3f getRotationVector() { // Returns Euler angles in degrees
        return eulerAngles; // Latest Euler angles, derived from Watch or ESP32 quaternion
    }

    public Quaternionf getQuaternion() {
        return quaternion; // Latest quaternion, from Watch or ESP32
    }

    // --- Helper for data validation (can be adapted) ---
    // Currently not directly used in the process methods, but good to keep for potential checks.
    private boolean isSensorDataValid(Vector3f vector) {
        return !Float.isNaN(vector.x) && !Float.isNaN(vector.y) && !Float.isNaN(vector.z) &&
                (vector.x != 0 || vector.y != 0 || vector.z != 0);
    }

    // --- Byte Conversion Methods for BLE Transmission (Android App -> ESP32) ---
    // These methods convert JOML Vector3f/Quaternionf into byte arrays suitable for BLE Characteristic Writes.

    /**
     * Converts a Vector3f (like linear acceleration) into a 6-byte array.
     * Each float component (x, y, z) is scaled and truncated to a short (2 bytes).
     * This requires the ESP32 to reverse the scaling by dividing by the same factor.
     * Example: value 1.234 becomes short 1234. ESP32 would then divide 1234 / 1000.0f = 1.234.
     *
     * @param vector The Vector3f to convert.
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

    /**
     * Converts Euler angles (in degrees) into a 6-byte array.
     * Each float component (x, y, z) is scaled and truncated to a short (2 bytes).
     * Similar scaling principle as `convertVector3fToBytes`.
     *
     * @param euler The Vector3f containing Euler angles in degrees.
     * @return A 6-byte array representing the Euler angles.
     */
    public byte[] convertEulerAnglesToBytes(Vector3f euler) { // Takes Euler angles in degrees
        ByteBuffer buffer = ByteBuffer.allocate(6);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        // Scaling factor for Euler angles. Adjust as needed.
        // For angles up to 360 degrees, a factor of 100 allows for 2 decimal places (36000 fits in short).
        final float EULER_SCALE_FACTOR = 100.0f;
        buffer.putShort((short) (euler.x * EULER_SCALE_FACTOR));
        buffer.putShort((short) (euler.y * EULER_SCALE_FACTOR));
        buffer.putShort((short) (euler.z * EULER_SCALE_FACTOR));
        return buffer.array();
    }

    /**
     * Converts a Quaternionf into an 8-byte array.
     * Each float component (x, y, z, w) is scaled and truncated to a short (2 bytes).
     * This is generally preferred for transmitting rotation data over Euler angles
     * due to precision and gimbal lock issues.
     *
     * @param quaternion The Quaternionf to convert.
     * @return An 8-byte array representing the quaternion.
     */
    public byte[] convertQuaternionToBytes(Quaternionf quaternion) {
        ByteBuffer buffer = ByteBuffer.allocate(8); // 4 shorts * 2 bytes/short
        buffer.order(ByteOrder.LITTLE_ENDIAN); // Crucial: Ensure byte order matches ESP32's expectation for writes

        // Scaling factor for quaternion components (which range from -1.0 to 1.0).
        // A factor like 10000 provides good precision (e.g., 0.5000 becomes 5000).
        // 1.0 * 10000 = 10000, which easily fits within a short.
        final float Q_SCALE_FACTOR = 10000.0f;
        buffer.putShort((short) (quaternion.x * Q_SCALE_FACTOR));
        buffer.putShort((short) (quaternion.y * Q_SCALE_FACTOR));
        buffer.putShort((short) (quaternion.z * Q_SCALE_FACTOR));
        buffer.putShort((short) (quaternion.w * Q_SCALE_FACTOR));

        return buffer.array();
    }
}