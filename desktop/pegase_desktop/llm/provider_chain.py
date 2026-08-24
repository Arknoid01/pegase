"""Chaîne providers Groq → Cerebras → OpenRouter."""

from __future__ import annotations

from dataclasses import dataclass, field

from pegase_desktop.llm.config import LlmKeys, UserProfile

GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"
CEREBRAS_URL = "https://api.cerebras.ai/v1/chat/completions"
OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions"

CEREBRAS_MODEL = "gpt-oss-120b"
OPENROUTER_MODEL = "openai/gpt-oss-120b"


@dataclass(frozen=True)
class LlmProvider:
    id: str
    display_name: str
    model_id: str
    api_key: str
    url: str
    connect_timeout_s: float = 10.0
    read_timeout_s: float = 15.0
    extra_headers: dict[str, str] = field(default_factory=dict)

    def has_key(self) -> bool:
        return bool(self.api_key)


def build_chain(keys: LlmKeys, profile: UserProfile) -> list[LlmProvider]:
    chain: list[LlmProvider] = []
    if keys.groq:
        chain.append(
            LlmProvider(
                id="groq",
                display_name="Groq",
                model_id=profile.groq_model,
                api_key=keys.groq,
                url=GROQ_URL,
                read_timeout_s=15.0,
            )
        )
    if keys.cerebras:
        chain.append(
            LlmProvider(
                id="cerebras",
                display_name="Cerebras",
                model_id=CEREBRAS_MODEL,
                api_key=keys.cerebras,
                url=CEREBRAS_URL,
                read_timeout_s=10.0,
            )
        )
    if keys.openrouter:
        chain.append(
            LlmProvider(
                id="openrouter",
                display_name="OpenRouter",
                model_id=OPENROUTER_MODEL,
                api_key=keys.openrouter,
                url=OPENROUTER_URL,
                read_timeout_s=10.0,
                extra_headers={
                    "HTTP-Referer": "https://pegase.local",
                    "X-Title": "Pegase",
                },
            )
        )
    return chain


def models_for(provider: LlmProvider) -> list[tuple[str, str]]:
    """(model_id, label) — Groq peut retenter le 120B si modèle préféré différent."""
    if provider.id == "groq":
        models: list[tuple[str, str]] = [(provider.model_id, provider.model_id)]
        fallback = "openai/gpt-oss-120b"
        if provider.model_id != fallback:
            models.append((fallback, fallback))
        return models
    return [(provider.model_id, provider.model_id)]
