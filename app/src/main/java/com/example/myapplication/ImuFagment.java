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
import java.util.Set;
import java.util.UUID;
import java.util.Vector;

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
    private static final float BETA = 0.03f; // Initial Madgwick filter parameter - tune this
    // Tuning BETA:
    // - Lower BETA (closer to 0): More reliance on accelerometer/magnetometer, faster response, more noise.
    // - Higher BETA: More reliance on gyroscope, smoother output, slower response.
    // - Typical range: 0.01 to 0.5, adjust based on sensor noise and application requirements.

    private static final float GYRO_BIAS_BETA = 0.00001f; // Parameter to tune gyro bias correction
    // Tuning GYRO_BIAS_BETA:
    // - Lower GYRO_BIAS_BETA (closer to 0): Slower, more stable bias correction, less sensitive to short-term drift.
    // - Higher GYRO_BIAS_BETA: Faster bias correction, more sensitive to short-term drift, potentially leading to instability.
    // - Typical range: 0.00001 to 0.01, adjust carefully based on gyro drift and stability.

    // Low-pass filter parameters
    private static final float LOW_PASS_FILTER_ALPHA = 0.1f; // Adjust this value as needed (0.0 < ALPHA < 1.0)
    private float prevPitch = 0, prevRoll = 0, prevYaw = 0;


    private long lastTime = 0;
    private boolean isExtraDataFragmentActive = false;
    private boolean isConnected = false;
    private GimbleFragment gimbleFragment;
    private Vector3f gyroBiasEstimate = new Vector3f();


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

        // Initialize TextViews for magnetometer data
        magXTextView = view.findViewById(R.id.magXTextView);
        magYTextView = view.findViewById(R.id.magYTextView);
        magZTextView = view.findViewById(R.id.magZTextView);

        // Initialize the back button and set a click listener
        backToScanButton = view.findViewById(R.id.backToScanButton);
        backToScanButton.setOnClickListener(v -> {
            // Use FragmentTransaction to replace current fragment with ScanFragment
            FragmentTransaction transaction = getParentFragmentManager().beginTransaction();
            transaction.replace(R.id.fragment_container_view, new ScanFragment()); // Replace with a new ScanFragment instance
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
            // Request the permission
            ActivityCompat.requestPermissions(requireActivity(), new String[]{android.Manifest.permission.BLUETOOTH_CONNECT}, 1);
        } else {
            // Permission already granted, proceed with connecting
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
                gimbleFragment = (GimbleFragment) fragment; // Store the GimbleFragment instance
            } else {
                gimbleFragment = null;
            }
            transaction.replace(R.id.secondary_fragment_container, fragment, fragmentType.name()); // Use enum name as tag
            //transaction.addToBackStack(null); // Consider if you want to add to backstack
            transaction.commit();
        } else if (fragmentType == SecondaryFragment.NONE) {
            // Remove any existing fragment
            Fragment currentFragment = fragmentManager.findFragmentById(R.id.secondary_fragment_container);
            if (currentFragment != null) {
                transaction.remove(currentFragment);
                transaction.commit();
            }
        }
    }

    private void connectToWatch() {
        if(isConnected) return;
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
                        isConnected = true;
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
                disconnectFromWatch();

            }
        }).start();
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
            calculateGimbalData(accelerometer, gyroscope); // Pass mag data
        } catch (NumberFormatException e) {
            Log.e(TAG, "Error parsing IMU data: " + e.getMessage());
        }
    }

    /**
     * Calculates and updates the gimbal data (pitch, roll, yaw) using the Madgwick filter.
     *
     * @param accelerometer The accelerometer data as a Vector3f.
     * @param gyroscope     The gyroscope data as a Vector3f.
     */
    private void calculateGimbalData(Vector3f accelerometer, Vector3f gyroscope) {
        long currentTime = System.currentTimeMillis();
        float deltaTime = (lastTime == 0) ? 0 : (currentTime - lastTime) / 1000.0f;
        lastTime = currentTime;
        Vector3f normAccel = new Vector3f(accelerometer).normalize();
        Vector3f normGyro = new Vector3f(gyroscope);

        Vector3f f = new Vector3f(
                2 * (quaternion.x * quaternion.z - quaternion.w * quaternion.y) - normAccel.x,
                2 * (quaternion.w * quaternion.x + quaternion.y * quaternion.z) - normAccel.y,
                2 * (0.5f - quaternion.x * quaternion.x - quaternion.y * quaternion.y) - normAccel.z
        );
        Matrix3f j = new Matrix3f(
                2 * quaternion.z, 2 * quaternion.w, -4 * quaternion.x,
                -2 * quaternion.y, 2 * quaternion.z, 2 * quaternion.w,
                2 * quaternion.x, 2 * quaternion.y, 0
        );
        Vector3f gradient = new Vector3f(f).mulTranspose(j);
        gradient.normalize();

        // Gyro bias correction
        Vector3f gyroBiasCorrection = new Vector3f(normGyro).mul(BETA * deltaTime);
        gyroBiasEstimate.add(new Vector3f(gradient).mul(GYRO_BIAS_BETA)).add(gyroBiasCorrection.mul(GYRO_BIAS_BETA));
        Vector3f gyroCorrected = new Vector3f(normGyro).sub(gyroBiasEstimate);

        Vector3f qDot = new Vector3f(quaternion.w * gyroCorrected.x - quaternion.z * gyroCorrected.y + quaternion.y * gyroCorrected.z,
                quaternion.z * gyroCorrected.x + quaternion.w * gyroCorrected.y - quaternion.x * gyroCorrected.z,
                -quaternion.y * gyroCorrected.x + quaternion.x * gyroCorrected.y + quaternion.w * gyroCorrected.z);
        qDot.mul(0.5f);
        Vector3f qDotMinusBetaGradient = new Vector3f(qDot).sub(new Vector3f(gradient).mul(BETA));

        float qx = quaternion.x + deltaTime * qDotMinusBetaGradient.x;
        float qy = quaternion.y + deltaTime * qDotMinusBetaGradient.y;
        float qz = quaternion.z + deltaTime * qDotMinusBetaGradient.z;
        float qw = quaternion.w + deltaTime * qDotMinusBetaGradient.z;
        quaternion = quaternion.set(qx, qy, qz, qw).normalize();

        // Calculate pitch, roll, and yaw from the updated quaternion.
        pitch = (float) Math.asin(2.0f * (quaternion.w * quaternion.y - quaternion.z * quaternion.x));
        roll = (float) Math.atan2(2.0f * (quaternion.w * quaternion.x + quaternion.y * quaternion.z),
                1.0f - 2.0f * (quaternion.x * quaternion.x + quaternion.y * quaternion.y));
        yaw = (float) Math.atan2(2.0f * (quaternion.w * quaternion.z + quaternion.x * quaternion.y),
                1.0f - 2.0f * (quaternion.y * quaternion.y + quaternion.z * quaternion.z));

        // Apply low-pass filter to the angles
        pitch = LOW_PASS_FILTER_ALPHA * pitch + (1 - LOW_PASS_FILTER_ALPHA) * prevPitch;
        roll = LOW_PASS_FILTER_ALPHA * roll + (1 - LOW_PASS_FILTER_ALPHA) * prevRoll;
        yaw = LOW_PASS_FILTER_ALPHA * yaw + (1 - LOW_PASS_FILTER_ALPHA) * prevYaw;

        // Store current angles for next iteration
        prevPitch = pitch;
        prevRoll = roll;
        prevYaw = yaw;

        // Convert the angles from radians to degrees.
        pitch = (float) Math.toDegrees(pitch);
        roll = (float) Math.toDegrees(roll);
        yaw = (float) Math.toDegrees(yaw);

        if (isExtraDataFragmentActive) {
            mainHandler.post(() -> {
                Fragment extraDataFragment = getParentFragmentManager().findFragmentByTag(SecondaryFragment.EXTRA.name());
                if (extraDataFragment instanceof ExtraDataFragment) {
                    ExtraDataFragment extraData = (ExtraDataFragment) extraDataFragment;
                    extraData.setAccData(linearAccX, linearAccY, linearAccZ);
                    extraData.setGimbalData(pitch, roll, yaw);
                }
            });
        }
        if (gimbleFragment != null) {
            gimbleFragment.setRotation(pitch, roll, yaw);
        }
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
                bluetoothSocket = null; //set it to null
            }
        }
    }
}
