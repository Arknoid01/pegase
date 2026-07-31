# Mode copilote Pégase — Plan v4 : contrôle UI générique

**Statut :** Développement en cours (étape 1–6 amorcée) — **branche isolée, ne pas intégrer sans confirmation explicite**
**Branche :** `cursor/v4-copilot-ui-control-14e9`
**Base de code :** forkée depuis `cursor/v3-p6-location-drive-14e9` (v3 non mergée)
**Dépendance :** v3 P2–P6 (PR [#17](https://github.com/Arknoid01/pegase/pull/17)) — validation device requise avant merge v3, puis rebase v4 si besoin
**Portée :** centrée uniquement sur le mode copilote, indépendante du reste de la roadmap Pégase (wake, localisation, mode voiture)

> **Garde-fou intégration** — voir `docs/V4_INTEGRATION_POLICY.md` et `.cursor/rules/copilot-v4-isolation.mdc`.
> Toute tentative de merge, rebase sur `main`, cherry-pick vers v3, ou PR vers `main` **doit être confirmée par l'utilisateur** tant que la v3 n'est pas finalisée.

---

## Constat de départ

Aujourd'hui, le copilote peut cliquer sur **exactement un bouton, sur une seule appli** : le CC (sous-titres) de YouTube, via `YouTubeSubtitleAction`. Tout le reste du copilote (analyse écran, traduction, notifications) est de la lecture, pas de l'action.

### Ce qui existe déjà et sera réutilisé

| Brique | Rôle actuel | Réutilisation v4 |
|---|---|---|
| `A11yTreeExtractor` | Extrait texte + bounds + `clickable` dans un JSON statique (120 nœuds max) | Ajouter `viewIdResourceName` (lu par Android mais jamais persisté aujourd'hui) |
| `YouTubeSubtitleAction` | Recherche un bouton par mots-clés + clic, remonte jusqu'à 4 parents cliquables | Généraliser en matcher partagé, plus de liste de mots-clés figée |
| `CopilotActionTool` | Outil LLM `copilot_action`, une seule action (`youtube_subtitles`) | Étendre avec les nouveaux outils `ui_action`, `ui_explain`, `ui_search` |
| `ElementHighlightService` / `BoundsOverlayHelper` | Surlignage des éléments cliquables, canvas overlay partagé avec la traduction | Même canvas, appelé aussi pour le highlight avant action |
| `TranslationOverlayService` | Cloud texte seul → overlay positionné | Modèle repris pour `ui_explain` (généralisé à toute question, pas que la traduction) |
| Outil navigateur existant | Ouvre une URL | Réutilisé tel quel par `ui_search`, pas de clics Chrome simulés |
| Whitelist (`CopilotPrefs.getWhitelist()`) | Protège déjà l'analyse écran et l'action YouTube | Conservée comme garde-fou pour toutes les actions v4 |

### Limite technique clé

Le nœud `AccessibilityNodeInfo` original est `recycle()` immédiatement après extraction — impossible de garder un handle vivant entre le moment où le LLM voit le snapshot et le moment de l'action. Toute action refait donc un scan frais de l'arbre au moment d'agir, et matche sur les critères disponibles (texte, viewId, classe).

---

## Décision de matching : hybride (retenue)

- **Recherche par texte**, comme `YouTubeSubtitleAction` aujourd'hui (simple, déjà éprouvé)
- **+ `viewIdResourceName` ajouté au snapshot** pour fiabiliser le matching (champ déjà disponible côté API Android, juste jamais écrit dans le JSON aujourd'hui)
- Un seul matcher partagé, utilisé par tous les nouveaux outils (`ui_action`, `ui_explain`, `ui_search`)

Option écartée (id stable par nœud, façon B) : plus robuste en théorie mais plus de code et de surface de test pour un gain marginal tant que l'usage réel n'a pas montré de vrai problème d'ambiguïté.

---

## Outil 1 — `ui_action` (clic / remplissage / scroll)

Un seul chantier pour les trois actions, car elles partagent le même matcher — seule l'action Android finale change :

| Action | Méthode Android | Confirmation |
|---|---|---|
| `type(cible, texte)` | `performAction(ACTION_SET_TEXT)` | **Jamais** — réversible, rien n'est envoyé tant que rien n'est validé |
| `scroll(direction)` | `performAction(ACTION_SCROLL_FORWARD/BACKWARD)` | **Jamais** — inoffensif |
| `back()` | — | **Jamais** — inoffensif |
| `click(cible)` | `performAction(ACTION_CLICK)`, remonte aux parents cliquables si besoin (jusqu'à 4 niveaux, comme aujourd'hui) | **Conditionnelle** (voir ci-dessous) |

### Confirmation du clic — 3 niveaux

1. **Jamais** : rien de spécial détecté sur la cible
2. **Toujours** : la cible est identifiée comme un **lien** (classe/rôle spécifique dans l'arbre a11y) — évite d'atterrir sur un site non désiré
3. **Conditionnelle** : le texte ou le viewId de la cible matche une **denylist** de mots sensibles :
   `envoyer, send, supprimer, delete, effacer, payer, pay, acheter, buy, confirmer, valider, désactiver, disable`

La denylist se vérifie **sur la cible demandée à l'origine**, pas sur les parents traversés en cas de remontée.

### Portée

- Whitelist identique à celle de l'analyse écran (pas de contrôle hors liste blanche)
- Disponible à la voix et à l'écrit — même point d'entrée (`Channel.COPILOT` / `CopilotController.sendUserMessage()`), donc pas de logique séparée par canal
- **Highlight avant chaque action** (voir section dédiée), même sans confirmation

---

## Outil 2 — `ui_explain`

Répond à une question libre sur un élément désigné à l'écran (« c'est quoi ce mot », « explique-moi ça »), sur le modèle de `TranslationOverlayService` mais généralisé au-delà de la traduction.

**Flux :**
1. Repérer le texte/élément désigné via le matcher partagé
2. Priorité au **texte déjà connu du snapshot écran** (rapide, local, gratuit)
3. **Repli vision** (capture + `OpenRouterVisionClient`) si le contenu n'est pas du texte sélectionnable (image, icône, contenu de jeu)
4. Réponse affichée en overlay positionné, comme la traduction

**Jamais d'ouverture de page** — `ui_explain` répond toujours en overlay/bulle, ne déclenche jamais `ui_search`.

---

## Outil 3 — `ui_search`

Ouvre une recherche web sur un mot désigné à l'écran, via **l'outil navigateur existant** (pas de clics Chrome simulés — pas de multi-étapes).

**Flux :** repérer le mot (même matcher) → appeler l'outil navigateur avec une URL de recherche → nouvel onglet/page de résultats en une seule action.

### Distinction avec `ui_explain`

Pour éviter d'ouvrir une page à chaque question :
- « c'est quoi ce mot », « explique-moi ça » → `ui_explain` (jamais d'ouverture)
- « cherche ça », « fais une recherche sur ce mot » → `ui_search` (ouvre un onglet)

Le LLM distingue les deux par la formulation, comme il le fait déjà entre les autres outils Pégase.

---

## Feedback visuel — highlight systématique

`ElementHighlightService`/`BoundsOverlayHelper` (canvas déjà existant, partagé avec la traduction) est étendu pour afficher un cadre sur la cible **avant** toute action `ui_action`, pas seulement lors d'une confirmation — décision prise par prudence, quitte à perdre un peu de fluidité, pour :
- Vérifier visuellement que le matcher a trouvé le bon élément avant que l'action parte
- Servir de debug en usage réel (voir où le matcher se trompe)

Distinct du mode continu d'`ElementHighlightService` (debounce 4s, max 12 rectangles) : ici, un affichage ponctuel sur une seule cible précise, déclenché par l'action.

Statut **"Action en cours"** à ajouter dans la bulle, à côté des statuts existants (capture, analyse, réflexion, erreurs).

---

## Explicitement repoussé à un chantier ultérieur

- **Séquences multi-étapes** (plusieurs actions enchaînées sans reconfirmer à chaque fois) — le "saint graal" de l'autonomie, mais volontairement mis de côté tant que le clic/type/scroll simple n'a pas été validé en usage réel. Sans retour d'expérience sur les échecs du simple, débugger une chaîne de 3-4 actions serait ingérable (impossible de savoir laquelle a cassé).
- **Automatisation réseaux sociaux** — hors périmètre actuel (déjà différé dans la roadmap générale). Si un jour branché sur Facebook/Instagram : pas d'intention de créer un bot, ajouter un délai entre chaque action au pire des cas pour rester dans un usage ponctuel/manuel plutôt qu'un rythme régulier détectable comme automatisé.

---

## Ordre de développement suggéré

1. `A11yTreeExtractor` — ajouter `viewIdResourceName` au snapshot
2. Matcher générique partagé (généraliser `YouTubeSubtitleAction`)
3. Highlight ponctuel sur cible (branché sur le canvas existant)
4. `ui_action` : `type`/`scroll`/`back` (sans confirmation) puis `click` (avec les 3 niveaux de confirmation)
5. `ui_explain` (texte local puis repli vision)
6. `ui_search` (via l'outil navigateur existant)
7. Tests unitaires sur le modèle des tests copilote existants (matcher, denylist, confirmation)
8. Validation device en usage réel avant d'envisager le multi-étapes

---

## Avancement (branche `cursor/v4-copilot-ui-control-14e9`)

| Étape | Statut | Fichiers |
|-------|--------|----------|
| 1. `viewId` dans snapshot | ✅ | `A11yTreeExtractor`, `A11ySnapshot` |
| 2. Matcher générique | ✅ | `A11yUiMatcher` |
| 3. Highlight ponctuel | ✅ | `ElementHighlightService.showActionTarget` |
| 4. `ui_action` | ✅ | `A11yUiExecutor`, `UiActionTool`, `A11yClickPolicy` |
| 5. `ui_explain` | ✅ (local + overlay, vision repli à venir) | `UiExplainTool`, `UiExplainHelper` |
| 6. `ui_search` | ✅ | `UiSearchTool` → `WebSearchTool` |
| 7. Tests unitaires | ✅ | `A11yUiMatcherTest`, `A11yClickPolicyTest` |
| 8. Validation device | ⏳ | — |

---

## Références

| Document | Rôle |
|----------|------|
| `docs/copilot-etat.md` | État actuel copilote (v2 + v3) |
| `docs/V4_INTEGRATION_POLICY.md` | Politique d'isolation v3 / v4 |
| `.cursor/rules/copilot-v4-isolation.mdc` | Règle agent Cursor |
