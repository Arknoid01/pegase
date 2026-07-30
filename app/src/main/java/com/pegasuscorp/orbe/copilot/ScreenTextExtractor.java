package com.pegasuscorp.orbe.copilot;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.util.Log;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.ArrayList;
import java.util.List;

/**
 * OCR local ML Kit — complément à l'arbre d'accessibilité (pas de compréhension).
 */
public final class ScreenTextExtractor {

    private static final String TAG = "ScreenTextExtractor";

    public interface Callback {
        void onSuccess(String plainText);
        void onError(String message);
    }

    public interface BlocksCallback {
        void onSuccess(List<TextBlock> blocks);
        void onError(String message);
    }

    public static final class TextBlock {
        public final String text;
        public final int left;
        public final int top;
        public final int right;
        public final int bottom;

        public TextBlock(String text, int left, int top, int right, int bottom) {
            this.text = text != null ? text : "";
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }
    }

    private static volatile TextRecognizer recognizer;

    private ScreenTextExtractor() {}

    public static void recognize(Bitmap bitmap, Callback callback) {
        recognizeBlocks(bitmap, new BlocksCallback() {
            @Override
            public void onSuccess(List<TextBlock> blocks) {
                if (callback == null) return;
                StringBuilder sb = new StringBuilder();
                for (TextBlock b : blocks) {
                    if (b.text.isEmpty()) continue;
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(b.text);
                }
                callback.onSuccess(sb.toString().trim());
            }

            @Override
            public void onError(String message) {
                if (callback != null) callback.onError(message);
            }
        });
    }

    public static void recognizeBlocks(Bitmap bitmap, BlocksCallback callback) {
        if (bitmap == null) {
            if (callback != null) callback.onError("Image vide.");
            return;
        }
        InputImage image = InputImage.fromBitmap(bitmap, 0);
        getRecognizer().process(image)
                .addOnSuccessListener(text -> {
                    if (callback != null) callback.onSuccess(flattenBlocks(text));
                })
                .addOnFailureListener(e -> {
                    Log.w(TAG, "OCR failed", e);
                    if (callback != null) {
                        callback.onError(e.getMessage() != null
                                ? e.getMessage() : "OCR impossible");
                    }
                });
    }

    private static TextRecognizer getRecognizer() {
        if (recognizer == null) {
            synchronized (ScreenTextExtractor.class) {
                if (recognizer == null) {
                    recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
                }
            }
        }
        return recognizer;
    }

    private static List<TextBlock> flattenBlocks(Text text) {
        List<TextBlock> out = new ArrayList<>();
        if (text == null) return out;
        for (Text.TextBlock block : text.getTextBlocks()) {
            String line = block.getText();
            if (line == null || line.trim().isEmpty()) continue;
            Rect rect = block.getBoundingBox();
            int l = 0, t = 0, r = 0, b = 0;
            if (rect != null) {
                l = rect.left;
                t = rect.top;
                r = rect.right;
                b = rect.bottom;
            }
            out.add(new TextBlock(line.trim(), l, t, r, b));
        }
        return out;
    }
}
