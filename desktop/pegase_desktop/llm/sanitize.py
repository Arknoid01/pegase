"""Nettoyage réponses — sous-ensemble de PegasePrompt.sanitizeForDisplay."""

from __future__ import annotations

import re

from pegase_desktop.llm.personality import strip_banned_phrases

_EMOJI = re.compile(
    r"(?:[\U00002600-\U000027BF]"
    r"|[\U0001F000-\U0001FAFF]"
    r"|\U0000FE0F|\U0000200D)+"
)


def sanitize_reply(text: str) -> str:
    if not text:
        return ""
    out = re.sub(r"(?s)<think>.*?</think>", "", text)
    out = re.sub(r"(?s)<think>.*", "", out)
    out = out.replace("/no_think", "")
    out = re.sub(r"```[a-z]*", "", out).replace("```", "")
    out = re.sub(r"\*\*([^*]+)\*\*", r"\1", out)
    out = re.sub(r"`([^`]+)`", r"\1", out)
    out = re.sub(r"(?m)^#{1,6}\s*", "", out)
    out = _EMOJI.sub("", out)
    out = re.sub(r"[ \t]+", " ", out)
    out = re.sub(r" *\n *", "\n", out).strip()
    return strip_banned_phrases(out)
