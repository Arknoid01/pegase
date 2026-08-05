# 🐎 Pégase — launcher & assistant personnel

> Un launcher Android construit autour d'une **orbe cyan ailée**, devenu un
> assistant vocal complet avec mémoire, outils et copilote d'écran.
> Signé **PegasusCorp**.

---

## 🎯 Pourquoi ce projet

Deux besoins qui se rejoignent :

1. **Un launcher à moi.** Les launchers du marché se ressemblent tous, et les
   fonctions sympas sont presque toujours derrière un abonnement. Le mien est
   **gratuit, sans pub, sans bloat**, avec exactement les fonctions que je veux —
   et modifiable à l'infini.

2. **Un assistant qui a une âme.** Pas juste un outil : une présence nommée
   **Pégase**, avec sa voix, son caractère, son identité visuelle.

**Principe directeur : sobriété et indépendance.** Tout ce qui peut tourner en
local tourne en local. Pas de serveur permanent, pas de dépendance verrouillée
quand une alternative libre existe.

---

## 🎨 Identité visuelle

Une sphère cyan lumineuse, dessinée par un dégradé radial (pas une texture) :

```
cœur   #B8FBF6   ·   milieu #35D0DD   ·   bord #0B7D8F
```

**Respiration** (pulsation lente), **halo** (anneaux + glow), **orbites**
(petits ronds gravitants) et **ailes** — deux arcs de Bézier discrets au repos,
déployés une fraction de seconde au réveil.

Texte en blanc, fond ardoise teinté cyan, animations lentes : l'écran d'accueil
se regarde vingt fois par jour, trop de mouvement fatigue.

---

## 🧩 Architecture

### Le principe : des couches interchangeables

Chaque « cerveau » est caché derrière une interface. Le reste de l'app ne connaît
que l'interface, jamais l'implémentation — on change de moteur en modifiant une
ligne. C'est le choix structurant du projet, et il a tenu :

| Interface | Rôle | Implémentations |
|---|---|---|
| `ChatBackend` | tenir une discussion | Gemini, Groq, OpenAI-compatible, LLM local, multi-provider, fallback |
| `IntentParser` | comprendre une commande | `LocalKeywordParser` |
| `IntentHandler` | exécuter un domaine | système, média, bureau, copilote, savoir, vie, Orion, diag |

### La règle d'or : le LLM n'a aucun pouvoir

Le modèle produit du texte ou demande un outil. C'est **l'app** qui exécute.
Ajouter un pouvoir à Pégase = ajouter un *tool*. Un Jarvis = un modèle plus
plein de petits outils, ajoutés un par un.

---

## ✨ Ce que fait Pégase aujourd'hui

### Le socle
- **Launcher** — orbe centrale, éventail de raccourcis, tiroir d'applications
- **Voix** — mot d'éveil local, commandes, discussion continue, TTS
- **Mémoire** — historique, graphe d'entités, consolidation, fiches projet

### Les briques ajoutées depuis
- **Copilote d'écran** — lit l'écran via l'accessibilité, clique et tape à la
  demande, avec confirmation obligatoire sur les actions sensibles
- **Bureau** — éditeur Markdown, projets, plans, recherche
- **Orion** — assistant de code, projets, index de fichiers
- **Intentions** — suggestions proactives (batterie, calendrier, wifi, voiture)
  avec heures calmes et quota journalier
- **RAG local** — embeddings all-MiniLM-L6-v2 en ONNX, 100 % sur l'appareil
- **Outils** — apps, notifications, calendrier, contacts, météo, Spotify, git
- **Diagnostic** — journal JSONL, santé du wake, rapports

---

## 🎙️ Le pipeline vocal

```
🎙️  « Hey Pégase »        → wake word LOCAL (openWakeWord ou Sherpa KWS)
🗣️  transcription         → SpeechRecognizer (hors-ligne si pack FR installé)
🧠  commande OU discussion → IntentParser (local) / ChatBackend
🔊  réponse parlée         → Piper (local) ou TTS Android
```

### Le mot d'éveil écoute toujours le micro du téléphone

**Décision assumée, prise après mesure.** Le lien Bluetooth mains-libres (HFP/SCO)
est conçu pour l'appel : le tenir ouvert en permanence monopolise l'A2DP, coûte
de la batterie des deux côtés, et livre un flux troué — mesuré à **39-51 % du
signal perdu, contre 7 % sur le micro intégré**.

Aucun assistant ne procède autrement : quand « Hey Google » fonctionne sur des
écouteurs, la détection vit dans le **firmware des écouteurs**, pas sur le
téléphone.

**La conversation, elle, bascule sur le casque** une fois le wake déclenché —
elle établit son propre lien SCO, en quelques dizaines de millisecondes.

Conséquence à connaître : téléphone en poche, pas de mot d'éveil. `PocketWakeGuard`
le coupe franchement via le capteur de proximité plutôt que de faire semblant.

---

## 🔒 Vie privée & sécurité

- **Local d'abord.** Wake word, embeddings, TTS Piper, reconnaissance hors-ligne :
  tout peut tourner sans réseau. Seul le mode discussion sort, par nature.
- **Le wake ne transmet rien avant déclenchement.** L'audio est analysé image par
  image en local puis jeté — c'est du *keyword spotting*, jamais une transcription.
  *Preuve :* couper le réseau ; si « Hey Pégase » réagit, tout se passe sur l'appareil.
- **Point vert Android 12+** dès que le micro est actif, plus un bouton coupe-micro.
- **Confirmation obligatoire** sur les actions sensibles du copilote.

### Limites dures (bac à sable Android)
- ❌ Pas d'accès au Voice Match de Google ni aux données d'autres apps
- ⚠️ Écoute continue = service en avant-plan + notification permanente
- ⚠️ Le micro Bluetooth n'est pas exploitable pour une écoute permanente (voir plus haut)

---

## 🛠️ Choix techniques

- **Java**, natif obligatoire — un launcher a besoin d'une intégration système
  trop profonde pour Capacitor.
- **Discussion : plusieurs fournisseurs** derrière `ChatBackend`, dont Gemini
  (gratuit, ~1500 req/j) et Groq. Ne jamais coder un nom de modèle en dur.
- **Wake word : openWakeWord** (libre, local, vérifiable) avec **Sherpa KWS** en
  second moteur. Pas Porcupine : propriétaire, AccessKey, cher en commercial.
- **Voix : Piper** via Sherpa-ONNX — open-source, local, français.
- **RAG : all-MiniLM-L6-v2** en ONNX, entièrement sur l'appareil.

---

## 📁 Structure

```
app/src/main/java/com/pegasuscorp/orbe/
├── voice/        mot d'éveil, STT, TTS, routage audio, coordination  (~17k lignes)
├── orion/        assistant de code et projets                        (~15k)
├── tools/        outils appelables par le LLM                        (~13k)
├── bureau/       éditeur Markdown et plans                           (~11k)
├── copilot/      lecture d'écran et actions via accessibilité         (~7k)
├── diag/         journal JSONL, santé, rapports                       (~7k)
├── memory/       historique, graphe d'entités, consolidation          (~6k)
├── chat/         backends LLM et gestion de conversation              (~5k)
└── …             intentions, session, rag, objects, learning, ui
```

> ⚠️ **Piège du thème** : les activités héritent d'`AppCompatActivity`, donc le
> thème DOIT être compatible AppCompat (`@style/Theme.Orbe`), sinon crash au
> lancement.

---

## 🧪 Tests & diagnostic

Environ **720 tests unitaires** (JUnit + Robolectric).

```bash
./gradlew :app:testDebugUnitTest
```

Le journal de cycle de vie du wake est écrit en JSONL dans
`files/diag/kws_lifecycle.jsonl` — il enregistre la route audio réelle, les
détections, les handoffs et les sessions STT. C'est l'outil qui a permis de
diagnostiquer les problèmes Bluetooth par la mesure plutôt que par hypothèse.

Un harnais d'évaluation hors ligne permet de tester le mot d'éveil sur des
fichiers : déposer des `kws_eval_*.wav` (PCM 16 bits mono 16 kHz) dans
`files/diag/`, relancer le service, lire le résultat dans le journal.

---

## 🗺️ Où en est le projet

**Fait** — launcher, orbe et gestes · voix niveau 1 et discussion · mémoire et
graphe d'entités · copilote d'écran · bureau Markdown · Orion · intentions
proactives · RAG local · Piper · mot d'éveil local

**En cours** — fiabilité du mot d'éveil, conversation continue, endpointing

**À venir** — vérification du locuteur (ECAPA), tracé de lettre pour le tiroir
(ML Kit Digital Ink), fond « Fluid », action sur la compta

**Écarté** — mot d'éveil via micro Bluetooth (voir la section pipeline vocal)

---

*Gratuit, sans pub, local, à moi — et il prend son envol brique par brique.* ✨
