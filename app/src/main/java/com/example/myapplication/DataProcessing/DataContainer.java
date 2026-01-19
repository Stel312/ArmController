package com.example.myapplication.DataProcessing;

import android.provider.ContactsContract;
import android.util.Log;

import com.example.myapplication.control.Kinematics;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class DataContainer {

    private Kinematics kinematics;

    private short[] armAngles = {0, 0, 0};

    private Vector3f gimbalAngles;
    private final Vector3f armVector;
    private byte clawAngle;
    private Quaternionf quaternion;
    private short[] armPositionAngles;
    public DataContainer(){
        this.gimbalAngles = new Vector3f();
        this.armVector = new Vector3f();
        this.clawAngle = 0;
        this.kinematics = new Kinematics();
        this.quaternion = new Quaternionf();
    }

    public void processRotationVector(Object o){
            // Alternatively, if ESP32 expects Euler angles:
        if(o instanceof Vector3f){
            gimbalAngles = (Vector3f) o;

        } else if (o instanceof Quaternionf) {

            Quaternionf quaternion = (Quaternionf) o;
            quaternion.getEulerAnglesYXZ(gimbalAngles);  // radians
        }
            gimbalAngles.set((Math.toDegrees(gimbalAngles.x)  +90) %360, (Math.toDegrees(gimbalAngles.y)  +90) % 360, (Math.toDegrees(gimbalAngles.z) +90 ) % 360);

    }

    public void processArmVector(Vector3f armVector){
        this.armVector.set(armVector);
        armPositionAngles =  kinematics.update(armVector);
    }

    public void processClawAngle(byte clawAngle){
        this.clawAngle = clawAngle;
    }


    public byte[] processData(){
        // Log.d("data" , "data being processed " + "{ " + gimbalAngles.x + ", " + gimbalAngles.y + ", "  + armPositionAngles[0] + ", " + armPositionAngles[1] + ", " + armPositionAngles[2] +  ", " + clawAngle + "}");
        ByteBuffer buffer = ByteBuffer.allocate(11);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.putShort((short)gimbalAngles.x);
        buffer.putShort((short)gimbalAngles.y);
        buffer.putShort(armPositionAngles[0]);
        buffer.putShort(armPositionAngles[1]);
        buffer.putShort(armPositionAngles[2]);
        buffer.put(clawAngle);
        return buffer.array();

    }

    public short[] getArmAngles() {
        return armAngles;
    }

    public Vector3f getArmVector() {
        return armVector;
    }

    public Vector3f getGimbalAngles() {
        return gimbalAngles;
    }

    public byte getClawAngle() {
        return clawAngle;
    }

    public Quaternionf getQuaternion() {
        return quaternion;
    }
}
