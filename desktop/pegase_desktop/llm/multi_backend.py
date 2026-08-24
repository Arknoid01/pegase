"""Rotation Groq → Cerebras → OpenRouter."""

from __future__ import annotations

import time
from collections.abc import Callable
from dataclasses import dataclass, field
from typing import Any

from pegase_desktop.context_store import ContextStore
from pegase_desktop.llm.client import (
    CancelToken,
    CompletionResult,
    LlmCancelledError,
    LlmError,
    LlmRateLimitError,
    LlmTimeoutError,
    chat_completion,
    chat_completion_stream,
)
from pegase_desktop.llm.config import LlmKeys, UserProfile, load_keys, load_profile
from pegase_desktop.llm.prompt_budget import BudgetLevel, exceeds_groq_budget, history_recent_limit
from pegase_desktop.llm.provider_chain import LlmProvider, build_chain, models_for
from pegase_desktop.mode_store import reply_max_tokens


@dataclass
class ProviderHealth:
    cooldown_until_ms: dict[str, int] = field(default_factory=dict)

    def is_unhealthy(self, provider_id: str) -> bool:
        return self.cooldown_until_ms.get(provider_id, 0) > _now_ms()

    def mark_rate_limit(self, provider_id: str, retry_after_ms: int = 60_000) -> None:
        self.cooldown_until_ms[provider_id] = _now_ms() + max(retry_after_ms, 60_000)

    def mark_timeout(self, provider_id: str) -> None:
        self.cooldown_until_ms[provider_id] = _now_ms() + 30_000

    def mark_success(self, provider_id: str) -> None:
        self.cooldown_until_ms.pop(provider_id, None)


def _now_ms() -> int:
    return int(time.time() * 1000)


class MultiProviderBackend:
    def __init__(
        self,
        keys: LlmKeys | None = None,
        profile: UserProfile | None = None,
        health: ProviderHealth | None = None,
        contexts: ContextStore | None = None,
    ) -> None:
        self.keys = keys or load_keys()
        self.profile = profile or load_profile()
        self.health = health or ProviderHealth()
        self.contexts = contexts or ContextStore()

    def send(
        self,
        history: list[dict[str, str]],
        user_message: str,
        *,
        memories: list[dict[str, Any]],
        notes: list[dict[str, Any]],
        max_tokens: int | None = None,
        on_partial: Callable[[str], None] | None = None,
        cancel: CancelToken | None = None,
    ) -> CompletionResult:
        tokens = max_tokens if max_tokens is not None else reply_max_tokens()
        chain = build_chain(self.keys, self.profile)
        if not chain:
            raise LlmError(
                "Aucune clé API configurée. "
                "Ajoute GROQ_API_KEY dans ~/.pegase/llm/keys.env"
            )

        last_error: Exception | None = None
        for provider in chain:
            if self.health.is_unhealthy(provider.id):
                continue
            budget = BudgetLevel.for_provider(provider.id)
            for model_id, _label in models_for(provider):
                try:
                    messages = _build_messages(
                        self.profile,
                        history,
                        user_message,
                        memories,
                        notes,
                        budget,
                        self.contexts,
                    )
                    body_len = sum(len(m["content"]) for m in messages)
                    if provider.id == "groq" and exceeds_groq_budget(body_len):
                        budget = BudgetLevel.EMERGENCY
                        messages = _build_messages(
                            self.profile,
                            history,
                            user_message,
                            memories,
                            notes,
                            budget,
                            self.contexts,
                        )
                    if on_partial is not None:
                        result = chat_completion_stream(
                            provider,
                            model_id,
                            messages,
                            on_partial,
                            max_tokens=tokens,
                            budget=budget,
                            cancel=cancel,
                        )
                    else:
                        result = chat_completion(
                            provider,
                            model_id,
                            messages,
                            max_tokens=tokens,
                            budget=budget,
                        )
                    self.health.mark_success(provider.id)
                    return result
                except LlmCancelledError:
                    raise
                except LlmRateLimitError as exc:
                    self.health.mark_rate_limit(provider.id, exc.retry_after_ms)
                    last_error = exc
                    break
                except LlmTimeoutError as exc:
                    self.health.mark_timeout(provider.id)
                    last_error = exc
                    continue
                except LlmError as exc:
                    if "413" in str(exc) and provider.id == "groq":
                        try:
                            messages = _build_messages(
                                self.profile,
                                history,
                                user_message,
                                memories,
                                notes,
                                BudgetLevel.EMERGENCY,
                                self.contexts,
                            )
                            fn = (
                                chat_completion_stream
                                if on_partial
                                else chat_completion
                            )
                            kwargs: dict[str, Any] = {
                                "max_tokens": tokens,
                                "budget": BudgetLevel.EMERGENCY,
                            }
                            if on_partial:
                                result = fn(
                                    provider,
                                    model_id,
                                    messages,
                                    on_partial,
                                    **kwargs,
                                )
                            else:
                                result = fn(
                                    provider,
                                    model_id,
                                    messages,
                                    **kwargs,
                                )
                            self.health.mark_success(provider.id)
                            return result
                        except Exception as retry_exc:
                            last_error = retry_exc
                            continue
                    last_error = exc
                    continue
        if last_error:
            raise LlmError(str(last_error))
        raise LlmError("Tous les providers LLM sont indisponibles")


def _build_messages(
    profile: UserProfile,
    history: list[dict[str, str]],
    user_message: str,
    memories: list[dict[str, Any]],
    notes: list[dict[str, Any]],
    budget: BudgetLevel,
    contexts: ContextStore,
) -> list[dict[str, str]]:
    from pegase_desktop.llm.prompt import build_system_prompt

    pointer = contexts.prompt_pointer()
    system = build_system_prompt(
        profile,
        memories,
        notes,
        budget,
        user_message=user_message,
        context_pointer=pointer,
    )
    messages: list[dict[str, str]] = [{"role": "system", "content": system}]
    recent = history[-history_recent_limit(budget) * 2 :]
    for turn in recent:
        role = turn.get("role")
        content = (turn.get("content") or "").strip()
        if role in ("user", "assistant") and content:
            messages.append({"role": role, "content": content})
    wrapped = contexts.wrap_user_message(user_message, budget)
    messages.append({"role": "user", "content": wrapped})
    return messages
