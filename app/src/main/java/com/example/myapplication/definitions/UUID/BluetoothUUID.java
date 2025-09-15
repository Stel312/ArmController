package com.example.myapplication.definitions.UUID;

import java.util.UUID;

public class BluetoothUUID {
    public static final UUID IMU_SERVICE_UUID = UUID.fromString("180d-0000-1000-8000-00805f9b34fb"); // Example Service UUID
    public static final UUID ROTATION_VECTOR_CHAR_UUID = UUID.fromString("2a37-0000-1000-8000-00805f9b34fb"); // Example for Rotation Vector (Human Interface Device service)
    public static final UUID LINEAR_ACCELERATION_CHAR_UUID = UUID.fromString("2a38-0000-1000-8000-00805f9b34fb"); // Example for Linear Acceleration (Environmental Sensing service)
    public static final UUID CLAW_CHAR_UUID = UUID.fromString("2a39-0000-1000-8000-00805f9b34fb");
    public static final UUID ESC_COMMAND_UUID = UUID.fromString("00002a40-0000-1000-8000-00805f9b34fb");

    public static final UUID STEPPER_CHAR_UUID = UUID.fromString("2a41-0000-1000-8000-00805f9b34fb");
    public static final UUID ARM_ANGLE_UUID = UUID.fromString("2a39-0000-1000-8000-00805f9b34fb");
    public static final UUID CLIENT_CHARACTERISTIC_CONFIG_UUID = UUID.fromString("00002A40-0000-1000-8000-00805f9b34fb");
}
