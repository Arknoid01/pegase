# Checklist régression Pégase (~5 min)

À valider avant chaque `installDebug` sur appareil.

## Accueil / tactile
- [ ] Tap message souligné en bas → **Discussion texte** (pas le drawer)
- [ ] 5 taps rapides sur le message → jamais de drawer par erreur
- [ ] Swipe vers le haut → drawer s'ouvre
- [ ] Tap orbe → éventail raccourcis
- [ ] Double-tap orbe → Bureau
- [ ] Long-press orbe → chat vocal
- [ ] Retour accueil après ouverture app depuis raccourci → touches OK

## Discussion texte
- [ ] Clavier ne cache pas le champ de saisie
- [ ] Envoi message → réponse affichée (mots séparés, pas de markdown)
- [ ] JSON outil tronqué → message d'erreur clair (pas de JSON brut affiché)
- [ ] Retour accueil → pas de crash, wake reprend si activé

## Bureau
- [ ] Écrire `50+30` → bon résultat
- [ ] « recalcule » / « c'était 50 pas 5 » → correction OK
- [ ] Micro push-to-talk → transcription bureau
- [ ] Fermer bureau → wake reprend sur accueil

## Voix / micro
- [ ] Long-press orbe → salutation + écoute
- [ ] Wake « Pégase » (si activé) → réponse depuis accueil
- [ ] Enchaîner accueil → discussion → bureau → voix sans redémarrer l'app
- [ ] Micro coupé (tiroir) → wake et voix respectent le mute

## Mémoire
- [ ] Fin discussion vocale → toast « Mémoire mise à jour » (si session non vide)
- [ ] Onglet Discussion → bandeau « Pégase se souvient : … » visible
- [ ] Mémoire → Profil → modifier « Personnalité de Pégase » → nouvelle discussion reflète le ton
