package com.example.myapplication;

import org.joml.Quaternionf;
import org.joml.Vector3f;

public class ImuSensorFusion {

    private long lastUpdateTime = 0;
    private float deltaTime = 0.0f;
    private float beta = 0.041f; // Initial filter gain (adjust as needed)
    private final float betaMin = 0.01f;
    private final float betaMax = 0.6f;
    private final float gainAdaptationRate = 0.2f;

    private final float accelerometerCutoffFrequency = 10.0f; // Hz
    private final float gyroscopeCutoffFrequency = 5.0f;     // Hz
    private Vector3f correctedGyro = new Vector3f();
    private Vector3f accelerometerFiltered = new Vector3f(0.0f, 0.0f, 0.0f);
    private Vector3f rawGyroscopePrevious = new Vector3f();
    private Quaternionf quaternion = new Quaternionf(0.0f, 0.0f, 0.0f, 1.0f);
    private float pitch, roll, yaw;
    private final float deadzone = 0.01f;

    public ImuSensorFusion() {
        lastUpdateTime = System.nanoTime(); // Initialize lastUpdateTime
    }

    public void update(Vector3f rawAccelerometer, Vector3f rawGyroscope) {
        long currentTime = System.nanoTime();
        deltaTime = (float) (currentTime - lastUpdateTime) / 1_000_000_000.0f;
        lastUpdateTime = currentTime;
        calculateGimbalData(rawAccelerometer, rawGyroscope);
    }

    public Vector3f getEulerAngles() {
        return new Vector3f(pitch, roll, yaw);
    }

    public Vector3f getFilteredAccelerometer() {
        return new Vector3f(accelerometerFiltered);
    }

    public Vector3f getCorrectedGyroscope() {
        return new Vector3f(correctedGyro);
    }

    private void calculateGimbalData(Vector3f rawAccelerometer, Vector3f rawGyroscope) {
        // --- Enhanced Filtering for Stability ---

        // 1. Apply a stronger Low-Pass Filter to Accelerometer readings for increased stability.
        float accelerometerCutoffFrequencyStable = 2.1f;
        float alphaAcc = calculateAlpha(accelerometerCutoffFrequencyStable);
        float accX = Math.round(rawAccelerometer.x * 10.0f) / 10.0f;
        float accY = Math.round(rawAccelerometer.y * 10.0f) / 10.0f;
        float accZ = Math.round(rawAccelerometer.z * 10.0f) / 10.0f;
        accelerometerFiltered.lerp(new Vector3f(accX, accY, accZ), alphaAcc);

        Vector3f accNorm = new Vector3f(accelerometerFiltered);
        if (accNorm.lengthSquared() > 1e-6f) { // Avoid division by zero during normalization
            accNorm.normalize();
        } else {
            accNorm.set(0, 0, 1); // Default to assuming gravity is along the Z-axis if reading is near zero
        }

        // 2. Apply a High-Pass Filter to Gyroscope readings with Deadzone and Bias Estimation.
        float alphaGyroHP = calculateAlphaHighPass(gyroscopeCutoffFrequency);
        float alphaGyroLP = calculateAlpha(0.1f); // Low-pass for bias estimation
        Vector3f gyroBias = new Vector3f();
        Vector3f deltaGyro = new Vector3f();

        float deadzoneSqr = deadzone * deadzone;
        float gyroMagSqr = rawGyroscope.lengthSquared();

        if (gyroMagSqr > deadzoneSqr) {
            deltaGyro.set(rawGyroscope).sub(rawGyroscopePrevious).mul(alphaGyroHP);
            gyroBias.fma(alphaGyroLP, new Vector3f(rawGyroscope).sub(correctedGyro)); // Estimate bias
            correctedGyro.set(rawGyroscope).sub(gyroBias);
        } else {
            correctedGyro.set(0, 0, 0);
            gyroBias.lerp(new Vector3f(), 0.1f * deltaTime); // Slowly reset bias if no motion
        }
        rawGyroscopePrevious.set(rawGyroscope); // Use raw gyroscope for the high-pass filter reference

        // --- Quaternion Update with Adjusted Dynamic Gain for Stability ---

        float q0 = quaternion.w();
        float q1 = quaternion.x();
        float q2 = quaternion.y();
        float q3 = quaternion.z();

        // Error terms based on accelerometer.
        float f1 = 2 * (q1 * q3 - q0 * q2) - accNorm.x;
        float f2 = 2 * (q0 * q1 + q2 * q3) - accNorm.y;
        float f3 = 2 * (0.5f - q1 * q1 - q2 * q2) - accNorm.z;

        float errorMagnitude = (float) Math.sqrt(f1 * f1 + f2 * f2 + f3 * f3);

        // More conservative dynamic beta adjustment for stability.
        float stableGainAdaptationRate = gainAdaptationRate * 0.5f;
        if (errorMagnitude > 0.1f) { // Reduced threshold for activation
            beta = Math.min(betaMax, beta + stableGainAdaptationRate * deltaTime);
        } else if (errorMagnitude < 0.02f) { // Adjusted lower threshold
            beta = Math.max(betaMin, beta - stableGainAdaptationRate * deltaTime);
        }

        // Gradient descent steps.
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

        // Apply feedback.
        float qDot1 = 0.5f * (-q1 * correctedGyro.x - q2 * correctedGyro.y - q3 * correctedGyro.z);
        float qDot2 = 0.5f * (q0 * correctedGyro.x + q2 * correctedGyro.z - q3 * correctedGyro.y) - beta * SEq2;
        float qDot3 = 0.5f * (q0 * correctedGyro.y - q1 * correctedGyro.z + q3 * correctedGyro.x) - beta * SEq3;
        float qDot4 = 0.5f * (q0 * correctedGyro.z + q1 * correctedGyro.y - q2 * correctedGyro.x) - beta * SEq4;

        // Integrate quaternion.
        float q0_new = q0 + qDot1 * deltaTime;
        float q1_new = q1 + qDot2 * deltaTime;
        float q2_new = q2 + qDot3 * deltaTime;
        float q3_new = q3 + qDot4 * deltaTime;

        quaternion.set(q1_new, q2_new, q3_new, q0_new).normalize();

        // --- Euler Angle Calculation (Degrees) ---

        float sqw = q0_new * q0_new;
        float sqx = q1_new * q1_new;
        float sqy = q2_new * q2_new;
        float sqz = q3_new * q3_new;
        float invs = 1 / (sqx + sqy + sqz + sqw);
        float m00 = (sqw + sqx - sqy - sqz) * invs;
        float m10 = 2.0f * (q1_new * q2_new + q0_new * q3_new) * invs;
        float m20 = 2.0f * (q1_new * q3_new - q0_new * q2_new) * invs;
        float m21 = 2.0f * (q2_new * q3_new + q0_new * q1_new) * invs;
        float m22 = (sqw - sqx - sqy + sqz) * invs;

        roll = (float) Math.toDegrees(Math.atan2(m21, m22));
        pitch = (float) Math.toDegrees(Math.asin(Math.max(-1.0f, Math.min(1.0f, -m20))));
        yaw = (float) Math.toDegrees(Math.atan2(m10, m00));
    }

    private float calculateAlpha(float cutoffFrequency) {
        float rc = 1.0f / (2 * (float) Math.PI * cutoffFrequency);
        return deltaTime / (rc + deltaTime);
    }

    private float calculateAlphaHighPass(float cutoffFrequency) {
        float rc = 1.0f / (2 * (float) Math.PI * cutoffFrequency);
        return rc / (rc + deltaTime);
    }
}