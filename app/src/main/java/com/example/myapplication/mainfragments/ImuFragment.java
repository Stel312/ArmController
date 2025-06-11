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

/**
 * A fragment responsible for managing IMU (Inertial Measurement Unit) data flow,
 * connecting to external Bluetooth devices (both Classic/RFCOMM for a "Watch" and BLE for an "ESP IMU"),
 * processing the received IMU data, and displaying it in various sub-fragments via a navigation drawer.
 * It acts as a central hub for IMU-related operations and UI updates.
 *
 * <p>It implements {@link BluetoothHelper.BleGattCallback} to receive Bluetooth Low Energy (BLE)
 * GATT callbacks for the ESP IMU connection.
 *
 * <p>Required permissions (handled at runtime):
 * <ul>
 * <li>{@link Manifest.permission#BLUETOOTH_CONNECT} (for Android 12+)</li>
 * </ul>
 */
public class ImuFragment extends Fragment implements BluetoothHelper.BleGattCallback {

    private static final String TAG = "ImuFragment";
    /**
     * The MAC address of the Bluetooth Classic (RFCOMM) "Watch" device.
     */
    private static final String WATCH_DEVICE_ADDRESS = "34:E3:FB:82:92:CD";
    /**
     * Request code for Bluetooth connection permissions.
     */
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

    /**
     * Required empty public constructor for Fragment instantiation.
     */
    public ImuFragment() {
        // Required empty public constructor
    }

    /**
     * Creates a new instance of `ImuFragment` with the specified BLE device address.
     * Use this factory method to pass arguments to the fragment.
     *
     * @param deviceAddress The MAC address of the ESP32 IMU device to connect via BLE.
     * @return A new instance of ImuFragment.
     */
    public static ImuFragment newInstance(String deviceAddress) {
        ImuFragment fragment = new ImuFragment();
        Bundle args = new Bundle();
        args.putString("deviceAddress", deviceAddress);
        fragment.setArguments(args);
        return fragment;
    }

    /**
     * Called when the fragment is first attached to its context.
     * Initializes Bluetooth helpers and sets up the callback for Bluetooth Classic data.
     *
     * @param context The context the fragment is attached to.
     */
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

            // Set up the callback for data received from the Bluetooth Classic Watch
            bluetoothClassicHelper.setWatchDataCallback(new BluetoothClassicHelper.WatchDataCallback() {
                @Override
                public void onDataReceived(String data) {
                    // Process raw data from the Watch using ImuDataProcessor
                    imuDataProcessor.process(data, imuDataProcessor.getImuInternalCallback());
                }

                @Override
                public void onConnectionStatus(boolean isConnected, String message) {
                    Log.d(TAG, "Watch connection status: " + message);
                    // TODO: Update UI status for watch connection if needed
                }

                @Override
                public void onReadError(String message) {
                    Log.e(TAG, "Watch data read error: " + message);
                    showToast("Watch error: " + message);
                }
            });
        }
    }

    /**
     * Called to do initial creation of the fragment.
     * Initializes the main handler and retrieves the ESP IMU device address from arguments.
     *
     * @param savedInstanceState If the fragment is being re-created from a previous saved state, this is the state.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mainHandler = new Handler(Looper.getMainLooper());
        if (getArguments() != null) {
            espImuDeviceAddress = getArguments().getString("deviceAddress");
        }
    }

    /**
     * Called to have the fragment instantiate its user interface view.
     * Inflates the layout, initializes UI elements, sets up data processing listeners,
     * and initiates Bluetooth connections.
     *
     * @param inflater The LayoutInflater object that can be used to inflate any views in the fragment.
     * @param container If non-null, this is the parent view that the fragment's UI should be attached to.
     * @param savedInstanceState If non-null, this fragment is being re-constructed from a previous saved state as given here.
     * @return The View for the fragment's UI, or null.
     */
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentImuFagmentBinding.inflate(inflater, container, false);
        View view = binding.getRoot();

        initUiElements();

        if (savedInstanceState == null) {
            loadSecondaryFragment(GimbleFragment.class); // Load GimbalFragment initially as default display
        }

        // Set up the listener for when processed IMU data is ready to be sent FROM Android TO ESP32 via BLE
        imuDataProcessor.setDataReadyListener(new ImuDataProcessor.ImuDataReadyListener() {
            @Override
            public void onRotationDataReady(byte[] rotationDataBytes) {
                // Check if BLE connection and characteristics are ready before writing
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
                // Check if BLE connection and characteristics are ready before writing
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

        // Set up the internal callback for IMU data processing, which updates the UI
        imuDataProcessor.setImuInternalCallback(new ImuDataProcessor.ImuDataCallback() {
            @Override
            public void onNewImuData(Vector3f accelerometer, Vector3f gyroscope, Vector3f magnetometer, Vector3f rotationEuler, Vector3f linearAcceleration) {
                // Ensure fragment is added and activity is active before attempting UI updates
                if (isAdded() && getActivity() != null) {
                    mainHandler.post(() -> updateSecondaryFragments(accelerometer, gyroscope, magnetometer, rotationEuler, linearAcceleration));
                }
            }

            @Override
            public void onIncompleteData(String rawData) {
                Log.w(TAG, "Received incomplete IMU data from Watch (via internal callback): " + rawData);
                // TODO: Consider showing a Toast for incomplete data if it's frequent and user-facing
            }

            @Override
            public void onParsingError(String rawData, String errorMessage) {
                Log.e(TAG, "Error parsing IMU data from Watch (via internal callback): " + errorMessage + " - Raw: " + rawData);
                showToast("IMU Data Error: " + errorMessage); // Notify user of parsing errors
            }
        });

        // Check for Bluetooth permissions and initiate connections if granted
        if (checkBluetoothPermissions()) {
            connectToWatch();
            connectToEspImuBle();
        }
        return view;
    }

    /**
     * Checks for necessary Bluetooth permissions (e.g., BLUETOOTH_CONNECT for Android 12+).
     * If permissions are not granted, it requests them from the user.
     *
     * @return True if all required permissions are already granted, false otherwise (request initiated).
     */
    private boolean checkBluetoothPermissions() {
        List<String> permissionsToRequest = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        // Add BLUETOOTH_SCAN if needed for initiating BLE scans within this fragment, though it's typically done in ScanFragment.
        // if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
        //     permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN);
        // }

        if (!permissionsToRequest.isEmpty()) {
            ActivityCompat.requestPermissions(requireActivity(), permissionsToRequest.toArray(new String[0]), BLUETOOTH_CONNECT_PERMISSION_REQUEST);
            return false;
        }
        return true;
    }

    /**
     * Initiates a connection to the Bluetooth Classic (RFCOMM) "Watch" device
     * using the {@link BluetoothClassicHelper}.
     */
    private void connectToWatch() {
        if (bluetoothClassicHelper != null) {
            bluetoothClassicHelper.connectToWatch(WATCH_DEVICE_ADDRESS);
        } else {
            Log.e(TAG, "BluetoothClassicHelper is null, cannot connect to watch.");
            showToast("Cannot connect to Watch (Classic helper not initialized).");
        }
    }

    /**
     * Initiates a connection to the Bluetooth Low Energy (BLE) ESP32 IMU device
     * using the {@link BluetoothHelper}.
     */
    private void connectToEspImuBle() {
        if (bluetoothBleHelper != null && espImuDeviceAddress != null) {
            Log.d(TAG, "Attempting to connect to ESP IMU (BLE): " + espImuDeviceAddress);
            bluetoothBleHelper.connectBLEDevice(espImuDeviceAddress, this); // 'this' refers to ImuFragment implementing BleGattCallback
        } else {
            Log.e(TAG, "BluetoothBleHelper or ESP IMU address is null, cannot connect BLE.");
            showToast("Cannot connect to ESP IMU (BLE).");
        }
    }

    // --- BluetoothHelper.BleGattCallback Implementation ---

    /**
     * Callback indicating a change in the BLE connection state.
     * This method is part of the {@link BluetoothHelper.BleGattCallback} interface.
     *
     * @param gatt The {@link BluetoothGatt} object representing the GATT client.
     * @param status Status of the GATT operation. {@link BluetoothGatt#GATT_SUCCESS} if the operation completed successfully.
     * @param newState The new connection state: {@link BluetoothProfile#STATE_CONNECTED} or {@link BluetoothProfile#STATE_DISCONNECTED}.
     */
    @Override
    public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
        connectedGatt = gatt; // Store the GATT instance
        if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
            Log.i(TAG, "BLE Connected to ESP IMU. Discovering services...");
            updateStatusText("Status: Connected to ESP IMU");
            showToast("BLE Connected to ESP IMU.");
            isBleCharacteristicsReady = false; // Reset flag until services are discovered
            // Initiate service discovery immediately upon connection
            gatt.discoverServices();
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            Log.i(TAG, "BLE Disconnected from ESP IMU.");
            updateStatusText("Status: Disconnected");
            showToast("BLE Disconnected from ESP IMU.");
            // Clear all GATT-related references
            connectedGatt = null;
            linearAccelerationNotificationCharacteristic = null;
            rotationVectorNotificationCharacteristic = null;
            isBleCharacteristicsReady = false;
        } else if (status != BluetoothGatt.GATT_SUCCESS) {
            Log.e(TAG, "BLE Connection error with ESP IMU, status: " + status);
            updateStatusText("Status: Connection Error (" + status + ")");
            showToast("BLE Connection error with ESP IMU.");
            // Clear all GATT-related references on error
            connectedGatt = null;
            linearAccelerationNotificationCharacteristic = null;
            rotationVectorNotificationCharacteristic = null;
            isBleCharacteristicsReady = false;
        }
    }

    /**
     * Callback invoked when GATT services have been discovered for the remote device.
     * This method is part of the {@link BluetoothHelper.BleGattCallback} interface.
     *
     * @param gatt The {@link BluetoothGatt} object representing the GATT client.
     * @param status Status of the GATT operation. {@link BluetoothGatt#GATT_SUCCESS} if the operation completed successfully.
     */
    @Override
    public void onServicesDiscovered(BluetoothGatt gatt, int status) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            Log.d(TAG, "Services discovered for ESP IMU. Attempting to get characteristics.");

            // Get the specific IMU service using its UUID
            BluetoothGattService imuService = gatt.getService(BluetoothHelper.IMU_SERVICE_UUID);
            if (imuService != null) {
                // Get the required characteristics within the IMU service
                rotationVectorNotificationCharacteristic = imuService.getCharacteristic(BluetoothHelper.ROTATION_VECTOR_CHAR_UUID);
                linearAccelerationNotificationCharacteristic = imuService.getCharacteristic(BluetoothHelper.LINEAR_ACCELERATION_CHAR_UUID);

                if (rotationVectorNotificationCharacteristic != null && linearAccelerationNotificationCharacteristic != null) {
                    Log.d(TAG, "Found Notification and Write Characteristics. Enabling notifications...");
                    // Enable notifications for both characteristics to receive data from ESP32
                    bluetoothBleHelper.enableCharacteristicNotifications(connectedGatt, rotationVectorNotificationCharacteristic);
                    bluetoothBleHelper.enableCharacteristicNotifications(connectedGatt, linearAccelerationNotificationCharacteristic);
                    isBleCharacteristicsReady = true; // Set flag indicating characteristics are ready for use
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

    /**
     * This callback is defined in {@link BluetoothHelper.BleGattCallback} but may be redundant
     * depending on how {@link BluetoothHelper} internally manages characteristic discovery.
     * For this specific setup, `onServicesDiscovered` is the primary point for characteristic setup.
     *
     * @param linearAccelerationChar The characteristic for linear acceleration.
     * @param rotationVectorChar The characteristic for rotation vector.
     */
    @Override
    public void onCharacteristicsDiscovered(BluetoothGattCharacteristic linearAccelerationChar, BluetoothGattCharacteristic rotationVectorChar) {
        Log.d(TAG, "onCharacteristicsDiscovered callback from BluetoothHelper triggered (redundant for current setup).");
        // The actual characteristics are obtained and stored in onServicesDiscovered
    }

    /**
     * Callback reporting the result of a characteristic read operation.
     * This method is part of the {@link BluetoothHelper.BleGattCallback} interface.
     * This is typically for notifications/indications, where data is pushed from the peripheral.
     *
     * @param gatt The {@link BluetoothGatt} object.
     * @param characteristic The characteristic that was changed.
     */
    @Override
    public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        byte[] value = characteristic.getValue();
        UUID charUuid = characteristic.getUuid();

        // Process the received byte array based on its characteristic UUID
        if (charUuid.equals(BluetoothHelper.ROTATION_VECTOR_CHAR_UUID)) {
            imuDataProcessor.processRotationVectorBLE(value, imuDataProcessor.getImuInternalCallback());
        } else if (charUuid.equals(BluetoothHelper.LINEAR_ACCELERATION_CHAR_UUID)) {
            imuDataProcessor.processLinearAccelerationBLE(value, imuDataProcessor.getImuInternalCallback());
        } else {
            Log.w(TAG, "Received data from unknown BLE characteristic (notification): " + charUuid.toString());
        }
    }

    /**
     * Callback indicating the result of a characteristic write operation.
     * This method is part of the {@link BluetoothHelper.BleGattCallback} interface.
     *
     * @param gatt The {@link BluetoothGatt} object.
     * @param characteristic The characteristic that was written to.
     * @param status Status of the write operation. {@link BluetoothGatt#GATT_SUCCESS} if successful.
     */
    @Override
    public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            Log.d(TAG, "Characteristic write successful: " + characteristic.getUuid().toString().substring(4, 8));
        } else {
            Log.e(TAG, "Characteristic write failed: " + characteristic.getUuid().toString().substring(4, 8) + ", status: " + status);
            showToast("BLE write failed: " + status);
        }
    }

    /**
     * Callback indicating the result of a characteristic read operation.
     * This method is part of the {@link BluetoothHelper.BleGattCallback} interface.
     *
     * @param gatt The {@link BluetoothGatt} object.
     * @param characteristic The characteristic that was read.
     * @param status Status of the read operation. {@link BluetoothGatt#GATT_SUCCESS} if successful.
     */
    @Override
    public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            Log.d(TAG, "Characteristic read successful: " + characteristic.getUuid().toString().substring(4, 8) + ", value: " + bytesToHex(characteristic.getValue()));
        } else {
            Log.e(TAG, "Characteristic read failed: " + characteristic.getUuid().toString().substring(4, 8) + ", status: " + status);
            showToast("BLE read failed: " + status);
        }
    }

    /**
     * Callback invoked when the BLE device has been disconnected.
     * This method is part of the {@link BluetoothHelper.BleGattCallback} interface.
     * It clears GATT-related references and updates the UI status.
     */
    @Override
    public void onDisconnected() {
        Log.i(TAG, "BLE device disconnected via BleGattCallback.");
        connectedGatt = null;
        linearAccelerationNotificationCharacteristic = null;
        rotationVectorNotificationCharacteristic = null;
        isBleCharacteristicsReady = false;
        updateStatusText("Status: Disconnected");
    }

    /**
     * Callback invoked when a BLE connection attempt has failed.
     * This method is part of the {@link BluetoothHelper.BleGattCallback} interface.
     * It clears GATT-related references and updates the UI status.
     *
     * @param message A descriptive message about the connection failure.
     */
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

    /**
     * Checks if the secondary fragment container is visible and currently holds a fragment.
     * @return True if a secondary fragment is actively displayed, false otherwise.
     */
    private boolean isAnySecondaryFragmentActive() {
        return binding.secondaryFragmentContainer.getVisibility() == View.VISIBLE &&
                getChildFragmentManager().findFragmentById(R.id.secondary_fragment_container) != null;
    }

    /**
     * Updates the currently loaded secondary fragment with the latest IMU data.
     * It determines which type of secondary fragment is active and calls its specific update method.
     *
     * @param accelerometer The current accelerometer data (filtered from `ImuDataProcessor`).
     * @param gyroscope The current gyroscope data (corrected from `ImuDataProcessor`).
     * @param magnetometer The current magnetometer data (raw from `ImuDataProcessor`).
     * @param rotationEuler The current Euler angles (in degrees) from `ImuDataProcessor`.
     * @param linearAcceleration The current linear acceleration data from `ImuDataProcessor`.
     */
    private void updateSecondaryFragments(Vector3f accelerometer, Vector3f gyroscope, Vector3f magnetometer, Vector3f rotationEuler, Vector3f linearAcceleration) {
        // Prevent updates if the fragment is not attached, activity is null, or no secondary fragment is active
        if (!isAdded() || getActivity() == null || !isAnySecondaryFragmentActive()) {
            Log.w(TAG, "Fragment not attached, container not visible, or no secondary fragment loaded when trying to update.");
            return;
        }

        FragmentManager fm = getChildFragmentManager();
        Fragment currentFragment = fm.findFragmentById(R.id.secondary_fragment_container);

        // Cast and update the specific secondary fragment based on its type
        if (currentFragment instanceof RawDataFragment) {
            ((RawDataFragment) currentFragment).setRawVectors(
                    imuDataProcessor.getFilteredAccelerometer(), // Using filtered data from processor
                    imuDataProcessor.getCorrectedGyroscope(),    // Using corrected data from processor
                    imuDataProcessor.getMagnetometer());         // Using raw data from processor
        } else if (currentFragment instanceof ExtraDataFragment) {
            ((ExtraDataFragment) currentFragment).setGimbalData(rotationEuler.x, rotationEuler.y, rotationEuler.z);
            ((ExtraDataFragment) currentFragment).setLinearAcceleration(linearAcceleration);
        } else if (currentFragment instanceof GimbleFragment) {
            ((GimbleFragment) currentFragment).setRotation(imuDataProcessor.getQuaternion()); // Using quaternion for gimbal rendering
        }
    }

    /**
     * Initializes UI elements such as the DrawerLayout, NavigationView, and Toolbar.
     * Sets up click listeners for navigation drawer items and the "Back to Scan" button.
     */
    private void initUiElements() {
        // Initialize DrawerLayout, NavigationView, and Toolbar from binding
        drawerLayout = binding.drawerLayout;
        navigationView = binding.navView;
        toolbar = binding.imuFragmentToolbar;

        binding.statusTextView.setText("Status: Initializing...");

        // Set up the click listener for the Disconnect button on the main layout
        binding.backToScanButton.setOnClickListener(v -> navigateToScanFragment());

        // Setup the Toolbar with the hamburger icon to open the navigation drawer
        if (getActivity() instanceof androidx.appcompat.app.AppCompatActivity) {
            ((androidx.appcompat.app.AppCompatActivity) getActivity()).setSupportActionBar(toolbar);
            // Link the hamburger icon to the drawer
            ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
                    getActivity(), drawerLayout, toolbar,
                    R.string.navigation_drawer_open, R.string.navigation_drawer_close); // Define these strings in strings.xml
            drawerLayout.addDrawerListener(toggle);
            toggle.syncState(); // Synchronize the state of the drawer indicator
        } else {
            Log.e(TAG, "Activity is not an AppCompatActivity. Toolbar won't function as ActionBar properly.");
        }

        // Set listener for Navigation Drawer item clicks to switch secondary fragments
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

        // Optionally, update the header of the NavigationView to show connected device info
        View headerView = navigationView.getHeaderView(0); // Get the first header view if present
        if (headerView != null) {
            TextView connectedDeviceTextView = headerView.findViewById(R.id.textViewConnectedDevice);
            if (connectedDeviceTextView != null) {
                connectedDeviceTextView.setText("Connected: " + (espImuDeviceAddress != null ? espImuDeviceAddress : "None"));
            }
        }
    }

    /**
     * Navigates back to the {@link ScanFragment} and initiates disconnection from
     * both the Watch (RFCOMM) and ESP IMU (BLE) devices.
     */
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
     * Loads a specified secondary fragment into the `secondary_fragment_container`
     * and manages its visibility. If the requested fragment is already loaded and visible,
     * it prevents redundant reloading.
     *
     * @param fragmentClass The {@link Class} of the fragment to load (e.g., `GimbleFragment.class`).
     */
    public void loadSecondaryFragment(Class<? extends Fragment> fragmentClass) {
        if (isAdded() && getActivity() != null) {
            FragmentManager fragmentManager = getChildFragmentManager();
            FragmentTransaction transaction = fragmentManager.beginTransaction();

            // Check if the same fragment is already loaded and visible to avoid unnecessary reloads
            if (currentSecondaryFragmentClass == fragmentClass && binding.secondaryFragmentContainer.getVisibility() == View.VISIBLE) {
                Log.d(TAG, "Fragment " + fragmentClass.getSimpleName() + " already loaded and visible. Skipping reload.");
                return;
            }

            try {
                // Create a new instance of the specified fragment class
                Fragment fragmentInstance = fragmentClass.newInstance();
                String tag = fragmentClass.getSimpleName(); // Use class name as tag for easy retrieval

                // Replace any existing fragment in the container
                transaction.replace(R.id.secondary_fragment_container, fragmentInstance, tag);
                binding.secondaryFragmentContainer.setVisibility(View.VISIBLE); // Ensure the container is visible
                transaction.commit();
                currentSecondaryFragmentClass = fragmentClass; // Update the reference to the currently loaded fragment
            } catch (java.lang.InstantiationException | IllegalAccessException e) {
                Log.e(TAG, "Error creating fragment instance for " + fragmentClass.getSimpleName() + ": " + e.getMessage(), e);
                showToast("Error loading fragment: " + fragmentClass.getSimpleName());
            }
        }
    }

    /**
     * Hides the secondary fragment container and removes any currently loaded secondary fragment.
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
            binding.secondaryFragmentContainer.setVisibility(View.GONE); // Hide the container
            currentSecondaryFragmentClass = null; // Clear the reference as no fragment is loaded
            showToast("Secondary display hidden.");
        }
    }

    /**
     * Callback for the result from requesting permissions. This method is invoked for every call
     * to {@link ActivityCompat#requestPermissions(android.app.Activity, String[], int)}.
     *
     * @param requestCode The request code passed in {@link #requestPermissions(String[], int)}.
     * @param permissions The requested permissions. Never null.
     * @param grantResults The grant results for the corresponding permissions
     * which is either {@link PackageManager#PERMISSION_GRANTED} or {@link PackageManager#PERMISSION_DENIED}. Never null.
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == BLUETOOTH_CONNECT_PERMISSION_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Bluetooth connect permission granted.");
                connectToWatch(); // Attempt connections after permission is granted
                connectToEspImuBle();
            } else {
                Log.e(TAG, "Bluetooth connect permission denied.");
                showToast("Bluetooth connect permission denied. Cannot connect to devices.");
            }
        }
    }

    /**
     * Called when the fragment is no longer attached to its activity.
     * Disconnects from both Bluetooth devices to release resources.
     */
    @Override
    public void onDetach() {
        super.onDetach();
        this.fragmentContext = null; // Clear context reference
        disconnectFromEspImuBle();
        disconnectFromWatch();
    }

    /**
     * Called when the view previously created by {@link #onCreateView(LayoutInflater, ViewGroup, Bundle)}
     * has been detached from the fragment. Cleans up binding and disconnects from Bluetooth devices.
     */
    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null; // Nullify the binding to prevent memory leaks
        disconnectFromWatch();
        disconnectFromEspImuBle();
    }

    /**
     * Disconnects from the Bluetooth Classic (RFCOMM) Watch device if connected.
     */
    private void disconnectFromWatch() {
        if (bluetoothClassicHelper != null) {
            bluetoothClassicHelper.disconnectFromWatch();
        }
    }

    /**
     * Disconnects from the Bluetooth Low Energy (BLE) ESP IMU device if connected.
     * Also clears all related GATT and characteristic references.
     */
    private void disconnectFromEspImuBle() {
        if (bluetoothBleHelper != null && connectedGatt != null) {
            bluetoothBleHelper.disconnectBLEDevice(connectedGatt);
            connectedGatt = null; // Clear the GATT reference
            linearAccelerationNotificationCharacteristic = null; // Clear characteristic references
            rotationVectorNotificationCharacteristic = null;
            isBleCharacteristicsReady = false; // Reset the characteristics ready flag
        } else {
            Log.d(TAG, "No BLE Gatt connection to disconnect.");
        }
    }

    /**
     * Displays a short Toast message on the main UI thread.
     * Ensures the Toast is only shown if the fragment is currently added to an activity.
     *
     * @param message The string message to display in the Toast.
     */
    private void showToast(String message) {
        if (isAdded() && fragmentContext != null) {
            mainHandler.post(() -> Toast.makeText(fragmentContext, message, Toast.LENGTH_SHORT).show());
        }
    }

    /**
     * Updates the status TextView on the main UI thread.
     * Ensures the TextView is only updated if the fragment's view is available.
     *
     * @param text The string to set as the status text.
     */
    private void updateStatusText(String text) {
        if (isAdded() && binding != null && binding.statusTextView != null) {
            mainHandler.post(() -> binding.statusTextView.setText(text));
        }
    }

    /**
     * Converts a byte array to its hexadecimal string representation for logging purposes.
     *
     * @param bytes The byte array to convert.
     * @return A string representing the byte array in hexadecimal format, or "null" if the input is null.
     */
    private static String bytesToHex(byte[] bytes) {
        if (bytes == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }
}