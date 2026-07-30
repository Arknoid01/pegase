package com.pegasuscorp.orbe.tools.device;

import com.pegasuscorp.orbe.tools.ToolTag;

import com.pegasuscorp.orbe.tools.ToolResult;

import com.pegasuscorp.orbe.tools.Tool;
import com.pegasuscorp.orbe.tools.ToolCallback;

import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraManager;
import android.os.Build;

import org.json.JSONObject;

/** Lampe torche du téléphone. */
public final class FlashlightTool implements Tool {

    private static volatile boolean torchOn;

    @Override
    public String id() {
        return "flashlight";
    }

    @Override
    public ToolTag tag() {
        return ToolTag.FLASHLIGHT;
    }

    @Override
    public String description() {
        return "flashlight(action:\"on\"|\"off\"|\"toggle\") — Allume ou éteint la lampe torche.";
    }

    @Override
    public void execute(Context ctx, JSONObject params, ToolCallback cb) {
        String action = params.optString("action", "toggle").toLowerCase();
        boolean target;
        switch (action) {
            case "on":
            case "allume":
                target = true;
                break;
            case "off":
            case "eteint":
            case "éteint":
                target = false;
                break;
            default:
                target = !torchOn;
        }
        try {
            setTorch(ctx, target);
            torchOn = target;
            cb.onSuccess(ToolResult.text(target ? "Lampe torche allumée." : "Lampe torche éteinte."));
        } catch (Exception e) {
            cb.onError("Lampe torche : "
                    + (e.getMessage() != null ? e.getMessage() : "indisponible sur cet appareil."));
        }
    }

    private static void setTorch(Context ctx, boolean on) throws CameraAccessException {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            throw new CameraAccessException(CameraAccessException.CAMERA_ERROR,
                    "Nécessite Android 6 ou plus.");
        }
        CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        if (cm == null) {
            throw new CameraAccessException(CameraAccessException.CAMERA_ERROR, "Pas de caméra.");
        }
        String cameraId = findFlashCamera(cm);
        if (cameraId == null) {
            throw new CameraAccessException(CameraAccessException.CAMERA_ERROR,
                    "Pas de flash sur cet appareil.");
        }
        cm.setTorchMode(cameraId, on);
    }

    private static String findFlashCamera(CameraManager cm) throws CameraAccessException {
        for (String id : cm.getCameraIdList()) {
            Boolean flash = cm.getCameraCharacteristics(id)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE);
            if (flash != null && flash) return id;
        }
        return null;
    }
}
