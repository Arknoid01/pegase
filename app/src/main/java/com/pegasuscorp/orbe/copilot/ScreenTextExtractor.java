package com.pegasuscorp.orbe.copilot;

import android.graphics.Bitmap;
import android.util.Log;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

/**
 * OCR local ML Kit — complément à l'arbre d'accessibilité (pas de compréhension).
 */
public final class ScreenTextExtractor {

    private static final String TAG = "ScreenTextExtractor";

    public interface Callback {
        void onSuccess(String plainText);
        void onError(String message);
    }

    private static volatile TextRecognizer recognizer;

    private ScreenTextExtractor() {}

    public static void recognize(Bitmap bitmap, Callback callback) {
        if (bitmap == null) {
            if (callback != null) callback.onError("Image vide.");
            return;
        }
        InputImage image = InputImage.fromBitmap(bitmap, 0);
        getRecognizer().process(image)
                .addOnSuccessListener(text -> {
                    String plain = flatten(text);
                    if (callback != null) callback.onSuccess(plain);
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

    private static String flatten(Text text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder();
        for (Text.TextBlock block : text.getTextBlocks()) {
            String line = block.getText();
            if (line == null || line.trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(line.trim());
        }
        return sb.toString().trim();
    }
}
