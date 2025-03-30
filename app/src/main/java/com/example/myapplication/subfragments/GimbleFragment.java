package com.example.myapplication.subfragments;

import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.fragment.app.Fragment;

import com.example.myapplication.R;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * A simple {@link Fragment} subclass.
 * Use the {@link GimbleFragment#newInstance} factory method to
 * create an instance of this fragment.
 */
public class GimbleFragment extends Fragment implements GLSurfaceView.Renderer {
    private GLSurfaceView glSurfaceView;
    private float pitch = 0;
    private float roll = 0;
    private float yaw = 0;

    // Vertex and fragment shaders
    private final String vertexShaderCode =
            "uniform mat4 uMVPMatrix;" +
                    "attribute vec4 vPosition;" +
                    "void main() {" +
                    "  gl_Position = uMVPMatrix * vPosition;" +
                    "}";

    private final String fragmentShaderCode =
            "precision mediump float;" +
                    "uniform vec4 vColor;" +
                    "void main() {" +
                    "  gl_FragColor = vColor;" +
                    "}";

    private int mProgram;
    private int mPositionHandle;
    private int mColorHandle;
    private int mMVPMatrixHandle;

    private final float[] cubeVertices = {
            -0.5f, -0.5f, -0.5f,
            0.5f, -0.5f, -0.5f,
            0.5f,  0.5f, -0.5f,
            -0.5f,  0.5f, -0.5f,
            -0.5f, -0.5f,  0.5f,
            0.5f, -0.5f,  0.5f,
            0.5f,  0.5f,  0.5f,
            -0.5f,  0.5f,  0.5f,
    };

    private final short[] drawOrder = {
            0, 1, 2, 0, 2, 3,
            4, 5, 6, 4, 6, 7,
            0, 4, 7, 0, 7, 3,
            1, 5, 6, 1, 6, 2,
            0, 1, 5, 0, 5, 4,
            2, 3, 7, 2, 7, 6,
    };

    private final float[] cubeColor = { 0.6f, 0.8f, 0.7f, 1.0f }; // light green

    private FloatBuffer vertexBuffer;
    private ByteBuffer drawListBuffer;

    private float[] projectionMatrix = new float[16];
    private float[] viewMatrix = new float[16];
    private float[] modelMatrix = new float[16];
    private float[] mvpMatrix = new float[16];

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
        View view = inflater.inflate(R.layout.fragment_gimble, container, false);

        // Initialize GLSurfaceView
        glSurfaceView = view.findViewById(R.id.gl_surface_view_gimble); // Make sure to use the ID from fragment_gimble.xml
        if (glSurfaceView != null) {
            glSurfaceView.setEGLContextClientVersion(2); // Use OpenGL ES 2.0
            glSurfaceView.setRenderer(this); // Use the fragment as the renderer.
            glSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY); // Keep rendering
        }
        return view;
    }

    public void setRotation(float pitch, float roll, float yaw) {
        this.pitch = pitch;
        this.roll = roll;
        this.yaw = yaw;
        // Request a render of the scene.
        if (glSurfaceView != null){
            glSurfaceView.requestRender();
        }

    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        // Compile the shaders.
        int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode);
        int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode);

        // Create the program.
        mProgram = GLES20.glCreateProgram();
        GLES20.glAttachShader(mProgram, vertexShader);
        GLES20.glAttachShader(mProgram, fragmentShader);
        GLES20.glLinkProgram(mProgram);

        // Get the attribute/uniform locations.
        mPositionHandle = GLES20.glGetAttribLocation(mProgram, "vPosition");
        mColorHandle = GLES20.glGetUniformLocation(mProgram, "vColor");
        mMVPMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uMVPMatrix");

        // Enable depth testing
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);

        // Prepare the vertex buffer.  This is done in the constructor
        ByteBuffer bb = ByteBuffer.allocateDirect(cubeVertices.length * 4);
        bb.order(ByteOrder.nativeOrder());
        vertexBuffer = bb.asFloatBuffer();
        vertexBuffer.put(cubeVertices);
        vertexBuffer.position(0);

        // Prepare the draw list buffer.
        drawListBuffer = ByteBuffer.allocateDirect(drawOrder.length * 2);
        drawListBuffer.order(ByteOrder.nativeOrder());
        drawListBuffer.asShortBuffer().put(drawOrder);
        drawListBuffer.position(0);
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        GLES20.glViewport(0, 0, width, height);

        float ratio = (float) width / height;
        Matrix.perspectiveM(projectionMatrix, 0, 45, ratio, 0.1f, 100f);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        // Use the program.
        GLES20.glUseProgram(mProgram);

        // Set the vertex data.
        GLES20.glVertexAttribPointer(
                mPositionHandle, 3, GLES20.GL_FLOAT, false,
                3 * 4, vertexBuffer);
        GLES20.glEnableVertexAttribArray(mPositionHandle);

        // Set the color.
        GLES20.glUniform4fv(mColorHandle, 1, cubeColor, 0);

        // Calculate the matrices.
        Matrix.setIdentityM(modelMatrix, 0);
        Matrix.rotateM(modelMatrix, 0, pitch, 1.0f, 0.0f, 0.0f); // Pitch
        Matrix.rotateM(modelMatrix, 0, roll, 0.0f, 1.0f, 0.0f);  // Roll
        Matrix.rotateM(modelMatrix, 0, yaw, 0.0f, 0.0f, 1.0f);   // Yaw

        Matrix.setLookAtM(viewMatrix, 0,
                0, 0, -3,
                0, 0, 0,
                0, 1, 0);

        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0);
        Matrix.multiplyMM(mvpMatrix, 0, mvpMatrix, 0, modelMatrix, 0);

        // Set the transformation matrix.
        GLES20.glUniformMatrix4fv(mMVPMatrixHandle, 1, false, mvpMatrix, 0);

        // Draw the cube.
        GLES20.glDrawElements(
                GLES20.GL_TRIANGLES, drawOrder.length,
                GLES20.GL_UNSIGNED_SHORT, drawListBuffer);

        // Disable vertex array
        GLES20.glDisableVertexAttribArray(mPositionHandle);
    }

    private int loadShader(int type, String shaderCode) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, shaderCode);
        GLES20.glCompileShader(shader);

        // Check for errors.
        final int[] result = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, result, 0);
        if (result[0] == 0) {
            Log.e("Shader", "Compilation failed: " + GLES20.glGetShaderInfoLog(shader));
            GLES20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    @Override
    public void onPause() {
        super.onPause();
        if (glSurfaceView != null) {
            glSurfaceView.onPause();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (glSurfaceView != null) {
            glSurfaceView.onResume();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (glSurfaceView != null) {
            glSurfaceView = null;
        }
    }
}
