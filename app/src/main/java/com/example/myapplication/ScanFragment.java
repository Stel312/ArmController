package com.example.myapplication;

import android.os.Bundle;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

/**
 * A simple {@link Fragment} subclass.
 * Use the {@link ScanFragment#newInstance} factory method to
 * create an instance of this fragment.
 */
public class ScanFragment extends Fragment {


    private Button goToImuButton;

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
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        View view = inflater.inflate(R.layout.fragment_scan, container, false);

        // Initialize the button
        goToImuButton = view.findViewById(R.id.goToImuButton);

        // Set click listener for the button
        goToImuButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Create new fragment instance
               ImuFagment imuFragment = ImuFagment.newInstance(); // Use newInstance

                // Use FragmentTransaction to replace current fragment with ImuFagment
                FragmentTransaction transaction = getParentFragmentManager().beginTransaction();
                transaction.replace(R.id.fragment_container_view, imuFragment); // Make sure you have a fragment_container
                //transaction.addToBackStack(null); // Optional: Add to back stack for navigation
                transaction.commit();
            }
        });

        return view;
    }


    @Override
    public void onDestroy() {
        super.onDestroy();
    }
}
