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
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.example.myapplication.enums.SecondaryFragment;
import com.example.myapplication.subfragments.ExtraDataFragment;
import com.example.myapplication.subfragments.GimbleFragment;

import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.UUID;

/**
 * A simple {@link Fragment} subclass.
 * Use the {@link ImuFagment#newInstance} factory method to
 * create an instance of this fragment.
 */
public class ImuFagment extends Fragment {

    private static final String TAG = "ImuFragment";
    private static final UUID APP_UUID = UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66");
    private BluetoothSocket bluetoothSocket;
    private Handler mainHandler;
    private Button backToScanButton, hideGimbel, showGimbel, showExtra;

    private TextView accXTextView, accYTextView, accZTextView;
    private TextView gyroXTextView, gyroYTextView, gyroZTextView;
    private TextView magXTextView, magYTextView, magZTextView;
    private Vector3f accelerometer = new Vector3f();
    private Vector3f gyroscope = new Vector3f();
    private Vector3f magnetometer = new Vector3f();
    private Quaternionf quaternion = new Quaternionf(0.0f, 0.0f, 0.0f, 1.0f);
    private float pitch, roll, yaw;
    private float linearAccX, linearAccY, linearAccZ;

    // Madgwick filter parameters:
    private static final float BETA = 0.03f;
    private static final float GYRO_BIAS_BETA = 0.00001f;
    private static final float LOW_PASS_FILTER_ALPHA = 0.1f;
    private float prevPitch = 0, prevRoll = 0, prevYaw = 0;

    private long lastTime = 0;
    private boolean isExtraDataFragmentActive = false;
    private boolean isConnected = false;
    private GimbleFragment gimbleFragment;
    private Vector3f gyroBiasEstimate = new Vector3f();
    private String deviceAddress;
    private OutputStream outputStream;

    public ImuFagment() {
        // Required empty public constructor
    }

    public static ImuFagment newInstance(String deviceAddress) {
        ImuFagment fragment = new ImuFagment();
        Bundle args = new Bundle();
        args.putString("deviceAddress", deviceAddress);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mainHandler = new Handler(Looper.getMainLooper());
        if (getArguments() != null) {
            deviceAddress = getArguments().getString("deviceAddress");
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_imu_fagment, container, false);

        accXTextView = view.findViewById(R.id.accXTextView);
        accYTextView = view.findViewById(R.id.accYTextView);
        accZTextView = view.findViewById(R.id.accZTextView);

        gyroXTextView = view.findViewById(R.id.gyroXTextView);
        gyroYTextView = view.findViewById(R.id.gyroYTextView);
        gyroZTextView = view.findViewById(R.id.gyroZTextView);

        magXTextView = view.findViewById(R.id.magXTextView);
        magYTextView = view.findViewById(R.id.magYTextView);
        magZTextView = view.findViewById(R.id.magZTextView);

        backToScanButton = view.findViewById(R.id.backToScanButton);
        backToScanButton.setOnClickListener(v -> {
            FragmentTransaction transaction = getParentFragmentManager().beginTransaction();
            transaction.replace(R.id.fragment_container_view, new ScanFragment());
            transaction.commit();
            disconnectFromWatch();
        });

        showExtra = view.findViewById(R.id.show_extra);
        showExtra.setOnClickListener(v -> {
            loadFragment(SecondaryFragment.EXTRA);
            isExtraDataFragmentActive = true;
        });

        showGimbel = view.findViewById(R.id.show_gimbel);
        showGimbel.setOnClickListener(v -> {
            loadFragment(SecondaryFragment.GIMBLE);
            isExtraDataFragmentActive = false;
        });

        hideGimbel = view.findViewById(R.id.hide_gimbel);
        hideGimbel.setOnClickListener(v -> {
            loadFragment(SecondaryFragment.NONE);
            isExtraDataFragmentActive = false;
        });

        if (ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(requireActivity(), new String[]{android.Manifest.permission.BLUETOOTH_CONNECT}, 1);
        } else {
            connectToWatch();
        }
        return view;
    }

    private void loadFragment(SecondaryFragment fragmentType) {
        FragmentManager fragmentManager = getParentFragmentManager();
        FragmentTransaction transaction = fragmentManager.beginTransaction();
        Fragment fragment = SecondaryFragment.newFragmentInstance(fragmentType);

        if (fragment != null) {
            if (fragmentType == SecondaryFragment.GIMBLE) {
                gimbleFragment = (GimbleFragment) fragment;
            } else {
                gimbleFragment = null;
            }
            transaction.replace(R.id.secondary_fragment_container, fragment, fragmentType.name());
            transaction.commit();
        } else if (fragmentType == SecondaryFragment.NONE) {
            Fragment currentFragment = fragmentManager.findFragmentById(R.id.secondary_fragment_container);
            if (currentFragment != null) {
                transaction.remove(currentFragment);
                transaction.commit();
            }
        }
    }

    private void connectToWatch() {
        if (isConnected || deviceAddress == null) return;
        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            Log.e(TAG, "Bluetooth not supported on this device.");
            return;
        }

        if (ActivityCompat.checkSelfPermission(requireContext(), android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        BluetoothDevice device = bluetoothAdapter.getRemoteDevice(deviceAddress);
        try {
            bluetoothSocket = device.createRfcommSocketToServiceRecord(APP_UUID);
            bluetoothSocket.connect();
            Log.d(TAG, "Connected to watch: " + device.getName());
            outputStream = bluetoothSocket.getOutputStream();
            receiveData();
            isConnected = true;
        } catch (IOException e) {
            Log.e(TAG, "Failed to connect to watch: " + e.getMessage());
            mainHandler.post(() -> Toast.makeText(getContext(), "Failed to connect", Toast.LENGTH_SHORT).show());
        }
    }

    private void receiveData() {
        new Thread(() -> {
            try {
                InputStream inputStream = bluetoothSocket.getInputStream();
                BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream));
                String receivedData;
                while ((receivedData = bufferedReader.readLine()) != null) {
                    final String data = receivedData;
                    mainHandler.post(() -> processImuData(data));
                }
            } catch (IOException e) {
                Log.e(TAG, "Error receiving data: " + e.getMessage());
                disconnectFromWatch();
            }
        }).start();
    }

    private void sendData(String data) {
        if (outputStream != null) {
            try {
                outputStream.write(data.getBytes());
                outputStream.flush();
            } catch (IOException e) {
                Log.e(TAG, "Error sending data: " + e.getMessage());
            }
        }
    }

    private void processImuData(String data) {
        String[] values = data.split(",");
        if (values[3].equals("acc")){
            accelerometer.set(Float.parseFloat(values[0]), Float.parseFloat(values[1]), Float.parseFloat(values[2]));
            accXTextView.setText("Acc X: " + String.format("%.3f", accelerometer.x));
            accYTextView.setText("Acc Y: " + String.format("%.3f", accelerometer.y));
            accZTextView.setText("Acc Z: " + String.format("%.3f", accelerometer.z));
        } else if (values[3].equals("gyro")) {
            gyroscope.set(Float.parseFloat(values[0]), Float.parseFloat(values[1]), Float.parseFloat(values[2]));
            gyroXTextView.setText("Gyro X: " + String.format("%.3f", gyroscope.x));
            gyroYTextView.setText("Gyro Y: " + String.format("%.3f", gyroscope.y));
            gyroZTextView.setText("Gyro Z: " + String.format("%.3f", gyroscope.z));
        } else if (values[3].equals("mag")) { //handle mag data
            magnetometer.set(Float.parseFloat(values[0]), Float.parseFloat(values[1]), Float.parseFloat(values[2]));
            magXTextView.setText("Mag X: " + String.format("%.3f", magnetometer.x));
            magYTextView.setText("Mag Y: " + String.format("%.3f", magnetometer.y));
            magZTextView.setText("Mag Z: " + String.format("%.3f", magnetometer.z));
        }
        try {
            // Calculate and display gimbal data
            calculateGimbalData(accelerometer, gyroscope, magnetometer); // Pass mag data
        } catch (NumberFormatException e) {
            Log.e(TAG, "Error parsing IMU data: " + e.getMessage());
        }
    }

    private void calculateGimbalData(Vector3f accelerometer, Vector3f gyroscope, Vector3f magnetometer) {
        // ... (Madgwick filter and angle calculations) ...
        // ... (rest of calculateGimbalData method remains the same) ...

        // Send pitch, roll, and yaw to the ESP32
        String dataToSend = String.format("%d,%d,%d\n", (int) pitch, (int) roll, (int) yaw);
        sendData(dataToSend);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission was granted.
                connectToWatch();
            } else {
                // Permission was denied.
                Log.e(TAG, "Bluetooth permission denied.");
                // Consider showing a message to the user.
            }
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        disconnectFromWatch();
    }

    @Override
    public void onDetach() {
        super.onDetach();
        disconnectFromWatch();
    }

    private void disconnectFromWatch() {
        if (bluetoothSocket != null) {
            try {
                bluetoothSocket.close();
                isConnected = false;
                Log.d(TAG, "Disconnected from watch");
            } catch (IOException e) {
                Log.e(TAG, "Error closing Bluetooth socket: " + e.getMessage());
            } finally {
                bluetoothSocket = null;
            }
        }
    }
}