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
    private TextView magXTextView, magYTextView, magZTextView; // Add these
    // Madgwick filter variables
    private float q0 = 1.0f, q1 = 0.0f, q2 = 0.0f, q3 = 0.0f; // Quaternion
    private float pitch, roll, yaw;
    private float linearAccX, linearAccY, linearAccZ;
    private static final float BETA = 0.041f; // Madgwick filter parameter - adjust for your sensor
    private long lastTime = 0;
    private boolean isExtraDataFragmentActive = false; // Track fragment state
    private boolean isConnected = false;
    private boolean registered = false;
    private float gyroX, gyroY, gyroZ;
    private float accX, accY, accZ;
    private float magX, magY, magZ; // Add these
    private TextView pitchTextView, rollTextView, yawTextView;
    private GimbleFragment gimbleFragment; // Add this line


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
        magYTextView = view.findViewById(R.id.magXTextView);
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
            accX = Float.parseFloat(values[0]);
            accY = Float.parseFloat(values[1]);
            accZ = Float.parseFloat(values[2]);
            accXTextView.setText("Acc X: " + String.format("%.3f", accX));
            accYTextView.setText("Acc Y: " + String.format("%.3f", accY));
            accZTextView.setText("Acc Z: " + String.format("%.3f", accZ));
        } else if (values[3].equals("gyro")) {
            gyroX = Float.parseFloat(values[0]);
            gyroY = Float.parseFloat(values[1]);
            gyroZ = Float.parseFloat(values[2]);
            gyroXTextView.setText("Gyro X: " + String.format("%.3f", gyroX));
            gyroYTextView.setText("Gyro Y: " + String.format("%.3f", gyroY));
            gyroZTextView.setText("Gyro Z: " + String.format("%.3f", gyroZ));
        } else if (values[3].equals("mag")) { //handle mag data
            magX = Float.parseFloat(values[0]);
            magY = Float.parseFloat(values[1]);
            magZ = Float.parseFloat(values[2]);
            magXTextView.setText("Mag X: " + String.format("%.3f", magX));
            magYTextView.setText("Mag Y: " + String.format("%.3f", magY));
            magZTextView.setText("Mag Z: " + String.format("%.3f", magZ));
        }
        try {
            // Calculate and display gimbal data
            calculateGimbalData(accX, accY, accZ, gyroX, gyroY, gyroZ, magX, magY, magZ); // Pass mag data
        } catch (NumberFormatException e) {
            Log.e(TAG, "Error parsing IMU data: " + e.getMessage());
        }
    }

    private void calculateGimbalData(float accX, float accY, float accZ, float gyroX, float gyroY, float gyroZ, float magX, float magY, float magZ) {
        long currentTime = System.currentTimeMillis();
        float deltaTime = (lastTime == 0) ? 0 : (currentTime - lastTime) / 1000.0f; // in seconds
        lastTime = currentTime;

        // 1. Normalise accelerometer data
        float normAcc = (float) Math.sqrt(accX * accX + accY * accY + accZ * accZ);
        if (normAcc == 0) return; //handle zero acceleration
        accX /= normAcc;
        accY /= normAcc;
        accZ /= normAcc;

        // 2. Normalise gyroscope data
        float normGyro = (float) Math.sqrt(gyroX * gyroX + gyroY * gyroY + gyroZ * gyroZ);
        if (normGyro > 0) {
            gyroX /= normGyro;
            gyroY /= normGyro;
            gyroZ /= normGyro;
        }

        // 3. Normalise magnetometer data
        float normMag = (float) Math.sqrt(magX * magX + magY * magY + magZ * magZ);
        if (normMag > 0) {
            magX /= normMag;
            magY /= normMag;
            magZ /= normMag;
        }

        // 4. Madgwick filter
        float errorX, errorY, errorZ;
        float s0, s1, s2, s3;

        // Auxiliary variables to avoid repeated calculations
        float q0q0 = q0 * q0;
        float q0q1 = q0 * q1;
        float q0q2 = q0 * q2;
        float q0q3 = q0 * q3;
        float q1q1 = q1 * q1;
        float q1q2 = q1 * q2;
        float q1q3 = q1 * q3;
        float q2q2 = q2 * q2;
        float q2q3 = q2 * q3;
        float q3q3 = q3 * q3;

        // Compute feedback signal from accelerometer and magnetometer
        float hx = 2.0f * (magX * (0.5f - q2q2 - q3q3) + magY * (q1q2 - q0q3) + magZ * (q1q3 + q0q2));
        float hy = 2.0f * (magX * (q1q2 + q0q3) + magY * (0.5f - q1q1 - q3q3) + magZ * (q2q3 - q0q1));
        float bx = (float) Math.sqrt((hx * hx) + (hy * hy));
        float bz = 2.0f * (magX * (q1q3 - q0q2) + magY * (q2q3 + q0q1) + magZ * (0.5f - q1q1 - q2q2));

        errorX = 2 * (q1q3 - q0q2) - accX;
        errorY = 2 * (q0q1 + q2q3) - accY;
        errorZ = 2 * (q0q0 - q1q1 - q2q2 + q3q3) - accZ;

        // Apply magnetometer measurement correction
        errorX += (2.0f * bx * (q1q3 - q0q2) + 2.0f * bz * (2.0f * q1 * q0 + 2.0f * q2 * q1));
        errorY += (2.0f * bx * (q0q1 + q2q3) + 2.0f * bz * (2.0f * q2 * q0 - 2.0f * q1 * q3));
        errorZ += (2.0f * bx * (q0q0 - q1q1 - q2q2 + q3q3) + 2.0f * bz * (2.0f * q3 * q0 - 2.0f * q2 * q2 - 2.0f * q3 * q3));



        // Apply feedback gain
        s0 = q1 * errorZ - q2 * errorY;
        s1 = q2 * errorX - q3 * errorZ;
        s2 = q3 * errorY - q1 * errorX;
        s3 = q0 * errorX + q1 * errorY + q2 * errorZ;

        // Integrate rate of change of quaternion
        q0 += ((-q1 * gyroX - q2 * gyroY - q3 * gyroZ) + BETA * s0) * deltaTime;
        q1 += ((q0 * gyroX + q2 * gyroZ - q3 * gyroY) + BETA * s1) * deltaTime;
        q2 += ((q0 * gyroY - q1 * gyroZ + q3 * gyroX) + BETA * s2) * deltaTime;
        q3 += ((q0 * gyroZ + q1 * gyroY - q2 * gyroX) + BETA * s3) * deltaTime;

        // Normalise quaternion
        float normQ = (float) Math.sqrt(q0 * q0 + q1 * q1 + q2 * q2 + q3 * q3);
        if (normQ == 0) return; //handle zero quaternion.  This is unlikely, but good to check.
        q0 /= normQ;
        q1 /= normQ;
        q2 /= normQ;
        q3 /= normQ;

        // 4. Calculate Euler angles from quaternion
        // Check for singularity (gimbal lock)
        float sinp = 2 * (q0 * q2 - q1 * q3);
        if (Math.abs(sinp) >= 1) {
            // Use a different formula in gimbal lock
            pitch = (float) Math.copySign(Math.PI / 2, sinp); // Use Math.copySign to preserve the sign
            roll = 0; // Roll is undefined in this case
            yaw = (float) Math.atan2(2 * (q1 * q2 + q0 * q3), 1 - 2 * (q0q0 + q1q1));
        } else {
            pitch = (float) Math.asin(sinp);
            roll = (float) Math.atan2(2 * (q0 * q1 + q2 * q3), 1 - 2 * (q1q1 + q2q2));
            yaw = (float) Math.atan2(2 * (q0 * q3 + q1 * q2), 1 - 2 * (q2q2 + q3q3));
        }


        // Convert to degrees
        pitch = (float) (pitch * 180.0f / Math.PI);
        roll = (float) (roll * 180.0f / Math.PI);
        yaw = (float) (yaw * 180.0f / Math.PI);

        // 5. Calculate linear acceleration
        linearAccX = accX - (float) (Math.cos(pitch * Math.PI / 180.0) * Math.sin(roll * Math.PI / 180.0));
        linearAccY = accY - (float) (Math.sin(pitch * Math.PI / 180.0) * Math.sin(roll * Math.PI / 180.0));
        linearAccZ = accZ - (float) Math.cos(roll * Math.PI / 180.0);

        //linear acceleration without gravity compensation
        //linearAccX = accX;
        //linearAccY = accY;
        //linearAccZ = accZ;


        // 6. Update UI if ExtraDataFragment is active
        if (isExtraDataFragmentActive) {
            mainHandler.post(() -> {
                // Find the ExtraDataFragment
                Fragment extraDataFragment = getParentFragmentManager().findFragmentByTag(SecondaryFragment.EXTRA.name());
                if (extraDataFragment instanceof ExtraDataFragment) {
                    // Cast the fragment to ExtraDataFragment
                    ExtraDataFragment extraData = (ExtraDataFragment) extraDataFragment;
                    // Update the views in the ExtraDataFragment
                    extraData.setAccData(linearAccX, linearAccY, linearAccZ);
                    extraData.setGimbalData(pitch, roll, yaw);
                }
            });
        }

        // 8. Pass the updated rotation data to the GimbleFragment
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
