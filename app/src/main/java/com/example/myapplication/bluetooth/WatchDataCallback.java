package com.example.myapplication.bluetooth;


/**
 * Callback interface for receiving data from and monitoring the connection status of the "Watch" device.
 */
public interface WatchDataCallback {
    /**
     * Called when a line of data is received from the connected Watch device.
     * @param data The received string data.
     */
    void onDataReceived(String data);

    /**
     * Called to report the current connection status to the Watch device.
     * @param isConnected True if connected, false otherwise.
     * @param message A descriptive message about the connection status.
     */
    void onConnectionStatus(boolean isConnected, String message);

    /**
     * Called when an error occurs during the data reading process from the Watch.
     * @param message A descriptive message about the read error.
     */
    void onReadError(String message);
}
