package com.example.myapplication.DataProcessing;

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