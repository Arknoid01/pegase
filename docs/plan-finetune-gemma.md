# Plan — ré-entraîner Gemma 4 E2B pour Pégase (LoRA)

Écrit le 2026-11-26. Statut : plan, rien de lancé. Données personnelles : JAMAIS dans ce dépôt.

## But

Garder Gemma 4 E2B (LiteRT, GPU du Nothing, ≈ 9,8 tok/s) et corriger par un petit ré-entraînement
ce que les invites et les garde-fous ne corrigent pas. On ne cherche PAS à lui ajouter des
connaissances (le web et les outils s'en chargent).

Cibles, tirées du journal du 2026-11-26 :

1. **Ton** : tics (« vrai casse-tête », « Salut Yannick » en cours de fil, question finale
   systématique), troisième personne (« la tolérance au risque de Yannick »), recopie de la consigne.
2. **Consignes** : « trois idées », « ne me pose aucune question », « ne modifie aucun chiffre »,
   longueur demandée.
3. **Vérification** : relire les contraintes avant de conclure (réserve de 1 000 €, budget,
   « tous sauf 9 »), refaire le calcul quand il y a des nombres.
4. **Honnêteté** : « je ne sais pas » plutôt que deviner ; ne rien ajouter aux résultats d'outil ;
   pas de vie inventée.
5. (option) **Réflexion courte** : apprendre à raisonner en quelques lignes plutôt qu'en 1 000 jetons,
   si le banc « réflexion » du 26/11 montre que la réflexion aide.

## Étape 0 — essai de bout en bout (avant tout gros travail)

Objectif : prouver que la chaîne complète marche, pas améliorer le modèle.

- 50 exemples qui corrigent UN tic visible (ex. « vrai casse-tête » / question finale).
- LoRA avec Unsloth (guide officiel Gemma 4) sur un pod RunPod 24 Go (L4 ou 4090) : la RTX 2060
  (6 Go) ne suffit pas, il faut 8-10 Go pour E2B en LoRA.
- Fusion → safetensors → `litert-torch export_hf` (guide Google « deploy from custom safetensors »,
  option `--externalize_embedder`) → `model.litertlm`.
- Pousser dans `Android/data/com.pegasuscorp.orbe/files/models/`, charger dans Orbe à la place du
  modèle d'origine (garder l'original pour revenir en arrière).
- Critères : se charge sur GPU, vitesse ≈ celle d'origine, tic disparu, rien d'autre de cassé.
- Aussi exporter en GGUF pour le banc llama.cpp (comparaison directe avec les bancs existants).
- Budget : ≈ 2-5 €, une soirée. Supprimer le pod à la fin.

Pièges connus : les couches à KV partagé de Gemma 4 (indices 15-34) n'ont pas de k_proj/v_proj,
PEFT les ignore sans prévenir → viser aussi les couches MLP. Vérifier que `export_hf` accepte un
dossier local (l'exemple de Google pointe sur le modèle de base).

## Étape 1 — jeu de données (le vrai travail)

Volume visé : 1 500 à 3 000 exemples, en français oral, tous au format d'invite RÉEL d'Orbe
(même consigne système que `ChatbotVoice` / `taskPrompt` / persona), sinon le modèle apprend
sur des invites qu'il ne verra jamais.

| Source | Part | Rôle |
|---|---|---|
| Fabriqué par un gros modèle ouvert (gpt-oss-120b via OpenRouter, ou Gemma 26B sur le PC via Ollama) | ~60 % | Ton Pégase, consignes, vérification, usage des résultats d'outil |
| Public français libre (Luth-2-Post-Training-SFT CC-BY 4.0, french_instruct MIT…) | ~25 % | Lest : ne pas oublier ce qu'il savait |
| « Je ne sais pas » / refus d'inventer (questions sans réponse possible, outils vides) | ~10 % | Honnêteté |
| Tours réels de Yannick (journal Orbe, export ChatGPT à venir) — côté question seulement, réponses refaites | ~5-15 % | Coller à son usage et son style |

- Professeur : pas de sortie OpenAI/Anthropic (leurs conditions interdisent d'entraîner un autre
  modèle avec). Modèles à licence ouverte seulement.
- Tri : chaque réponse notée (juge automatique + échantillon relu par Yannick) ; jeter ce qui a un
  tic, une invention ou un mauvais calcul.
- Licences « non commercial » (Claire…) : à éviter si Orbe devient un produit de la société.
- Données perso : générées et stockées hors dépôt (`~/.pegase/finetune/`, chmod 600) ; pod effacé.

## Étape 2 — évaluation (fixée AVANT l'entraînement)

- Jeu de test jamais vu à l'entraînement : les 27 problèmes du 26/11 + 12 conversations du 23/11
  (`/mnt/jeux/bench-models/banc`), + de NOUVEAUX pièges de lecture (variantes « tous sauf »
  inédites) écrits après coup, pour détecter le par-cœur.
- Non-régression : banc commandes (format compact GBNF, 12/12 aujourd'hui), banc inventions
  `CHATBOT_HALLU`, vitesse.
- Vote à l'aveugle de Yannick (page comme le 23/11) : d'origine contre ré-entraîné.

## Étape 3 — entraînement

- Unsloth, LoRA r 16 (alpha 16-32), attention + MLP, perte sur les réponses seulement,
  2-3 passes, lr ≈ 2e-4, contexte 2 048. Garder 10 % des données pour la validation.
- Plusieurs essais courts plutôt qu'un long ; arrêter si la validation remonte.
- Coût attendu : 1-3 h de GPU par essai → quelques euros.

## Étape 4 — mise en service

- Export `.litertlm` (+ GGUF), poussé sur le Nothing.
- Lié au futur sélecteur de modèle (réglages) et au bouton « Approfondir » : le modèle d'origine
  reste disponible.
- Garder la recette (scripts, versions, graine) dans un dépôt séparé SANS les données.


## Logique (décidé 2026-11-26) — V2, après validation du ton seul

1. **Vérification courte visible** sur les demandes de travail : une ligne « Contraintes : … → … »
   avant la conclusion (réserve, budget, délais). Sans mode réflexion, compatible LiteRT.
2. **Pièges de lecture** (« tous sauf », négations, fausses prémisses) : exemples variés ; test
   OBLIGATOIRE sur des pièges inédits écrits après l'entraînement.
3. Plus tard : **réflexion courte en français** (3-5 lignes) pour le bouton « Approfondir ».

Mélange V2 : ton + 20-30 % de logique, logique seulement sous `taskPrompt()` (jamais en discussion).
La calculatrice reste l'outil pour les chiffres.

## Questions ouvertes pour Yannick

1. Ordre des cibles (ton d'abord ? vérification d'abord ?).
2. Professeur : gpt-oss-120b via OpenRouter (rapide, quelques €) ou Gemma 26B sur le PC (gratuit, lent) ?
3. Utiliser ses propres conversations, et jusqu'où ?
4. Réflexion courte : à décider après le banc « réflexion » du 26/11.

Sources : guide Unsloth Gemma 4 (unsloth.ai/docs/models/gemma-4/train) ;
guide Google LiteRT-LM Gemma 4 (developers.google.com/edge/litert-lm/models/gemma-4).
