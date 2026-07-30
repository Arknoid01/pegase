# RAG local — assets Phase 1 + store Phase 2

| Fichier | Source |
|---------|--------|
| `all-MiniLM-L6-v2.onnx` | sentence-transformers · `onnx/model_qint8_avx512_vnni.onnx` (~23 Mo) |
| `vocab.txt` | sentence-transformers/all-MiniLM-L6-v2 |

**Phase 2** : `VectorStore` → `files/memory/vectors.db` (BLOB float32 + cosine Java).
Pas de sqlite-vss pour l’instant (intégration Android fragile) ; suffisant jusqu’à des milliers de souvenirs.

Re-télécharger le modèle :
```powershell
Invoke-WebRequest -Uri "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/onnx/model_qint8_avx512_vnni.onnx" -OutFile all-MiniLM-L6-v2.onnx
Invoke-WebRequest -Uri "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/vocab.txt" -OutFile vocab.txt
```

ORT Android : réutilise `com.xdcobra.sherpa:onnxruntime` (déjà dans l'app pour Piper).
