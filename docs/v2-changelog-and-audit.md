# Pégase v2 — Ce qui a été ajouté + audit

**Repo :** [Arknoid01/pegase](https://github.com/Arknoid01/pegase)  
**Période :** 30 juillet 2026  
**Base :** commit initial → `main` (`1d454a5`, juill. 2026)  
**App :** `com.pegasuscorp.orbe`

Ce document résume **ce qui est entré dans `main`**, puis un **audit d’opinion** (forces, risques, priorités).

---

## 1. Vue d’ensemble

Cinq axes v2 ont été implémentés en branches parallèles, puis mergés dans cet ordre :

| # | Axe | Contenu court |
|---|-----|----------------|
| 0 | Correctifs diag | Callbacks LLM stale, poison `HISTORY_SAFE`, troncature historique |
| 1 | Personnalité | Guide unique `pegase-personality.md` + `PersonalityGuide` |
| 2 | Présence vocale | Budgets agentiques, TTS partiel, filtres de phrases |
| 3 | Mémoire | Scoring, graphe d’entités multi-hop, consolidation, UI 3D |
| 4 | Actions utilitaires | Alarme, minuteur, agenda (voix + outils) |
| 5 | Mode copilote | Orbe overlay, a11y, traduction, notifs, share, OCR |

Volume approximatif côté app Java / docs : **~9k lignes ajoutées** sur le diff pertinent (hors `llama.cpp`).

---

## 2. Détail par axe

### 2.1 Correctifs diagnostic / historique

- File d’attente LLM sérialisée ; callbacks stale ne réécrivent plus une réponse diag locale.
- Nettoyage du poison `HISTORY_SAFE` dans la mémoire de conversation.
- Troncature d’historique corrigée (trace + stockage LLM).
- Métriques reasoning card / dédup `routing_match`.

**Pourquoi c’est important :** sans ça, le chat “menteait” (mauvaise source / mauvais provider affiché) et l’historique pouvait contaminer le prompt.

---

### 2.2 Personnalité centralisée

| Élément | Rôle |
|---------|------|
| `assets/contexts/pegase-personality.md` | Source de vérité du ton Pégase |
| `PersonalityGuide.java` | Chargement / injection dans les prompts |
| `PegasePrompt`, Bureau, Orion | Consomment le guide ; règles opérationnelles séparées du ton |

- Dédup : le ton n’est plus recopié dans plusieurs builders (`buildSpeechRules` allégé).
- Objectif produit : une voix cohérente chat / bureau / Orion / oral.

---

### 2.3 Présence vocale

- Budgets agentiques selon le mode (ex. conduite = plus court, latence prioritaire).
- TTS **partiel** pendant un tour agentic (stream oral plus tôt).
- Filtre de phrases bannies côté TTS (anti “assistant générique”).
- Ajustements `VoiceInputHandler` / `ChatSendOptions` / `ResponseDelivery`.

---

### 2.4 Mémoire v2

| Élément | Rôle |
|---------|------|
| `MemoryScorer` | Score composite (mots-clés + sémantique + entités + récence) |
| `EntityGraphStore` / `EntityEdge` | Graphe d’entités, arêtes pondérées / typées |
| Multi-hop | Expansion 1–2 sauts à la récupération |
| `MemoryConsolidator` / `LocalSessionExtractor` | Consolidation de session, décisions / pending |
| `MemoryVitality` | Renforcement à l’usage + oubli naturel |
| `MemoryGraph3DView` | Preview 3D dans les réglages mémoire |

UI : `MemorySettingsActivity` enrichie (graphe / liens).

---

### 2.5 Actions utilitaires

| Outil / module | Capacité |
|----------------|----------|
| `AlarmTool` | Alarmes plus complètes |
| `TimerTool` | Minuteurs |
| `AgendaTool` / `CalendarQuery` / `CalendarWriter` | Lecture / écriture agenda |
| `DurationParser` | Parsing durées en langage naturel |
| `UtilityScheduleStore` | Persistance des plannings utilitaires |
| Handlers voix | `SystemIntentHandler`, `LifeIntentHandler` branchés |

---

### 2.6 Mode copilote (Phase 5 + polish)

Architecture : process **`:copilot`** (comme `:voice` pour le KWS), listes blanches strictes, local d’abord.

| Domaine | Fichiers / comportement |
|---------|-------------------------|
| Overlay | `FloatingOrbService` dual-mode VOICE/COPILOT, orbe 56 dp, drag, long-press |
| Bulle | `CopilotBubblePanel` — chat, streaming, confirmations Oui/Non |
| Contrôle | `CopilotController` → `PegaseSession` canal `COPILOT` |
| Analyse | `CopilotService` + `CopilotAnalysisEngine` (debounce, SCREEN_OFF stop) |
| A11y | `PegaseAccessibilityService`, YouTube CC local (`YouTubeSubtitleAction`) |
| Traduction | `TranslationOverlayService` + `CopilotTranslator` (cloud = texte seul) |
| Notifs | Filtre whitelist + phrase type « X t’a écrit : … » |
| Surlignage | `ElementHighlightService` |
| OCR | `OcrFallback` / ML Kit si arbre a11y vide |
| Share | `ShareIngestActivity` / `ShareIngestRouter` |
| Réglages | `CopilotSettingsActivity` + picker d’apps |
| Strings | `strings_copilot.xml` |

Docs dédiées : `docs/copilot-done.md`, `docs/copilot-phase5-session.md`, `docs/copilot-polish-checklist.md`.

---

### 2.7 Correctifs post-merge (device)

Commit `3dec64a` — nécessaire pour que le stack compile après fusion des branches parallèles :

- Import AIDL `ICopilotCallback`
- Méthode stream dupliquée dans `MultiProviderBackend`
- Ambiguïtés / types (`MemoryScorer`, `MemoryGraph3DView`, `PegasePrompt`, etc.)
- `SessionObserver.onToolResult` manquant côté copilote
- Script `scripts/device-smoke-tests.ps1` + `docs/device-test-report-final.txt`

---

## 3. État device (smoke du 30/07)

Sur Nothing Phone (1) après install debug :

| Check | Résultat |
|-------|----------|
| Launch Main / Interface | OK |
| Processus `:voice` + `:copilot` | OK |
| `VoiceService` / `CopilotService` / `FloatingOrbService` | OK |
| Overlay permission | allow |
| A11y Pégase | activée |
| Listener notifs | déjà ON |
| Share ingest | OK |
| Écran Mode copilote | OK (orbe always-on ON ; dialogue MediaProjection) |
| YouTube CC / Chrome trad / WhatsApp phrase | **pas encore validés end-to-end** |
| Tests unitaires Robolectric copilote | **cassés** (dépendance `androidx.test` manquante) |

---

## 4. Audit — ce que j’en pense

### Verdict

**Bonne direction produit, merge réussi, maturité inégale.**  
Les 5 axes collent au plan de route (« approfondir plutôt qu’empiler »). Le copilote est le chantier le plus ambitieux et le mieux documenté ; la personnalité et les correctifs diag sont les plus “sains” à court terme. La mémoire et les utilitaires sont solides en structure, mais leur valeur réelle dépend encore de l’usage au quotidien (scoring, graphe, alarmes/agenda).

### Forces

1. **Cohérence stratégique** — personnalité → voix → mémoire → utilitaires → copilote : l’ordre de merge et le plan de route sont alignés.
2. **Isolation processus** — `:voice` + `:copilot` : bon réflexe Android (crash / kill partiel plutôt que tout l’app).
3. **Copilote prudent par design** — whitelist vide par défaut, SCREEN_OFF stop, cloud texte-only : beaucoup mieux qu’un “screen spy” naïf.
4. **Personnalité centralisée** — un seul markdown pour le ton évite la dérive multi-prompt ; c’est le bon levier pour casser le style “assistant générique”.
5. **Doc de session** — rare et utile (`copilot-done`, phase5, checklists) ; facilite le test device et le handoff.

### Faiblesses / risques

1. **Merge de branches parallèles** — plusieurs conflits logiques (pas seulement Git) : méthodes dupliquées, signatures divergentes, AIDL incomplet. Le commit de fix était prévisible ; d’autres arêtes cassées peuvent rester sous les tests.
2. **Surface copilote très large** — overlay + a11y + MediaProjection + OCR + traduction + notifs + IPC AIDL. Beaucoup de permissions sensibles ; la confiance utilisateur dépendra du polish des réglages et des échecs silencieux.
3. **Mémoire “futuriste” vs ROI** — graphe 3D et multi-hop sont impressionnants, mais le gain oral quotidien vient surtout du scoring + consolidation. Risque de complexité sans boucle de feedback claire (métriques : “souvenir utile / souvenir faux”).
4. **Tests** — unit tests copilote ne compilent pas (`androidx.test.core` absent) ; peu de couverture instrumentée device. La validation repose trop sur smoke manuel.
5. **Activités non exportées** — bien pour la sécu, pénible pour l’automatisation de test ; à compenser par des tests UI ou un mode debug.
6. **Batterie / vie privée** — a11y + FGS `:copilot` + overlay : à mesurer sur une journée réelle (comme on l’a fait pour le micro).

### Ce qui est “prêt” vs “prototype avancé”

| Zone | Lecture |
|------|---------|
| Diag / historique | Prêt — à garder comme base stable |
| Personnalité | Prêt à itérer sur le markdown (few-shots) |
| Voix budgets / TTS partiel | Prometteur — à valider en conduite / conversation longue |
| Utilitaires alarme/timer/agenda | Utile si les intents voix sont fiables — tester 10 phrases réelles |
| Mémoire scoring / graphe | Architecture bonne — produit encore “lab” sans télémétrie d’utilité |
| Copilote | Feature-complete — smoke device PASS ; checks visuels YouTube/Chrome/WhatsApp manuels |

### Priorités recommandées (ordre)

1. **Checks visuels copilote** (YouTube CC, Chrome trad, WhatsApp notif) — seul reste non automatisé.
2. **CI / `testDebugUnitTest`** sur machine avec SDK (dépendances Robolectric déjà dans `build.gradle`).
3. **Mesurer batterie 24 h** avec copilote always-on + a11y ON vs OFF.
4. **Boucle mémoire** : 1 écran simple “pourquoi ce souvenir a été rappelé” (debug) avant d’investir plus dans le 3D.
5. **Pousser les utilitaires** via 20 utterances FR figées (régression manuelle ou script).
6. Remettre le **P0 brief / quiet hours / exact alarm** (hors v2 merge, encore ouvert historiquement).

### Opinion nette

Le dépôt a franchi un cap : ce n’est plus “un assistant qui parle”, c’est une **plateforme** (voix + mémoire + overlay). C’est le bon moment pour **ralentir les features** et **durcir** : tests verts, checklist copilote, batterie, et 2–3 phrases de personnalité few-shot dans `pegase-personality.md`.  
Si un seul axe doit porter la perception “v2”, c’est le **copilote discret + whitelist** — à condition que YouTube / Chrome / share marchent sans friction la première fois.

---

## 5. Liens utiles

- Plan / session : `docs/session-recap.md`
- Copilote terminé : `docs/copilot-done.md`
- Détail Phase 5 : `docs/copilot-phase5-session.md`
- Smoke device : `docs/device-test-report-final.txt`
- Script : `scripts/device-smoke-tests.ps1`
