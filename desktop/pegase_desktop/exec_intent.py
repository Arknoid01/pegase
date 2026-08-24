"""Détection et garde-fous pour les commandes shell proposées par le LLM.

Le LLM n'a aucun pouvoir d'exécution direct : il ne peut que proposer une
commande via un tag `<pegase-exec>`, que ce module extrait puis fait valider
par l'app (blocklist + confirmation utilisateur + exécution toujours visible
via terminal_launch). Voir gtk_app.py:_handle_exec_proposal.
"""

from __future__ import annotations

import re
import time

from pegase_desktop import json_io
from pegase_desktop.paths import EXEC_LOG_FILE

_TAG_RE = re.compile(r"<pegase-exec>(.*?)</pegase-exec>", re.DOTALL)

# Nom de paquet pacman : lettres/chiffres/@._+- uniquement — exclut tout
# métacaractère shell (;, &, |, backtick, $()...), donc pas d'injection possible
# même en fullmatch sur la commande entière.
_PKG = r"[a-zA-Z0-9][a-zA-Z0-9@._+-]*"

# Liste blanche stricte des commandes sudo autorisées : fullmatch sur la
# commande ENTIÈRE (pas de recherche partielle), donc rien d'autre ne peut se
# glisser avant/après. Le terminal ouvert par launch_in_terminal redemandera
# de toute façon le mot de passe en interactif — double filet humain.
_SUDO_ALLOWLIST = [
    re.compile(rf"^sudo pacman -S(?: {_PKG})+$"),
]
_SUDO_RE = re.compile(r"\bsudo\b|\bdoas\b", re.I)


def _sudo_allowed(command: str) -> bool:
    stripped = command.strip()
    return any(pattern.fullmatch(stripped) for pattern in _SUDO_ALLOWLIST)


# Heuristique de défense en profondeur, pas une frontière de sécurité absolue :
# un modèle compromis par une injection pourrait tenter de l'obfusquer. La
# vraie garantie vient de la confirmation systématique + exécution visible.
_DANGEROUS_PATTERNS = [
    (re.compile(r"rm\s+(-[a-z]*\s+)*-[a-z]*r[a-z]*f|rm\s+(-[a-z]*\s+)*-[a-z]*f[a-z]*r", re.I), "suppression récursive forcée"),
    (re.compile(r"\bmkfs\b", re.I), "formatage de disque"),
    (re.compile(r"\bdd\b.*\bof=/dev/", re.I), "écriture brute sur un périphérique"),
    (re.compile(r">\s*/dev/sd[a-z]"), "écriture brute sur un disque"),
    (re.compile(r":\(\)\s*\{.*:\|:.*\}\s*;\s*:"), "fork bomb"),
    (re.compile(r"\b(shutdown|reboot|poweroff|halt)\b", re.I), "extinction/redémarrage de la machine"),
    (re.compile(r"\b(passwd|useradd|userdel|usermod|groupdel)\b", re.I), "gestion des comptes système"),
    (re.compile(r"chmod\s+(-R\s+)?777\s+/(?:\s|$)"), "permissions dangereuses sur la racine"),
    (re.compile(r"chown\s+-R\s+\S+\s+/(?:\s|$)"), "changement de propriétaire sur la racine"),
    (re.compile(r"\bmv\b[^|;&]*\s/(?:\s|$)"), "écrasement de la racine"),
    (re.compile(r"(curl|wget)[^|]*\|\s*(sudo\s+)?(bash|sh|zsh)\b", re.I), "exécution d'un script distant non vérifié"),
    (re.compile(r"\biptables\b.*-F\b", re.I), "purge du pare-feu"),
    (re.compile(r"\bcrontab\s+-r\b", re.I), "suppression de toutes les tâches planifiées"),
    (re.compile(r"\bpacman\s+.*-R.*\b(linux|glibc)\b", re.I), "suppression d'un paquet système critique"),
]


def parse_exec_proposal(reply: str) -> tuple[str, str | None]:
    """Extrait un éventuel `<pegase-exec>commande</pegase-exec>` de la réponse du LLM.

    Retourne (texte à afficher, commande proposée ou None). Une proposition
    multi-lignes est ignorée par construction : ça élimine les scripts/heredocs
    déguisés en "une commande".
    """
    match = _TAG_RE.search(reply)
    if not match:
        return reply, None
    cleaned = _TAG_RE.sub("", reply).strip()
    command = match.group(1).strip()
    if not command or "\n" in command:
        return cleaned, None
    return cleaned, command


def check_danger(command: str) -> str | None:
    """Motif de blocage si la commande matche un pattern jugé destructeur, sinon None."""
    if _SUDO_RE.search(command) and not _sudo_allowed(command):
        return "élévation de privilèges hors liste blanche (seul « sudo pacman -S <paquet> » est autorisé)"
    for pattern, reason in _DANGEROUS_PATTERNS:
        if pattern.search(command):
            return reason
    return None


def log_exec(command: str, *, source: str, outcome: str, reason: str = "") -> None:
    entry = {
        "tsMs": int(time.time() * 1000),
        "command": command,
        "source": source,
        "outcome": outcome,
    }
    if reason:
        entry["reason"] = reason
    entries = json_io.read_json(EXEC_LOG_FILE, [])
    if not isinstance(entries, list):
        entries = []
    entries.append(entry)
    json_io.write_json_atomic(EXEC_LOG_FILE, entries[-500:])
