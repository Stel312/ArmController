package com.example.myapplication.DataProcessing;

import org.joml.Quaternionf;
import org.joml.Vector3f;

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