package com.example.myapplication.mainfragments;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.example.myapplication.R;
import com.example.myapplication.bluetooth.BluetoothClassicHelper;
import com.example.myapplication.bluetooth.BluetoothHelper;
import com.example.myapplication.imu.ImuDataProcessor;
import com.example.myapplication.imu.ImuSensorFusion;
import com.example.myapplication.subfragments.ExtraDataFragment;
import com.example.myapplication.subfragments.GimbleFragment;
import com.example.myapplication.subfragments.RawDataFragment;
import com.example.myapplication.databinding.FragmentImuFagmentBinding;
import com.google.android.material.navigation.NavigationView;

import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ImuFragment extends Fragment implements BluetoothHelper.BleGattCallback {

    private static final String TAG = "ImuFragment";
    private static final String WATCH_DEVICE_ADDRESS = "34:E3:FB:82:92:CD";
    private static final int BLUETOOTH_CONNECT_PERMISSION_REQUEST = 1;

    private FragmentImuFagmentBinding binding;
    private DrawerLayout drawerLayout;
    private NavigationView navigationView;
    private Toolbar toolbar;

    private BluetoothClassicHelper bluetoothClassicHelper;
    private BluetoothHelper bluetoothBleHelper;

    private String espImuDeviceAddress;
    private BluetoothGatt connectedGatt;

    private BluetoothGattCharacteristic linearAccelerationNotificationCharacteristic;
    private BluetoothGattCharacteristic rotationVectorNotificationCharacteristic;

    private boolean isBleCharacteristicsReady = false;

    private Handler mainHandler;

    private final ImuSensorFusion sensorFusion = new ImuSensorFusion();
    private final ImuDataProcessor imuDataProcessor = new ImuDataProcessor(sensorFusion);
    private Context fragmentContext;

    private Class<? extends Fragment> currentSecondaryFragmentClass = null;

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
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        this.fragmentContext = context;
        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            showToast("Bluetooth not supported on this device.");
            Log.e(TAG, "Bluetooth not supported on this device.");
        } else {
            bluetoothBleHelper = new BluetoothHelper(context, bluetoothAdapter);
            bluetoothClassicHelper = new BluetoothClassicHelper(context, bluetoothAdapter);

            bluetoothClassicHelper.setWatchDataCallback(new BluetoothClassicHelper.WatchDataCallback() {
                @Override
                public void onDataReceived(String data) {
                    imuDataProcessor.process(data, imuDataProcessor.getImuInternalCallback());
                }

                @Override
                public void onConnectionStatus(boolean isConnected, String message) {
                    Log.d(TAG, "Watch connection status: " + message);
                    // Update UI status for watch connection if needed
                }

                @Override
                public void onReadError(String message) {
                    Log.e(TAG, "Watch data read error: " + message);
                    showToast("Watch error: " + message);
                }
            });
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mainHandler = new Handler(Looper.getMainLooper());
        if (getArguments() != null) {
            espImuDeviceAddress = getArguments().getString("deviceAddress");
        }
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentImuFagmentBinding.inflate(inflater, container, false);
        View view = binding.getRoot();

        initUiElements();

        if (savedInstanceState == null) {
            loadSecondaryFragment(GimbleFragment.class); // Load GimbalFragment initially
        }

        imuDataProcessor.setDataReadyListener(new ImuDataProcessor.ImuDataReadyListener() {
            @Override
            public void onRotationDataReady(byte[] rotationDataBytes) {
                if (connectedGatt != null && rotationVectorNotificationCharacteristic != null && isBleCharacteristicsReady) {
                    bluetoothBleHelper.writeCharacteristic(connectedGatt, rotationVectorNotificationCharacteristic, rotationDataBytes);
                } else {
                    Log.w(TAG, "Cannot send rotation data via BLE: " +
                            (connectedGatt == null ? "ESP IMU GATT is null" : "") +
                            (rotationVectorNotificationCharacteristic == null ? " or rotationVectorNotificationCharacteristic is null" : "") +
                            (!isBleCharacteristicsReady ? " or BLE characteristics are not yet ready" : "") + ".");
                }
            }

            @Override
            public void onLinearAccelerationDataReady(byte[] linearAccelerationDataBytes) {
                if (connectedGatt != null && linearAccelerationNotificationCharacteristic != null && isBleCharacteristicsReady) {
                    bluetoothBleHelper.writeCharacteristic(connectedGatt, linearAccelerationNotificationCharacteristic, linearAccelerationDataBytes);
                } else {
                    Log.w(TAG, "Cannot send linear acceleration data via BLE: " +
                            (connectedGatt == null ? "ESP IMU GATT is null" : "") +
                            (linearAccelerationNotificationCharacteristic == null ? " or linearAccelerationNotificationCharacteristic is null" : "") +
                            (!isBleCharacteristicsReady ? " or BLE characteristics are not yet ready" : "") + ".");
                }
            }
        });

        imuDataProcessor.setImuInternalCallback(new ImuDataProcessor.ImuDataCallback() {
            @Override
            public void onNewImuData(Vector3f accelerometer, Vector3f gyroscope, Vector3f magnetometer, Vector3f rotationEuler, Vector3f linearAcceleration) {
                if (isAdded() && getActivity() != null) {
                    mainHandler.post(() -> updateSecondaryFragments(accelerometer, gyroscope, magnetometer, rotationEuler, linearAcceleration));
                }
            }

            @Override
            public void onIncompleteData(String rawData) {
                Log.w(TAG, "Received incomplete IMU data from Watch (via internal callback): " + rawData);
            }

            @Override
            public void onParsingError(String rawData, String errorMessage) {
                Log.e(TAG, "Error parsing IMU data from Watch (via internal callback): " + errorMessage + " - Raw: " + rawData);
            }
        });

        if (checkBluetoothPermissions()) {
            connectToWatch();
            connectToEspImuBle();
        }
        return view;
    }

    private boolean checkBluetoothPermissions() {
        List<String> permissionsToRequest = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT);
        }

        if (!permissionsToRequest.isEmpty()) {
            ActivityCompat.requestPermissions(requireActivity(), permissionsToRequest.toArray(new String[0]), BLUETOOTH_CONNECT_PERMISSION_REQUEST);
            return false;
        }
        return true;
    }

    private void connectToWatch() {
        if (bluetoothClassicHelper != null) {
            bluetoothClassicHelper.connectToWatch(WATCH_DEVICE_ADDRESS);
        } else {
            Log.e(TAG, "BluetoothClassicHelper is null, cannot connect to watch.");
            showToast("Cannot connect to Watch (Classic helper not initialized).");
        }
    }

    private void connectToEspImuBle() {
        if (bluetoothBleHelper != null && espImuDeviceAddress != null) {
            Log.d(TAG, "Attempting to connect to ESP IMU (BLE): " + espImuDeviceAddress);
            bluetoothBleHelper.connectBLEDevice(espImuDeviceAddress, this);
        } else {
            Log.e(TAG, "BluetoothBleHelper or ESP IMU address is null, cannot connect BLE.");
            showToast("Cannot connect to ESP IMU (BLE).");
        }
    }

    @Override
    public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
        connectedGatt = gatt;
        if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
            Log.i(TAG, "BLE Connected to ESP IMU. Discovering services...");
            updateStatusText("Status: Connected to ESP IMU");
            showToast("BLE Connected to ESP IMU.");
            isBleCharacteristicsReady = false;
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            Log.i(TAG, "BLE Disconnected from ESP IMU.");
            updateStatusText("Status: Disconnected");
            showToast("BLE Disconnected from ESP IMU.");
            connectedGatt = null;
            linearAccelerationNotificationCharacteristic = null;
            rotationVectorNotificationCharacteristic = null;
            isBleCharacteristicsReady = false;
        } else if (status != BluetoothGatt.GATT_SUCCESS) {
            Log.e(TAG, "BLE Connection error with ESP IMU, status: " + status);
            updateStatusText("Status: Connection Error (" + status + ")");
            showToast("BLE Connection error with ESP IMU.");
            connectedGatt = null;
            linearAccelerationNotificationCharacteristic = null;
            rotationVectorNotificationCharacteristic = null;
            isBleCharacteristicsReady = false;
        }
    }

    @Override
    public void onServicesDiscovered(BluetoothGatt gatt, int status) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            Log.d(TAG, "Services discovered for ESP IMU. Attempting to get characteristics.");

            BluetoothGattService imuService = gatt.getService(BluetoothHelper.IMU_SERVICE_UUID);
            if (imuService != null) {
                rotationVectorNotificationCharacteristic = imuService.getCharacteristic(BluetoothHelper.ROTATION_VECTOR_CHAR_UUID);
                linearAccelerationNotificationCharacteristic = imuService.getCharacteristic(BluetoothHelper.LINEAR_ACCELERATION_CHAR_UUID);

                if (rotationVectorNotificationCharacteristic != null && linearAccelerationNotificationCharacteristic != null) {
                    Log.d(TAG, "Found Notification and Write Characteristics. Enabling notifications...");
                    bluetoothBleHelper.enableCharacteristicNotifications(connectedGatt, rotationVectorNotificationCharacteristic);
                    bluetoothBleHelper.enableCharacteristicNotifications(connectedGatt, linearAccelerationNotificationCharacteristic);
                    isBleCharacteristicsReady = true;
                    updateStatusText("Status: Connected, Services Ready");
                } else {
                    Log.e(TAG, "Required characteristics not found on ESP IMU. Check UUIDs and ESP32 GATT service definition.");
                    showToast("BLE: IMU Characteristics not found on ESP32.");
                    isBleCharacteristicsReady = false;
                    updateStatusText("Status: Services Not Ready");
                }
            } else {
                Log.e(TAG, "IMU Service not found on ESP32 with UUID: " + BluetoothHelper.IMU_SERVICE_UUID);
                showToast("BLE: IMU Service not found on ESP32.");
                isBleCharacteristicsReady = false;
                updateStatusText("Status: Service Missing");
            }
        } else {
            Log.e(TAG, "Service discovery failed for ESP IMU with status: " + status);
            showToast("BLE Service discovery failed for ESP IMU.");
            isBleCharacteristicsReady = false;
            updateStatusText("Status: Service Discovery Failed (" + status + ")");
        }
    }

    @Override
    public void onCharacteristicsDiscovered(BluetoothGattCharacteristic linearAccelerationChar, BluetoothGattCharacteristic rotationVectorChar) {
        Log.d(TAG, "onCharacteristicsDiscovered callback from BluetoothHelper triggered (redundant for current setup).");
    }

    @Override
    public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        byte[] value = characteristic.getValue();
        UUID charUuid = characteristic.getUuid();

        if (charUuid.equals(BluetoothHelper.ROTATION_VECTOR_CHAR_UUID)) {
            imuDataProcessor.processRotationVectorBLE(value, imuDataProcessor.getImuInternalCallback());
        } else if (charUuid.equals(BluetoothHelper.LINEAR_ACCELERATION_CHAR_UUID)) {
            imuDataProcessor.processLinearAccelerationBLE(value, imuDataProcessor.getImuInternalCallback());
        } else {
            Log.w(TAG, "Received data from unknown BLE characteristic (notification): " + charUuid.toString());
        }
    }

    @Override
    public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            Log.d(TAG, "Characteristic write successful: " + characteristic.getUuid().toString().substring(4, 8));
        } else {
            Log.e(TAG, "Characteristic write failed: " + characteristic.getUuid().toString().substring(4, 8) + ", status: " + status);
            showToast("BLE write failed: " + status);
        }
    }

    @Override
    public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            Log.d(TAG, "Characteristic read successful: " + characteristic.getUuid().toString().substring(4, 8) + ", value: " + bytesToHex(characteristic.getValue()));
        } else {
            Log.e(TAG, "Characteristic read failed: " + characteristic.getUuid().toString().substring(4, 8) + ", status: " + status);
            showToast("BLE read failed: " + status);
        }
    }

    @Override
    public void onDisconnected() {
        Log.i(TAG, "BLE device disconnected via BleGattCallback.");
        connectedGatt = null;
        linearAccelerationNotificationCharacteristic = null;
        rotationVectorNotificationCharacteristic = null;
        isBleCharacteristicsReady = false;
        updateStatusText("Status: Disconnected");
    }

    @Override
    public void onConnectionFailed(String message) {
        Log.e(TAG, "BLE Connection failed: " + message);
        showToast("BLE Connection failed: " + message);
        connectedGatt = null;
        linearAccelerationNotificationCharacteristic = null;
        rotationVectorNotificationCharacteristic = null;
        isBleCharacteristicsReady = false;
        updateStatusText("Status: Connection Failed");
    }

    // --- End BluetoothHelper.BleGattCallback Implementation ---


    private boolean isAnySecondaryFragmentActive() {
        return binding.secondaryFragmentContainer.getVisibility() == View.VISIBLE &&
                getChildFragmentManager().findFragmentById(R.id.secondary_fragment_container) != null;
    }

    private void updateSecondaryFragments(Vector3f accelerometer, Vector3f gyroscope, Vector3f magnetometer, Vector3f rotationEuler, Vector3f linearAcceleration) {
        if (!isAdded() || getActivity() == null || !isAnySecondaryFragmentActive()) {
            Log.w(TAG, "Fragment not attached, container not visible, or no secondary fragment loaded when trying to update.");
            return;
        }

        FragmentManager fm = getChildFragmentManager();
        Fragment currentFragment = fm.findFragmentById(R.id.secondary_fragment_container);

        if (currentFragment instanceof RawDataFragment) {
            ((RawDataFragment) currentFragment).setRawVectors(
                    imuDataProcessor.getFilteredAccelerometer(),
                    imuDataProcessor.getCorrectedGyroscope(),
                    imuDataProcessor.getMagnetometer());
        } else if (currentFragment instanceof ExtraDataFragment) {
            ((ExtraDataFragment) currentFragment).setGimbalData(rotationEuler.x, rotationEuler.y, rotationEuler.z);
            ((ExtraDataFragment) currentFragment).setLinearAcceleration(linearAcceleration);
        } else if (currentFragment instanceof GimbleFragment) {
            ((GimbleFragment) currentFragment).setRotation(imuDataProcessor.getQuaternion());
        }
    }

    private void initUiElements() {
        // Initialize DrawerLayout, NavigationView, and Toolbar from binding
        drawerLayout = binding.drawerLayout;
        navigationView = binding.navView;
        toolbar = binding.imuFragmentToolbar;

        binding.statusTextView.setText("Status: Initializing...");

        // Set up the click listener for the Disconnect button on the main layout
        binding.backToScanButton.setOnClickListener(v -> navigateToScanFragment());

        // Setup the Toolbar with the hamburger icon
        // This makes the Toolbar function as an ActionBar for this fragment
        // You might need to cast getActivity() to AppCompatActivity if it's not directly so.
        if (getActivity() instanceof androidx.appcompat.app.AppCompatActivity) {
            ((androidx.appcompat.app.AppCompatActivity) getActivity()).setSupportActionBar(toolbar);
            // Link the hamburger icon to the drawer
            ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
                    getActivity(), drawerLayout, toolbar,
                    R.string.navigation_drawer_open, R.string.navigation_drawer_close); // Define these strings in strings.xml
            drawerLayout.addDrawerListener(toggle);
            toggle.syncState();
        } else {
            Log.e(TAG, "Activity is not an AppCompatActivity. Toolbar won't function as ActionBar.");
        }

        // Set listener for Navigation Drawer item clicks
        navigationView.setNavigationItemSelectedListener(item -> {
            drawerLayout.closeDrawer(navigationView); // Close the drawer when an item is clicked

            int id = item.getItemId();
            if (id == R.id.nav_hide_display) {
                hideSecondaryFragment();
                return true;
            } else if (id == R.id.nav_show_gimbal) {
                loadSecondaryFragment(GimbleFragment.class);
                return true;
            } else if (id == R.id.nav_show_raw) {
                loadSecondaryFragment(RawDataFragment.class);
                return true;
            } else if (id == R.id.nav_show_extra) {
                loadSecondaryFragment(ExtraDataFragment.class);
                return true;
            }
            return false;
        });

        // You can also update the header of the NavigationView here
        View headerView = navigationView.getHeaderView(0); // Get the first header view if present
        if (headerView != null) {
            TextView connectedDeviceTextView = headerView.findViewById(R.id.textViewConnectedDevice);
            if (connectedDeviceTextView != null) {
                connectedDeviceTextView.setText("Connected: " + (espImuDeviceAddress != null ? espImuDeviceAddress : "None"));
            }
        }
    }

    private void navigateToScanFragment() {
        if (isAdded() && getActivity() != null) {
            getParentFragmentManager().beginTransaction()
                    .replace(R.id.fragment_container_view, new ScanFragment())
                    .commit();
            disconnectFromWatch();
            disconnectFromEspImuBle();
        }
    }

    /**
     * Loads a secondary fragment into the secondary_fragment_container.
     * Manages its visibility.
     *
     * @param fragmentClass The Class of the fragment to load.
     */
    public void loadSecondaryFragment(Class<? extends Fragment> fragmentClass) {
        if (isAdded() && getActivity() != null) {
            FragmentManager fragmentManager = getChildFragmentManager();
            FragmentTransaction transaction = fragmentManager.beginTransaction();

            // Check if the same fragment is already loaded and visible, avoid reloading
            if (currentSecondaryFragmentClass == fragmentClass && binding.secondaryFragmentContainer.getVisibility() == View.VISIBLE) {
                Log.d(TAG, "Fragment " + fragmentClass.getSimpleName() + " already loaded and visible.");
                return;
            }

            try {
                Fragment fragmentInstance = fragmentClass.newInstance();
                String tag = fragmentClass.getSimpleName();

                transaction.replace(R.id.secondary_fragment_container, fragmentInstance, tag);
                binding.secondaryFragmentContainer.setVisibility(View.VISIBLE); // Ensure container is visible
                transaction.commit();
                currentSecondaryFragmentClass = fragmentClass; // Update the current loaded fragment
            } catch (InstantiationException | IllegalAccessException |
                     java.lang.InstantiationException e) {
                Log.e(TAG, "Error creating fragment instance: " + e.getMessage());
                showToast("Error loading fragment.");
            }
        }
    }

    /**
     * Hides the secondary fragment container and removes any loaded fragment.
     */
    public void hideSecondaryFragment() {
        if (isAdded() && getActivity() != null) {
            FragmentManager fragmentManager = getChildFragmentManager();
            Fragment currentFragment = fragmentManager.findFragmentById(R.id.secondary_fragment_container);

            if (currentFragment != null) {
                fragmentManager.beginTransaction()
                        .remove(currentFragment)
                        .commit();
            }
            binding.secondaryFragmentContainer.setVisibility(View.GONE);
            currentSecondaryFragmentClass = null; // Clear the reference
            showToast("Secondary display hidden.");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == BLUETOOTH_CONNECT_PERMISSION_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Bluetooth connect permission granted.");
                connectToWatch();
                connectToEspImuBle();
            } else {
                Log.e(TAG, "Bluetooth connect permission denied.");
                showToast("Bluetooth connect permission denied. Cannot connect to devices.");
            }
        }
    }

    @Override
    public void onDetach() {
        super.onDetach();
        this.fragmentContext = null;
        disconnectFromEspImuBle();
        disconnectFromWatch();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null; // Nullify the binding
        disconnectFromWatch();
        disconnectFromEspImuBle();
    }

    private void disconnectFromWatch() {
        if (bluetoothClassicHelper != null) {
            bluetoothClassicHelper.disconnectFromWatch();
        }
    }

    private void disconnectFromEspImuBle() {
        if (bluetoothBleHelper != null && connectedGatt != null) {
            bluetoothBleHelper.disconnectBLEDevice(connectedGatt);
            connectedGatt = null;
            linearAccelerationNotificationCharacteristic = null;
            rotationVectorNotificationCharacteristic = null;
            isBleCharacteristicsReady = false;
        } else {
            Log.d(TAG, "No BLE Gatt connection to disconnect.");
        }
    }

    private void showToast(String message) {
        if (isAdded() && fragmentContext != null) {
            mainHandler.post(() -> Toast.makeText(fragmentContext, message, Toast.LENGTH_SHORT).show());
        }
    }

    private void updateStatusText(String text) {
        if (isAdded() && binding != null && binding.statusTextView != null) {
            mainHandler.post(() -> binding.statusTextView.setText(text));
        }
    }

    private static String bytesToHex(byte[] bytes) {
        if (bytes == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }
}