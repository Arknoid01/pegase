# Pégase — function calling et fonctionnalités : comparaison avec Eta

**Date :** 2026-09-20 · **Référence :** [Mangi-11/Eta](https://github.com/Mangi-11/Eta) (snapshot 2026-09-12)

**Périmètre.** Ce qui compte : le function calling et ce que Pégase sait faire. **Écartés :** terminal embarqué (proot/PTY), agent de code, Kimi, hooks Xposed ColorOS/HyperOS, personnages. Licence d'Eta = PolyForm Noncommercial : on s'inspire des idées, on ne copie pas de code.

## 1. Deux philosophies d'outils

| | Eta | Pégase |
|---|---|---|
| Style | **Primitives génériques** : `observe_screen`, `find_elements`, `tap`, `swipe`, `scroll`, `input_text`, `wait_for_text`, `press_key`… Le modèle compose tout | **Outils métier** : ~55 outils (`spotify`, `weather`, `agenda`, `sms`, `brief`, `f1_companion`, `nasa`…) + copilote UI (`ui_action`, `ui_loop`, `ui_search`, `ui_explain`) |
| Force | Couvre l'inconnu (n'importe quelle app) | Fiable, rapide, peu de tokens, marche vite en vocal |
| Faiblesse | Lent, coûteux, dépend d'un gros modèle | Chaque nouveau besoin = un nouvel outil à écrire |

Le mélange est déjà le bon choix pour Pégase : métier pour le quotidien, copilote UI générique en filet de sécurité.

## 2. Le moteur de function calling

| Aspect | Eta (`AgentLoop`) | Pégase (`PegaseSession`, `ToolOrchestrator`, `AgenticTurnPolicy`) | Verdict |
|---|---|---|---|
| Validation des arguments par JSON Schema | Oui (`$ref`, `oneOf`…) | Oui, `ToolCallValidator` (commit 59829e6), schéma explicite ou déduit de `description()` | Équivalent |
| Refus des appels tronqués (`finish_reason=length`) | Oui | Oui (même commit) | Équivalent |
| Refus d'appels dans un `stop` | Oui (contradiction de protocole) | À vérifier | À vérifier |
| Nombre d'outils par tour | **Illimité** ; le modèle arrête quand il veut | **`MAX_TOOLS_PER_TURN = 2`**, plus arrêt après une recherche, calculatrice, brief, wiki, etc. | **Plus gros écart.** Interdit tout enchaînement (« cherche, compare, ajoute à l'agenda et envoie un SMS ») |
| Anti-doublon | Non explicite | Oui (empreinte nom+args) | Orbe en avance, à garder |
| Outils en lot par réponse | Lot séquentiel, résultats groupés | Chaîne un par un (`AgenticChain`) | À revoir avec l'écart précédent |
| Compaction du contexte | `AgentContextCompactor` : résume l'historique aux frontières d'échanges complets, budget de tokens | Sélection/assainissement de l'historique (`ConversationHistorySelector`), mémoire Atlas | Pas de compaction en cours de tour |
| Steering / pause / annulation | File de consignes, pause, cancel distincts | Annulation seulement | Manque (utile pour le vocal : « non attends ») |
| Confirmation d'actions sensibles | Interrupteurs par famille d'outils, relus à chaque exécution | `ChatConfirmBridge` / `PendingToolConfirm` | Équivalent |
| Données sensibles | Notifications, codes SMS, Wi‑Fi retirés du transcript persistant | `EphemeralMemoryFilter`, `ScreenPiiRedactor` | Équivalent, à étendre aux résultats d'outils |
| Prompt d'outils | Catalogues par famille, n'expose que ce qui est activé | Filtrage par `ToolTag` | Équivalent |
| Requis d'outil (permission manquante) | `AgentToolRequirements` : outil visible mais marqué « non disponible » avec la raison | À vérifier | Piste UX |

**Conclusion :** le socle est sain. Le vrai frein est la limite à 2 outils par tour, pas la qualité de validation.

## 3. Fonctionnalités : ce qu'Eta a et pas Pégase

| Capacité | Eta | Pégase | Intérêt |
|---|---|---|---|
| **Skills** (`SKILL.md` à la demande, installation GitHub/ZIP) | Oui (6 outils `skills_*`) | Non | **Élevé.** Ajouter des comportements sans recompiler |
| **MCP** (outils distants, Bearer) | Oui | Non | **Élevé.** Brancher n'importe quel service |
| **Navigateur piloté** (`browser_use`, WebView, lecture DOM) | Oui | Recherche web/Tavily, pas de navigation | Moyen |
| **Lecture d'image** (`read_image`) | Oui | `screen_capture` + vision cloud du copilote | Moyen : outil général « regarde cette photo » |
| **Santé** (rythme cardiaque, poids, exercices) | Oui, Root/Health Connect | Non | Faible/moyen |
| **Usage des applis, historique de notifications** | Oui | Notifications (listener) | Moyen : « qu'est-ce que j'ai fait sur mon tel ? » |
| **Réglages système** (`get/set_setting`, panneaux, Wi‑Fi, volume par flux) | Oui, fin | `settings`, `volume`, `connectivity` | Comparable |
| **Contrôle d'appli** (force stop, gel, info) | Root | Non | Faible (Root) |
| **Attentes conditionnelles** (`wait_for_text`, `wait_for_package`) | Oui | Dans `ui_loop` | À vérifier |
| **Presse-papiers, coller, touches** | Oui | `clipboard` | Comparable |
| **Historique de conversation comme outil** | `conversation_history` | Mémoire/Atlas, pas d'outil de recherche de l'historique | Moyen |
| **Cartes/commandes personnelles** (colis, lieux enregistrés) | Système ColorOS | Non | Hors sujet (ROM) |

## 4. Ce que Pégase a et pas Eta (à protéger)

- Mémoire riche : Atlas/graphe d'entités, RAG, consolidation, sync Drive téléphone↔PC (`memory`, `notepad`, `context`).
- Voix complète : wake word, STT, Piper, mode conduite, écran verrouillé.
- Vie quotidienne : `agenda`, `calendar`, `timer`, `alarm`, `routines`, `life_pattern`, `project_object`, `brief`.
- Connaissance : `weather`, `news`, `wikipedia`, `wikidata`, `tavily`, `sports`, `f1_companion`, `nasa`.
- Média : `spotify`, `youtube`, `create_file`.
- Communication : `sms`, `call`, `email`, `contacts`, `share`.
- Copilote d'écran avec liste blanche, OCR, traduction en surimpression, surlignage.
- Terminal PC via SSH (`pcshell`) — déjà là, on n'y touche pas.
- LLM local (llama.cpp) en secours.

## 5. Roadmap proposée (function calling + fonctionnalités)

| # | Chantier | Détail | Pourquoi |
|---|---|---|---|
| 0 | **Consolider le working tree** | Commiter thématiquement les ~74 fichiers en cours | Base propre |
| 1 | **Lever la limite d'outils** | Remplacer `MAX_TOOLS_PER_TURN=2` par un plafond configurable (≈8) avec garde-fous : anti-doublon conservé, budget de tokens, arrêt si le modèle répète. Règles « une recherche suffit » conservées comme *heuristique par outil*, pas comme blocage global | Débloque les enchaînements |
| 2 | **Lots d'appels dans une réponse** | Exécuter tous les `tool_calls` d'un même message, résultats renvoyés ensemble | Moins d'allers-retours LLM, moins de latence |
| 3 | **Steering et annulation vocale** | File de consignes injectée au tour suivant ; « stop » vocal annule | Naturel à l'oral |
| 4 | **Compaction en cours de tour** | Résumer les échanges d'outils anciens quand le budget approche | Longs enchaînements |
| 5 | **Outils « non disponibles »** | Exposer l'outil avec la raison (permission absente, service coupé) plutôt que le masquer ; ne pas laisser le LLM réessayer | Moins d'appels inutiles, meilleures réponses |
| 6 | **Skills** | Dossier `skills/*/SKILL.md`, index dans le prompt, outils `skills_list/read`, import ZIP/GitHub | Extensibilité sans recompiler |
| 7 | **MCP (client)** | Streamable HTTP, activation outil par outil, passage dans `ToolRegistry` + `ToolCallValidator` | Écosystème externe |
| 8 | **Nouveaux outils métier** | `read_image` (photo → description), `conversation_history`, `app_usage`, historique des notifications, navigateur léger | Comble les vrais manques |
| 9 | **Tests d'outils** | Jeu de scénarios « prompt → suite d'appels attendue » exécutable sans téléphone | Éviter les régressions du n°1 |
| 10 | **Hygiène des logs** | Aucun argument/résultat d'outil ni prompt dans les logs release | Confidentialité |

Ordre recommandé : **0 → 1 → 2 → 9**, puis 5, 3, 4, puis 6 et 7.

## 6. Points à vérifier avant de coder

- Où est réellement appliquée la limite de 2 outils : `AgenticTurnPolicy` seul, ou aussi `PegaseSession`/`AgenticChain` (2116 lignes) ?
- Les modèles locaux (llama.cpp) supportent-ils les lots d'appels ? La limite est peut-être là pour eux : envisager une limite par backend.
- Latence vocale : plus d'outils par tour = plus long avant la réponse parlée ; prévoir un « j'y travaille » via TTS.
