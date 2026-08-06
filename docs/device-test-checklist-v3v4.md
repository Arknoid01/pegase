# Pégase — Checklist validation device (v3 + v4 + ui_loop)

**Objectif :** valider en conditions réelles ce qui est sur `main` (copilote, contrôle UI, wake, mémoire).  
**Durée indicative :** 2–3 h (une session complète) ou 45 min (smoke rapide, sections marquées ⚡).  
**Dernière mise à jour :** 6 août 2026

---

## Avant de commencer

| Champ | Valeur |
|-------|--------|
| Date | |
| Appareil (modèle / Android) | |
| Commit ou tag `main` | `git rev-parse --short HEAD` |
| Connexion | USB / Wi-Fi ADB |
| Audio | ☐ Haut-parleur téléphone ☐ Écouteurs filaires ☐ Bluetooth (noter modèle) |
| Réseau | ☐ Wi-Fi ☐ 4G/5G (requis pour LLM cloud) |
| Clé API OpenRouter | ☐ Configurée |

### Préparation (une fois)

1. Installer l’APK debug : `./gradlew :app:assembleDebug` puis `adb install -r app/build/outputs/apk/debug/app-debug.apk`
2. Ouvrir Pégase → terminer l’onboarding / permissions de base
3. **Réglages → Outils → Mode copilote** :
   - ☐ Afficher par-dessus les apps (overlay)
   - ☐ Service d’accessibilité « Pégase copilote »
   - ☐ Accès aux notifications (si test notifs)
   - ☐ Capture écran (si test vision / ui_explain vision)
4. Dans Mode copilote :
   - ☐ Orbe toujours visible : **ON**
   - ☐ Analyse d'écran : **ON**
   - ☐ Whitelist : **YouTube**, **Chrome**, **WhatsApp** (minimum)
   - ☐ Overlay traduction : **ON**
   - ☐ Réflexion cachée : **ON** (test P3)
5. **Réglages → Personnalisation** : wake activé, micro non coupé
6. Quitter l’app Pégase (HOME ou autre app) — l’orbe copilote doit apparaître (56 dp)

**Légende résultats :** ✅ PASS · ❌ FAIL · ⏭ SKIP (non testé) · 📝 noter le symptôme dans « Notes »

---

## 1. Wake & voix ⚡

| # | Scénario | Étapes | Résultat attendu | ✅/❌ | Notes |
|---|----------|--------|------------------|-------|-------|
| 1.1 | Wake téléphone | Dire « Pégase » (ou mot-clé configuré) sur HOME | Overlay écoute / orbe réagit, STT démarre | | |
| 1.2 | Question simple | « Quelle heure est-il ? » | Réponse vocale ou texte, pas de crash | | |
| 1.3 | Wake in-place | Ouvrir Chrome, dire « Pégase » | Écoute **sans** forcer retour à Pégase | | |
| 1.4 | Notification FGS wake | Wake activé, regarder la barre de notifs | Notif honnête (« Pégase écoute » ou état erreur cohérent) | | |
| 1.5 | Orbe rouge si problème | Simuler échec KWS (si possible) ou couper micro BT | Orbe / notif reflètent l’état dégradé | | |
| 1.6 | Wake Bluetooth | Avec écouteurs / kit voiture BT connecté, dire « Pégase » | Détection + STT sur route BT (pas seulement micro tel) | | |
| 1.7 | Handoff wake → STT | Après wake, parler une phrase complète | Pas de coupure prématurée, pas de double session | | |
| 1.8 | Reprise après TTS | Poser une question, laisser Pégase répondre, reparler | Écoute reprend sans redémarrer l’app | | |

**Si échec wake/BT :** exporter les logs diag (section 8) avant de continuer — c’est souvent le goulot prioritaire.

---

## 2. Copilote — socle ⚡

| # | Scénario | Étapes | Résultat attendu | ✅/❌ | Notes |
|---|----------|--------|------------------|-------|-------|
| 2.1 | Orbe visible | Quitter Pégase, ouvrir une app tierce | Orbe 56 dp visible (sauf MainActivity Pégase) | | |
| 2.2 | Bulle messenger | Tap orbe → taper « Bonjour » → envoyer | Streaming réponse LLM dans la bulle | | |
| 2.3 | Orbe masquée sur Pégase | Ouvrir MainActivity / interface Pégase | Orbe copilote masquée | | |
| 2.4 | Contexte écran P2 | Chrome : page avec texte visible, demander « De quoi parle cette page ? » | Réponse utilise le contenu écran (pas générique) | | |
| 2.5 | Fraîcheur snapshot | Changer de page, attendre > 45 s, redemander | Contexte obsolète **non** injecté (ou réponse prudente) | | |
| 2.6 | Réflexion P3 | Demande complexe : « Que dois-je faire sur cet écran pour… » | Statut « réflexion » visible, réponse cohérente | | |
| 2.7 | Traduction overlay | Chrome : page **en anglais** | Labels traduits positionnés sur le texte | | |
| 2.8 | Bouton Écran (vision) | Tap **Écran** dans la bulle | Consentement capture → description de l’écran | | |
| 2.9 | Retenir (mémoire) | Tap **Retenir** ou « retiens ça » après contenu à mémoriser | Confirmation mémoire, pas d’erreur bulle | | |
| 2.10 | Partage texte | Sélectionner texte → Partager → Pégase | Ingestion OK (mémoire ou contexte) | | |

---

## 3. Actions UI — `ui_action` (v4)

**Prérequis :** app cible dans la whitelist, analyse écran ON.

| # | Scénario | Étapes | Résultat attendu | ✅/❌ | Notes |
|---|----------|--------|------------------|-------|-------|
| 3.1 | Highlight avant action | Demander un clic visible (« clique sur Rechercher ») | Cadre sur la cible **avant** le clic + statut « Action en cours » | | |
| 3.2 | Clic simple | YouTube : « active les sous-titres » (voix ou bulle) | CC activés (action locale / ui_action) | | |
| 3.3 | Clic sans confirmation | Clic sur bouton neutre (ex. « Fermer » bannière) | Clic direct, pas de popup Oui/Non | | |
| 3.4 | Clic conditionnel | Cibler un bouton denylist (ex. « Envoyer », « Supprimer ») | Demande confirmation Oui/Non dans la bulle | | |
| 3.5 | Clic lien | Cibler un lien hypertexte | Confirmation **toujours** demandée | | |
| 3.6 | Scroll | « Fais défiler vers le bas » sur une liste longue | Défilement visible | | |
| 3.7 | Saisie texte | Champ recherche Chrome : « tape hello » | Texte saisi, **sans** validation auto | | |
| 3.8 | Back | « Retour » / back UI | Navigation arrière | | |
| 3.9 | Bulle ne bloque pas | Cible derrière la bulle | Bulle se replie ou passthrough — clic atteint la bonne cible | | |
| 3.10 | Hors whitelist | Ouvrir app **non** whitelistée, demander un clic | Refus / message clair, pas d’action | | |

### Apps suggérées (cocher celles testées)

- ☐ YouTube (`com.google.android.youtube`)
- ☐ Chrome (`com.android.chrome`)
- ☐ WhatsApp (`com.whatsapp`)
- ☐ Autre : _______________

---

## 4. Compréhension UI — `ui_explain` & `ui_search`

| # | Scénario | Étapes | Résultat attendu | ✅/❌ | Notes |
|---|----------|--------|------------------|-------|-------|
| 4.1 | ui_explain texte | Sur un mot visible : « C'est quoi ce mot ? » | Overlay explication près du mot (texte local) | | |
| 4.2 | ui_explain vision | Sur une icône / image sans texte a11y | Repli vision si besoin, réponse en overlay | | |
| 4.3 | ui_search | « Fais une recherche sur [mot visible] » | Onglet navigateur avec résultats (pas de clics Chrome simulés) | | |
| 4.4 | Distinction explain/search | « Explique X » vs « Cherche X » | explain = overlay ; search = ouverture web | | |

---

## 5. Boucle adaptative — `ui_loop`

**Objectifs simples, une app à la fois.** Max 10 tours côté code.

| # | Scénario | Étapes | Résultat attendu | ✅/❌ | Notes |
|---|----------|--------|------------------|-------|-------|
| 5.1 | Objectif 2 gestes | « Ouvre le menu puis les paramètres » (app connue) | Enchaînement sans script figé, finish OK | | |
| 5.2 | Imprévu (bannière) | Objectif avec popup cookie / consentement | Boucle gère ou échoue proprement (finish_fail + message) | | |
| 5.3 | Comparaison ui_action.steps | Même tâche en `steps` vs `ui_loop` | steps plus rapide si stable ; loop plus robuste si UI change | | |
| 5.4 | Hint post-run | Après un run réussi, confirmer hint proposé | Hint persisté pour l’app (réglages / prochain run) | | |
| 5.5 | Timeout / abandon | Objectif impossible (« ouvre une app non installée ») | Échec explicite avant 90 s, pas de blocage infini | | |

---

## 6. Mains libres & contexte (v3 P1 / P6) ⚡

| # | Scénario | Étapes | Résultat attendu | ✅/❌ | Notes |
|---|----------|--------|------------------|-------|-------|
| 6.1 | PTT bulle copilote | Appui long micro bulle → parler → relâcher | Transcription + réponse (même flux que chat vocal) | | |
| 6.2 | PTT Discussion | Appui long micro dans Discussion | Idem, confirmations outils via voix si actif | | |
| 6.3 | Son état wake | Wake activé puis fin d’écoute | Son distinct « j’écoute » / « plus d’écoute » (si activé) | | |
| 6.4 | Hint vocal | Dire « aide » (ou hint configuré) | Liste des capacités / rappel commandes | | |
| 6.5 | Écran verrouillé — calcul | Écran verrouillé : « combien font 12 fois 7 » | Réponse sans déverrouiller | | |
| 6.6 | Écran verrouillé — minuteur | « Lance un minuteur de 2 minutes » | Minuteur créé, notif système | | |
| 6.7 | Écran verrouillé — agenda | « Ajoute un rdv demain 10h » (si vérif vocale ON) | Succès si voix enrollée ; refus discret sinon | | |
| 6.8 | Mode DRIVE auto | Simuler vitesse > 20 km/h (voiture ou mock GPS) | Mode DRIVE actif, réponses courtes | | |
| 6.9 | Orbe masquée en conduite | Option « masquer orbe en auto-drive » ON + vitesse | Orbe copilote masquée | | |
| 6.10 | Contexte lieu | Être tagué « maison » ou « travail » (si configuré) | Contexte lieu dans les réponses globales | | |

---

## 7. Notifications copilote

| # | Scénario | Étapes | Résultat attendu | ✅/❌ | Notes |
|---|----------|--------|------------------|-------|-------|
| 7.1 | Whitelist notif | Activer alertes notifs + whitelist WhatsApp | | | |
| 7.2 | Message entrant | Recevoir un message WhatsApp | Phrase résumée dans la bulle (`📩 …`) | | |
| 7.3 | App hors whitelist | Notif d’une app non listée | Ignorée | | |

---

## 8. Mémoire (Mem0-style)

| # | Scénario | Étapes | Résultat attendu | ✅/❌ | Notes |
|---|----------|--------|------------------|-------|-------|
| 8.1 | Mémorisation | « Retiens que mon code porte est 1234 » (donnée fictive) | Confirmé | | |
| 8.2 | Rappel | Plus tard : « Quel est mon code porte ? » | Rappel correct depuis la mémoire | | |
| 8.3 | Graphe / UI mémoire | Ouvrir réglages Mémoire, constellation 3D | Affichage sans crash | | |
| 8.4 | Redaction PII | Page avec email/tel visible, question sur l’écran | Données sensibles masquées dans le prompt (comportement prudent) | | |

---

## 9. Stabilité & régressions ⚡

| # | Scénario | Étapes | Résultat attendu | ✅/❌ | Notes |
|---|----------|--------|------------------|-------|-------|
| 9.1 | Processus vivants | Après 10 min d’usage | `:voice`, `:copilot`, process principal OK (`adb shell ps \| grep orbe`) | | |
| 9.2 | Pas de FATAL | Pendant toute la session | Aucun crash (`adb logcat \| grep FATAL`) | | |
| 9.3 | Double envoi bulle | Taper vite 2 messages | `isSending` bloque le second | | |
| 9.4 | Rotation écran | Pivoter pendant bulle ouverte | UI récupère sans perte majeure | | |
| 9.5 | SCREEN_OFF | Éteindre écran 30 s, rallumer | Analyse copilote reprend ; wake OK | | |

---

## 10. Export diagnostic (en cas d’échec)

À faire **avant** de quitter la session si un test critique échoue :

```bash
# Logs récents
adb logcat -d -t 500 | grep -iE "pegase|orbe|kws|copilot|UiLoop|VoiceService" > pegase-log-$(date +%Y%m%d).txt

# Processus
adb shell ps -A | grep orbe

# Option : exporter depuis l’app si dispo (Diag / KWS JSONL)
```

Fichiers utiles côté app (si accessibles) : `kws_lifecycle.jsonl`, logs KWS JSONL, export diag depuis les réglages debug.

---

## Smoke rapide (45 min) — minimum viable

Si peu de temps, exécuter **uniquement** les lignes ⚡ :

1. **1.1, 1.6, 1.7** — wake tel + BT  
2. **2.1, 2.2, 2.4, 2.7** — copilote base  
3. **3.1, 3.2, 3.4** — clic + highlight + confirmation  
4. **6.1, 6.8** — PTT + drive  
5. **9.1, 9.2** — stabilité  

---

## Synthèse de session

| Bloc | PASS | FAIL | SKIP |
|------|------|------|------|
| 1. Wake & voix | | | |
| 2. Copilote socle | | | |
| 3. ui_action | | | |
| 4. explain / search | | | |
| 5. ui_loop | | | |
| 6. Mains libres / DRIVE | | | |
| 7. Notifications | | | |
| 8. Mémoire | | | |
| 9. Stabilité | | | |

### Décision après session

- ☐ **Priorité wake/BT** — section 1 rouge → stabiliser audio avant nouvelles features  
- ☐ **Priorité UI** — section 3 rouge → polish matcher / bulle / confirmations  
- ☐ **Priorité ui_loop** — section 5 rouge → borner objectifs, hints, timeouts  
- ☐ **Prêt usage quotidien** — majorité verte → polish UX + doc utilisateur  

### Top 3 bugs à ouvrir en issue

1.  
2.  
3.  

---

## Références

- `docs/copilot-etat.md` — architecture copilote  
- `docs/copilot-v4-plan.md` — périmètre ui_action / explain / search  
- `docs/device-test-report-final.txt` — dernier rapport automatisé (30 juil. 2026)  
- `docs/inspirations-assistants-foss.md` — backlog post-FOSS (hints, ui_loop)
