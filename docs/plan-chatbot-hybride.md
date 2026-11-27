# Plan — Chatbot hybride Pégase (chatbot + Gemma 4 E2B)

*Rédigé le 2026-11-25 avec Yannick. Statut : plan validé dans les grandes lignes, rien n'est codé.*

## 1. Pourquoi

- Sur le Nothing, on garde **Gemma 4 E2B (LiteRT, GPU)** : rapide (≈ 9,8 jetons/s), RAM acceptable. L'E4B local ne tient pas (Orbe tué par manque de mémoire, test du 2026-11-25) ; le Nord reste une option, pas la base.
- Les défauts de l'E2B viennent de ce qu'on lui demande de **trouver** la réponse : calcul faux (« 17 moutons »), vie inventée (« quand je codais une boucle infinie »), souvenirs placés de force (« ton anniversaire le 2 août » dans une histoire).
- Idée : un **chatbot classique** (règles + banque de réponses + mémoire rangée) trouve le fond ; l'E2B ne fait que **le dire**, avec le ton de Pégase. Le chatbot est le cerveau et la mémoire, l'E2B est la voix.

## 2. Principes

1. **Le fond vient du chatbot, des outils et de la mémoire ; le ton vient de l'E2B.** L'E2B ne doit rien ajouter.
2. **Savoir quand on ne sait pas** : sous un seuil de confiance, on passe la main (E2B seul, Nord ou cloud si autorisé).
3. **Rythme régulier** : chaque type de réponse a une durée prévisible, avec une réflexion affichée qui montre les **vraies** étapes du script.
4. **Tout est mesuré** : on rejoue les phrases réelles du journal avant et après chaque changement.

## 3. Le chemin d'un message

```
message
  │
  ├─ 1. Outil instantané ? (calcul, minuteur, lampe…) ──► réponse directe, modèle de phrase, sans réflexion
  │
  ├─ 2. Outil variable ? (météo, web, F1…) ──► réflexion à durée estimée ──► résultat ──► mise en forme
  │
  ├─ 3. Règles (formes de phrases connues, énigmes, questions sur Pégase)
  ├─ 4. Banque de réponses (MiniLM : question la plus proche)
  ├─ 5. Mémoire rangée (fiches, humeur, sujet en cours)
  │
  └─ 6. Décision selon la confiance :
        forte   ──► réponse du chatbot (variante non répétée)
        moyenne ──► E2B reformule à partir d'1–2 idées de réponse du chatbot
        faible  ──► E2B seul (comportement actuel) — ou Nord/cloud si joignable et autorisé
```

## 4. Les composants

### 4.1 Règles
- Fichier de règles lisible (JSON ou YAML) : forme de phrase → intention → plusieurs réponses possibles, avec des emplacements à remplir (`{prenom}`, `{projet}`, `{heure}`).
- Concepts (« animal » = chien, chat…) et tolérance aux fautes, aux accents et aux mots coupés par le vocal.
- Reprend et étend le routeur actuel (`quickParse`, regex) au lieu de le doubler.
- Énigmes et calculs : passés à l'outil `calculator` ou à une règle dédiée (réponse exacte), jamais au modèle.

### 4.2 Banque de réponses
- Paires « question type → réponses » ; plusieurs formulations de question par entrée.
- Recherche par sens avec **MiniLM** (`paraphrase-multilingual-MiniLM-L12`, déjà sur le téléphone pour la recherche privée).
- Empreintes de la banque **calculées à l'avance sur PC**, livrées avec l'appli (int8 si possible) ; sur le téléphone, seule la phrase de l'utilisateur est encodée.
- Anti-répétition : on mémorise les dernières réponses servies et on tourne entre les variantes.

### 4.3 Mémoire rangée
- Des **fiches** plutôt que des bouts de texte : projets en cours (état, prochaine étape), personnes proches, animaux, habitudes, humeur récente, sujet de la conversation en cours, choses que Yannick a dit vouloir faire.
- Alimentation : à partir des souvenirs existants (`permanent.json`, `entities.json`), puis mise à jour pendant les conversations (règles + validation).
- Usage : relances justes (« Alors, le Ranch, tu as fini les achats ? »), continuité d'un jour à l'autre. **Un souvenir n'est utilisé que si la question s'y rapporte** (« tu te souviens », nom de projet, « la dernière fois »…).

### 4.4 Gemma 4 E2B (LiteRT)
- **Zone moyenne** : reçoit la phrase de l'utilisateur et 1–2 idées de réponse ; consigne courte et stricte (« reformule avec le ton de Pégase, n'ajoute aucun fait »), sortie courte, température basse (≈ 0,3).
- **Zone faible** : comportement actuel, avec une personnalité **allégée** pour l'E2B (3–4 règles : complice, phrases courtes, jamais de vécu inventé).
- **Garde-fou** : les chiffres et les noms propres de l'idée de réponse doivent se retrouver dans la reformulation ; sinon, on sert la version du chatbot.
- Préchargement à l'ouverture de la discussion, pour réduire le premier message lent (≈ 24 s mesurés ce soir à froid, dont ≈ 10 s de génération).

## 5. Réflexion affichée à durée fixe

- **Étapes réelles** du script, affichées à un rythme lisible : « Je reconnais : une question sur un de tes projets » → « Je retrouve ma fiche Ranch » → « Je prépare ma réponse… ».
- **Durée fixe par type de réponse** (valeurs de départ, à calibrer) :

| Type | Durée visée |
|---|---|
| Outil instantané | aucune (immédiat) |
| Chatbot direct | ≈ 1,5 s |
| Reformulation E2B | ≈ 3 s |
| E2B seul | ≈ 4–5 s |
| Outil variable | estimée par outil (météo stable ; web 5–25 s) |

- **Modèle plus rapide que prévu** : ses mots sont gardés en réserve puis affichés à la fin de la durée, à rythme régulier.
- **Modèle plus lent** : une étape supplémentaire apparaît (« je peaufine… »), jamais d'écran vide.
- **Calibration automatique** : durée = temps tenu 9 fois sur 10 sur les vrais messages (mesures `LocalChat`), recalculée régulièrement ; estimation tenant compte de la longueur du message envoyé au modèle et de son état (chargé ou à froid).
- Une fois la réponse affichée, les étapes se replient dans le bouton « ⋯ » (sources/outils, déjà existant) ; l'orbe reste verte pendant la réflexion.

## 6. Outils : instantanés ou variables

*Classement d'après le rôle des outils ; à vérifier dans le code de chacun (« ? »).*

**Instantanés (sans réflexion)** : `calculator`, `timer`, `alarm`, `flashlight`, `volume`, `batterie`, `connectivity`, `device`, `settings`, `clipboard`, `open_app`, `open_interface`, `notepad`, `agenda`, `contacts`, `notifications`, `app_usage`, `memory`, `call`, `sms` (envoi), `share`, `navigation`, `spotify` (?).

**Variables (réflexion à durée estimée)** : `weather`, `search`, `web_search`, `web_read`, `wikipedia`, `wikidata`, `news`, `sports`, `f1`, `nasa`, `youtube`, `email`, `mails`, `messageries` (?), `brief`, `read_image`, `screen_capture`, `ui_explain`, `ui_search`, `ui_loop`, `copilot_action`, `pc_shell`, `orion_*`, `git_commit`, `create_file`, `files` (?).

- Les confirmations des actions sensibles (`SensitiveTools` : appel, SMS…) sont conservées.
- Les résultats des outils instantanés sont mis en forme par des **modèles de phrases** du chatbot, pas par l'E2B, pour rester immédiats.

## 7. Le contenu

- **Amorce** : intentions de bavardage Hexastack `hexabot-smalltalk` (FR, CC0) ; morceaux de PersonaChat français (XPersona, version relue) pour le naturel.
- **Le gros de la banque, généré par IA** (Claude, Composer, Gemma) : beaucoup de formulations de questions et plusieurs réponses dans le ton de Pégase, à partir du journal réel, de la personnalité et des fiches. **Yannick trie.**
- **Licences** : exclure le corpus Claire (non commercial) si Pégase devient un produit de la société ; CC0 et contenu généré par nous : OK.
- Pas de base AIML ou ChatScript française réutilisable (recherche du 2026-11-25) : moteur maison.

## 8. Boucle d'amélioration

- Chaque phrase où le chatbot passe la main est notée (avec la réponse donnée par l'E2B ou le cloud).
- Revue régulière par Yannick : ajouter une réponse en quelques secondes depuis un **éditeur simple** (sur PC d'abord, éventuellement dans l'appli).
- Les réponses cloud de référence du journal (`cloud_reference`) servent de suggestions.

## 9. Mesures et critères de réussite

- **Banc** : rejouer les phrases du journal (214 tours au 2026-11-25) + jeu de tests du harnais `orbe-llm-cadrage` **adapté à l'E2B**.
- **Couverture** : part des phrases traitées sans E2B seul.
- **Justesse du choix** : part des réponses du chatbot jugées bonnes par Yannick (le LLM ne rattrape pas un mauvais choix).
- **Temps** : chatbot ≈ 20–50 ms (estimé) ; respect des durées fixes 9 fois sur 10.
- **RAM** : chatbot ≈ 300–400 Mo avec MiniLM chargé (estimé) ; cohabitation avec l'E2B vérifiée sur le Nothing.

## 10. Étapes

| # | Étape | Contenu | Livrable |
|---|---|---|---|
| 0 | Analyse du journal | Classer les phrases réelles (salutations, journée/humeur, commandes, projets, culture, énigmes…) | Estimation de la couverture possible |
| 1 | Prototype PC (Python) | Règles + banque MiniLM + mémoire rangée + seuils ; rejeu du journal | Scores couverture/justesse/temps |
| 2 | Banque v1 | Amorce Hexastack/PersonaChat + génération IA + tri par Yannick | Banque versionnée + empreintes |
| 3 | Portage Android | Moteur en Java dans Orbe, branché avant le LLM ; outils instantanés directs | APK testé sur le Nothing (temps, RAM) |
| 4 | Reformulation E2B | Zone moyenne, consigne stricte, garde-fou chiffres/noms, personnalité allégée | Comparaison avant/après au banc |
| 5 | Réflexion à durée fixe | Étapes réelles, réserve de mots, calibration automatique | Ressenti Yannick + respect des durées |
| 6 | Boucle et éditeur | Journal des passages de main, éditeur de réponses | Couverture qui progresse |

## 11. Questions encore ouvertes

1. **Rôle du chatbot** : bavardage et personnalité seulement, ou aussi soutien (journée, émotions), aide sur Orbe, culture… ?
2. **Contenu** : Yannick écrit, relit, ou trie une banque générée ?
3. **Style** : « complice et sarcastique avec bienveillance » (profil actuel) ou autre ?
4. **Étapes affichées** : à chaque réponse passant par l'E2B, ou seulement au-delà d'une certaine durée ?

## Annexe — état au 2026-11-25

- Étiquette du vrai moteur sous les réponses : `LocalLlmServer.engineLabel()` (« Nord · », « LiteRT · », « llama · »), **non commitée**.
- Téléphone : LiteRT E2B + Nord configurés (`local_llm.xml`) ; sauvegarde `files/local_llm.xml.bak-e4b`.
- Le GGUF E4B (5 Go) sur le Nothing peut être supprimé.

## Annexe — Étape 0 : classement du journal (2026-11-25)

216 tours du 22 au 25/11, dont 13 tests techniques → **203 tours réels** (≈ 140 phrases distinctes). Classement à la main.

| Catégorie | Tours | Part | Qui s'en charge |
|---|---|---|---|
| A. Outil instantané (minuteur, note, agenda, SMS, réveil, calcul) | 60 | 30 % | Chatbot (routeur existant) |
| D. Salutations / sur Pégase | 35 | 17 % | Chatbot |
| B. Outil variable (météo, web, wiki, F1, brief, écran) | 33 | 16 % | Outil + mise en forme ; relances (« on a des explications ? ») → E2B |
| F. Vie perso / opinions / émotions | 31 | 15 % | Banque + E2B (reformulation ou seul) |
| G. Énigme / raisonnement | 17 | 8 % | Calcul ou règle ; raisonnement nouveau → E2B |
| C. Suivi court (oui, laisse tomber, la suite…) | 13 | 6 % | Chatbot (état de conversation) |
| E. Souvenirs | 7 | 3 % | Mémoire rangée |
| H. Histoire / créatif | 7 | 3 % | E2B (ou petite banque d'histoires) |

**Estimation** : ≈ 64 % des tours traitables par le chatbot seul (A + C + D + E + G connus), ≈ 16 % par les outils avec mise en forme, ≈ 20 % restent pour l'E2B (F, H, relances, raisonnement nouveau).

**Biais** : journal de 4 jours dominé par les tests de Yannick (moutons ×17, minuteurs, agenda répétés). La catégorie F (vraie conversation) est sous-représentée par rapport à un usage quotidien normal : c'est elle qui décidera du ressenti.

**Correction de Yannick (2026-11-25)** : la conversation (catégorie F) est en réalité **son usage principal**. Conséquences :
- le cœur du système est la **zone moyenne** (banque + fiches → E2B reformule), pas la réponse directe du chatbot ;
- la banque doit être organisée par **thèmes de sa vie** (travail, projets dev, F1, jeux, proches, animaux, humeur et fatigue, travail) et par **gestes de conversation** (accueillir une émotion, relancer, rappeler une fiche, donner l'avis de Pégase) ;
- le journal de 4 jours ne suffit pas comme banc : il faut un échantillon de vraies conversations (historique des sessions du téléphone et du PC).
