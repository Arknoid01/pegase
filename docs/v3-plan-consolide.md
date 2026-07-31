# Pégase v3 — Plan consolidé

**Base :** [`v3-audit-main-libre-comprehension-ui.md`](./v3-audit-main-libre-comprehension-ui.md)  
**Statut :** discussion validée point par point — reste à convertir en tickets/specs avant implémentation.

---

## Priorité 1 — Mains libres + UI (overlay et interface texte)

**Objectif :** unifier micro et confirmations là où ils sont aujourd'hui tactiles.

- **PTT dans Discussion et la bulle copilote** — réutilise `VoiceManager`/`ChatVoiceBridge` existants, pas de nouvelle stack STT. Le temps de l'appui, bascule vers le même flux que le chat vocal, puis revient à l'état précédent au relâchement.
- **Confirmations unifiées** — `PendingToolConfirm` (texte) pointe vers `VoiceConfirmation` quand le mode PTT est actif, au lieu d'avoir deux mécanismes séparés.
- **Écoute depuis n'importe quelle app ("wake in-place")** — le wake (ou le PTT) ouvre la session `VoiceManager` et affiche le retour via `FloatingOrbService` par-dessus l'app en cours, sans forcer le retour à `MainActivity`. Nécessite un nouvel état dans `PegaseWakeController` ("écoute active, pas HOME au premier plan").
- **Liste blanche écran verrouillé** — pas d'ouverture d'app, seulement des outils précis exposés à la voix :
  - Calcul
  - Horloge / minuteur
  - Ajout ou modification de rendez-vous agenda
  - Confirmation finale par **notification système** (pas d'overlay pertinent écran verrouillé)
  - **Vérification vocale (`SpeakerVerifyGate`) exigée spécifiquement pour l'agenda** — pas pour calcul/minuteur
  - En cas d'échec de la vérification vocale sur l'agenda : refus silencieux + notification discrète (pas de dialogue de clarification qui pourrait exposer du contenu à quelqu'un d'autre)
- **Finition — hint vocal** — un hint du type « dis "aide" » en complément du PTT/wake in-place, pour combler l'absence actuelle de découverte vocale des surfaces (friction notée dans l'audit, section couplages).
- **Finition — signal sonore de l'état du wake** — pas seulement visuel : un son distinct pour "je t'écoute" / "je ne t'entends plus", pour que l'indicateur reste utilisable mains vraiment libres (conduite, cuisine…).

---

## Priorité 2 — Compréhension (contexte écran)

**Objectif :** brancher `ScreenContextStore` (déjà capturé en continu) dans `ContextBuilder`, sans construire de nouveau pipeline.

- Le LLM accède au **dernier snapshot** capturé (texte écran + package + timestamp), pas à un flux d'événements historique — pas de coût de contexte supplémentaire à chaque tour.
- **Activation uniquement en mode copilote actif** — en simple conversation, Pégase n'a pas accès à l'écran ; les deux contextes (conversation / écran) restent séparés.
- **Finition — seuil de fraîcheur** — si le snapshot capturé date de plus de X secondes, ne pas l'injecter (évite de répondre sur une page déjà quittée).

---

## Priorité 3 — Compréhension (réflexion ciblée)

**Objectif :** passe de planification cachée, réservée aux cas complexes — sans réouvrir le chantier copilote complet.

- Réflexion cachée limitée **aux intents copilote complexes** (perception d'écran + décision d'action + synthèse) — c'est là que le risque d'hallucination est le plus élevé une fois le contexte écran branché (priorité 2).
- Ne touche pas au reste du mode copilote : le gros du chantier copilote part dans un **document v4 séparé**.

---

## Priorité 4 — Mains libres (fiabilité du wake)

**Objectif :** donner un état réel du wake, pas seulement le toggle de réglage.

- **Notification FGS honnête** — le service `VoiceService` a déjà une notification obligatoire ; elle reflète l'état réel :
  - "Pégase écoute" (KWS sain)
  - "Écoute coupée — problème détecté" (coupe-circuit déclenché après 2 morts rapides)
  - Rien si désactivé manuellement
- Visible **depuis n'importe quelle app** (notification système), pas seulement sur HOME.
- **Orbe rouge en cas de problème** — déjà présent dans `OrbThemes`/`OrbeTokens`, à déclencher sur l'état réel (coupe-circuit) plutôt qu'un choix de thème manuel.
- **Finition — indice de forme en plus de la couleur** — pulse différent, pas seulement teinte rouge, pour rester lisible en plein soleil et pour l'accessibilité.

---

## Priorité 5 — UI (feedback unifié)

**Objectif :** un seul état d'écoute/réflexion partagé par toutes les représentations visuelles.

- HOME (`OrbView`), overlay (`FloatingOrbService`) et Discussion (`ThinkingView`) écoutent **la même source d'état** (extension de `PegaseWakeController`) au lieu d'avoir chacun leur propre logique de déduction.

---

## Priorité 6 — Contexte de localisation et mode voiture automatique

**Objectif :** déclencher automatiquement le mode voiture, et enrichir la mémoire d'un tag de lieu — en réutilisant les mécanismes déjà en place plutôt qu'un nouveau système.

- **Signal unique : la localisation** — pas de Wi-Fi séparé (ferait doublon), un seul signal couvre à la fois les zones fixes (maison, travail, restaurant) et la détection de mouvement (vitesse).
- **Mode voiture automatique** — au-delà de **20 km/h** détectés, bascule automatique vers `PegaseModeStore` en mode DRIVE (réponses courtes, priorité aux outils, overlay bloqué pour rester 100 % vocal). Se branche comme une règle de plus dans `IntentionEvaluator`, qui gère déjà des règles capteurs similaires (batterie, Bluetooth voiture…).
- **Contexte global de localisation** — zones taguées (maison, travail, restaurant, voiture) injectées dans la mémoire/RAG pour enrichir le contexte, sur le même principe que les autres signaux situationnels déjà gérés par `IntentionEvaluator`.

---

## Hors scope (volontairement, pour cette v3)

- Nouveaux modules lourds (planner général, nouveau moteur STT, refonte `PegaseSession`)
- Mode copilote dans son ensemble → **document v4 dédié**
- Actions réseaux sociaux directes (API Meta) — différées

---

*Document généré à partir de la discussion consolidée — à transformer en tickets avant implémentation.*
