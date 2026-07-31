# Instructions agents — projet Pégase (Orbe)

## Branches parallèles v3 / v4 (copilote)

La **v3** et la **v4** copilote sont développées en parallèle. La v3 n'est pas encore mergée sur `main`.

| Version | Branche | Intégration |
|---------|---------|-------------|
| v3 P2–P6 | `cursor/v3-p6-location-drive-14e9` | PR #17 — en attente validation device |
| v4 UI | `cursor/v4-copilot-ui-control-14e9` | **Isolée — ne pas merger sans accord** |

### Garde-fou obligatoire

Avant toute tentative d'intégration de la v4 (merge, rebase sur `main`, cherry-pick, PR vers `main`, fusion avec v3) :

1. **Demander confirmation explicite** à l'utilisateur
2. **Ne pas procéder** sans un « oui » clair

Détails : `docs/V4_INTEGRATION_POLICY.md` et `.cursor/rules/copilot-v4-isolation.mdc`.

## Développement v4

- Travailler uniquement sur `cursor/v4-copilot-ui-control-14e9`
- Plan : `docs/copilot-v4-plan.md`
- État copilote actuel : `docs/copilot-etat.md`
