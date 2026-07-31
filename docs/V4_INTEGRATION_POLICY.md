# Politique d'intégration v3 / v4 — NE PAS IGNORER

**Dernière mise à jour :** 31 juillet 2026

## Contexte

Deux chantiers copilote coexistent **en parallèle** :

| Chantier | Branche | PR | Statut |
|----------|---------|-----|--------|
| **v3** P2–P6 | `cursor/v3-p6-location-drive-14e9` | [#17](https://github.com/Arknoid01/pegase/pull/17) | En attente validation device — **pas encore mergé** |
| **v4** contrôle UI | `cursor/v4-copilot-ui-control-14e9` | (draft, base `main` interdit) | Développement isolé |

La v4 **dépend** du code v3 mais **ne doit pas être intégrée en même temps** que la v3.

---

## Règle absolue pour les agents Cursor / Cloud Agent

> **STOP — demander confirmation explicite à l'utilisateur** avant toute action listée ci-dessous.

### Actions interdites sans confirmation explicite

1. **Merger** `cursor/v4-copilot-ui-control-14e9` dans `main`
2. **Merger** ou **rebaser** la v4 sur / dans une branche v3 active
3. **Cherry-pick** des commits v4 vers `main`, `cursor/v3-*`, ou toute branche non-v4
4. **Créer ou mettre à jour une PR** v4 avec `base_branch: main` (sauf draft explicite demandé par l'utilisateur)
5. **Fusionner** v3 et v4 en une seule branche / un seul PR
6. **Pousser** des commits v4 sur une branche v3 (ou l'inverse)
7. **Marquer une PR v4 comme "ready for review"** ou retirer le statut draft sans accord

### Ce qui est autorisé sans confirmation

- Développer du code **uniquement** sur `cursor/v4-copilot-ui-control-14e9`
- Commits et push sur la branche v4
- PR **draft** v4 → branche v4 (ou base temporaire documentée), clairement étiquetée « NE PAS MERGER »
- Lire / documenter / tester sur la branche v4
- Travailler sur la v3 sur `cursor/v3-p6-location-drive-14e9` **sans toucher** à la v4

---

## Séquence d'intégration attendue (ordre imposé)

```
1. Finaliser + valider device la v3
2. Merger PR #17 (v3) dans main          ← confirmation utilisateur requise
3. Rebaser cursor/v4-copilot-ui-control-14e9 sur main
4. Valider device la v4
5. Merger la v4                          ← confirmation utilisateur requise
```

**Ne jamais inverser les étapes 2 et 5.**

---

## Comment demander la confirmation

Avant toute intégration, l'agent doit poser une question explicite du type :

> « La v3 n'est pas encore mergée. Veux-tu que j'intègre la v4 maintenant (merge/rebase/PR vers main) ? Réponds oui/non. »

Attendre une réponse **oui** non ambiguë. En cas de doute → **ne pas intégrer**.

---

## Fichiers sentinelles

Si ces fichiers existent, la politique est active :

- `docs/V4_INTEGRATION_POLICY.md` (ce fichier)
- `.cursor/rules/copilot-v4-isolation.mdc`
- `docs/copilot-v4-plan.md` (en-tête « branche isolée »)
