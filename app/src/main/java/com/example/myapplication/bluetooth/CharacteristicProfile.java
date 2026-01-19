package com.example.myapplication.bluetooth;

public class CharacteristicProfile {
    final long rateLimitMs; // The minimum time between sends for this characteristic.
    long lastWriteTime = 0; // The timestamp of the last queued write.

    CharacteristicProfile(long rateLimitMs) {
        this.rateLimitMs = rateLimitMs;
    }
}