"""Construction prompt système — aligné PegasePrompt + ContextBuilder (simplifié)."""

from __future__ import annotations

from datetime import datetime
from typing import Any
from zoneinfo import ZoneInfo

from pegase_desktop.llm.config import UserProfile
from pegase_desktop.llm.personality import prompt_block
from pegase_desktop.llm.prompt_budget import BudgetLevel, memory_max_chars, personality_max_chars
from pegase_desktop.mode_store import mode_prompt_line


def build_system_prompt(
    profile: UserProfile,
    memories: list[dict[str, Any]],
    notes: list[dict[str, Any]],
    budget: BudgetLevel = BudgetLevel.NORMAL,
    *,
    user_message: str = "",
    context_pointer: str = "",
) -> str:
    from pegase_desktop.atlas import build_atlas_block

    personality = prompt_block(personality_max_chars(budget))
    mem_block = _memories_block(memories, memory_max_chars(budget))
    notes_block = _notes_block(notes)
    atlas_block = build_atlas_block(user_message) if user_message else ""
    clock = _device_clock()
    rules = _desktop_operational_rules(profile.user_name)
    return (
        f"Tu es {profile.assistant_name}, l'assistant personnel de {profile.user_name}.\n"
        f"Tu le tutoies toujours.\n"
        f"Personnalité : {profile.assistant_personality}\n"
        f"Plateforme : Pégase Desktop sur Arch Linux — même personnalité que sur le téléphone.\n"
        f"{mode_prompt_line()}"
        f"{personality}"
        f"{clock}\n"
        f"{atlas_block}"
        f"{mem_block}"
        f"{notes_block}"
        f"{context_pointer}"
        f"{rules}"
    )


def _device_clock() -> str:
    try:
        tz = ZoneInfo("Europe/Paris")
    except Exception:
        tz = None
    now = datetime.now(tz) if tz else datetime.now()
    return (
        f"=== Contexte horloge ===\n"
        f"Date/heure locale : {now.strftime('%A %d %B %Y, %H:%M')} (Europe/Paris)\n"
    )


def _memories_block(memories: list[dict[str, Any]], max_chars: int) -> str:
    if not memories:
        return ""
    lines = ["=== Souvenirs pertinents (mémoire partagée) ==="]
    used = 0
    for entry in memories:
        content = (entry.get("content") or "").strip()
        if not content:
            continue
        cat = entry.get("category") or "?"
        line = f"- [{cat}] {content[:240]}"
        if used + len(line) > max_chars:
            break
        lines.append(line)
        used += len(line)
    if len(lines) <= 1:
        return ""
    return "\n".join(lines) + "\n"


def _notes_block(notes: list[dict[str, Any]], max_items: int = 6) -> str:
    active = [n for n in notes if not n.get("done")][:max_items]
    if not active:
        return ""
    lines = ["=== Notes actives (bloc-notes partagé) ==="]
    for item in active:
        text = (item.get("text") or "").strip()
        if text:
            lines.append(f"- {text[:200]}")
    return "\n".join(lines) + "\n"


def _desktop_operational_rules(user_name: str) -> str:
    return (
        "Sur les questions factuelles → 1 à 3 phrases, concis.\n"
        "Ne coupe jamais une explication au milieu — si tu commences, termine.\n"
        "Si tu n'es pas sûr, dis-le plutôt que d'inventer.\n"
        "Réponds directement, sans raisonnement interne, sans balise <think>.\n"
        "Pas d'emojis. Pas de markdown (pas de **, pas de #, pas de blocs ```).\n"
        "Pas de listes à puces — style oral français.\n"
        "Phrases 100 % françaises : espace correctement CHAQUE mot.\n"
        "Pour une conversation simple, réponds en français naturel.\n"
        "Commandes locales Desktop : /memo et /note enregistrent dans la mémoire partagée.\n"
        f"Tu parles à {user_name} depuis son PC — pas de commandes téléphone pour l'instant.\n"
        "\n"
        "=== Exécution de commandes shell ===\n"
        "Tu n'as JAMAIS accès à la sortie d'une commande — rien ne revient jamais dans cette conversation. "
        "N'invente donc JAMAIS un résultat de commande (tableau htop, sortie de ls, logs, etc.) : "
        f"si {user_name} demande de voir ou lancer quelque chose qui nécessite d'exécuter une commande, "
        "utilise le tag ci-dessous, tu ne peux pas répondre à sa place.\n"
        f"Dès que {user_name} exprime l'intention de lancer/voir/ouvrir un programme ou une commande — même sous "
        "forme de question comme « tu peux lancer X ? » ou « montre-moi X » — c'est une demande d'exécution, "
        "pas une question à laquelle répondre par du texte.\n"
        "Propose alors une commande, et une seule, avec ce tag exact, seul sur sa ligne, sans rien d'autre dedans : "
        "<pegase-exec>la-commande</pegase-exec>\n"
        f"Exemple : {user_name} dit « tu peux lancer htop ? » → tu réponds "
        "« Je te lance ça. <pegase-exec>htop</pegase-exec> » (rien d'autre, pas de faux tableau).\n"
        "Jamais par initiative sans qu'on te le demande. Seule commande sudo autorisée : "
        "« sudo pacman -S nom-du-paquet » pour installer un paquet — rien d'autre en sudo, "
        "et jamais --noconfirm (pacman doit garder sa propre confirmation). "
        "Jamais de commande destructrice sinon "
        "(suppression récursive, formatage, extinction machine, gestion des comptes, script distant exécuté en pipe). "
        f"{user_name} verra toujours la commande affichée et devra la confirmer avant qu'elle parte dans un terminal visible — "
        "dis-le lui si besoin, mais ne décris jamais un résultat que tu n'as pas.\n"
    )
