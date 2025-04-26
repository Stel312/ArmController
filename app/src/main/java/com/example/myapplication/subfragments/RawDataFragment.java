package com.example.myapplication.subfragments;

import android.os.Bundle;

import androidx.fragment.app.Fragment;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.example.myapplication.R;

import org.joml.Vector3f;

/**
 * A simple {@link Fragment} subclass.
 * Use the {@link RawDataFragment#newInstance} factory method to
 * create an instance of this fragment.
 */
public class RawDataFragment extends Fragment {


    private TextView accXTextView, accYTextView, accZTextView;
    private TextView gyroXTextView, gyroYTextView, gyroZTextView;
    private TextView magXTextView, magYTextView, magZTextView;
    public RawDataFragment() {
        // Required empty public constructor
    }


    public static RawDataFragment newInstance() {
        RawDataFragment fragment = new RawDataFragment();
        Bundle args = new Bundle();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    public void setRawVectors(Vector3f accelerometer, Vector3f gyroscope, Vector3f magnetometer)
    {
        accXTextView.setText(String.format(getString(R.string.acc_x), accelerometer.x));
        accYTextView.setText(String.format(getString(R.string.acc_y), accelerometer.y));
        accZTextView.setText(String.format(getString(R.string.acc_z), accelerometer.z));
        gyroXTextView.setText(String.format(getString(R.string.gyro_x), gyroscope.x));
        gyroYTextView.setText(String.format(getString(R.string.gyro_y), gyroscope.y));
        gyroZTextView.setText(String.format(getString(R.string.gyro_z), gyroscope.z));
        magXTextView.setText(String.format(getString(R.string.mag_x), magnetometer.x));
        magYTextView.setText(String.format(getString(R.string.mag_y), magnetometer.y));
        magZTextView.setText(String.format(getString(R.string.mag_z), magnetometer.z));
    }
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_raw_data, container, false);
        accXTextView = view.findViewById(R.id.accXTextView);
        accYTextView = view.findViewById(R.id.accYTextView);
        accZTextView = view.findViewById(R.id.accZTextView);

        gyroXTextView = view.findViewById(R.id.gyroXTextView);
        gyroYTextView = view.findViewById(R.id.gyroYTextView);
        gyroZTextView = view.findViewById(R.id.gyroZTextView);

        magXTextView = view.findViewById(R.id.magXTextView);
        magYTextView = view.findViewById(R.id.magYTextView);
        magZTextView = view.findViewById(R.id.magZTextView);
        return view;

    }
}