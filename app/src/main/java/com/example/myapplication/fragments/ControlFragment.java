package com.example.myapplication.fragments;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
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
import androidx.lifecycle.ViewModelProvider;

import com.example.myapplication.DataProcessing.ImuDataProcessor;
import com.example.myapplication.R;
import com.example.myapplication.bluetooth.BleGattCallback;
import com.example.myapplication.databinding.FragmentImuFagmentBinding;
import com.example.myapplication.definitions.UUID.BluetoothUUID;
import com.example.myapplication.fragments.subfragments.ExtraDataFragment;
import com.example.myapplication.fragments.subfragments.GimbleFragment;
import com.example.myapplication.fragments.subfragments.RawDataFragment;
import com.example.myapplication.view.SharedImuViewModel;
import com.google.android.material.navigation.NavigationView;
import com.google.android.material.slider.Slider;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * A fragment responsible for managing IMU (Inertial Measurement Unit) data flow,
 * connecting to external Bluetooth devices (both Classic/RFCOMM for a "Watch" and BLE for an "ESP IMU"),
 * processing the received IMU data, and displaying it in various sub-fragments via a navigation drawer.
 * It acts as a central hub for IMU-related operations and UI updates.
 *
 * <p>It implements {@link BleGattCallback} to receive Bluetooth Low Energy (BLE)
 * GATT callbacks for the ESP IMU connection.
 *
 * <p>Required permissions (handled at runtime):
 * <ul>
 * <li>{@link Manifest.permission#BLUETOOTH_CONNECT} (for Android 12+)</li>
 * </ul>
 */
public class ControlFragment extends Fragment {

    private static final String TAG = "ImuFragment";
    /**
     * Request code for Bluetooth connection permissions.
     */
    private static final int BLUETOOTH_CONNECT_PERMISSION_REQUEST = 1;

    private FragmentImuFagmentBinding binding;
    private DrawerLayout drawerLayout;
    private NavigationView navigationView;
    private Toolbar toolbar;
    private Slider clawSlider;

    private String espImuDeviceAddress;
    private BluetoothGatt connectedGatt;

    private BluetoothGattCharacteristic linearAccelerationNotificationCharacteristic;
    private BluetoothGattCharacteristic rotationVectorNotificationCharacteristic;
    private BluetoothGattCharacteristic clawNotificationCharacteristic;

    private Handler mainHandler;

    private ImuDataProcessor imuDataProcessor;
    private Context fragmentContext;
    private Class<? extends Fragment> currentSecondaryFragmentClass = null;
    private SharedImuViewModel sharedViewModel;
    /**
     * Required empty public constructor for Fragment instantiation.
     */
    public ControlFragment() {
        // Required empty public constructor
    }

    /**
     * Creates a new instance of `ImuFragment` with the specified BLE device address.
     * Use this factory method to pass arguments to the fragment.
     *
     * @param deviceAddress The MAC address of the ESP32 IMU device to connect via BLE.
     * @return A new instance of ImuFragment.
     */
    public static ControlFragment newInstance(String deviceAddress) {
        ControlFragment fragment = new ControlFragment();
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
        sharedViewModel = new ViewModelProvider(requireActivity()).get(SharedImuViewModel.class);
        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        imuDataProcessor = new ImuDataProcessor(fragmentContext, bluetoothAdapter, espImuDeviceAddress);
        imuDataProcessor.setSharedViewModel(sharedViewModel);
        if (bluetoothAdapter == null) {
            showToast("Bluetooth not supported on this device.");
            Log.e(TAG, "Bluetooth not supported on this device.");
        }
        if (checkBluetoothPermissions()) {
            imuDataProcessor.connect();
        }
        setObservers();
    }
    private void setObservers(){
        sharedViewModel.getStatus().observe(getViewLifecycleOwner(), updateStatusText -> {
            binding.statusTextView.setText(updateStatusText);
        });
        sharedViewModel.getRotation().observe(getViewLifecycleOwner(),  rotation -> {
            updateSecondaryFragments(imuDataProcessor.getQuaternion(), imuDataProcessor.getLinearAcceleration());
        });
        sharedViewModel.getAcceleration().observe(getViewLifecycleOwner(), acceleration -> {
            updateSecondaryFragments(imuDataProcessor.getQuaternion(), imuDataProcessor.getLinearAcceleration());
        });
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

        // Check for Bluetooth permissions and initiate connections if granted

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
         if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN);
         }

        if (!permissionsToRequest.isEmpty()) {
            ActivityCompat.requestPermissions(requireActivity(), permissionsToRequest.toArray(new String[0]), BLUETOOTH_CONNECT_PERMISSION_REQUEST);
            return false;
        }
        return true;
    }


    /**
     * Callback indicating the result of a characteristic read operation.
     * This method is part of the {@link BleGattCallback} interface.
     *
     * @param gatt The {@link BluetoothGatt} object.
     * @param characteristic The characteristic that was read.
     * @param status Status of the read operation. {@link BluetoothGatt#GATT_SUCCESS} if successful.
     */
    public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] value, int status) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            Log.d(TAG, "Characteristic read successful: " + characteristic.getUuid().toString().substring(4, 8) + ", value: " + bytesToHex(characteristic.getValue()));
            if (characteristic.getUuid().equals(BluetoothUUID.ARM_ANGLE_UUID)){

            }
        } else {
            Log.e(TAG, "Characteristic read failed: " + characteristic.getUuid().toString().substring(4, 8) + ", status: " + status);
            showToast("BLE read failed: " + status);
        }
    }


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
     * @param rotation The current Euler angles (in degrees) from `ImuDataProcessor`.
     * @param linearAcceleration The current linear acceleration data from `ImuDataProcessor`.
     */
    private void updateSecondaryFragments(Quaternionf rotation, Vector3f linearAcceleration) {
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
                    imuDataProcessor.getRoationAngles(), // Using filtered data from processor
                    linearAcceleration,    // Using corrected data from processor
                    new Vector3f(0, 0, 0));         // Using raw data from processor
        } else if (currentFragment instanceof ExtraDataFragment) {
            ((ExtraDataFragment) currentFragment).setGimbalData(rotation);
            ((ExtraDataFragment) currentFragment).setLinearAcceleration(linearAcceleration);
        } else if (currentFragment instanceof GimbleFragment) {
            ((GimbleFragment) currentFragment).setRotation(rotation); // Using quaternion for gimbal rendering
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
        clawSlider = binding.clawSlider;
        binding.statusTextView.setText("Status: Initializing...");

        // Set up the click listener for the Disconnect button on the main layout


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

        // Set listener for Navigaion Drawer item clicks to switch secondary fragments
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
        clawSlider.addOnChangeListener((slider, value, fromUser) -> {


            ByteBuffer buffer = ByteBuffer.allocate(2);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            buffer.putShort((short) (value * 100));
            // Send the correctly formatted 2-byte array.
            imuDataProcessor.onClawDataReady(buffer.array());
        });
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
            imuDataProcessor.disconnect();
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
        imuDataProcessor.disconnect();
    }

    /**
     * Called when the view previously created by {@link #onCreateView(LayoutInflater, ViewGroup, Bundle)}
     * has been detached from the fragment. Cleans up binding and disconnects from Bluetooth devices.
     */
    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null; // Nullify the binding to prevent memory leaks
        imuDataProcessor.disconnect();
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