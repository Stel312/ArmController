package com.example.myapplication;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import java.util.ArrayList;
import java.util.Set;

/**
 * A simple {@link Fragment} subclass.
 * Use the {@link ScanFragment#newInstance} factory method to
 * create an instance of this fragment.
 */
public class ScanFragment extends Fragment {

    private Button goToImuButton;
    private Button scanButton;
    private ListView deviceListView;
    private ArrayAdapter<String> deviceListAdapter;
    private ArrayList<BluetoothDevice> bluetoothDevices;
    private BluetoothAdapter bluetoothAdapter;
    private final int REQUEST_ENABLE_BT = 1;
    private final int REQUEST_LOCATION_PERMISSION = 2;

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
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        View view = inflater.inflate(R.layout.fragment_scan, container, false);

        goToImuButton = view.findViewById(R.id.goToImuButton);
        scanButton = view.findViewById(R.id.scanButton);
        deviceListView = view.findViewById(R.id.deviceListView);

        deviceListAdapter = new ArrayAdapter<>(getContext(), android.R.layout.simple_list_item_1);
        deviceListView.setAdapter(deviceListAdapter);

        deviceListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < bluetoothDevices.size()) {
                    BluetoothDevice selectedDevice = bluetoothDevices.get(position);
                    String deviceAddress = selectedDevice.getAddress(); // Declare deviceAddress here
                    ImuFagment imuFragment = ImuFagment.newInstance(deviceAddress);

                    FragmentTransaction transaction = getParentFragmentManager().beginTransaction();
                    transaction.replace(R.id.fragment_container_view, imuFragment);
                    transaction.commit();
                }
            }
        });

        scanButton.setOnClickListener(v -> startBluetoothScan());

        // Set item click listener for the ListView
        deviceListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < bluetoothDevices.size()) {
                    BluetoothDevice selectedDevice = bluetoothDevices.get(position);
                    String deviceAddress = selectedDevice.getAddress();
                    ImuFagment imuFragment = ImuFagment.newInstance(deviceAddress);

                    FragmentTransaction transaction = getParentFragmentManager().beginTransaction();
                    transaction.replace(R.id.fragment_container_view, imuFragment);
                    transaction.commit();
                }
            }
        });

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
            checkLocationPermissionAndScan();
        }
    }

    private void checkLocationPermissionAndScan() {
        if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(getActivity(), new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_LOCATION_PERMISSION);
        } else {
            performBluetoothScan();
        }
    }

    private void performBluetoothScan() {
        deviceListAdapter.clear();
        bluetoothDevices.clear();

        Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
        if (pairedDevices.size() > 0) {
            for (BluetoothDevice device : pairedDevices) {
                deviceListAdapter.add(device.getName() + "\n" + device.getAddress());
                bluetoothDevices.add(device);
            }
        }

        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        getContext().registerReceiver(receiver, filter);

        if (bluetoothAdapter.startDiscovery()) {
            Toast.makeText(getContext(), "Scanning for devices...", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(getContext(), "Scan failed.", Toast.LENGTH_SHORT).show();
        }
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                deviceListAdapter.add(device.getName() + "\n" + device.getAddress());
                bluetoothDevices.add(device);
                deviceListAdapter.notifyDataSetChanged();
            }
        }
    };

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ENABLE_BT) {
            if (resultCode == getActivity().RESULT_OK) {
                checkLocationPermissionAndScan();
            } else {
                Toast.makeText(getContext(), "Bluetooth enabling failed.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_LOCATION_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                performBluetoothScan();
            } else {
                Toast.makeText(getContext(), "Location permission is required for Bluetooth scanning.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try{
            getContext().unregisterReceiver(receiver);
        }catch(IllegalArgumentException e){
            //receiver was not registered.
        }

    }

    private void connectToDevice(BluetoothDevice device) {
        // Implement your Bluetooth connection logic here.
        // For example, create a Bluetooth socket and connect to the device.
        // You'll need to handle the Bluetooth connection in a separate thread.
        Toast.makeText(getContext(), "Connecting to: " + device.getName(), Toast.LENGTH_SHORT).show();
        // Add your connection code here...
    }
}