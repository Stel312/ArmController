package com.example.myapplication.view;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.OvershootInterpolator;

import com.example.myapplication.R;

/**
 * A custom view that implements a self-centering joystick.
 */
public class SelfCenteringJoystickView extends View {

    private Paint basePaint;
    private Paint handlePaint;
    private int baseRadius;
    private int handleRadius;
    private int centerX;
    private int centerY;
    private float handleX;
    private float handleY;
    private float touchOffsetX;
    private float touchOffsetY;
    private JoystickListener joystickListener;
    private ValueAnimator animatorX;
    private ValueAnimator animatorY;

    public SelfCenteringJoystickView(Context context) {
        super(context);
        init(context, null, 0);
    }

    public SelfCenteringJoystickView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context, attrs, 0);
    }

    public SelfCenteringJoystickView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init(context, attrs, defStyle);
    }

    private void init(Context context, AttributeSet attrs, int defStyle) {
        // Load attributes
        final TypedArray a = context.obtainStyledAttributes(
                attrs, R.styleable.SelfCenteringJoystickView, defStyle, 0);

        // Initialize paints
        basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        basePaint.setColor(a.getColor(R.styleable.SelfCenteringJoystickView_baseColor, Color.GRAY)); // Default to GRAY
        basePaint.setStyle(Paint.Style.FILL);

        handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        handlePaint.setColor(a.getColor(R.styleable.SelfCenteringJoystickView_handleColor, Color.LTGRAY)); // Default to LTGRAY
        handlePaint.setStyle(Paint.Style.FILL);

        // Get radii from attributes, with defaults
        baseRadius = a.getDimensionPixelSize(R.styleable.SelfCenteringJoystickView_baseRadius, 100); // Default to 100
        handleRadius = a.getDimensionPixelSize(R.styleable.SelfCenteringJoystickView_handleRadius, 60); // Default to 60

        a.recycle();

        // Initial handle position (center)
        handleX = centerX;
        handleY = centerY;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int desiredWidth = (baseRadius + handleRadius) * 4; // Adjust based on your desired size
        int desiredHeight = (baseRadius + handleRadius) * 4;

        int width = resolveSize(desiredWidth, widthMeasureSpec);
        int height = resolveSize(desiredHeight, heightMeasureSpec);

        setMeasuredDimension(width, height);

        centerX = getWidth() / 2;
        centerY = getHeight() / 2;
        handleX = centerX;
        handleY = centerY;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        centerX = w / 2;
        centerY = h / 2;
        handleX = centerX;
        handleY = centerY;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // Draw the base
        canvas.drawCircle(centerX, centerY, baseRadius, basePaint);

        // Draw the handle
        canvas.drawCircle(handleX, handleY, handleRadius, handlePaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float touchX = event.getX();
        float touchY = event.getY();

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                // Check if the touch is within the bounds of the handle
                if (Math.pow(touchX - handleX, 2) + Math.pow(touchY - handleY, 2) < Math.pow(handleRadius * 1.5, 2)) {
                    touchOffsetX = touchX - handleX;
                    touchOffsetY = touchY - handleY;
                    return true; // Consume the touch event
                }
                return false;

            case MotionEvent.ACTION_MOVE:
                handleX = touchX - touchOffsetX;
                handleY = touchY - touchOffsetY;

                // Keep the handle within the bounds of the base
                float distance = (float) Math.sqrt(Math.pow(handleX - centerX, 2) + Math.pow(handleY - centerY, 2));
                if (distance > baseRadius - handleRadius) {
                    float angle = (float) Math.atan2(handleY - centerY, handleX - centerX);
                    handleX = (float) (centerX + (baseRadius - handleRadius) * Math.cos(angle));
                    handleY = (float) (centerY + (baseRadius - handleRadius) * Math.sin(angle));
                }

                if (joystickListener != null) {
                    float deltaX = (handleX - centerX) / (baseRadius - handleRadius);
                    float deltaY = (handleY - centerY) / (baseRadius - handleRadius);
                    joystickListener.onJoystickMoved(deltaX, deltaY);
                }
                invalidate(); // Redraw the view
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                // Animate the handle back to the center
                animatorX = ValueAnimator.ofFloat(handleX, centerX);
                animatorX.setDuration(200);
                animatorX.setInterpolator(new OvershootInterpolator(0.5f));
                animatorX.addUpdateListener(animation -> {
                    handleX = (float) animation.getAnimatedValue();
                    invalidate();
                });
                animatorX.start();

                animatorY = ValueAnimator.ofFloat(handleY, centerY);
                animatorY.setDuration(200);
                animatorY.setInterpolator(new OvershootInterpolator(0.5f));
                animatorY.addUpdateListener(animation -> {
                    handleY = (float) animation.getAnimatedValue();
                    invalidate();
                });
                animatorY.start();

                if (joystickListener != null) {
                    joystickListener.onJoystickReleased();
                }
                return true;
        }
        return super.onTouchEvent(event);
    }

    public interface JoystickListener {
        void onJoystickMoved(float xPercent, float yPercent); // Values between -1 and 1
        void onJoystickReleased();
    }

    public void setJoystickListener(JoystickListener listener) {
        this.joystickListener = listener;
    }

    // Add attributes to res/values/attrs.xml
    // Declare the following attributes in res/values/attrs.xml
    /**
     * <declare-styleable name="SelfCenteringJoystickView">
     * <attr name="baseColor" format="color"/>
     * <attr name="handleColor" format="color"/>
     * <attr name="baseRadius" format="dimension"/>
     * <attr name="handleRadius" format="dimension"/>
     * </declare-styleable>
     */
}

