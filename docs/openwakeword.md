# openWakeWord — « Hey Pégase »

Pipeline retenu : **enregistrer sur le téléphone** → entraîner sur PC/Colab → **détecter dans `:voice`**.

## Pourquoi le micro du téléphone

Le wake tourne sur le Nothing Phone (source `MIC`, 16 kHz mono). Les clips d’entraînement doivent venir de la **même chaîne audio**. Le script PC (`record_wake_word.py`) reste utile pour le format WAV, mais le micro laptop ≠ téléphone.

## Dans Pégase

Tiroir → Personnalisation → **Wake openWakeWord** → **Enregistrer mon wake word**

1. Lance une session (~40 clips × 1,8 s). Dis clairement **« Hey Pégase »**.
2. Varie un peu le ton, le rythme, la distance.
3. **Partager les échantillons (zip)** ou tire via adb :

```bash
adb shell "run-as com.pegasuscorp.orbe tar -c files/wake_oww/samples" > hey_pegase_samples.tar
# ou après partage du zip depuis l’app
```

4. Sur le téléphone : **Télécharger backbone** (`melspectrogram.onnx` + `embedding_model.onnx`).
5. Après entraînement PC : **Importer hey_pegase.onnx**.
6. Active l’écoute « Pégase ». `VoiceService` choisit **openWakeWord en priorité**, Sherpa en filet.

Fichiers sous `files/wake_oww/` :

| Fichier | Rôle |
|---------|------|
| `samples/hey_pegase_XXX.wav` | Clips 16 kHz mono 16-bit |
| `melspectrogram.onnx` | Backbone partagé |
| `embedding_model.onnx` | Backbone partagé |
| `hey_pegase.onnx` | Classifieur custom |

## Entraînement PC

Références :

- [openWakeWord](https://github.com/dscripka/openWakeWord)
- Colab / recettes type [openwakeword-colab-2026](https://github.com/alfiedennen/openwakeword-colab-2026)

Place les WAV exportés dans le dossier positif du notebook (ou pointe `OUTPUT_DIR` du script PC vers ce dossier — pas besoin de réenregistrer sur le laptop).

Exporte un classifieur ONNX nommé `hey_pegase.onnx`, puis importe-le dans l’app.

## Détection

`OpenWakeWordEngine` : chunks 80 ms → mel → embedding → score. Seuil défaut `0.78` (réglable dans Personnalisation). Capture via `KwsAudioRouteManager` (même chemin que Sherpa / PR #20 : prepare/release sous lock, preferred device, restart sur changement de route). Anti-poche + debounce.
