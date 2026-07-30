# Orion — Contexte Pégase

## Vision
Copilote de code vocal piloté par Pégase.

## Statut
Priorité : 🟡 Moyenne
État : 🟢 Boucle Bureau → generate → review → commit en place
Cible : stream unifié + voix codegen (suite)

## Stack technique
- Android Java — com.pegasuscorp.orbe
- PegaseSession + FC natif + boucle agentique
- RunPod Serverless — qwen3-coder via Ollama

## Fichiers à ne pas toucher
- VoiceIntentRouter.java
- BureauCanvasView.java

## Décisions prises
- Token Bearer pour Ollama auth
- maxWorkers RunPod = 1
- Bureau → Orion : prompt enfilé, auto-submit dès READY (sinon propose launch)
- Session fichiers : Valider tout → Commit GitHub
- TavilyTool pour la recherche web, exclude_domains configuré
  (Facebook, Twitter/X, TikTok, Reddit, Instagram)

## Plan d'action
- [x] PegaseSession complète
- [x] FC natif + boucle agentique
- [x] OrionManagerTool (start/stop/status)
- [x] OrionCodeTool + streaming (onglet)
- [x] Session fichiers + git_commit
- [x] Pont Bureau → Orion (auto-génération)
- [ ] Stream orion_code visible depuis chat Pégase
- [ ] Voix codegen (au-delà de start/stop pod)
