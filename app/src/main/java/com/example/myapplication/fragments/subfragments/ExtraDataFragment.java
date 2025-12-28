package com.example.myapplication.fragments.subfragments;

import android.os.Bundle;
import androidx.fragment.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.example.myapplication.R;

import org.joml.AxisAngle4f;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * A simple {@link Fragment} subclass.
 * Use the {@link ExtraDataFragment#newInstance} factory method to
 * create an instance of this fragment.
 */
public class ExtraDataFragment extends Fragment {

    private TextView accXExtraTextView, accYExtraTextView, accZExtraTextView;
    private TextView pitchTextView, rollTextView, yawTextView;


    public ExtraDataFragment() {
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
        pitchTextView = view.findViewById(R.id.X);
        rollTextView = view.findViewById(R.id.Y);
        yawTextView = view.findViewById(R.id.Z);

        return view;
    }

    public void setLinearAcceleration(Vector3f accelerometer) {
        if (accXExtraTextView != null && accYExtraTextView != null && accZExtraTextView != null) {
            accXExtraTextView.setText("X: " + String.format("%.3f", accelerometer.x));
            accYExtraTextView.setText("Y: " + String.format("%.3f", accelerometer.y));
            accZExtraTextView.setText("Z: " + String.format("%.3f", accelerometer.z));
        }
    }

    public void setGimbalData(Quaternionf q) {
        if (pitchTextView != null && rollTextView != null && yawTextView != null) {
            // Roll (X-axis rotation)
            Matrix3f matrix3f = new Matrix3f();
            q.get(matrix3f);


            AxisAngle4f axisAngle4f = new AxisAngle4f();
            matrix3f.getRotation(axisAngle4f);



            // Update UI
            pitchTextView.setText("X: " + String.format("%.3f", Math.toDegrees( axisAngle4f.x)));
            rollTextView.setText("Y: " + String.format("%.3f", Math.toDegrees( axisAngle4f.y)));
            yawTextView.setText("Z: " + String.format("%.3f", Math.toDegrees( axisAngle4f.z)));
        }
    }
    public float getAngleAroundAxis(Quaternionf q, char axis) {
        // Extract the rotation quaternion component around specified axis
        float x=0, y=0, z=0, w=0;

        switch(axis) {
            case 'X':
                x = q.x;
                w = q.w;
                // Normalize
                float magX = (float)Math.sqrt(x*x + w*w);
                x /= magX;
                w /= magX;
                return (float)(2 * Math.toDegrees(Math.acos(w)));
            case 'Y':
                y = q.y;
                w = q.w;
                float magY = (float)Math.sqrt(y*y + w*w);
                y /= magY;
                w /= magY;
                return (float)(2 * Math.toDegrees(Math.acos(w)));
            case 'Z':
                z = q.z;
                w = q.w;
                float magZ = (float)Math.sqrt(z*z + w*w);
                z /= magZ;
                w /= magZ;
                return (float)(2 * Math.toDegrees(Math.acos(w)));
            default:
                return 0f;
        }
    }

}