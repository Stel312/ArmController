package com.example.myapplication.imu;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Performs sensor fusion using accelerometer and gyroscope data to estimate device orientation.
 * Implements a quaternion-based complementary filter.
 */
public class ImuSensorFusion {

    // Filter parameters (adjust as needed)
    private float beta = 0.041f; // Initial filter gain
    private final float betaMin = 0.01f;
    private final float betaMax = 0.6f;
    private final float gainAdaptationRate = 0.2f;
    private final float deadzone = 0.01f;

    // Cutoff frequencies for filters
    private final float accelerometerCutoffFrequency = 2.1f; // Hz -  More stable accelerometer filter
    private final float gyroscopeCutoffFrequency = 5.0f;     // Hz

    // Filtered sensor data
    private Vector3f accelerometerFiltered = new Vector3f();
    private Vector3f correctedGyroscope = new Vector3f();
    private Vector3f gyroscopeBias = new Vector3f();  // Estimated gyroscope bias

    // Orientation representation
    private Quaternionf quaternion = new Quaternionf(0.0f, 0.0f, 0.0f, 1.0f);
    private float pitch, roll, yaw;  // Euler angles (degrees)

    private long lastUpdateTime = 0;    // Timestamp of the last update
    private float deltaTime = 0.0f;    // Time difference between updates
    private Vector3f rawGyroscopePrevious = new Vector3f(); // Previous raw gyroscope reading


    /**
     * Constructor.  Initializes the last update time.
     */
    public ImuSensorFusion() {
        lastUpdateTime = System.nanoTime();
    }

    /**
     * Updates the sensor fusion with new accelerometer and gyroscope data.
     *
     * @param rawAccelerometer The raw accelerometer reading (g-force).
     * @param rawGyroscope     The raw gyroscope reading (rad/s).
     */
    public void update(Vector3f rawAccelerometer, Vector3f rawGyroscope) {
        long currentTime = System.nanoTime();
        deltaTime = (float) (currentTime - lastUpdateTime) / 1_000_000_000.0f;
        lastUpdateTime = currentTime;

        filterAccelerometer(rawAccelerometer);
        filterGyroscope(rawGyroscope);
        updateQuaternion();
        updateEulerAngles();
    }

    /**
     * Applies a low-pass filter to the accelerometer data for noise reduction and stability.
     * @param rawAccelerometer
     */
    private void filterAccelerometer(Vector3f rawAccelerometer) {
        float alphaAcc = calculateAlpha(accelerometerCutoffFrequency);
        Vector3f accelerometerInput = new Vector3f(rawAccelerometer);
        // Apply a stronger filter.  Round the input to reduce high-frequency noise
        accelerometerInput.set(Math.round(accelerometerInput.x * 10.0f) / 10.0f, Math.round(accelerometerInput.y * 10.0f) / 10.0f, Math.round(accelerometerInput.z * 10.0f) / 10.0f);
        accelerometerFiltered.lerp(accelerometerInput, alphaAcc);

        // Normalize the filtered accelerometer data.  Important for accurate orientation estimation.
        if (accelerometerFiltered.lengthSquared() > 1e-6f) {
            accelerometerFiltered.normalize();
        } else {
            accelerometerFiltered.set(0, 0, 1); // Default to gravity along Z-axis if reading is near zero
        }
    }

    /**
     * Applies a high-pass filter to the gyroscope data to remove bias and drift.  Also applies a deadzone.
     * @param rawGyroscope
     */
    private void filterGyroscope(Vector3f rawGyroscope) {
        float alphaGyroHP = calculateAlphaHighPass(gyroscopeCutoffFrequency);
        float alphaGyroLP = calculateAlpha(0.1f);  // For bias estimation

        Vector3f deltaGyro = new Vector3f();
        float deadzoneSqr = deadzone * deadzone;
        float gyroMagSqr = rawGyroscope.lengthSquared();

        if (gyroMagSqr > deadzoneSqr) {
            // High-pass filter:  Difference between current and previous, scaled by alpha
            deltaGyro.set(rawGyroscope).sub(rawGyroscopePrevious).mul(alphaGyroHP);
            // Bias estimation:  Low-pass filter of the difference between raw and corrected.
            gyroscopeBias.fma(alphaGyroLP, new Vector3f(rawGyroscope).sub(correctedGyroscope));
            correctedGyroscope.set(rawGyroscope).sub(gyroscopeBias);  // Subtract the bias
        } else {
            correctedGyroscope.set(0, 0, 0); // Apply deadzone
            gyroscopeBias.lerp(new Vector3f(), 0.1f * deltaTime); // Slowly reset bias if no motion
        }
        rawGyroscopePrevious.set(rawGyroscope); // Store raw gyroscope for next high-pass calculation
    }

    /**
     * Updates the quaternion representing the device's orientation based on filtered sensor data.
     * Uses a complementary filter to combine accelerometer and gyroscope information.
     */
    private void updateQuaternion() {
        float q0 = quaternion.w();
        float q1 = quaternion.x();
        float q2 = quaternion.y();
        float q3 = quaternion.z();

        // Error terms based on accelerometer (using the already filtered accelerometer).
        float f1 = 2 * (q1 * q3 - q0 * q2) - accelerometerFiltered.x;
        float f2 = 2 * (q0 * q1 + q2 * q3) - accelerometerFiltered.y;
        float f3 = 2 * (0.5f - q1 * q1 - q2 * q2) - accelerometerFiltered.z;

        float errorMagnitude = (float) Math.sqrt(f1 * f1 + f2 * f2 + f3 * f3);

        // Dynamic beta adjustment (more conservative for stability)
        float stableGainAdaptationRate = gainAdaptationRate * 0.5f;  // Reduced rate
        if (errorMagnitude > 0.1f) { // Reduced threshold
            beta = Math.min(betaMax, beta + stableGainAdaptationRate * deltaTime);
        } else if (errorMagnitude < 0.02f) { // Increased lower threshold
            beta = Math.max(betaMin, beta - stableGainAdaptationRate * deltaTime);
        }

        // Gradient descent steps
        float SEq1 = 0f;
        float SEq2 = q0 * f1 + q1 * f3 - q2 * f2;
        float SEq3 = q0 * f2 - q1 * f3 - q2 * f1;
        float SEq4 = q0 * f3 + q1 * f2 - q3 * f1;
        float norm = (float) Math.sqrt(SEq2 * SEq2 + SEq3 * SEq3 + SEq4 * SEq4);
        if (norm > 0.0f) {
            SEq2 /= norm;
            SEq3 /= norm;
            SEq4 /= norm;
        }

        // Apply feedback from accelerometer and integrate gyroscope data.
        float qDot1 = 0.5f * (-q1 * correctedGyroscope.x - q2 * correctedGyroscope.y - q3 * correctedGyroscope.z);
        float qDot2 = 0.5f * (q0 * correctedGyroscope.x + q2 * correctedGyroscope.z - q3 * correctedGyroscope.y) - beta * SEq2;
        float qDot3 = 0.5f * (q0 * correctedGyroscope.y - q1 * correctedGyroscope.z + q3 * correctedGyroscope.x) - beta * SEq3;
        float qDot4 = 0.5f * (q0 * correctedGyroscope.z + q1 * correctedGyroscope.y - q2 * correctedGyroscope.x) - beta * SEq4;

        // Integrate quaternion using the calculated rates.
        float q0_new = q0 + qDot1 * deltaTime;
        float q1_new = q1 + qDot2 * deltaTime;
        float q2_new = q2 + qDot3 * deltaTime;
        float q3_new = q3 + qDot4 * deltaTime;

        quaternion.set(q1_new, q2_new, q3_new, q0_new).normalize(); // Normalize after integration
    }

    /**
     * Calculates Euler angles (pitch, roll, yaw) in degrees from the current quaternion.
     * This provides a human-readable representation of the device's orientation.
     */
    private void updateEulerAngles() {
        float q0 = quaternion.w();
        float q1 = quaternion.x();
        float q2 = quaternion.y();
        float q3 = quaternion.z();

        float sqw = q0 * q0;
        float sqx = q1 * q1;
        float sqy = q2 * q2;
        float sqz = q3 * q3;
        float invs = 1 / (sqx + sqy + sqz + sqw);
        float m00 = (sqw + sqx - sqy - sqz) * invs;
        float m10 = 2.0f * (q1 * q2 + q0 * q3) * invs;
        float m20 = 2.0f * (q1 * q3 - q0 * q2) * invs;
        float m21 = 2.0f * (q2 * q3 + q0 * q1) * invs;
        float m22 = (sqw - sqx - sqy + sqz) * invs;

        roll = (float) Math.toDegrees(Math.atan2(m21, m22));
        pitch = (float) Math.toDegrees(Math.asin(Math.max(-1.0f, Math.min(1.0f, -m20))));
        yaw = (float) Math.toDegrees(Math.atan2(m10, m00));
    }

    /**
     * Calculates the alpha value for a low-pass filter based on the cutoff frequency and time step.
     *
     * @param cutoffFrequency The cutoff frequency of the filter in Hz.
     * @return The calculated alpha value (0 <= alpha <= 1).
     */
    private float calculateAlpha(float cutoffFrequency) {
        float rc = 1.0f / (2 * (float) Math.PI * cutoffFrequency);
        return deltaTime / (rc + deltaTime);
    }

    /**
     * Calculates the alpha value for a high-pass filter.
     *
     * @param cutoffFrequency The cutoff frequency of the filter in Hz.
     * @return The calculated alpha value (0 <= alpha <= 1).
     */
    private float calculateAlphaHighPass(float cutoffFrequency) {
        float rc = 1.0f / (2 * (float) Math.PI * cutoffFrequency);
        return rc / (rc + deltaTime);
    }

    /**
     * Gets the current Euler angles (pitch, roll, yaw) in degrees.
     *
     * @return A Vector3f containing the pitch, roll, and yaw angles.
     */
    public Vector3f getEulerAngles() {
        return new Vector3f(pitch, roll, yaw);
    }

    /**
     * Gets the filtered accelerometer data.
     *
     * @return A Vector3f containing the filtered accelerometer values.
     */
    public Vector3f getFilteredAccelerometer() {
        return new Vector3f(accelerometerFiltered);
    }

    /**
     * Gets the corrected gyroscope data (after bias removal).
     *
     * @return A Vector3f containing the corrected gyroscope values.
     */
    public Vector3f getCorrectedGyroscope() {
        return new Vector3f(correctedGyroscope);
    }
}

