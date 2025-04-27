package com.example.myapplication;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
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

import org.joml.Vector3f;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.UUID;

public class ImuFragment extends Fragment {

    private static final String TAG = "ImuFragment";
    private static final UUID APP_UUID = UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66");
    private BluetoothSocket bluetoothSocket;
    private Handler mainHandler;
    private boolean isExtraDataFragmentActive = false;
    private boolean isConnected = false;
    private String deviceAddress;
    private OutputStream outputStream;
    private final Vector3f accelerometer = new Vector3f();
    private final Vector3f gyroscope = new Vector3f();
    private final Vector3f magnetometer = new Vector3f();
    private final ImuSensorFusion sensorFusion = new ImuSensorFusion();
    private Context context; // To check if the fragment is attached

    public ImuFragment() {
    }

    public static ImuFragment newInstance(String deviceAddress) {
        ImuFragment fragment = new ImuFragment();
        Bundle args = new Bundle();
        args.putString("deviceAddress", deviceAddress);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        this.context = context;
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

        Button backToScanButton = view.findViewById(R.id.backToScanButton);
        backToScanButton.setOnClickListener(v -> {
            if (isAdded() && getActivity() != null) {
                FragmentTransaction transaction = getParentFragmentManager().beginTransaction();
                transaction.replace(R.id.fragment_container_view, new ScanFragment());
                transaction.commit();
                disconnectFromWatch();
            }
        });

        Button showExtra = view.findViewById(R.id.show_extra);
        showExtra.setOnClickListener(v -> {
            if (isAdded() && getActivity() != null) {
                loadFragment(SecondaryFragment.EXTRA);
                isExtraDataFragmentActive = true;
            }
        });

        Button showGimbal = view.findViewById(R.id.show_gimbel);
        showGimbal.setOnClickListener(v -> {
            if (isAdded() && getActivity() != null) {
                loadFragment(SecondaryFragment.GIMBLE);
                isExtraDataFragmentActive = true;
            }
        });

        Button hideGimbal = view.findViewById(R.id.hide_gimbel);
        hideGimbal.setOnClickListener(v -> {
            if (isAdded() && getActivity() != null) {
                loadFragment(SecondaryFragment.NONE);
                isExtraDataFragmentActive = false;
            }
        });

        Button rawData = view.findViewById(R.id.raw_data);
        rawData.setOnClickListener(v -> {
            if (isAdded() && getActivity() != null) {
                loadFragment(SecondaryFragment.RAW);
                isExtraDataFragmentActive = true;
            }
        });

        if (ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(requireActivity(), new String[]{android.Manifest.permission.BLUETOOTH_CONNECT}, 1);
        } else {
            connectToWatch();
        }
        return view;
    }

    private void loadFragment(SecondaryFragment fragmentType) {
        if (isAdded() && getActivity() != null) {
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
        BluetoothDevice device = bluetoothAdapter.getRemoteDevice("34:E3:FB:82:92:CD"); // Use your device address
        try {
            bluetoothSocket = device.createRfcommSocketToServiceRecord(APP_UUID);
            bluetoothSocket.connect();
            Log.d(TAG, "Connected to watch: " + device.getName());
            receiveData();
            isConnected = true;
        } catch (IOException e) {
            Log.e(TAG, "Failed to connect to watch: " + e.getMessage());
            if (isAdded() && getActivity() != null) {
                mainHandler.post(() -> Toast.makeText(getContext(), "Failed to connect", Toast.LENGTH_SHORT).show());
            }
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
                    if (isAdded() && getActivity() != null) {
                        mainHandler.post(() -> processImuData(data));
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Error receiving data: " + e.getMessage());
                disconnectFromWatch();
            }
        }).start();
    }

    private void processImuData(String data) {
        if (isAdded() && getActivity() != null) {
            String[] values = data.split(",");
            if (values.length == 4) { // Ensure we have all the expected data
                try {
                    float x = Float.parseFloat(values[0]);
                    float y = Float.parseFloat(values[1]);
                    float z = Float.parseFloat(values[2]);
                    String type = values[3];

                    switch (type) {
                        case "acc":
                            accelerometer.set(x, y, z);
                            break;
                        case "gyro":
                            gyroscope.set(x, y, z);
                            break;
                        case "mag":  // Handle magnetometer data if available in the future
                            magnetometer.set(x, y, z);
                            break;
                    }

                    // Only call update if accelerometer and gyroscope data are valid (not NaN or zeroed)
                    if (!Float.isNaN(accelerometer.x) && !Float.isNaN(accelerometer.y) && !Float.isNaN(accelerometer.z) &&
                            (accelerometer.x != 0 || accelerometer.y != 0 || accelerometer.z != 0) &&
                            !Float.isNaN(gyroscope.x) && !Float.isNaN(gyroscope.y) && !Float.isNaN(gyroscope.z) &&
                            (gyroscope.x != 0 || gyroscope.y != 0 || gyroscope.z != 0)) {
                        sensorFusion.update(accelerometer, gyroscope);
                        Vector3f eulerAngles = sensorFusion.getEulerAngles();
                        Vector3f filteredAcc = sensorFusion.getFilteredAccelerometer();
                        Vector3f correctedGyro = sensorFusion.getCorrectedGyroscope();

                        if (isExtraDataFragmentActive && isAdded() && getActivity() != null) {
                            mainHandler.post(() -> {
                                FragmentManager fm = getParentFragmentManager();
                                Fragment rawDataFragment = fm.findFragmentByTag(SecondaryFragment.RAW.name());
                                if (rawDataFragment instanceof RawDataFragment) {
                                    ((RawDataFragment) rawDataFragment).setRawVectors(filteredAcc, correctedGyro, magnetometer);
                                }
                                Fragment extraFragment = fm.findFragmentByTag(SecondaryFragment.EXTRA.name());
                                if (extraFragment instanceof ExtraDataFragment) {
                                    ((ExtraDataFragment) extraFragment).setGimbalData(eulerAngles.y, eulerAngles.z, eulerAngles.x); // Adjust order if needed
                                }
                                Fragment gimbalFragment = fm.findFragmentByTag(SecondaryFragment.GIMBLE.name());
                                if (gimbalFragment instanceof GimbleFragment) {
                                    ((GimbleFragment) gimbalFragment).setRotation(eulerAngles.y, eulerAngles.z, eulerAngles.x); // Adjust order if needed
                                }
                            });
                        }
                    }
                } catch (NumberFormatException e) {
                    Log.e(TAG, "Error parsing IMU data: " + e.getMessage());
                }
            } else {
                Log.w(TAG, "Received incomplete IMU data: " + data);
            }
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
    public void onDetach() {
        super.onDetach();
        this.context = null; // Clear context when detached
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
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