# Audit comparé Eta / Orbe-Pégase

**Date :** 2026-09-20 · **Eta :** Mangi-11/Eta, snapshot du 2026-09-12 · **Orbe :** branche `feat/eta-tool-calling` (HEAD `3f4c7fb`)
**Méthode :** mesures sur les deux arbres de sources, lecture ciblée du code (boucle agent, retry, politique de données sensibles, manifests, build, CI), et exécution d'un test jetable pour mesurer les schémas d'outils d'Orbe. Tout ce qui est marqué **[vérifié]** a été constaté dans le code ; **[à confirmer]** ne l'a pas été. Aucun des deux projets n'a été exécuté sur un appareil.

Périmètre : function calling, sécurité/confidentialité, robustesse, qualité du code. Hors périmètre, comme convenu : terminal embarqué, agent de code/Kimi, hooks Xposed spécifiques aux ROM chinoises.

---

## 1. Synthèse

| | Eta | Orbe |
|---|---|---|
| **Force principale** | Runtime d'agent rigoureux (transactions, reprise, retry, checkpoints), hygiène sécurité/build | Produit beaucoup plus large : voix, mémoire, launcher, copilote, sync PC, LLM local |
| **Faiblesse principale** | Étroit (pas de voix, pas de LLM local), très lié aux ROM chinoises, CI sans tests | Sécurité de l'appli à durcir (sauvegarde, composants exportés, clés), classe centrale trop grosse, budget de tokens serré |
| **Verdict** | Meilleur *moteur* d'agent | Meilleur *assistant personnel* ; moteur désormais proche après les derniers chantiers |

**Les 5 actions les plus rentables pour Orbe** (détail en §6) :
1. Fermer la fuite de sauvegarde (`allowBackup="true"` + règles vides + clés API en clair).
2. Restreindre `DiagSuiteReceiver` (exporté sans permission).
3. Réduire le poids des schémas d'outils (le set « quotidien » consomme plus de la moitié du budget Groq).
4. Généraliser la politique « données sensibles » (résultats d'outils personnels hors trace/persistance).
5. Découper `PegaseSession` (2 279 lignes).

---

## 2. Mesures

| Mesure | Eta | Orbe |
|---|---|---|
| Langage | Kotlin (Compose) | Java (vues programmatiques) |
| Fichiers source (main) | 571 | 679 |
| Lignes (main, hors natif) | ≈ 109 900 | ≈ 138 900 |
| Fichiers de test | 182 (≈ 25 600 lignes) | 237 (≈ 21 600 lignes) |
| Ratio test / code | ≈ 23 % | ≈ 15,6 % |
| Tests exécutés | non mesuré | 1 210, tous verts |
| Plus gros fichiers | `AgentAppState` 2 895 · `ChatMessageItem` 2 838 · `BreenoHooks` 2 384 · `AgentAccessibilityService` 2 378 | `PegaseSession` 2 279 · `OrionStreamView` 1 785 · `BureauMarkdownPanel` 1 547 · `PersonalizationPanel` 1 534 · `VoiceInputHandler` 1 504 |
| Marqueurs TODO/FIXME | 0 | 5 |
| Exceptions avalées (`catch (Exception ignored)`) | non mesuré (Kotlin) | ≈ 441 |
| minSdk / targetSdk | 34 / 36 (compile 37) | 24 / 34 (compile 34) |
| Release minifié (R8) | **oui** | **non** (`minifyEnabled false`) |
| `allowBackup` | `false` | **`true`** |
| Permissions déclarées | 18 | 33 |
| Composants `exported="true"` | 8 | 9 |
| Processus séparés | `:voice`, `:voice_session`, `:recognition` | un seul (+ `:copilot`) |
| CI | build Debug+Release signé, **sans exécuter de tests** | `testDebugUnitTest` + `assembleDebug` |

Lecture : les deux bases sont de taille comparable. Orbe a plus de tests en nombre de fichiers et une CI qui les exécute ; Eta a un meilleur ratio de test par ligne de code, concentré sur son cœur agent (`agent/model` : 25 fichiers, `agent/runtime` : 17).

---

## 3. Moteur d'agent et function calling

| Sujet | Eta | Orbe (état actuel) | Écart |
|---|---|---|---|
| Machine à états d'un run | `RUNNING → COMMITTING → TERMINAL`, un seul gagnant pour le résultat final [vérifié, doc] | Générations (`agenticOperationGeneration`) qui invalident les callbacks tardifs [vérifié] | Orbe fonctionne, moins formel |
| Retry modèle | Transactionnel : jusqu'à 3 retries, backoff 2 s / 4 s / 8 s, l'historique n'est validé qu'après réponse complète, arrêt si un outil hébergé a démarré [vérifié, `AgentModelRetry.kt`] | Rotation sur **toute** erreur (429, timeout, 5xx, réseau) vers le modèle puis le fournisseur suivant, avec suivi de santé ; cas ciblés : 429 avec `Retry-After`, 413 avec réduction de prompt, ré-essai avec outils élargis [vérifié, `MultiProviderBackend`] | Approche différente et plus robuste tant que plusieurs fournisseurs sont configurés. Seul manque : si toute la chaîne échoue d'un coup (coupure réseau), aucune nouvelle tentative après attente |
| Validation des arguments | JSON Schema complet, `$ref`, `oneOf`… (348 lignes) | `ToolCallValidator` (227 lignes), schémas explicites ou déduits | Comparable ; Orbe déduit encore certains schémas de la ligne de description |
| Appels tronqués | Refusés, erreur structurée renvoyée | Idem depuis `59829e6` | Équivalent |
| Lots d'outils | Séquentiels, résultats groupés | Séquentiels sans aller-retour LLM (`ce60ed3`) | Équivalent |
| Limite d'outils par tour | Aucune | 8 (+ 3 par outil, usage unique pour certains) | Choix assumé, à ajuster par usage |
| Erreur d'outil | Renvoyée au modèle | Renvoyée au modèle (`34a5f70`) | Équivalent |
| Steering / pause / annulation | Trois mécanismes distincts | Annulation + steering (`8c6aa51`), **pas de pause** | Pause absente (peu utile en vocal) |
| Compaction du contexte | Résumé par le modèle aux frontières d'échanges complets, validé avant commit | Abrègement déterministe des anciens résultats (`287bd69`) | Eta plus fin, Orbe moins coûteux |
| Reprise après mort du processus | Store de checkpoints + archives + boîte aux lettres de résultats [vérifié, `AgentRunCheckpointStore`, `AgentRunArchiveStore`] | Aucune : un tour en cours est perdu si l'appli est tuée **[à confirmer]** | Écart réel, impact faible tant que les tours sont courts |
| Isolation | Runtime dans son propre processus, IPC typé | Même processus que l'UI | Orbe plus simple, moins isolé |
| Fournisseurs | Chat Completions, Responses API, Anthropic ; SSE unifié ; raisonnement visible | Groq / Cerebras / OpenRouter avec rotation et santé des fournisseurs ; llama.cpp local | Orbe a le local et la rotation ; Eta a Responses/Anthropic |

---

## 4. Sécurité et confidentialité

### Constats sur Orbe

| # | Gravité | Constat | Preuve |
|---|---|---|---|
| S1 | **Haute** | Sauvegarde cloud / transfert d'appareil non maîtrisés | `android:allowBackup="true"` ; `backup_rules.xml` et `data_extraction_rules.xml` existent mais sont des **gabarits d'origine non modifiés et jamais référencés** par le manifest (ni `fullBackupContent` ni `dataExtractionRules`), donc les règles par défaut d'Android s'appliquent ; les clés API sont dans des `SharedPreferences` privées en clair (`ApiKeyStore`) ; mémoire, bloc-notes et journaux vivent sous `files/`. Par défaut, ces fichiers sont donc inclus dans la sauvegarde Google et le transfert d'appareil (dans la limite de taille d'Android). |
| S2 | **Moyenne** | `DiagSuiteReceiver` exporté **sans permission** | `exported="true"` avec le filtre d'action `RUN_DIAG_SUITE` ; n'importe quelle appli installée peut lancer les suites de diagnostic (qui appellent les LLM et les outils) et consommer ton quota. |
| S3 | Basse à moyenne | Liens profonds `pegase://pc-shell/run` et `/ntfy` déclarés `BROWSABLE` | `/run` ne fait que mémoriser un *slug de commande enregistrée* à lancer après déverrouillage (`PcShellAutoRunPending.setAfterUnlock`) : pas d'exécution arbitraire **[vérifié pour `/run`]**. Une page web peut néanmoins déclencher une commande enregistrée dès que tu déverrouilles. Le traitement de `/ntfy` n'a pas été audité **[à confirmer]**. |
| S4 | Moyenne | Trace locale des résultats d'outils | `Trace.toolEnd` écrit un extrait de la réponse pour tous les outils (SMS, notifications, presse-papiers…) dans `files/diag/trace.jsonl`, avec l'option `redact` **désactivée par défaut**. Corrigé seulement pour les outils `mcp_*` (`3185f97`). |
| S5 | Moyenne | Pas de politique « donnée sensible » générale | Orbe n'exclut de la mémoire que les outils d'interface (`EphemeralMemoryFilter.isUiToolId`). Les hints d'outils ne sont pas persistés dans `recent_turns.json` [vérifié : les tours `system` sont ignorés], mais une réponse de l'assistant qui recopie un SMS ou une notification l'est. |
| S6 | Basse | Release non minifié, `targetSdk 34` | `minifyEnabled false` ; Play Store exige 35 pour publier des mises à jour depuis août 2025 (sans objet tant que c'est un usage personnel). |
| S7 | Info | Jeton MCP en clair | Préférences privées non chiffrées (ajouté par nous, `1fc9f25`). |

### Ce qu'Eta fait mieux
- `allowBackup="false"`, `networkSecurityConfig`, R8 activé en release.
- `AgentSensitiveToolPolicy` : une **liste explicite** d'une quarantaine d'outils (notifications, SMS, position, presse-papiers, santé, contacts, journaux…) plus le préfixe `mcp_` dont les arguments *et* résultats ne sont jamais écrits dans la session persistante [vérifié].
- Jeton MCP chiffré (`McpSecretStore`).
- Politique de logs formelle (aucun prompt, chemin ou sortie de commande), appliquée par R8 en release.
- Les outils sensibles demandent de vérifier l'interrupteur utilisateur **à chaque exécution**, pas une seule fois.

### Faiblesses d'Eta
- `cleartextTrafficPermitted="true"` sur toute l'appli (`network_security_config.xml`).
- CI de release sans exécution de tests.
- Nombreux hooks pinnés sur des versions précises d'applis tierces : fragile aux mises à jour.

---

## 5. Qualité, maintenabilité, coût en tokens

| # | Gravité | Constat |
|---|---|---|
| Q1 | **Moyenne** | **Budget de tokens Groq** : mesure faite avec un test jetable. Les 57 outils pèsent ≈ 25 300 caractères de schéma (≈ 6 300 tokens), plus que le budget total de 6 000 tokens fixé pour Groq. Le set « quotidien » (34 outils) fait ≈ 13 100 caractères, soit ≈ 3 300 tokens : plus de la moitié du budget avant même le prompt système et l'historique. Les plus lourds : `diag` 1 240, `notepad` 1 197, `files` 1 044, `brief` 1 014, `git_commit` 1 012, `ui_action` 965 caractères. Ce sont des règles de comportement écrites dans les descriptions. |
| Q2 | **Moyenne** | **`PegaseSession` : 2 279 lignes** qui mélangent routage vocal, exécution d'outils, boucle agentique, cartes de raisonnement, confirmations. Ma contribution (file de lots, steering, récupération d'erreurs) y ajoute ≈ 230 lignes ; ces morceaux sont cohérents et testables séparément. |
| Q3 | Moyenne | **≈ 441 exceptions avalées** (`catch (Exception ignored)`). Un échec d'écriture mémoire, de sync ou de lecture disparaît sans trace, ce qui complique tous les tests sur appareil. |
| Q4 | Basse | Ratio test/code de 15,6 % contre 23 % pour Eta ; le dossier `session` (le cœur) n'a que 6 fichiers de test pour 2 279 lignes, complétés par mes nouveaux tests. Aucun test instrumenté hormis un fichier. |
| Q5 | Basse | Vues construites en code Java (peu lisibles, pas d'aperçu) ; Eta utilise Compose. |
| Q6 | Info | Pas d'aperçu d'écran ni de test d'UI dans Orbe ; Eta a des tests d'état UI. |
| Q7 | Info | `minSdk 24` : sert le futur LLM embarqué mais interdit d'utiliser des API récentes sans garde. |

---

## 6. Plan d'action proposé

### Priorité 1 : moins d'une demi-journée chacune
| Action | Effort | Bénéfice |
|---|---|---|
| S1 : `allowBackup="false"` **ou** référencer des règles (`dataExtractionRules` + `fullBackupContent`) excluant les préférences de clés, `files/memory`, `files/diag` | 15 min | Ferme la fuite la plus large |
| S1 bis : chiffrer `ApiKeyStore` avec `EncryptedSharedPreferences` (dépendance `security-crypto` déjà présente) | 1-2 h | Clés protégées sur disque ; même travail pour le jeton MCP |
| S2 : `DiagSuiteReceiver` en `exported="false"`, ou permission `signature`, ou seulement dans le source set `debug` | 15 min | Supprime l'entrée pour les autres applis |
| S4 : `Trace.setRedact(true)` par défaut hors mode debug ; masquer résultats des outils personnels | 1 h | Trace utilisable sans exposer SMS/notifications |
| Q3 : remplacer les `catch ignored` des chemins d'écriture (mémoire, sync, journal) par `Trace.error` | 2 h | Diagnostic possible sur appareil |

### Priorité 2 : une à deux journées
| Action | Effort | Bénéfice |
|---|---|---|
| Q1 : ramener les 10 descriptions les plus lourdes sous 400 caractères ; déplacer les règles vers le prompt système, chargées seulement à l'intention concernée | 1 j | Libère ≈ 1 000 tokens en usage quotidien |
| S5 : liste d'outils sensibles à la manière d'Eta (notifications, sms, contacts, clipboard, app_usage, read_image, web_read, mcp_*) : jamais dans `recent_turns`, ni dans la trace, ni dans la mémoire durable | 1 j | Politique unique et testable |
| ~~Politique générale de retry~~ : la rotation de fournisseurs couvre déjà les erreurs transitoires. Reste à envisager : une nouvelle tentative de toute la chaîne après 1,5 s quand tout échoue d'un coup (coupure réseau) | 0,5 j | Moins d'échecs sur réseau mobile instable |
| S3 : retirer `BROWSABLE` des deux liens profonds ou exiger une confirmation explicite | 2 h | Ferme le déclenchement depuis une page web |

### Priorité 3 : plus lourd
| Action | Effort | Bénéfice |
|---|---|---|
| Q2 : extraire de `PegaseSession` un `AgenticTurnRunner` (boucle, lots, steering, erreurs) et un `ToolExecutionCoordinator` | 2-3 j | Cœur testable, futur plus simple |
| Reprise après mort du processus (checkpoint minimal d'un tour agentique) | 2 j | Utile surtout si des tours durent plusieurs dizaines de secondes |
| R8 en release (avec règles `keep` pour llama.cpp, ONNX, réflexion) | 1 j | Taille et lisibilité de l'APK |
| `targetSdk` 35/36 | 1-2 j | Nécessaire seulement pour une publication |

### Ce qu'il ne faut pas copier d'Eta
Le runtime en processus séparé (coût d'IPC, complexité), les hooks par ROM, `cleartextTrafficPermitted` global, et une CI qui ne teste pas.

---

## 7. Limites de cet audit
- Aucun des deux projets n'a tourné : tout ce qui touche au comportement réel (latence, mémoire, fiabilité du STT, lots d'appels en vocal) reste à mesurer.
- Le nombre de tests d'Eta n'a pas été calculé (uniquement des fichiers et des lignes).
- Les 441 exceptions avalées ont été comptées par motif textuel ; toutes ne sont pas des erreurs (certaines sont légitimes : `NumberFormatException` attendue, etc.).
- La taille des schémas d'outils est celle envoyée par `OpenAiToolSchemaBuilder` ; les fournisseurs tokenisent différemment (estimation à 4 caractères par token, comme le fait `PromptBudget`).
- Je n'ai pas audité : le module `desktop/`, les scripts de sync, le code natif (llama.cpp, ONNX), ni les permissions une à une.


---

## 8. Statut des actions (mis à jour le 2026-09-20)

| Action | État | Commit |
|---|---|---|
| S1 sauvegarde (`allowBackup=false`) | fait | `27bfb04` |
| S1 bis clés API et jeton MCP chiffrés (`SecurePrefs`, migration) | fait | `79eae8f`, `0392ef5` |
| S2 `DiagSuiteReceiver` réservé au manifest debug | fait | `647cd55` |
| S4 trace censurée par défaut en release | fait | `d139d5a` |
| Q3 échecs d'écriture tracés (8 chemins) | fait | `cfea027` |
| Q1 descriptions d'outils raccourcies (−2,1 Ko sur 25,3 ; quotidien −0,8 Ko) | fait, gain limité : c'est le *nombre* d'outils qui pèse | `fd2e5a1` |
| S5 politique d'outils sensibles (trace, historique, Drive, mémoire) | fait | `e7dff0b` |
| S3 liens `pegase://pc-shell` sans `BROWSABLE` | fait, **à vérifier** avec la notification ntfy de mise à jour Arch | `fbd86ed` |
| Retry général | non nécessaire dans l'état (rotation), voir §3 | — |
| Q2 découpage de `PegaseSession` : trois blocs autonomes extraits (`AgenticTurnQueues`, `LlmErrorAnalysis`, `DiagTraceAnswers`) ; 2 281 → 2 196 lignes | **partiel** : le vrai découpage (`AgenticTurnRunner`, `ToolExecutionCoordinator`) reste un chantier de plusieurs jours | `886148d` |
| R8 en release | non fait : ne se valide qu'en lançant un APK release (JNI llama.cpp, ONNX, réflexion) | — |
| `targetSdk` 35/36 | non fait : change le comportement à l'exécution (edge-to-edge, services de premier plan), à valider sur l'appareil | — |
| Reprise après arrêt du processus | non fait : conception à valider avec des tours réels | — |
