# Inspirations assistants Android FOSS

Notes de veille (août 2026) : idées générales réutilisables pour **Pégase**, issues de projets open source explorés localement.

Sources :

| Projet | Repo | Archive locale |
|--------|------|----------------|
| **Sanna** | [sannabotdev/sannabotapp](https://github.com/sannabotdev/sannabotapp) | `Downloads/sannabotapp-main` |
| **OpenDroid** | [GhostOfJero/opendroid](https://github.com/GhostOfJero/opendroid) | `Downloads/opendroid-main` |
| **Hark** | [OpenAppCapabilityProtocol/hark](https://github.com/OpenAppCapabilityProtocol/hark) | `Downloads/hark-main` |
| **SightSync** | [qsn0915/SightSync](https://github.com/qsn0915/SightSync) | `Downloads/SightSync-main` |
| **SwitchAI** | [WSTxda/SwitchAI](https://github.com/WSTxda/SwitchAI) | `Downloads/SwitchAI-main` (sélecteur d’apps IA, pas un cerveau) |

Autres à surveiller (non détaillés ici) : [Dicio](https://github.com/DicioTeam/dicio-android), [Jandal AI](https://github.com/NickMonrad/kernel-ai-assistant), [Open-Dash](https://github.com/yuga-hashimoto/open-dash).

---

## Positionnement vs Pégase

| | **Sanna** | **OpenDroid** | **Hark** | **SightSync** | **Pégase** |
|---|---|---|---|---|---|
| Idée centrale | Voice + a11y + sub-agents | Plan JSON + re-eval | Capacités déclarées (OACP) | Actions UI bornées / auditables | Launcher + assistant + copilote |
| Contrôle UI | Sous-agent a11y + hints | Scripts + a11y | Intents OACP (pas scraping) | Whitelist + validation | Copilote générique + `ui_action.steps` |
| Wake | Wake + STT | STT en boucle (faible) | openWakeWord | Voix continue | Sherpa / OpenWakeWord |
| Stack | React Native + Kotlin | Kotlin native | Flutter + Kotlin | Kotlin + proxy Node | Java native |

**Ne pas régresser chez Pégase :** wake KWS réel, copilote a11y générique, multi-provider, mémoire / RAG, local-first.

---

## Top idées transversales

### 1. Re-eval après chaque étape — OpenDroid

Plan → agir → regarder le résultat → `CONTINUE` / `MODIFY` / `ABANDON`.

C’est la **vraie boucle agent** (pas seulement UI). Utile pour chaînes multi-outils, notifs, et future boucle a11y live.

### 2. Anti-hallucination d’outils — OpenDroid

Whitelist dynamique + fuzzy map (`VERIFY_CONTACT` → action réelle) + validator avant exécution.

Applicable au `ToolRegistry` / `ui_action` (noms inventés, packages espacés, etc.).

### 3. Compound-intent guard — OpenDroid

Ne pas short-cut « ouvre WhatsApp » si la phrase contient aussi « envoie un message ».

Pégase a déjà le pattern `looksLikeUi` vs `open_app` — à **généraliser** (ouvre + tape, ouvre + SMS…).

### 4. Hints a11y par package — Sanna

Après un run UI : condenser succès/échecs en hints humains (sans viewId) → injecter au prochain run sur la même app.

**Priorité #1 en cours** côté Pégase.

### 5. Sub-agents (notifs / scheduler) — Sanna (+ esprit OpenDroid)

Notif ou horaire → sous-agent LLM indépendant avec accès outils, pas un simple toast / alarme.

**Priorité #2** envisagée.

### 6. Capacités déclaratives (OACP) — Hark

Les apps exposent un manifeste → l’assistant découvre. A11y = fallback pour le reste.

Idée **long terme** (écosystème encore petit). Court terme : même esprit pour *nos* outils (schéma + aliases + examples).

### 7. NLU en 2 temps — Hark

Classer l’intention / l’outil (léger) → LLM seulement pour remplir les slots.

Moins de tokens, moins d’inventions — proche IntentParser + LLM Pégase.

### 8. Overlay assistant léger — Hark

Panneau au-dessus de l’app courante (`VoiceInteractionService`), sans perdre le contexte.

Polish UX « assistant système », pas forcément Flutter.

### 9. Stabilité de page + re-match avant clic — SightSync

Deux fingerprints a11y identiques → OK ; au clic, revérifier package / bounds / libellé.

Proche de `waitTreeSettle` — à **pousser** (re-validation au moment du geste).

### 10. Plan UI avec attentes explicites — SightSync

Steps `ACTION` | `WAIT_FOR_UI` | `VALIDATE_PAGE` + timeout + max échecs.

Upgrade naturel de `ui_action.steps` / future boucle.

### 11. Redaction PII avant envoi LLM — SightSync

Masquer tel, email, OTP, password dans le contexte écran (copilote cloud + traces).

### 12. Confirmation au niveau du geste — SightSync

Confirmer le clic sensible *quand l’écran est réellement là* — aligné `A11yClickPolicy` (pas une seule confirm pour tout le plan).

### Bonus OpenDroid utiles

- Chaînage `$stepId` entre étapes (propager un résultat sans replanifier tout).
- Mode multi-agent optionnel (planner + critic + merge) pour commandes complexes.
- Séparation stricte : erreurs d’exécution ≠ mémoire sémantique (anti-poison).

---

## Ce que chaque projet fait mieux / moins bien

### Sanna

- **Mieux :** sous-agent a11y + hints + sub-agents notifs/scheduler + skills Markdown.
- **Moins :** stack RN ; dépend cloud OpenAI/Claude ; moins de launcher / identité.

### OpenDroid

- **Mieux :** discipline plan / re-eval, anti-hallu actions, overlay lié à l’état agent.
- **Moins :** wake faible ; mémoire sémantique souvent regex ; automations app hardcodées ; catalogue ~100 actions à maintenir.

### Hark

- **Mieux :** contrat app↔assistant (OACP), overlay système, NLU 2 étapes, wake openWakeWord.
- **Moins :** couverture apps OACP minuscule ; STT encore cloud Android ; agentique / mémoire absents ; Flutter dual-engine lourd à porter.

### SightSync

- **Mieux :** protocole auditable, stabilité page, redaction, plans bornés, risque contextuel.
- **Moins :** prototype ; nodeId index fragile ; proxy cloud ; AGPL ; listes de mots-clés risque statiques.

### SwitchAI (hors scope « cerveau »)

Lanceur d’apps assistants (package + activity hardcodés). Utile seulement pour un raccourci « ouvre Gemini / Claude », pas pour le routing LLM.

---

## Tranche FOSS livrée (discipline copilote)

Sans toucher wake KWS / SCO / multi-provider / mémoire-RAG :

- `CopilotHintsLearner` — proposition hints après `ui_loop`, persistée seulement si confirm
- `ScreenPiiRedactor` — email / tel / OTP / carte masqués dans le **prompt** écran
- Rematch bounds+libellé avant clic (`A11yUiExecutor.targetStillMatches`)
- Compound « ouvre + envoie/SMS/appelle » via `looksLikeUi`
- Suggestions fuzzy si outil inconnu (`ToolRegistry.suggestSimilarIds`)
- Reflection planner charge `CopilotAppHintsStore.get()` (overrides inclus)

---

## Backlog Pégase suggéré

Ordre sensé d’inspiration (pas un engagement de sprint) :

| # | Ticket | Source | Statut |
|---|--------|--------|--------|
| 1 | **Hints a11y par package** (seeds + voix + **learner post-run opt-in**) | Sanna | Fait (tranche FOSS) |
| 2 | **Stabilité + re-match + redaction écran** | SightSync | Fait (tranche FOSS) |
| 3 | **Sub-agents notifs** (+ re-eval post-étape) | Sanna + OpenDroid | Reporté (phase suivante) |
| 4 | **Anti-hallu tools + compound-intent guard** | OpenDroid | Fait (tranche FOSS) |
| 5 | **Boucle a11y live** (goal → action → refresh → finish) | Sanna / OpenDroid | Déjà là (`ui_loop`) |
| 6 | **Overlay / VoiceInteraction** | Hark | Polish UX |
| 7 | **OACP / capacités déclaratives** | Hark | Long terme |

### Détail ticket 5 — vraie boucle a11y

Aujourd’hui : `ui_action.steps` = script figé.

Boucle : goal NL → arbre frais → 1 geste → settle → replan → `finish_task`.

Gère l’imprévu (banner, libellé faux) ; coûte plus de tokens / latence. Les hints préparent le terrain sans être la boucle complète.

---

## Fichiers intéressants (référence rapide)

### OpenDroid

- `core/agent/AgentLoop.kt`, `ReEvaluationEngine.kt`, `PlanValidator.kt`
- `actions/ActionAutoMapper.kt`
- `core/memory/MemoryManager.kt`
- `accessibility/OpenDroidAccessibilityService.kt`

### Hark

- `HarkPlatformPlugin.kt` (discovery OACP)
- `nlu_command_resolver.dart`, `capability_registry.dart`
- `OverlayActivity.kt`, `HarkVoiceInteractionService.kt`
- `WakeWordService.kt` / `WakeWordDetector.kt`

### SightSync

- `AgentPlanValidator.kt`, `AgentPlanExecutor.kt`
- `PageStabilityWaiter.kt`, `ActionExecutor.kt`, `RiskClassifier.kt`
- `SensitiveTextRedactor.kt`
- `backend/src/protocol.js`, `screen-summary.js`

### Sanna

- `src/agent/accessibility-sub-agent.ts`, `accessibility-hint-store.ts`
- `src/agent/notification-sub-agent.ts`, `tool-loop.ts`
- `src/tools/accessibility-tool.ts`

---

## Synthèse en une phrase

Pégase a déjà le **socle local + copilote générique** ; les projets FOSS apportent surtout la **discipline agent** (re-eval, anti-hallu), l’**apprentissage UI** (hints), les **sous-agents événementiels**, et la **formalisation sécurité** (stabilité, redaction, plans bornés) — pas un remplacement de stack.
