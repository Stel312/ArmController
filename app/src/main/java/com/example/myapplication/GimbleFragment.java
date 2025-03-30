package com.example.myapplication;

import android.os.Bundle;

import androidx.fragment.app.Fragment;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

/**
 * A simple {@link Fragment} subclass.
 * Use the {@link GimbleFragment#newInstance} factory method to
 * create an instance of this fragment.
 */
public class GimbleFragment extends Fragment {

    public GimbleFragment() {
        // Required empty public constructor
    }


    public static GimbleFragment newInstance() {
        GimbleFragment fragment = new GimbleFragment();
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
        return inflater.inflate(R.layout.fragment_gimble, container, false);
    }
}