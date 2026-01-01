package com.example.myapplication.view;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import org.joml.Quaternionf;
import org.joml.Vector3f;

public class SharedImuViewModel extends ViewModel {
    private final MutableLiveData<Quaternionf> rotation = new MutableLiveData<>();
    private final MutableLiveData<Vector3f> acceleration = new MutableLiveData<>();
    private final MutableLiveData<String> status = new MutableLiveData<>();

    public LiveData<Quaternionf> getRotation() { return rotation; }
    public LiveData<Vector3f> getAcceleration() { return acceleration; }
    public LiveData<String> getStatus() { return status; }

    public void updateRotation(Quaternionf newRotation) {
        rotation.setValue(newRotation);
    }

    public void updateAcceleration(Vector3f newAcceleration){
        acceleration.setValue(newAcceleration);
    }
    public void updateStatus(String newStatus) {
        status.setValue(newStatus);
    }
}
