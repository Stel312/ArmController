package com.example.myapplication.enums;

import androidx.fragment.app.Fragment;

import com.example.myapplication.subfragments.ExtraDataFragment;
import com.example.myapplication.subfragments.GimbleFragment;

public enum SecondaryFragment {
    NONE, EXTRA, GIMBLE;

    public static Fragment newFragmentInstance(SecondaryFragment fragmentType) {
        switch (fragmentType) {
            case EXTRA:
                return new ExtraDataFragment();
            case GIMBLE:
                return new GimbleFragment();
            case NONE:
            default:
                return null;
        }
    }
}
