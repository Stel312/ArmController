package com.example.myapplication;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Set;
import java.util.UUID;

/**
 * A simple {@link Fragment} subclass.
 * Use the {@link ImuFagment#newInstance} factory method to
 * create an instance of this fragment.
 */
public class ImuFagment extends Fragment {

    private static final String TAG = "ImuFragment";
    // Use this static UUID
    private static final UUID APP_UUID = UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66");
    private BluetoothSocket bluetoothSocket;
    private Handler mainHandler;
    private Button backToScanButton, hideGimbel, showGimbel, showExtra;

    private TextView accXTextView, accYTextView, accZTextView;
    private TextView gyroXTextView, gyroYTextView, gyroZTextView;

    public ImuFagment() {
        // Required empty public constructor
    }

    public static ImuFagment newInstance() {
        return new ImuFagment();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mainHandler = new Handler(Looper.getMainLooper()); // Initialize handler
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_imu_fagment, container, false);

        // Initialize TextViews for accelerometer data
        accXTextView = view.findViewById(R.id.accXTextView);
        accYTextView = view.findViewById(R.id.accYTextView);
        accZTextView = view.findViewById(R.id.accZTextView);

        // Initialize TextViews for gyroscope data
        gyroXTextView = view.findViewById(R.id.gyroXTextView);
        gyroYTextView = view.findViewById(R.id.gyroYTextView);
        gyroZTextView = view.findViewById(R.id.gyroZTextView);

        // Initialize the back button and set a click listener
        backToScanButton = view.findViewById(R.id.backToScanButton);
        backToScanButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Use FragmentTransaction to replace current fragment with ScanFragment
                FragmentTransaction transaction = getParentFragmentManager().beginTransaction();
                transaction.replace(R.id.fragment_container_view, new ScanFragment()); // Replace with a new ScanFragment instance
                transaction.commit();
            }
        });

        if (ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            // Request the permission
            ActivityCompat.requestPermissions(requireActivity(), new String[]{android.Manifest.permission.BLUETOOTH_CONNECT}, 1);
        } else {
            // Permission already granted, proceed with connecting
            connectToWatch();
        }
        return view;
    }


    private void connectToWatch() {
        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            Log.e(TAG, "Bluetooth not supported on this device.");
            return;
        }


        if (ActivityCompat.checkSelfPermission(requireContext(), android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
        if (pairedDevices.size() > 0) {
            for (BluetoothDevice device : pairedDevices) {
                Log.d(TAG, "Paired Device: " + device.getName() + ", Address: " + device.getAddress()); //log all paired devices
                if (device.getAddress().equals("34:E3:FB:82:92:CD")) {
                    try {
                        bluetoothSocket = device.createRfcommSocketToServiceRecord(APP_UUID);
                        bluetoothSocket.connect();
                        Log.d(TAG, "Connected to watch: " + device.getName());
                        receiveData();
                        return; // Connected, no need to continue the loop
                    } catch (IOException e) {
                        Log.e(TAG, "Failed to connect to watch: " + e.getMessage());
                    }
                }
            }
        }
        Log.e(TAG, "Watch not found or not paired.");
    }

    private void receiveData() {
        new Thread(() -> {
            try {
                InputStream inputStream = bluetoothSocket.getInputStream();
                BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream));
                String receivedData;
                while ((receivedData = bufferedReader.readLine()) != null) {
                    final String data = receivedData; // Make a final copy for the UI thread
                    mainHandler.post(() -> processImuData(data));
                }
            } catch (IOException e) {
                Log.e(TAG, "Error receiving data: " + e.getMessage());
            }
        }).start();
    }

    private void processImuData(String data) {
        String[] values = data.split(",");
        if (values.length == 4) {
            try {
                float x = Float.parseFloat(values[0]);
                float y = Float.parseFloat(values[1]);
                float z = Float.parseFloat(values[2]);
                String sensorType = values[3];

                if (sensorType.equals("acc")) {
                    accXTextView.setText("X: " + String.format("%.3f", x));
                    accYTextView.setText("Y: " + String.format("%.3f", y));
                    accZTextView.setText("Z: " + String.format("%.3f", z));
                } else if (sensorType.equals("gyro")) {
                    gyroXTextView.setText("X: " + String.format("%.3f", x));
                    gyroYTextView.setText("Y: " + String.format("%.3f", y));
                    gyroZTextView.setText("Z: " + String.format("%.3f", z));
                }
            } catch (NumberFormatException e) {
                Log.e(TAG, "Error parsing IMU data: " + e.getMessage());
            }
        } else {
            Log.e(TAG, "Invalid IMU data format: " + data);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (bluetoothSocket != null) {
            try {
                bluetoothSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing Bluetooth socket: " + e.getMessage());
            }
        }
    }
}
