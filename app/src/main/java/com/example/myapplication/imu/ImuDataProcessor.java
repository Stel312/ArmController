package com.example.myapplication.imu;

import static java.lang.Math.clamp;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Math;
import org.joml.Matrix3f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

// It's good practice to use Android's Log for logging in an Android project
import android.util.Log;

/**
 * Processes raw Inertial Measurement Unit (IMU) data from different sources
 * (e.g., RFCOMM Watch connection, BLE ESP32 IMU).
 * It updates internal sensor states, performs conversions (e.g., Quaternion to Euler angles),
 * and can prepare processed data for transmission over BLE to an ESP32 microcontroller.
 *
 * This class uses the JOML (Java OpenGL Mathematics Library) for 3D math operations
 * like vector and quaternion handling.
 */
public class ImuDataProcessor {

    private static final String TAG = "ImuDataProcessor";

    private final ImuSensorFusion sensorFusion;
    private final Vector3f accelerometer = new Vector3f();
    private final Vector3f gyroscope = new Vector3f();
    private final Vector3f magnetometer = new Vector3f();
    private final Vector3f linearAcceleration = new Vector3f();
    private final Quaternionf quaternion = new Quaternionf(); // Stores rotation from Watch (RFCOMM) or ESP32 (BLE)
    private final Vector3f eulerAngles = new Vector3f(); // Stores rotation in degrees from Watch (RFCOMM) or ESP32 (BLE)

    private short[] armAngles = {0, 0, 0}; // Stores the rotation first second and third servos of what the esp32 thinks the arm is at


    /**
     * Listener interface to signal when processed IMU data (from the Watch, via RFCOMM)
     * is ready for BLE transmission from the Android App TO an ESP32.
     * This allows the app to send processed sensor values as BLE characteristic updates.
     */
    public interface ImuDataReadyListener {
        /**
         * Called when rotation data (as a byte array) is ready for BLE transmission.
         * The data typically represents a quaternion.
         * @param rotationDataBytes A byte array containing the rotation data.
         */
        void onRotationDataReady(byte[] rotationDataBytes);

        /**
         * Called when linear acceleration data (as a byte array) is ready for BLE transmission.
         * @param linearAccelerationDataBytes A byte array containing the linear acceleration data.
         */
        void onLinearAccelerationDataReady(byte[] linearAccelerationDataBytes);
        // Add other data types if you plan to send them from Android to ESP32

        void onClawDataReady(byte[] clawDataBytes);
    }

    /**
     * Callback interface for internal IMU data processing updates or UI updates.
     * This interface is used for data coming FROM the watch (RFCOMM) OR FROM the ESP32 (BLE)
     * to notify the consuming component (e.g., a Fragment or Activity).
     */
    public interface ImuDataCallback {
        /**
         * Called when new, processed IMU data is available.
         * @param rotationEuler The current Euler angles (in degrees) representing rotation.
         * @param linearAcceleration The current linear acceleration data.
         */
        void onNewImuData(Quaternionf rotationEuler, Vector3f linearAcceleration);

        /**
         * Called when the raw data received is incomplete and cannot be fully parsed.
         * @param rawData The incomplete raw string data.
         */
        void onIncompleteData(String rawData);

        /**
         * Called when an error occurs during parsing of raw IMU data.
         * @param rawData The raw string data that caused the parsing error.
         * @param errorMessage A descriptive message about the parsing error.
         */
        void onParsingError(String rawData, String errorMessage);
    }

    private ImuDataReadyListener dataReadyListener; // Listener to notify when data is ready for BLE sending
    private ImuDataCallback imuInternalCallback; // Internal callback for UI updates etc.

    /**
     * Constructs an `ImuDataProcessor` with a given `ImuSensorFusion` instance.
     *
     * @param sensorFusion The {@link ImuSensorFusion} instance used for sensor data filtering and fusion.
     */
    public ImuDataProcessor(ImuSensorFusion sensorFusion) {
        this.sensorFusion = sensorFusion;
    }

    /**
     * Sets the listener that will be notified when processed IMU data (from the Watch)
     * is ready to be sent over BLE to the ESP32.
     * @param listener The implementation of {@link ImuDataReadyListener}.
     */
    public void setDataReadyListener(ImuDataReadyListener listener) {
        this.dataReadyListener = listener;
    }

    public ImuDataReadyListener getDataReadyListener() {
        return dataReadyListener;
    }

    /**
     * Returns the currently set internal callback for IMU data updates.
     * This is useful for passing this callback into the `process` or `processBLE` methods,
     * ensuring that all processing functions use the same callback for consistency.
     * @return The {@link ImuDataCallback} instance.
     */
    public ImuDataCallback getImuInternalCallback() {
        return imuInternalCallback;
    }

    /**
     * Sets the internal callback that will receive processed IMU data, typically for UI updates.
     * This callback handles data from both the Watch (RFCOMM) and ESP32 (BLE).
     * @param callback The implementation of {@link ImuDataCallback}.
     */
    public void setImuInternalCallback(ImuDataCallback callback) {
        this.imuInternalCallback = callback;
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
                        // If configured, notify listener that linear acceleration data is ready for BLE transmission
                        if (dataReadyListener != null) {
                            dataReadyListener.onLinearAccelerationDataReady(convertVector3fToBytes(linearAcceleration));
                        }
                        break;
                    case "rotation": // Expecting quaternion (x, y, z, w) from Watch
                        if (values.length == 5) {
                            float w = Float.parseFloat(values[4].trim());
                            quaternion.set(x, y, z, w);
                            // Convert quaternion to Euler angles (degrees) for display/fusion

                            dataUpdated = true;

                            // If configured, notify listener that rotation data is ready for BLE transmission
                            if (dataReadyListener != null) {
                                // Sending raw quaternion is generally more precise for rotation than Euler angles
                                dataReadyListener.onRotationDataReady(convertQuaternionToBytes(quaternion));
                                // Alternatively, if ESP32 expects Euler angles:
                                // dataReadyListener.onRotationDataReady(convertEulerAnglesToBytes(eulerAngles));
                            }
                        } else if ((values.length == 4)) {


                            // Convert quaternion to Euler angles (degrees) for display/fusion
                            eulerAngles.set((x+360) %360, (y +360 )%360, (z+ 360) % 360);
                            dataUpdated = true;

                            // If configured, notify listener that rotation data is ready for BLE transmission
                            if (dataReadyListener != null) {
                                // Alternatively, if ESP32 expects Euler angles:
                                dataReadyListener.onRotationDataReady(convertEulerAnglesToBytes(eulerAngles));
                            }
                        } else {
                            // If "rotation" type doesn't have 5 values (x,y,z,w), it's incomplete
                            callback.onIncompleteData(rawData);
                            return;
                        }
                        break;
                    default:
                        Log.w(TAG, "Received unknown data type from Watch: " + type + " in raw data: " + rawData);
                        break;
                }

                // If any relevant data was updated, trigger the internal callback for UI updates or further processing
                if (dataUpdated && imuInternalCallback != null) {
                    // Note: sensorFusion.update() is currently commented out.
                    // If you want to run sensor fusion on data coming from the Watch,
                    // uncomment and call it here, passing the relevant sensor readings.
                    // For example: sensorFusion.update(accelerometer, gyroscope, magnetometer);
                    imuInternalCallback.onNewImuData( quaternion, linearAcceleration);
                }

            } catch (NumberFormatException e) {
                // Catches errors if x, y, z, or w cannot be parsed as floats
                callback.onParsingError(rawData, "Error parsing numeric value from Watch: " + e.getMessage());
            } catch (Exception e) {
                // Catch any other unexpected exceptions during processing
                callback.onParsingError(rawData, "Unexpected error during Watch data processing: " + e.getMessage());
            }
        } else {
            // If the raw data doesn't have enough comma-separated values
            callback.onIncompleteData(rawData);
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

    // --- Getters for current sensor states ---
    /**
     * Retrieves the current accelerometer data. This might be raw or filtered depending on
     * how `ImuSensorFusion` processes it.
     * @return A {@link Vector3f} representing the filtered accelerometer data.
     */
    public Vector3f getFilteredAccelerometer() {
        return this.sensorFusion.getFilteredAccelerometer(); // Assuming sensor fusion is applied
    }

    /**
     * Retrieves the current gyroscope data. This might be raw or corrected (e.g., bias removal)
     * depending on how `ImuSensorFusion` processes it.
     * @return A {@link Vector3f} representing the corrected gyroscope data.
     */
    public Vector3f getCorrectedGyroscope() {
        return sensorFusion.getCorrectedGyroscope(); // Assuming sensor fusion is applied
    }

    /**
     * Retrieves the current magnetometer data.
     * @return A {@link Vector3f} representing the raw magnetometer data.
     */
    public Vector3f getMagnetometer() {
        return magnetometer; // Direct value
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