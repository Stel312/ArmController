package com.example.myapplication.controls;

import org.joml.Vector3f;

import java.nio.ByteBuffer;

/**
 * The Kinematics class handles the calculations for a 3-link planar robotic arm.
 * It uses a combination of kinematics and physics principles to determine the arm's
 * position, velocity, and jerk based on linear acceleration data, and calculates
 * the joint angles required to position the arm's end-effector.
 */
public class Kinematics {
    private long lastTime;
    private Vector3f position;
    private Vector3f velocity;
    private Vector3f jerk;
    private Vector3f lastAcceleration;
    private final float minZ = 7.0f; // unit in inches

    // All link lengths are 247 mm
    private final float L1 = 247.0f;
    private final float L2 = 247.0f;
    private final float L3 = 247.0f;

    /**
     * Constructs a new Kinematics object and initializes the arm's state.
     * All vectors (position, velocity, acceleration, jerk) are set to zero,
     * and the last update time is recorded.
     */
    public Kinematics() {
        position = new Vector3f(0, 0, 0);
        velocity = new Vector3f(0, 0, 0);
        lastAcceleration = new Vector3f(0, 0, 0);
        jerk = new Vector3f(0, 0, 0);
        lastTime = System.nanoTime();
    }

    /**
     * Updates the arm's position, velocity, and jerk based on new linear acceleration data.
     * This method integrates the acceleration data over time to update the state.
     *
     * @param linearAcceleration A byte array containing the linear acceleration data.
     * @param armAngles A short array representing the current angles of the arm's joints.
     */
    public void update(byte[] linearAcceleration, short[] armAngles) {
        ByteBuffer buffer = ByteBuffer.wrap(linearAcceleration);
        float x = buffer.getFloat();
        float y = buffer.getFloat();
        float z = buffer.getFloat();
        Vector3f currentAcceleration = new Vector3f(x, y, z);

        long currentTime = System.nanoTime();
        float dt = (currentTime - lastTime) / 1000000000.0f;
        this.lastTime = currentTime;

        this.integratePosition(currentAcceleration, dt);
        this.deriveJerk(currentAcceleration, dt);
        this.lastTime = currentTime;
    }

    /**
     * Calculates the new joint angles for the 3-link planar arm using inverse kinematics.
     * The method determines the angles required to position the end-effector at the
     * current `position` vector. It uses a simplified approach by first solving
     * for the wrist position and then the final joint angle.
     *
     * @return A short array containing the three calculated joint angles (theta1, theta2, theta3) in degrees.
     */
    public short[] updateArmAngles() {
        float x_target = position.x;
        float z_target = position.z;

        // Assuming the third link is horizontal for a simplified IK solution.
        float x_wrist = x_target - L3;
        float z_wrist = z_target;

        double d_wrist = Math.sqrt(Math.pow(x_wrist, 2) + Math.pow(z_wrist, 2));

        // Safety check: is the wrist reachable?
        if (d_wrist > (L1 + L2) || d_wrist < Math.abs(L1 - L2)) {
            // Target is unreachable for the first two links, return a safe position.
            return new short[]{0, 0, 0};
        }
        double cos_theta2 = (Math.pow(L1, 2) + Math.pow(L2, 2) - Math.pow(d_wrist, 2)) / (2 * L1 * L2);

        // Clamp the value to the valid range [-1, 1] to prevent errors from floating-point inaccuracies
        cos_theta2 = Math.max(-1.0, Math.min(1.0, cos_theta2));
        double theta2_rad = Math.acos(cos_theta2); // Elbow angle (in radians)

        double alpha = Math.atan2(z_wrist, x_wrist);
        double beta = Math.acos((Math.pow(L1, 2) + Math.pow(d_wrist, 2) - Math.pow(L2, 2)) / (2 * L1 * d_wrist));
        double theta1_rad = alpha - beta; // Shoulder angle (in radians)

        // theta3 is calculated to make the end-effector point to the target
        double theta3_rad = (Math.atan2(z_target - z_wrist, x_target - x_wrist)) - (theta1_rad + theta2_rad);

        // Convert the angles from radians to degrees
        short theta1_short = (short) Math.toDegrees(theta1_rad);
        short theta2_short = (short) Math.toDegrees(theta2_rad);
        short theta3_short = (short) Math.toDegrees(theta3_rad);

        return new short[]{theta1_short, theta2_short, theta3_short};
    }

    /**
     * Integrates linear acceleration over time to update the velocity and position.
     * This method performs a basic numerical integration using the Euler method.
     *
     * @param linearAcceleration The current linear acceleration vector.
     * @param dt The time step (delta time) in seconds.
     */
    private void integratePosition(Vector3f linearAcceleration, float dt) {
        // v_new = v_old + a * dt
        Vector3f accelerationStep = new Vector3f(linearAcceleration).mul(dt);
        velocity.add(accelerationStep);

        // p_new = p_old + v * dt
        Vector3f velocityStep = new Vector3f(velocity).mul(dt);
        position.add(velocityStep);
    }

    /**
     * Calculates the jerk (the rate of change of acceleration) for the current time step.
     *
     * @param linearAcceleration The current linear acceleration vector.
     * @param dt The time step (delta time) in seconds.
     */
    private void deriveJerk(Vector3f linearAcceleration, float dt) {
        if (dt > 0) { // Avoid division by zero
            // Calculate the change in acceleration
            Vector3f deltaAcceleration = new Vector3f(linearAcceleration).sub(lastAcceleration);
            // Calculate jerk and update the jerk vector
            this.jerk.set(deltaAcceleration).div(dt);
        }
        // Update the 'lastAcceleration' for the next frame's calculation
        this.lastAcceleration.set(linearAcceleration);
    }

    /**
     * Returns the current position of the end-effector.
     * @return A Vector3f representing the position.
     */
    public Vector3f getPosition() {
        return position;
    }

    /**
     * Returns the current velocity of the end-effector.
     * @return A Vector3f representing the velocity.
     */
    public Vector3f getVelocity() {
        return velocity;
    }

    /**
     * Returns the current jerk of the end-effector.
     * @return A Vector3f representing the jerk.
     */
    public Vector3f getJerk() {
        return jerk;
    }
}