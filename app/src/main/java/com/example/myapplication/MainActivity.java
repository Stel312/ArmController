package com.example.myapplication;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentTransaction;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "PhoneMainActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Check if this is the first time the activity is created
        if (savedInstanceState == null) {
            // Create an instance of ScanFragment
            ScanFragment scanFragment = ScanFragment.newInstance(); //or use newInstance()

            // Use FragmentTransaction to add ScanFragment to the container
            FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
            transaction.add(R.id.fragment_container_view, scanFragment); // Use the id of your FragmentContainerView
            transaction.commit();
        }
    }
}
