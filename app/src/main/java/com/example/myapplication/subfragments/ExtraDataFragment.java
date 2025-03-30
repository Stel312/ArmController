package com.example.myapplication.subfragments;

import android.os.Bundle;
import androidx.fragment.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.example.myapplication.R;

/**
 * A simple {@link Fragment} subclass.
 * Use the {@link ExtraDataFragment#newInstance} factory method to
 * create an instance of this fragment.
 */
public class ExtraDataFragment extends Fragment {

    private TextView accXExtraTextView, accYExtraTextView, accZExtraTextView;
    private TextView pitchTextView, rollTextView, yawTextView;


    public ExtraDataFragment() {
        // Required empty public constructor
    }


    // TODO: Rename and change types and number of parameters
    public static ExtraDataFragment newInstance() {
        ExtraDataFragment fragment = new ExtraDataFragment();
        Bundle args = new Bundle();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        View view = inflater.inflate(R.layout.fragment_extra_data, container, false);

        // Initialize accelerometer text views
        accXExtraTextView = view.findViewById(R.id.accXExtra);
        accYExtraTextView = view.findViewById(R.id.accYExtra);
        accZExtraTextView = view.findViewById(R.id.accZExtra);

        // Initialize gimbal text views
        pitchTextView = view.findViewById(R.id.pitch);
        rollTextView = view.findViewById(R.id.roll);
        yawTextView = view.findViewById(R.id.yaw);

        return view;
    }

    public void setAccData(float x, float y, float z) {
        if (accXExtraTextView != null && accYExtraTextView != null && accZExtraTextView != null) {
            accXExtraTextView.setText("X: " + String.format("%.3f", x));
            accYExtraTextView.setText("Y: " + String.format("%.3f", y));
            accZExtraTextView.setText("Z: " + String.format("%.3f", z));
        }
    }

    public void setGimbalData(float pitch, float roll, float yaw) {
        if (pitchTextView != null && rollTextView != null && yawTextView != null) {
            pitchTextView.setText("Pitch: " + String.format("%.3f", pitch));
            rollTextView.setText("Roll: " + String.format("%.3f", roll));
            yawTextView.setText("Yaw: " + String.format("%.3f", yaw));
        }
    }
}
