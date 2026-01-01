package com.example.myapplication.bluetooth;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;


/**
 * This interface is responsible for different implementations of the CallBack for Bluetooth low energy
 */
public interface BleGattCallback {
    void onConnectionStateChange(BluetoothGatt gatt, int status, int newState);
    void onServicesDiscovered(BluetoothGatt gatt, int status);
    void onCharacteristicsDiscovered(BluetoothGattCharacteristic linearAccelerationCharacteristic, BluetoothGattCharacteristic rotationVectorCharacteristic);
    void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status);
    void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status);
    void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic);
    void onDisconnected();
    void onConnectionFailed(String message);
}
