package com.example.myapplication.mainfragments;

import android.Manifest;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.Toast;

import com.example.myapplication.R;
import com.example.myapplication.bluetooth.BluetoothHelper;
import com.example.myapplication.bluetooth.BluetoothClassicHelper; // Import the new Classic helper

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ScanFragment extends Fragment implements BluetoothHelper.BleScanCallback {

    private static final String TAG = "ScanFragment";
    private Button goToImuButton;
    private Button scanButton;
    private ListView deviceListView;
    private ArrayAdapter<String> deviceListAdapter;
    private ArrayList<BluetoothDevice> bluetoothDevices;
    private Set<String> discoveredDeviceAddresses;
    private BluetoothAdapter bluetoothAdapter;
    private final int REQUEST_ENABLE_BT = 1;
    private static final int REQUEST_BLUETOOTH_PERMISSIONS = 123;
    private boolean isScanning = false;
    private Handler handler = new Handler(Looper.getMainLooper());

    private BluetoothHelper bluetoothHelper; // For BLE operations
    private BluetoothClassicHelper bluetoothClassicHelper; // For Bluetooth Classic operations

    public ScanFragment() {
        // Required empty public constructor
    }

    public static ScanFragment newInstance() {
        ScanFragment fragment = new ScanFragment();
        Bundle args = new Bundle();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        bluetoothDevices = new ArrayList<>();
        discoveredDeviceAddresses = new HashSet<>();
        // Initialize both Bluetooth helpers
        bluetoothHelper = new BluetoothHelper(getContext(), bluetoothAdapter);
        bluetoothClassicHelper = new BluetoothClassicHelper(getContext(), bluetoothAdapter);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_scan, container, false);

        goToImuButton = view.findViewById(R.id.goToImuButton);
        scanButton = view.findViewById(R.id.scanButton);
        deviceListView = view.findViewById(R.id.deviceListView);

        deviceListAdapter = new ArrayAdapter<>(getContext(), android.R.layout.simple_list_item_1);
        deviceListView.setAdapter(deviceListAdapter);

        deviceListView.setOnItemClickListener((parent, view1, position, id) -> {
            if (position >= 0 && position < bluetoothDevices.size()) {
                BluetoothDevice selectedDevice = bluetoothDevices.get(position);
                String deviceAddress = selectedDevice.getAddress();

                // Stop any ongoing scans before navigating
                stopScanning();

                ImuFragment imuFragment = ImuFragment.newInstance(deviceAddress);
                FragmentTransaction transaction = getParentFragmentManager().beginTransaction();
                transaction.replace(R.id.fragment_container_view, imuFragment);
                transaction.addToBackStack(null);
                transaction.commit();
            }
        });

        scanButton.setOnClickListener(v -> startBluetoothScan());

        return view;
    }

    private void startBluetoothScan() {
        if (bluetoothAdapter == null) {
            Toast.makeText(getContext(), "Bluetooth is not supported on this device.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!bluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
        } else {
            checkAndRequestBluetoothPermissions();
        }
    }

    private void checkAndRequestBluetoothPermissions() {
        List<String> permissionsToRequest = new ArrayList<>();

        if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN);
        }
        if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        // ACCESS_FINE_LOCATION is required for BLE scanning on Android 6.0+
        if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }

        if (!permissionsToRequest.isEmpty()) {
            new AlertDialog.Builder(getContext())
                    .setTitle("Permissions Required")
                    .setMessage("Bluetooth Scan, Connect, and Location permissions are needed to find nearby devices.")
                    .setPositiveButton("OK", (dialog, which) -> {
                        ActivityCompat.requestPermissions(getActivity(), permissionsToRequest.toArray(new String[0]), REQUEST_BLUETOOTH_PERMISSIONS);
                    })
                    .setNegativeButton("Cancel", (dialog, which) -> Toast.makeText(getContext(), "Permissions denied. Cannot scan for devices.", Toast.LENGTH_SHORT).show())
                    .show();
        } else {
            // All permissions granted, proceed with scan
            performBluetoothScan();
        }
    }

    private void performBluetoothScan() {
        if (isScanning) {
            Log.d(TAG, "Scan already in progress.");
            return;
        }

        isScanning = true;
        scanButton.setText("Scanning...");
        deviceListAdapter.clear();
        bluetoothDevices.clear();
        discoveredDeviceAddresses.clear();

        // 1. Add already paired Bluetooth Classic devices
        if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
            if (pairedDevices.size() > 0) {
                for (BluetoothDevice device : pairedDevices) {
                    if (device.getName() != null && !device.getName().isEmpty()) {
                        addDeviceToList(device, device.getName() + "\n" + device.getAddress() + " (Paired)");
                    }
                }
            }
        } else {
            Log.w(TAG, "BLUETOOTH_CONNECT permission not granted, cannot list paired devices.");
        }


        // 2. Start Bluetooth Classic Discovery (for non-paired classic devices)
        if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            bluetoothClassicHelper.startDiscovery(device -> { // Use bluetoothClassicHelper
                // This callback runs on the main thread because BluetoothClassicHelper posts to it
                if (device.getName() != null && !device.getName().isEmpty()) {
                    addDeviceToList(device, device.getName() + "\n" + device.getAddress() + " (Classic)");
                } else {
                    addDeviceToList(device, "Unknown Classic Device\n" + device.getAddress());
                }
            });
        } else {
            Log.w(TAG, "BLUETOOTH_SCAN permission not granted for classic discovery.");
        }


        // 3. Start BLE Scanning
        if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(getContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            bluetoothHelper.startBleScan(this); // Use bluetoothHelper for BLE
        } else {
            Log.w(TAG, "BLE scan permissions not granted, cannot start BLE scan.");
        }


        Toast.makeText(getContext(), "Scanning for devices...", Toast.LENGTH_SHORT).show();

        // Stop scanning after a delay (e.g., 10 seconds for BLE and Classic discovery)
        handler.postDelayed(this::stopScanning, 10000); // 10 seconds
    }

    // Helper to add a device to the list and prevent duplicates
    private void addDeviceToList(BluetoothDevice device, String displayString) {
        if (!discoveredDeviceAddresses.contains(device.getAddress())) {
            bluetoothDevices.add(device);
            discoveredDeviceAddresses.add(device.getAddress());
            deviceListAdapter.add(displayString);
            deviceListAdapter.notifyDataSetChanged(); // Update UI
        }
    }

    private void stopScanning() {
        if (!isScanning) return;

        Log.d(TAG, "Stopping all Bluetooth scans.");
        bluetoothClassicHelper.cancelDiscovery(); // Stop Classic discovery using the dedicated helper
        bluetoothHelper.stopBleScan();     // Stop BLE scan using the BLE helper

        isScanning = false;
        scanButton.setText("Start Scan");
        Toast.makeText(getContext(), "Scan complete.", Toast.LENGTH_SHORT).show();
        handler.removeCallbacksAndMessages(null); // Remove any pending stop callbacks
    }


    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ENABLE_BT) {
            if (resultCode == getActivity().RESULT_OK) {
                checkAndRequestBluetoothPermissions(); // Proceed if Bluetooth is enabled
            } else {
                Toast.makeText(getContext(), "Bluetooth enabling failed or cancelled.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_BLUETOOTH_PERMISSIONS) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (allGranted) {
                Log.d(TAG, "All required Bluetooth permissions granted.");
                performBluetoothScan();
            } else {
                Toast.makeText(getContext(), "Necessary permissions denied. Cannot scan for devices.", Toast.LENGTH_LONG).show();
            }
        }
    }

    // --- BluetoothHelper.BleScanCallback Implementation ---
    @Override
    public void onBleDeviceFound(BluetoothDevice device, int rssi, byte[] scanRecord) {
        // This callback is invoked on the main thread by BluetoothHelper
        if (device.getName() != null && !device.getName().isEmpty()) {
            addDeviceToList(device, device.getName() + "\n" + device.getAddress() + " (BLE, RSSI: " + rssi + ")");
        } else {
            addDeviceToList(device, "Unknown BLE Device\n" + device.getAddress() + " (BLE, RSSI: " + rssi + ")");
        }
    }

    @Override
    public void onScanFailed(int errorCode) {
        Log.e(TAG, "BLE Scan failed: " + errorCode);
        Toast.makeText(getContext(), "BLE Scan failed: " + errorCode, Toast.LENGTH_SHORT).show();
        // Ensure scanning state is reset even if scan fails
        stopScanning();
    }
    // --- End BleScanCallback Implementation ---


    @Override
    public void onDestroy() {
        super.onDestroy();
        stopScanning(); // Ensure all scans are stopped on fragment destruction
    }
}
