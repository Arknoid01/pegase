"""Atlas entités — port EntityResolver + EntityStore."""

from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass, field
from typing import Any

from pegase_desktop import json_io
from pegase_desktop.paths import ENTITIES_FILE

INJECT_THRESHOLD = 0.55
AMBIGUOUS_GAP = 0.12
MAX_ENTITIES = 2

TYPE_LABELS = {
    "person": "Personne",
    "project": "Projet",
    "place": "Lieu",
    "device": "Appareil",
    "routine": "Routine",
    "preference": "Préférence",
}


@dataclass
class Entity:
    id: str
    type: str
    name: str
    aliases: list[str] = field(default_factory=list)
    data: dict[str, Any] = field(default_factory=dict)

    def all_match_terms(self) -> list[str]:
        terms = [self.name] if self.name else []
        terms.extend(self.aliases)
        return terms

    def to_prompt_block(self) -> str:
        label = TYPE_LABELS.get(self.type, "Entité")
        line = f"{label} : {self.name}"
        relation = (self.data.get("relation") or "").strip()
        if relation:
            line += f", {relation}"
        status = (self.data.get("status") or "").strip()
        if status:
            line += f" ({status})"
        city = (self.data.get("city") or "").strip()
        if city:
            line += f" — {city}"
        facts = self.data.get("facts") or []
        if isinstance(facts, list) and facts:
            bits = []
            for f in facts[:3]:
                s = str(f).strip()
                if s:
                    bits.append(s if s.endswith(".") else s + ".")
            if bits:
                line += ". " + " ".join(bits)
        return line.strip()


@dataclass
class EntityMatch:
    entity: Entity
    score: float
    matched_term: str


class EntityStore:
    def __init__(self, path=ENTITIES_FILE) -> None:
        self.path = path

    def load_all(self) -> list[Entity]:
        raw = json_io.read_json(self.path, [])
        if not isinstance(raw, list):
            return []
        out: list[Entity] = []
        for item in raw:
            if not isinstance(item, dict):
                continue
            aliases = item.get("aliases") or []
            if not isinstance(aliases, list):
                aliases = []
            out.append(
                Entity(
                    id=str(item.get("id") or ""),
                    type=str(item.get("type") or ""),
                    name=str(item.get("name") or ""),
                    aliases=[str(a) for a in aliases],
                    data=item.get("data") if isinstance(item.get("data"), dict) else {},
                )
            )
        return out


def fold_text(text: str) -> str:
    if not text:
        return ""
    out = unicodedata.normalize("NFKD", text.lower())
    out = out.replace("'", " ").replace("’", " ")
    out = re.sub(r"[^a-z0-9\s]", " ", out)
    return re.sub(r"\s+", " ", out).strip()


def fold_term(term: str) -> str:
    return fold_text(term)


def resolve_entities(message: str, entities: list[Entity]) -> tuple[list[EntityMatch], list[Entity]]:
    fold = fold_text(message)
    if not fold:
        return [], []
    matches: list[EntityMatch] = []
    for entity in entities:
        best: EntityMatch | None = None
        for term in entity.all_match_terms():
            score = _score_term(fold, fold_term(term))
            if score <= 0:
                continue
            if best is None or score > best.score:
                best = EntityMatch(entity, score, term)
        if best:
            matches.append(best)
    matches.sort(key=lambda m: m.score, reverse=True)
    ambiguous: list[Entity] = []
    if len(matches) >= 2:
        first, second = matches[0], matches[1]
        if (
            first.score >= INJECT_THRESHOLD
            and second.score >= INJECT_THRESHOLD
            and first.entity.type == second.entity.type
            and first.score - second.score <= AMBIGUOUS_GAP
        ):
            ambiguous = [first.entity, second.entity]
    return matches, ambiguous


def build_atlas_block(message: str) -> str:
    entities = EntityStore().load_all()
    matches, ambiguous = resolve_entities(message, entities)
    inject = [m for m in matches if m.score >= INJECT_THRESHOLD][:MAX_ENTITIES]
    if not inject and not ambiguous:
        return ""
    lines = ["--- Atlas (entités concernées) ---"]
    for m in inject:
        lines.append(f"- {m.entity.to_prompt_block()}")
    if ambiguous:
        names = " ou ".join(e.name for e in ambiguous)
        lines.append(f"Ambiguïté possible : {names}. Demande confirmation si besoin.")
    return "\n".join(lines) + "\n"


def _score_term(fold: str, term_fold: str) -> float:
    if not term_fold:
        return 0.0
    if fold == term_fold:
        return 1.0
    padded = f" {fold} "
    needle = f" {term_fold} "
    if needle in padded or fold.startswith(term_fold + " ") or fold.endswith(" " + term_fold):
        return 0.92 if len(term_fold) >= 6 else 0.78
    if len(term_fold) >= 4 and term_fold in fold:
        return 0.65 + min(0.2, len(term_fold) * 0.02)
    if len(term_fold) >= 3 and _contains_word(fold, term_fold):
        return 0.58
    return 0.0


def _contains_word(fold: str, word: str) -> bool:
    idx = fold.find(word)
    while idx >= 0:
        left = idx == 0 or not fold[idx - 1].isalnum()
        end = idx + len(word)
        right = end >= len(fold) or not fold[end].isalnum()
        if left and right:
            return True
        idx = fold.find(word, idx + 1)
    return False
