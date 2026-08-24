"""Budgets prompt — alignés sur PromptBudget.java (Groq serré)."""

from __future__ import annotations

from enum import Enum


class BudgetLevel(Enum):
    NORMAL = "normal"
    TIGHT = "tight"
    EMERGENCY = "emergency"

    @classmethod
    def for_provider(cls, provider_id: str) -> "BudgetLevel":
        return cls.TIGHT if provider_id == "groq" else cls.NORMAL


GROQ_MAX_PROMPT_TOKENS = 6000


def history_recent_limit(level: BudgetLevel) -> int:
    if level == BudgetLevel.EMERGENCY:
        return 3
    if level == BudgetLevel.TIGHT:
        return 4
    return 6


def personality_max_chars(level: BudgetLevel) -> int:
    if level == BudgetLevel.EMERGENCY:
        return 1200
    if level == BudgetLevel.TIGHT:
        return 2000
    return 6000


def memory_max_chars(level: BudgetLevel) -> int:
    if level == BudgetLevel.EMERGENCY:
        return 800
    if level == BudgetLevel.TIGHT:
        return 1500
    return 3000


def attached_max_chars(level: BudgetLevel) -> int:
    if level == BudgetLevel.EMERGENCY:
        return 2500
    if level == BudgetLevel.TIGHT:
        return 6000
    return 14000


def exceeds_groq_budget(total_chars: int) -> bool:
    return max(0, total_chars) // 4 > GROQ_MAX_PROMPT_TOKENS
