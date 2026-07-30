package com.pegasuscorp.orbe;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * Rail A-Z latéral avec glisser du doigt et bulle de lettre flottante.
 */
public class AlphabetRailView extends View {

    public interface OnLetterSelectedListener {
        void onLetterSelected(char letter, boolean fromDrag);
    }

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    private final Paint letterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint activePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scrollPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubbleTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint railBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF railRect = new RectF();

    private OnLetterSelectedListener listener;
    private int activeIndex = -1;
    private int scrollHighlightIndex = -1;
    private float touchY = -1f;
    private boolean dragging = false;
    private float letterHeight;
    private float topPad;
    private float bottomPad;
    private float railRadius;

    public AlphabetRailView(Context context, AttributeSet attrs) {
        super(context, attrs);

        railBgPaint.setColor(Color.parseColor("#14FFFFFF"));
        railBgPaint.setStyle(Paint.Style.FILL);

        Typeface light = Typeface.create("sans-serif-light", Typeface.NORMAL);

        letterPaint.setColor(Color.WHITE);
        letterPaint.setAlpha(150);
        letterPaint.setTextAlign(Paint.Align.CENTER);
        letterPaint.setTypeface(light);

        activePaint.setColor(Color.parseColor("#35D0DD"));
        activePaint.setTextAlign(Paint.Align.CENTER);
        activePaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));

        scrollPaint.setColor(Color.parseColor("#35D0DD"));
        scrollPaint.setTextAlign(Paint.Align.CENTER);
        scrollPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        scrollPaint.setAlpha(220);

        bubblePaint.setColor(Color.parseColor("#CC35D0DD"));
        bubblePaint.setStyle(Paint.Style.FILL);

        bubbleTextPaint.setColor(Color.WHITE);
        bubbleTextPaint.setTextAlign(Paint.Align.CENTER);
        bubbleTextPaint.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        bubbleTextPaint.setTextSize(28f);
    }

    public void setOnLetterSelectedListener(OnLetterSelectedListener listener) {
        this.listener = listener;
    }

    public void setAccentColor(int middle) {
        activePaint.setColor(middle);
        scrollPaint.setColor(middle);
        bubblePaint.setColor(Color.argb(0xCC, Color.red(middle), Color.green(middle), Color.blue(middle)));
        invalidate();
    }

    public void setScrollHighlight(char letter) {
        int idx = ALPHABET.indexOf(Character.toUpperCase(letter));
        if (idx >= 0 && idx != scrollHighlightIndex) {
            scrollHighlightIndex = idx;
            invalidate();
        }
    }

    public void clearScrollHighlight() {
        if (scrollHighlightIndex != -1) {
            scrollHighlightIndex = -1;
            invalidate();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        float density = getResources().getDisplayMetrics().density;
        topPad = 6 * density;
        bottomPad = 6 * density;
        railRadius = Math.min(w / 2f, 14 * density);
        float usable = Math.max(0f, h - topPad - bottomPad);
        letterHeight = usable / ALPHABET.length();
        float textSize = Math.min(letterHeight * 0.58f, 11f * density);
        letterPaint.setTextSize(textSize);
        activePaint.setTextSize(Math.min(textSize * 1.15f, 12.5f * density));
        scrollPaint.setTextSize(Math.min(textSize * 1.1f, 12f * density));
        railRect.set(density, density, w - density, h - density);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawRoundRect(railRect, railRadius, railRadius, railBgPaint);

        float cx = getWidth() / 2f;

        for (int i = 0; i < ALPHABET.length(); i++) {
            float cy = topPad + letterHeight * (i + 0.5f);
            char c = ALPHABET.charAt(i);
            int dotIndex = activeIndex >= 0 ? activeIndex : scrollHighlightIndex;
            Paint p = letterPaint;
            if (i == activeIndex) p = activePaint;
            else if (i == scrollHighlightIndex && activeIndex < 0) p = scrollPaint;
            canvas.drawText(String.valueOf(c), cx, cy + p.getTextSize() * 0.35f, p);

            if (i == dotIndex && dotIndex >= 0) {
                float dotR = Math.max(2.5f, getWidth() * 0.09f);
                scrollPaint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(getWidth() - dotR * 1.5f, cy, dotR, scrollPaint);
            }
        }

        if (dragging && activeIndex >= 0 && touchY >= 0) {
            float bubbleR = getWidth() * 0.85f;
            float bubbleX = -bubbleR * 1.1f;
            canvas.drawCircle(bubbleX, touchY, bubbleR, bubblePaint);
            String letter = String.valueOf(ALPHABET.charAt(activeIndex));
            canvas.drawText(letter, bubbleX,
                    touchY + bubbleTextPaint.getTextSize() * 0.35f, bubbleTextPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                dragging = true;
                touchY = event.getY();
                updateIndexFromY(touchY, false);
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                touchY = event.getY();
                updateIndexFromY(touchY, true);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (activeIndex >= 0 && listener != null) {
                    listener.onLetterSelected(ALPHABET.charAt(activeIndex), dragging);
                }
                dragging = false;
                touchY = -1f;
                activeIndex = -1;
                invalidate();
                return true;
        }
        return super.onTouchEvent(event);
    }

    private void updateIndexFromY(float y, boolean fromDrag) {
        if (letterHeight <= 0f) return;
        int idx = (int) ((y - topPad) / letterHeight);
        idx = Math.max(0, Math.min(ALPHABET.length() - 1, idx));
        if (idx != activeIndex) {
            activeIndex = idx;
            if (listener != null) {
                listener.onLetterSelected(ALPHABET.charAt(activeIndex), fromDrag);
            }
            invalidate();
        } else if (fromDrag) {
            invalidate();
        }
    }

    public void highlightLetter(char letter) {
        int idx = ALPHABET.indexOf(Character.toUpperCase(letter));
        if (idx >= 0) {
            activeIndex = idx;
            scrollHighlightIndex = -1;
            invalidate();
            postDelayed(() -> {
                activeIndex = -1;
                invalidate();
            }, 400);
        }
    }
}
