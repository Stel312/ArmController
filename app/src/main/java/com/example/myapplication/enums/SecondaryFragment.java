package com.example.myapplication.enums;

import androidx.fragment.app.Fragment;

import com.example.myapplication.subfragments.ExtraDataFragment;
import com.example.myapplication.subfragments.GimbleFragment;
import com.example.myapplication.subfragments.RawDataFragment;

public enum SecondaryFragment {
    NONE, EXTRA, GIMBLE, RAW;

    public static Fragment newFragmentInstance(SecondaryFragment fragmentType) {
        switch (fragmentType) {
            case EXTRA:
                return new ExtraDataFragment();
            case GIMBLE:
                return new GimbleFragment();
            case RAW:
                return new RawDataFragment();
            case NONE:
            default:
                return null;
        }
    }
}
