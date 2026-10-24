package com.example.netra_ai;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import com.google.mlkit.vision.objects.DetectedObject;

import java.util.ArrayList;
import java.util.List;

public class OverlayView extends View {

    private final Paint boxPaint = new Paint();
    private final Paint textPaint = new Paint();
    private List<DetectedObject> results = new ArrayList<>();
    private int imageWidth = 1;
    private int imageHeight = 1;

    public OverlayView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        boxPaint.setColor(Color.RED);
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(8f);

        textPaint.setColor(Color.GREEN);
        textPaint.setTextSize(36f);
        textPaint.setFakeBoldText(true);
    }

    public void setResults(List<DetectedObject> results, int imageWidth, int imageHeight) {
        this.results = results;
        this.imageWidth = imageWidth;
        this.imageHeight = imageHeight;
        postInvalidate(); // Triggers redrawing on UI thread
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (results == null || results.isEmpty()) return;

        // Scale factors between Camera Frame coordinates (rotated) and View dimensions
        float scaleX = (float) getWidth() / (float) imageHeight;
        float scaleY = (float) getHeight() / (float) imageWidth;

        for (DetectedObject obj : results) {
            RectF bounds = new RectF(obj.getBoundingBox());

            // Coordinates mapping for 90-degree rotated portrait camera feed
            float left = bounds.top * scaleX;
            float top = bounds.left * scaleY;
            float right = bounds.bottom * scaleX;
            float bottom = bounds.right * scaleY;

            RectF scaledRect = new RectF(left, top, right, bottom);

            // 1. Draw rounded red bounding box
            canvas.drawRoundRect(scaledRect, 16f, 16f, boxPaint);

            // 2. Draw label
            canvas.drawText("OBSTACLE", scaledRect.left + 8, Math.max(scaledRect.top - 12, 40), textPaint);
        }
    }
}