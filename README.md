# 🐎 Pégase — launcher & assistant personnel

> Un launcher Android minimaliste construit autour d'une **orbe cyan ailée**,
> qui grandit couche par couche pour devenir un assistant vocal complet.
> Signé **PegasusCorp**.

---

## 🎯 Pourquoi ce projet

Deux besoins qui se rejoignent :

1. **Un launcher à moi.** Les launchers du marché se ressemblent tous (grille
   d'icônes + dock + tiroir), et les fonctions sympas (gestes, thèmes, sans pub)
   sont presque toujours derrière un abonnement ou une version « Pro ». Le mien
   sera **gratuit pour toujours, sans pub, sans bloat**, avec exactement les
   fonctions que je veux — et modifiable à l'infini.

2. **Un assistant qui a une âme.** Pas juste un outil : une présence nommée
   **Pégase**, avec sa voix, son caractère, son identité visuelle — le fil
   conducteur de tout mon univers PegasusCorp.

**Principe directeur : sobriété et indépendance.** Tout ce qui peut tourner en
local tourne en local. Pas de serveur permanent, pas de dépendance verrouillée
quand une alternative libre existe, empreinte minimale.

**Différence clé avec Project-Orion :** Orion est un gouffre à *contenu*
(sprites ComfyUI, tuiles, ~10 pistes de musique, 36 systèmes à équilibrer).
Pégase est l'inverse : **quasiment aucun asset à produire**. L'orbe, le halo,
les ailes = du dessin vectoriel en code. Les voix et modèles = des briques
pré-entraînées qu'on télécharge. C'est un projet sérieux mais **léger en
production** — de la logique et de l'UI, pas de la fabrication de contenu.

---

## 🎨 Identité visuelle

### L'orbe
Le cœur de Pégase. Une sphère cyan lumineuse, dessinée par un dégradé radial
(pas une texture). Teinte de référence :

```
cœur   #B8FBF6   (clair)
milieu #35D0DD   (cyan)
bord   #0B7D8F   (profond)
```

Trois variantes de teinte au choix : **Cyan / Teal / Aqua**.

### Les éléments vivants
- **Respiration** — l'orbe pulse doucement (échelle ~1.0 ↔ 1.035, cycle lent).
- **Halo** — anneaux concentriques semi-transparents + glow radial cyan derrière
  l'orbe.
- **Orbites** — quelques petits ronds gravitent autour comme des planètes, à des
  vitesses et inclinaisons différentes (un sur orbite inclinée pour la profondeur).
- **Ailes** 🐎 — signature de Pégase : deux arcs lumineux cyan de chaque côté
  (courbes de Bézier en code), **discrètes au repos, qui se déploient une
  fraction de seconde au réveil** (« il prend son envol »). Version SVG possible
  pour un tracé plus fin.

### Règles de style
- **Texte en blanc** partout.
- **Animations lentes et discrètes** : l'écran d'accueil se regarde 20× par jour,
  trop de mouvement fatigue. Une orbite calme > trois qui s'agitent.
- Fond sombre ardoise avec une légère teinte cyan.

### Ambition lointaine
- **Fond « Fluid »** — atmosphère vivante qui change selon l'heure (« Midday »…).
  Le plus ambitieux visuellement → tout à la fin.
- **Mode « Halo »** — orbe évidée qui laisse voir le fond d'écran (bonus).

---

## 🧩 Architecture

### Le principe : des couches interchangeables

L'idée maîtresse de tout le projet : **chaque « cerveau » est caché derrière une
interface.** Le reste de l'app ne connaît que l'interface, jamais
l'implémentation. Résultat : on change de moteur en modifiant *une seule ligne*,
sans rien casser ailleurs. On peut aussi **router** selon le besoin.

Trois interphases-clés :

| Interface | Rôle | Implémentation actuelle | Alternatives futures |
|---|---|---|---|
| `IntentParser` | comprendre une **commande** | `LocalKeywordParser` (mots-clés) | `GemmaParser` (LLM local) |
| `ChatBackend` | tenir une **discussion** | `GeminiChatBackend` (API gratuite) | `GroqBackend`, `GemmaChatBackend` |
| `SpeakerVerifier` *(à venir)* | vérifier **que c'est moi** | — | ECAPA-TDNN local |

### Le pipeline vocal complet (vision cible)

```
🎙️  "Hey Pégase"            → wake word LOCAL (openWakeWord)
🔐  c'est bien moi ?         → vérification du locuteur (ECAPA local)
🗣️  transcription            → SpeechRecognizer (local si pack FR hors-ligne)
🧠  commande OU discussion   → IntentParser (local) / ChatBackend (Gemini)
🔊  réponse parlée           → TTS (Piper local, voix française)
```

Chaque étage est indépendant : on peut n'avoir que l'étage 3-4-5 (voix niveau 1)
et brancher les autres plus tard.

### La règle d'or : le LLM n'a aucun pouvoir
Le modèle ne fait que **produire du texte / choisir un outil**. C'est **l'app**
qui exécute. Ajouter un « pouvoir » à Pégase = ajouter une fonction (un *tool*)
que le modèle peut demander : `ouvre_app()`, `minuteur()`, `web_search()`,
`saisie_compta()`… Un Jarvis = un modèle + plein de petits outils, ajoutés un par un.

---

## ✨ Fonctionnalités

### Socle — le launcher (le projet « simple », prioritaire)

| Fonction | État | Notes techniques |
|---|---|---|
| Orbe centrale + gestes | ✅ base | tap = éventail, appui long = voix, swipe haut = tiroir |
| Éventail de raccourcis | ✅ base | intents directs, dessin custom + anim |
| Tiroir d'applications | ✅ base | `PackageManager`, grille, tri alpha |
| Teinte cyan + halo + orbites + ailes | ⏳ | dessin dans `OrbView` (dégradé, cercles, Bézier) |
| Tracé de lettre → drawer | ⏳ | **ML Kit Digital Ink** (natif, gratuit, hors-ligne) |
| Rail A-Z + bulle de lettre | ⏳ | index latéral dans le tiroir |
| Écran de réglages | ⏳ | couleur d'orbe, nb de raccourcis, etc. |
| Bouton coupe-micro | ⏳ | booléen `voiceEnabled` + icône micro barré (concerts, réunions) |

### Assistant — Pégase (bonus, brique par brique)

| Fonction | État | Techno retenue |
|---|---|---|
| Voix niveau 1 (commandes) | ✅ code | `SpeechRecognizer` + `TextToSpeech` natifs |
| Mode discussion (mémoire) | ✅ code | `ChatBackend` → **API Gemini gratuite** |
| Ton / caractère personnalisé | ⏳ facile | `systemInstruction` (discussion) + phrases variées (commandes) |
| Mot d'éveil « Hey Pégase » | ⏳ enregistrement app + moteur ONNX | **openWakeWord** (libre, local) — voir `docs/openwakeword.md` |
| Voix qui ne répond qu'à moi | ⏳ | **ECAPA-TDNN** local (text-dependent + phrase-clé) |
| Voix Pégase agréable | ⏳ | **Piper** (neuronal, local, français) |
| Action sur la compta | ⏳ | endpoint sur mon site Hostinger *(idéal)* ou WebView pilotée en JS |

---

## 🛠️ Choix techniques & justifications

- **Langage : Java** (cohérent avec mes mods Forge). Natif obligatoire — un
  launcher a besoin d'une intégration système trop profonde, **Capacitor ne
  convient pas**.
- **Discussion : API Gemini gratuite** (Google AI Studio). Frontier, ~1500 req/j
  sur Flash, recherche web intégrée, **et en UE Google n'entraîne pas ses
  modèles sur mes prompts**. Usage ponctuel → je peux même viser le modèle Pro
  (plafond ~50-100 req/j, largement suffisant). Ne jamais coder un nom de modèle
  en dur (les paliers changent souvent).
- **Mot d'éveil : openWakeWord** plutôt que Porcupine. Porcupine est plus facile
  (console, `.ppn`, SDK clé en main) mais propriétaire + AccessKey + cher en
  commercial. openWakeWord est **libre, gratuit, débloqué, et je peux vérifier le
  code** (aucun appel réseau). Un peu plus de plomberie (audio 16 kHz →
  mel-spectrogram → ONNX Runtime Mobile), mais aucune porte fermée.
- **Locuteur : ECAPA-TDNN local.** Même classe que ce que Google utilise, tourne
  en temps réel sur mobile avec charge minime. Mon cas est le plus favorable
  (un seul utilisateur, phrase-clé fixe, téléphone en main, environnement connu).
  Le modèle n'est pas le facteur limitant — c'est le **réglage du seuil**.
- **Voix : Piper** (Rhasspy). Open-source, local, ONNX compact, 100+ voix / 35+
  langues dont le français, via **Sherpa-ONNX** sur Android. Pas de clone de voix
  — juste une belle voix française toute prête. *(Clonage possible via RunPod
  plus tard, mais c'est un mini-projet à part.)*
- **Tracé de lettre : ML Kit Digital Ink Recognition** (natif, gratuit,
  hors-ligne) — bien plus fiable qu'un template-matching maison.

---

## 🔒 Vie privée & sécurité

- **Local d'abord.** Wake word, reconnaissance de parole (pack FR hors-ligne),
  locuteur, TTS : tout peut tourner sans réseau. Seul le mode discussion (Gemini)
  sort, par nature.
- **Le wake word ne transmet RIEN avant déclenchement.** L'audio est analysé
  image par image en local puis jeté. *Preuve imparable :* couper le réseau (mode
  avion) — si « Hey Pégase » réagit, c'est que tout se passe sur l'appareil.
- **Point vert Android 12+** : indicateur visible dès que le micro est actif →
  transparence. Combiné au bouton coupe-micro, contrôle total.
- **Le mythe de l'écoute publicitaire** ne tient pas techniquement (gouffre à
  batterie/data, jamais démontré). Monopoliser le micro n'est PAS un rempart
  fiable (micro partageable sur Android moderne). Le vrai levier = **gérer les
  permissions micro** des apps.
- **Confirmation obligatoire sur les actions sensibles** (compta) : Pégase relit
  à voix haute et attend le « oui » avant toute action irréversible. Un LLM peut
  se tromper sur un chiffre.

### Limites dures (le bac à sable Android)
- ❌ Impossible d'accéder au **Voice Match de Google** ni aux **données d'autres
  apps** (isolation du sandbox).
- ❌ Écrire partout sur le disque sans le sélecteur système (scoped storage).
- ⚠️ Écoute continue = **service en avant-plan + notification permanente**
  imposés par Android.
- Actions système profondes → nécessiteraient root / device owner.

---

## 📁 Structure du projet (squelette actuel)

```
app/src/main/
├── AndroidManifest.xml           (HOME launcher + perms micro/internet)
├── java/com/pegasuscorp/orbe/
│   ├── MainActivity.java         (écran d'accueil, aiguillage voix)
│   ├── OrbView.java              (orbe + éventail, dessin custom + anim)
│   ├── AppDrawerActivity.java    (tiroir d'apps via PackageManager)
│   ├── voice/
│   │   ├── VoiceManager.java     (SpeechRecognizer + TextToSpeech)
│   │   ├── IntentParser.java     (interface — le cerveau commandes)
│   │   └── LocalKeywordParser.java (parsing local, 0 conso au repos)
│   └── chat/
│       ├── ChatBackend.java      (interface — le cerveau discussion)
│       ├── ConversationManager.java (état + mémoire du mode discussion)
│       └── GeminiChatBackend.java (API Gemini gratuite, appel réseau async)
```

> ⚠️ **Piège du thème** : les activités héritent d'`AppCompatActivity`, donc le
> thème DOIT être compatible AppCompat (`@style/Theme.Orbe`), sinon crash au
> lancement. En nommant le projet `Orbe`, Android Studio génère le bon thème.

---

## 🗺️ Feuille de route (l'ordre compte)

Chaque couche doit **tourner et me plaire** avant d'ajouter la suivante. Sinon on
débogue dix systèmes à la fois.

1. **Faire tourner la base** — orbe + éventail + tiroir sur le téléphone. ✅ *en cours*
2. **Habiller l'orbe** — teinte cyan, halo, orbites, **ailes**.
3. **Tracé de lettre** — ML Kit Digital Ink + rail A-Z.
4. **Écran de réglages** + bouton coupe-micro.
5. **Voix niveau 1** — commandes locales (déjà codées).
6. **Caractère de Pégase** — `systemInstruction` amical + phrases variées.
7. **Mode discussion** — clé Gemini.
8. **Voix Piper** — belle voix française.
9. **« Hey Pégase »** — openWakeWord (service arrière-plan).
10. **Locuteur** — ECAPA, « ne répond qu'à moi ».
11. *(lointain)* fond Fluid, action compta, mode Halo.

---

## 🐎 Récap — le petit Pégase

- 🐎 **Pégase** — nom + mot d'éveil, esprit PegasusCorp
- 🔵 Orbe cyan **ailée** et vivante (halo, orbites, respiration)
- ✍️ Tracé de lettre pour le tiroir
- 🎙️ « Hey Pégase » en local (openWakeWord)
- 🔐 Voix qui ne répond qu'à moi (ECAPA)
- 🗣️ Voix Piper française agréable
- 😊 Caractère amical et cohérent
- 💬 Mode discussion (Gemini gratuit)
- 🔇 Bouton coupe-micro (concerts)
- 🥩 Action sur la compta (avec confirmation)

*Gratuit, sans pub, local, à moi — et il prend son envol brique par brique.* ✨
