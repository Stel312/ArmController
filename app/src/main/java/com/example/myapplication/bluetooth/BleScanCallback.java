package com.example.myapplication.bluetooth;

import android.bluetooth.BluetoothDevice;

public interface BleScanCallback {
    void onBleDeviceFound(BluetoothDevice device, int rssi, byte[] scanRecord);
    void onScanFailed(int errorCode);
}