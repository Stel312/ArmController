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
import com.example.myapplication.subfragments.RawDataFragment;

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
 * Use the {@link ImuFragment#newInstance} factory method to
 * create an instance of this fragment.
 */
public class ImuFragment extends Fragment {

    private static final String TAG = "ImuFragment";
    private static final UUID APP_UUID = UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66");
    private BluetoothSocket bluetoothSocket;
    private Handler mainHandler;
    private Button backToScanButton, hideGimbal, showGimbal, showExtra, rawData;
    private boolean isExtraDataFragmentActive = false;
    private boolean isConnected = false;
    private String deviceAddress;
    private OutputStream outputStream;

    private long lastUpdateTime = 0;
    private float deltaTime = 0.0f;
    private float beta = 0.1f; // Initial beta value
    private float betaMin = 0.01f;
    private float betaMax = 1.0f;
    private float gainAdaptationRate = 0.2f; // Adjust this value
    private Vector3f accelerometer = new Vector3f();
    private Vector3f gyroscope = new Vector3f();
    private Vector3f magnetometer = new Vector3f();
    private Vector3f accelerometerFiltered = new Vector3f();
    private Vector3f gyroscopeFiltered = new Vector3f();
    private Vector3f rawGyroscopePrevious = new Vector3f();
    private Quaternionf quaternion = new Quaternionf(0.0f, 0.0f, 0.0f, 1.0f);
    private float pitch, roll, yaw;




    public ImuFragment() {
        // Required empty public constructor
    }

    public static ImuFragment newInstance(String deviceAddress) {
        ImuFragment fragment = new ImuFragment();
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

        showGimbal = view.findViewById(R.id.show_gimbel);
        showGimbal.setOnClickListener(v -> {
            loadFragment(SecondaryFragment.GIMBLE);
            isExtraDataFragmentActive = true;
        });

        hideGimbal = view.findViewById(R.id.hide_gimbel);
        hideGimbal.setOnClickListener(v -> {
            loadFragment(SecondaryFragment.NONE);
            isExtraDataFragmentActive = false;
        });

        rawData = view.findViewById(R.id.raw_data);
        rawData.setOnClickListener(v -> {
            loadFragment(SecondaryFragment.RAW);
            isExtraDataFragmentActive = true;
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
        //if (isConnected || deviceAddress == null) return;
        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            Log.e(TAG, "Bluetooth not supported on this device.");
            return;
        }
        if (ActivityCompat.checkSelfPermission(requireContext(), android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        BluetoothDevice device = bluetoothAdapter.getRemoteDevice("34:E3:FB:82:92:CD");
        try {
            bluetoothSocket = device.createRfcommSocketToServiceRecord(APP_UUID);
            bluetoothSocket.connect();
            Log.d(TAG, "Connected to watch: " + device.getName());
            //outputStream = bluetoothSocket.getOutputStream();
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
        switch (values[3]) {
            case "acc":
                accelerometer.set(Float.parseFloat(values[0]), Float.parseFloat(values[1]), Float.parseFloat(values[2]));
                break;
            case "gyro":
                gyroscope.set(Float.parseFloat(values[0]), Float.parseFloat(values[1]), Float.parseFloat(values[2]));
                break;
            case "mag":  //handle mag data
                magnetometer.set(Float.parseFloat(values[0]), Float.parseFloat(values[1]), Float.parseFloat(values[2]));
                break;
        }
        try {
            if (isExtraDataFragmentActive) {
                mainHandler.post(() -> {
                    Fragment extraDataFragment = getParentFragmentManager().findFragmentByTag(SecondaryFragment.RAW.name());
                    if (extraDataFragment instanceof RawDataFragment) {
                        RawDataFragment extraData = (RawDataFragment) extraDataFragment;
                        extraData.setRawVectors(accelerometerFiltered, gyroscopeFiltered, magnetometer);
                    }
                });
            }

            // Only call calculateGimbalData if none of the vector components are NaN and var is not 0
            if (!Float.isNaN(accelerometer.x) && accelerometer.x != 0 &&
                    !Float.isNaN(accelerometer.y) && accelerometer.y != 0 &&
                    !Float.isNaN(accelerometer.z) && accelerometer.z != 0 &&
                    !Float.isNaN(gyroscope.x) && gyroscope.x != 0 &&
                    !Float.isNaN(gyroscope.y) && gyroscope.y != 0 &&
                    !Float.isNaN(gyroscope.z) && gyroscope.z != 0 &&
                    !Float.isNaN(magnetometer.x) && magnetometer.x != 0 &&
                    !Float.isNaN(magnetometer.y) && magnetometer.y != 0 &&
                    !Float.isNaN(magnetometer.z) && magnetometer.z != 0
                    ) { // Assuming 'var' is a float or integer variable in scope
                calculateGimbalData(accelerometer, gyroscope, magnetometer); // Pass mag data
            }
        } catch (NumberFormatException e) {
            Log.e(TAG, "Error parsing IMU data: " + e.getMessage());
        }
    }

    private void calculateGimbalData(Vector3f rawAccelerometer, Vector3f rawGyroscope, Vector3f magnetometer) {
        // Calculate the time difference (deltaTime) between the current and last sensor update.
        long currentTime = System.nanoTime();
        if (lastUpdateTime != 0) {
            deltaTime = (float) (currentTime - lastUpdateTime) / 1_000_000_000.0f; // Convert nanoseconds to seconds.
        }
        lastUpdateTime = currentTime;


        // ------------------------------------------------------------------------------------------------
        // Dynamic Filtering
        // ------------------------------------------------------------------------------------------------
        // 1. Dynamic Low-Pass Filter for Accelerometer
        float accelerometerCutoffFrequency = 10.0f; // Adjust as needed
        float accelerometerRC = 1.0f / (2 * (float) Math.PI * accelerometerCutoffFrequency);
        float accelerometerAlpha = deltaTime / (accelerometerRC + deltaTime);

        accelerometerFiltered.x = accelerometerAlpha * rawAccelerometer.x + (1 - accelerometerAlpha) * accelerometerFiltered.x;
        accelerometerFiltered.y = accelerometerAlpha * rawAccelerometer.y + (1 - accelerometerAlpha) * accelerometerFiltered.y;
        accelerometerFiltered.z = accelerometerAlpha * rawAccelerometer.z + (1 - accelerometerAlpha) * accelerometerFiltered.z;

        // 2. Dynamic High-Pass Filter for Gyroscope
        float gyroscopeCutoffFrequency = 0.5f; // Adjust as needed
        float gyroscopeRC = 1.0f / (2 * (float) Math.PI * gyroscopeCutoffFrequency);
        float gyroscopeAlpha = gyroscopeRC / (gyroscopeRC + deltaTime);

        gyroscopeFiltered.x = gyroscopeAlpha * gyroscopeFiltered.x + gyroscopeAlpha * (rawGyroscope.x - rawGyroscopePrevious.x);
        gyroscopeFiltered.y = gyroscopeAlpha * gyroscopeFiltered.y + gyroscopeAlpha * (rawGyroscope.y - rawGyroscopePrevious.y);
        gyroscopeFiltered.z = gyroscopeAlpha * gyroscopeFiltered.z + gyroscopeAlpha * (rawGyroscope.z - rawGyroscopePrevious.z);

        // Store current gyroscope reading for next iteratio
// Store current gyroscope reading for next iteration
        rawGyroscopePrevious.set(rawGyroscope);
        // Normalize the raw sensor readings to obtain unit vectors.
        Vector3f accNorm = rawAccelerometer.normalize();
        Vector3f magNorm = magnetometer.normalize();
        Vector3f gyroNorm = rawGyroscope.normalize();

        // Extract the individual components of the quaternion for easier access.
        float q0 = quaternion.w();
        float q1 = quaternion.x();
        float q2 = quaternion.y();
        float q3 = quaternion.z();

        // Auxiliary variables to avoid repeated calculations.
        // Calculate sensor error terms (objective function) which represent the difference
        // between the predicted sensor measurements based on the current quaternion and the
        // actual normalized sensor readings.
        float f1 = 2 * q1 * q3 - 2 * q0 * q2 - accNorm.x; // Error term for accelerometer x-axis.
        float f2 = 2 * q0 * q1 + 2 * q2 * q3 - accNorm.y; // Error term for accelerometer y-axis.
        float f3 = 2 * q1 * q1 + 2 * q2 * q2 - 1 + 2 * q3 * q3 - accNorm.z; // Error term for accelerometer z-axis.
        float f4 = 2 * q1 * q3 - 2 * q0 * q2 - magNorm.x; // Error term for magnetometer x-axis.
        float f5 = 2 * q0 * q1 + 2 * q2 * q3 - magNorm.y; // Error term for magnetometer y-axis.
        float f6 = 2 * q1 * q1 + 2 * q2 * q2 - 1 + 2 * q0 * q0 - magNorm.z; // Error term for magnetometer z-axis.

        // Calculate the magnitude of the combined sensor error. This gives an overall measure
        // of how well the current quaternion aligns with the sensor readings.
        float errorMagnitude = (float) Math.sqrt(f1 * f1 + f2 * f2 + f3 * f3 + f4 * f4 + f5 * f5 + f6 * f6);


        // Dynamically adjust the filter gain (beta) based on the error magnitude.
        // A larger error suggests higher disturbances, so we increase beta for faster convergence.
        // A smaller error suggests the system is more stable, so we decrease beta for smoother filtering.
        float errorThresholdHigh = 0.1f; // Tune this threshold to determine when to increase beta.
        float errorThresholdLow = 0.01f;  // Tune this threshold to determine when to decrease beta.
        float gainAdaptationRate = 0.1f; // Tune the rate at which beta changes.
        float betaMin = 0.01f;           // Minimum value for beta.
        float betaMax = 1.0f;            // Maximum value for beta.

        if (errorMagnitude > errorThresholdHigh) {
            beta = Math.min(betaMax, beta + gainAdaptationRate * deltaTime); // Increase beta.
        } else if (errorMagnitude < errorThresholdLow) {
            beta = Math.max(betaMin, beta - gainAdaptationRate * deltaTime); // Decrease beta.
        }

        // Gradient descent step for accelerometer and magnetometer.
        // This step aims to find the direction in which to adjust the quaternion to minimize the sensor error.
        // Calculate Jacobian matrix elements. The Jacobian matrix represents the partial derivatives
        // of the error functions with respect to the quaternion components.
        float J11or24 = 2 * q2;
        float J12or23 = 2 * q3;
        float J13or22 = 2 * q0;
        float J14or21 = 2 * q1;
        float J32 = 2 * f3 * q1;
        float J33 = 2 * f3 * q2;
        float J41 = 2 * magNorm.z * q2;
        float J42 = 2 * magNorm.z * q3;
        float J43 = 2 * magNorm.z * q0 - 4 * magNorm.x * q1;
        float J44 = 2 * magNorm.z * q1 + 4 * magNorm.x * q0;
        float J51 = 2 * magNorm.x * q1 - 4 * magNorm.y * q0;
        float J52 = 2 * magNorm.x * q0 - 4 * magNorm.y * q1 - 2 * magNorm.z * q3;
        float J53 = 2 * magNorm.x * q3 + 4 * magNorm.y * q2;
        float J54 = 2 * magNorm.x * q2 + 4 * magNorm.y * q3 - 2 * magNorm.z * q1;
        float J61 = 2 * magNorm.x * q0;
        float J62 = 2 * magNorm.x * q1;
        float J63 = 2 * magNorm.x * q2 - 4 * magNorm.z * q3;
        float J64 = 2 * magNorm.x * q3 + 4 * magNorm.z * q2 - 2 * magNorm.y * q1;


        // Compute the gradient of the objective function by multiplying the Jacobian matrix
        // by the error terms. SEq1 to SEq4 represent the direction to adjust each quaternion component
        // to minimize the sensor error.
        float SEq1 = J41 * f4 + J51 * f5 + J61 * f6; // Gradient component for q0.
        float SEq2 = J11or24 * f1 + J32 * f3 + J42 * f4 + J52 * f5 + J62 * f6; // Gradient component for q1.
        float SEq3 = J12or23 * f1 + J33 * f3 + J43 * f4 + J53 * f5 + J63 * f6; // Gradient component for q2.
        float SEq4 = J13or22 * f1 + J14or21 * f2 + J44 * f4 + J54 * f5 + J64 * f6; // Gradient component for q3.

        // Normalize the gradient vector to obtain a unit direction. This ensures that the
        // step size during gradient descent is controlled.
        float norm = (float) Math.sqrt(SEq1 * SEq1 + SEq2 * SEq2 + SEq3 * SEq3 + SEq4 * SEq4);
        SEq1 /= norm;
        SEq2 /= norm;
        SEq3 /= norm;
        SEq4 /= norm;

        // Apply feedback from accelerometer and magnetometer by subtracting the scaled gradient
        // from the quaternion derivative estimated from the gyroscope. This fusion of sensor data
        // helps to correct for gyroscope drift and provides an absolute orientation reference.
        float qDot1 = 0.5f * (-q1 * gyroNorm.x - q2 * gyroNorm.y - q3 * gyroNorm.z) - beta * SEq1;
        float qDot2 = 0.5f * (q0 * gyroNorm.x + q2 * gyroNorm.z - q3 * gyroNorm.y) - beta * SEq2;
        float qDot3 = 0.5f * (q0 * gyroNorm.y - q1 * gyroNorm.z + q3 * gyroNorm.x) - beta * SEq3;
        float qDot4 = 0.5f * (q0 * gyroNorm.z + q1 * gyroNorm.y - q2 * gyroNorm.x) - beta * SEq4;

        // Integrate the quaternion derivative to obtain the new quaternion. This is a first-order
        // approximation of the quaternion update over the time step deltaTime.
        q0 += qDot1 * deltaTime;
        q1 += qDot2 * deltaTime;
        q2 += qDot3 * deltaTime;
        q3 += qDot4 * deltaTime;

        // Update the quaternion object with the new values and normalize it to prevent drift
        // due to numerical errors. A unit quaternion represents a valid rotation.
        quaternion.set(q0, q1, q2, q3);
        quaternion = quaternion.normalize();

        // Calculate pitch, roll, and yaw from the quaternion.
        // These formulas convert a quaternion to Euler angles (in radians), then convert to degrees.
        float sinr_cosp = 2 * (q0 * q1 + q2 * q3);
        float cosr_cosp = 1 - 2 * (q1 * q1 + q2 * q2);
        roll = (float) Math.atan2(sinr_cosp, cosr_cosp);
        roll = (float) Math.toDegrees(roll); // Convert from radians to degrees

        float sinp = 2 * (q0 * q2 - q3 * q1);
        if (Math.abs(sinp) >= 1) {
            pitch = (float) Math.copySign(Math.PI / 2, sinp); // Use 90 degrees if out of range
        } else {
            pitch = (float) Math.asin(sinp);
        }
        pitch = (float) Math.toDegrees(pitch); // Convert from radians to degrees

        float siny_cosp = 2 * (q0 * q3 + q1 * q2);
        float cosy_cosp = 1 - 2 * (q2 * q2 + q3 * q3);
        yaw = (float)  Math.atan2(siny_cosp, cosy_cosp);
        yaw = (float) Math.toDegrees(yaw); // Convert from radians to degrees


        // If the extra data fragment is active, update the displayed gimbal data (pitch, roll, yaw).
        if (isExtraDataFragmentActive) {
            mainHandler.post(() -> {
                // Find the ExtraDataFragment using its tag.
                Fragment extraDataFragment = getParentFragmentManager().findFragmentByTag(SecondaryFragment.EXTRA.name());
                if (extraDataFragment instanceof ExtraDataFragment) {
                    // Cast the fragment to ExtraDataFragment to access its methods.
                    ExtraDataFragment extraData = (ExtraDataFragment) extraDataFragment;
                    // Update the gimbal data in the ExtraDataFragment. Assuming pitch, roll, and yaw
                    // are calculated elsewhere and accessible in this scope.
                    extraData.setGimbalData(pitch, roll, yaw);
                }
                extraDataFragment = getParentFragmentManager().findFragmentByTag(SecondaryFragment.GIMBLE.name());
                // Check if the fragment is an instance of GimbleFragment (typo in original code likely).
                if (extraDataFragment instanceof GimbleFragment) {
                    // Cast the fragment to GimbleFragment.
                    GimbleFragment extraData = (GimbleFragment) extraDataFragment;
                    // Update the rotation data in the GimbleFragment.
                    extraData.setRotation(pitch, roll, yaw);
                }
            });
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
                bluetoothSocket = null;
            }
        }
    }
}