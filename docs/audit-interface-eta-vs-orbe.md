# Audit de l'interface : Eta / Orbe-Pégase

**Date :** 2026-09-20 · complète `docs/audit-eta-vs-orbe.md` (moteur, sécurité, code).

**Sources.** Code des écrans des deux applications ; captures d'écran du README d'Eta (interface en chinois, traduite par mes soins) ; deux captures d'Orbe du 30 juillet (`docs/device-test-chat2.png`, `device-test-tools-expanded.png`), donc **antérieures** aux derniers chantiers. Trois autres captures d'Orbe (`device-v3-*.png`) sont corrompues (fichiers UTF-16, illisibles).
**Limite.** Je n'ai lancé aucune des deux interfaces ; les constats de comportement viennent du code. **[à confirmer]** signale ce que je n'ai pas pu vérifier.

---

## 1. Deux interfaces pour deux produits

| | Eta | Orbe |
|---|---|---|
| **Nature** | Une application de chat/agent. L'écran d'accueil est une conversation. | Un **launcher** : l'orbe, l'horloge, le tiroir d'applis, les widgets ; le chat est un onglet d'une interface secondaire. |
| **Technique** | Kotlin, Jetpack Compose, bibliothèque de composants Miuix (style Xiaomi). 31 écrans/routes. | Java, vues construites en code, 5 onglets (💬 📝 🖥️ 🔧 📁) + une trentaine d'activités. |
| **Identité** | Sobre, clair/sombre, tout au service du texte. | Forte : orbe cyan ailée, fond « Fluid », thèmes d'orbe, jeu d'icônes. |
| **Modalité principale** | Texte (pas de voix). | Voix (réveil, appui-parler, voix sur écran verrouillé), texte en second. |

Ce ne sont pas les mêmes défis : Eta optimise la lisibilité d'un long échange avec un agent, Orbe optimise l'accès immédiat depuis l'écran d'accueil et la voix.

---

## 2. Le chat

Capture Eta : bulle utilisateur grise à droite ; sous elle une carte repliable « 1 étape terminée » ; dépliée, elle montre le raisonnement (« réflexion terminée · 9 s »), l'outil appelé (« lire les notifications », point vert « terminé ») et l'analyse suivante ; la réponse en Markdown (liste numérotée) avec un bouton copier ; un bouton « aller en bas » ; une barre de saisie en bulle (« + » pour joindre, puce « Réflexion », flèche d'envoi).

| Fonction | Eta | Orbe | Écart |
|---|---|---|---|
| **Historique des outils dans le fil** | Carte repliable **conservée** : outils, statut, durée, raisonnement | `ThinkingView` : ligne éphémère (« Pégase réfléchit… » + statut des outils) qui **disparaît quand la réponse arrive** [vérifié dans le code] | **Grand écart** : après coup, on ne voit plus ce que Pégase a fait |
| **Raisonnement visible** | Affiché, repliable, avec durée | Des `ReasoningCard` sont stockées (`ReasoningStore`) pour le diagnostic ; leur affichage dans le chat n'a pas été vérifié **[à confirmer]** | Probable manque |
| **Actions sur un message** | Copier la réponse / le code / la commande, modifier, régénérer, supprimer | **Aucune trouvée** (pas d'appui long ni de bouton copier dans le chat) | Grand écart |
| **Rendu Markdown** | Streaming Markdown, blocs de code copiables | Bulles de texte simple ; Markwon n'est utilisé que dans l'onglet Orion **[à confirmer côté adaptateur]** | Écart |
| **Arrêter une réponse** | La flèche d'envoi devient « stop » | Idem depuis `7140131` (⏹) | Comblé |
| **Consigne pendant une réponse** | File d'attente de consignes | Texte saisi pendant un tour → consigne (`7140131`), mais **pas de bulle** dans le fil | Comblé côté moteur, incomplet côté affichage |
| **Pièces jointes** | Bouton « + » : fichiers, images | Menu ⋮ → « Analyser une image », « Analyser un PDF », « Joindre / contextes .md » | Orbe couvre autant, mais caché dans un menu |
| **Choix du modèle** | Dans la barre du chat (modèle, effort de raisonnement) | Réglages (clés API) ; rotation automatique des fournisseurs | Choix d'Orbe : moins de contrôle, moins d'encombrement |
| **Jauge de contexte** | Utilisation du contexte + action « compacter » | Aucune ; la compaction (`287bd69`) est invisible | Manque |
| **Liste des conversations** | Panneau latéral, recherche, export Markdown, suppression | Une discussion en cours, « Nouvelle discussion », export ; résumés de sessions dans Mémoire → Sessions | Écart |
| **Statut d'un run en cours** | Carte d'état sur l'accueil avec bouton stop, notification | Orbe flottante et bulle copilote | Approches différentes |
| **Bouton « aller en bas »** | Oui | Non trouvé **[à confirmer]** | Détail |

---

## 3. Outils, extensions, réglages

Capture Eta « capacités des outils » : grille de cartes à icônes colorées, groupées (« Écran et contrôles » : observer l'écran, toucher un élément, toucher une zone, appui long, glisser, défiler ; « Texte et presse-papiers » : saisir, remplacer, effacer, coller), une ligne de description chacune. Selon le code, chaque carte indique aussi **ce qui manque pour l'utiliser** (accessibilité, accès aux données d'usage, notifications, localisation, Root) et un filtre bascule entre « cet appareil » et « toutes les capacités ».

Capture Orbe (30 juillet) : fond sombre ; en tête « État : ✓ Spotify · ✓ Recherche · ✓ Wiki · ✓ Actus · ○ Sports · Apprentissage actif » ; catégories repliables avec compteur (Musique 6, Info & web 14, Diagnostic 6, Productivité 12, Téléphone 34…) ; chaque outil est une phrase d'exemple avec ▶ (lancer) et ⋮ (favori).

| Fonction | Eta | Orbe |
|---|---|---|
| **Découvrir les outils** | Cartes avec description | **Phrases à dire** avec lancement en un appui : très adapté à la voix, meilleur qu'Eta sur ce point |
| **Disponibilité d'un outil** | Prérequis affiché par carte (permission manquante…) | Statut global par service seulement (✓/○) ; pas de prérequis par outil |
| **Activer/désactiver** | Par outil ou famille [documenté], par skill (interrupteur) | Pas d'interrupteur d'outil |
| **Skills** | Écran : liste, interrupteurs, suppression, import ZIP, installation GitHub | Une ligne avec le compteur + import ZIP (ajouté) ; pas de liste ni de suppression |
| **MCP** | Écrans liste, ajout, détail, actualisation, états « connexion… » | Un écran d'ajout et d'activation par outil (ajouté) ; boîtes de dialogue |
| **Permissions** | Écran « santé des permissions » et carte récapitulative | Demandes ponctuelles au moment du besoin (`PermissionFlow`) ; pas de tableau de bord |
| **Fournisseurs de modèles** | Liste, détail, en-têtes, onglet des modèles | Écran de clés API |
| **Mémoire** | Simple (`MEMORY.md`) | **Bien plus riche** : souvenirs, profil, sessions, Atlas des entités, constellation 3D |
| **Personnalisation** | Thème et apparence | **Bien plus riche** : thèmes d'orbe, fond Fluid, paquets d'icônes, widgets, fonds d'écran |
| **Voix** | Aucune | Réveil, appui-parler, enrôlement du locuteur, corrections vocales |
| **Launcher** | Non | Tiroir d'applis, widgets, raccourcis |

---

## 4. Qualité de l'interface

| Sujet | Eta | Orbe |
|---|---|---|
| **Cohérence** | Composants Miuix partagés (`ListEmptyState`, `SectionHeader`, cartes, dialogues) | Beaucoup de vues construites à la main. J'en ai moi-même ajouté trois entrées quasi identiques (Skills, MCP, et l'existante Drive), copiées de ~45 lignes chacune. |
| **Textes** | Ressources `strings.xml` (chinois + anglais) | Textes français en dur dans le code Java (ex. « Envoi en cours… », « Consigne ajoutée… ») ; un `strings.xml` existe mais peu utilisé pour l'interface récente |
| **Navigation** | Navigateur typé (`AppRoute`), retour prédictif | Activités + onglets ; **[à confirmer]** pour le retour prédictif |
| **États vides** | Composant dédié | Variable selon l'écran |
| **Aperçu / tests d'interface** | Tests sur les états d'interface (`ui/model`, `ui/app`) | Aucun test d'interface ; une seule classe instrumentée |
| **Accessibilité** | Descriptions de contenu sur les icônes | Partielle : description sur les boutons de l'onglet chat, pas systématique |

---

## 5. Ce qu'Orbe devrait reprendre, par ordre d'intérêt

| # | Amélioration | Effort | Pourquoi |
|---|---|---|---|
| 1 | **Carte « N outils utilisés » repliable dans le fil**, conservée après la réponse (outils, statut, durée) | 1 j | Les événements `onToolStart/onToolEnd` existent déjà ; avec les lots et l'enchaînement de 8 outils, on ne voit plus rien de ce que fait Pégase |
| 2 | **Copier sur les réponses** (bouton et appui long) + rendu Markdown des bulles (Markwon est déjà en dépendance) | 1 j | Manque le plus concret au quotidien |
| 3 | **Prérequis par outil** (« autorise l'accès aux photos », « accès données d'usage ») avec un appui qui ouvre le bon réglage | 1-2 j | Les erreurs d'outil renvoient déjà ces messages au modèle ; l'utilisateur ne les voit qu'au moment de l'échec |
| 4 | **Écran Skills complet** : liste, activer/désactiver, supprimer, aperçu du SKILL.md | 0,5 j | Seul le compteur est visible aujourd'hui |
| 5 | **Bulle de consigne** dans le fil quand on l'envoie en cours de réponse | 2 h | Sinon on ne sait pas si elle a été prise en compte |
| 6 | **Jauge de contexte** et mention discrète « contexte abrégé » | 0,5 j | Rend visible la compaction |
| 7 | **Liste des conversations + recherche** | 2-3 j | Les résumés de sessions existent déjà en mémoire |
| 8 | **Factoriser les entrées de l'onglet Outils** (une seule fonction `addEntryRow`) | 1 h | Supprime la duplication que j'ai introduite |
| 9 | **Interrupteurs d'outils** (désactiver SMS ou l'accès aux fichiers) | 1 j | Reprend le contrôle utilisateur d'Eta |

## 6. Ce qu'il ne faut pas copier
- Le design Miuix : l'identité d'Orbe (orbe, fond Fluid, thèmes) est ce qui la distingue.
- L'accueil-chat : Orbe est un launcher, l'accès direct à l'orbe et à la voix est le point fort.
- La navigation par écrans en pile : les onglets d'Orbe conviennent à un usage rapide.

## 7. Limites
- Captures d'Orbe anciennes (30 juillet) : l'interface a changé depuis (Skills, MCP, bouton ⏹, menu Pégase, raccourcis Termux).
- Aucune mesure de fluidité, de temps de démarrage ni d'accessibilité réelle.
- La comparaison des textes d'Eta repose sur ma traduction du chinois.
