"""Client HTTP OpenAI-compatible (stdlib)."""

from __future__ import annotations

import json
import re
import socket
import threading
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from typing import Any, Callable

from pegase_desktop.llm.prompt_budget import BudgetLevel
from pegase_desktop.llm.provider_chain import LlmProvider
from pegase_desktop.llm.sanitize import sanitize_reply


class LlmError(Exception):
    pass


class LlmCancelledError(LlmError):
    pass


class CancelToken:
    def __init__(self) -> None:
        self._event = threading.Event()

    def cancel(self) -> None:
        self._event.set()

    @property
    def cancelled(self) -> bool:
        return self._event.is_set()


class LlmRateLimitError(LlmError):
    def __init__(self, provider_id: str, retry_after_ms: int = 0) -> None:
        super().__init__(f"Rate limit {provider_id}")
        self.provider_id = provider_id
        self.retry_after_ms = retry_after_ms


class LlmTimeoutError(LlmError):
    pass


@dataclass(frozen=True)
class CompletionResult:
    text: str
    provider_id: str
    model_id: str
    latency_ms: int


def parse_completion(body_text: str) -> str:
    data = json.loads(body_text)
    choices = data.get("choices") or []
    if not choices:
        return ""
    message = choices[0].get("message") or {}
    content = (message.get("content") or "").strip()
    return sanitize_reply(content)


def extract_stream_delta(json_line: str) -> str | None:
    try:
        obj = json.loads(json_line)
        choices = obj.get("choices") or []
        if not choices:
            return None
        delta = choices[0].get("delta") or {}
        content = delta.get("content")
        if content is None:
            return None
        return str(content)
    except Exception:
        return None


def chat_completion_stream(
    provider: LlmProvider,
    model_id: str,
    messages: list[dict[str, str]],
    on_partial: Callable[[str], None],
    *,
    max_tokens: int = 600,
    temperature: float = 0.92,
    budget: BudgetLevel = BudgetLevel.NORMAL,
    cancel: CancelToken | None = None,
) -> CompletionResult:
    body: dict[str, Any] = {
        "model": model_id,
        "messages": messages,
        "temperature": temperature,
        "max_tokens": max_tokens,
        "stream": True,
    }
    if "qwen" in model_id.lower():
        body["reasoning_format"] = "hidden"

    payload = json.dumps(body, ensure_ascii=False).encode("utf-8")
    headers = {
        "Content-Type": "application/json",
        "Authorization": f"Bearer {provider.api_key}",
        **provider.extra_headers,
    }
    req = urllib.request.Request(provider.url, data=payload, headers=headers, method="POST")
    start = time.monotonic()
    accumulated: list[str] = []
    try:
        with urllib.request.urlopen(req, timeout=provider.read_timeout_s) as resp:
            for raw_line in resp:
                if cancel and cancel.cancelled:
                    raise LlmCancelledError("Génération interrompue")
                line = raw_line.decode("utf-8", errors="replace").strip()
                if not line.startswith("data:"):
                    continue
                data = line[5:].strip()
                if data == "[DONE]":
                    break
                delta = extract_stream_delta(data)
                if not delta:
                    continue
                accumulated.append(delta)
                on_partial("".join(accumulated))
    except LlmCancelledError:
        raise
    except urllib.error.HTTPError as exc:
        err_body = exc.read().decode("utf-8", errors="replace")
        if exc.code == 429:
            raise LlmRateLimitError(provider.id, _parse_retry_after_ms(err_body)) from exc
        if exc.code == 413 and provider.id == "groq" and budget != BudgetLevel.EMERGENCY:
            raise LlmError("HTTP 413 request too large") from exc
        raise LlmError(f"{provider.display_name} HTTP {exc.code}: {err_body[:300]}") from exc
    except (TimeoutError, socket.timeout) as exc:
        raise LlmTimeoutError(f"Timeout {provider.display_name}") from exc
    except urllib.error.URLError as exc:
        if isinstance(getattr(exc, "reason", None), (TimeoutError, socket.timeout)):
            raise LlmTimeoutError(f"Timeout {provider.display_name}") from exc
        raise LlmError(str(exc)) from exc

    latency_ms = int((time.monotonic() - start) * 1000)
    text = sanitize_reply("".join(accumulated))
    return CompletionResult(
        text=text,
        provider_id=provider.id,
        model_id=model_id,
        latency_ms=latency_ms,
    )


def chat_completion(
    provider: LlmProvider,
    model_id: str,
    messages: list[dict[str, str]],
    *,
    max_tokens: int = 600,
    temperature: float = 0.92,
    budget: BudgetLevel = BudgetLevel.NORMAL,
) -> CompletionResult:
    body: dict[str, Any] = {
        "model": model_id,
        "messages": messages,
        "temperature": temperature,
        "max_tokens": max_tokens,
        "stream": False,
    }
    if "qwen" in model_id.lower():
        body["reasoning_format"] = "hidden"

    payload = json.dumps(body, ensure_ascii=False).encode("utf-8")
    headers = {
        "Content-Type": "application/json",
        "Authorization": f"Bearer {provider.api_key}",
        **provider.extra_headers,
    }
    req = urllib.request.Request(provider.url, data=payload, headers=headers, method="POST")
    start = time.monotonic()
    try:
        with urllib.request.urlopen(
            req, timeout=provider.read_timeout_s
        ) as resp:
            raw = resp.read().decode("utf-8")
    except urllib.error.HTTPError as exc:
        err_body = exc.read().decode("utf-8", errors="replace")
        if exc.code == 429:
            retry_ms = _parse_retry_after_ms(err_body)
            raise LlmRateLimitError(provider.id, retry_ms) from exc
        if exc.code == 413 and provider.id == "groq" and budget != BudgetLevel.EMERGENCY:
            raise LlmError("HTTP 413 request too large") from exc
        raise LlmError(f"{provider.display_name} HTTP {exc.code}: {err_body[:300]}") from exc
    except (TimeoutError, socket.timeout) as exc:
        raise LlmTimeoutError(f"Timeout {provider.display_name}") from exc
    except urllib.error.URLError as exc:
        if isinstance(getattr(exc, "reason", None), (TimeoutError, socket.timeout)):
            raise LlmTimeoutError(f"Timeout {provider.display_name}") from exc
        raise LlmError(str(exc)) from exc

    latency_ms = int((time.monotonic() - start) * 1000)
    text = parse_completion(raw)
    return CompletionResult(
        text=text,
        provider_id=provider.id,
        model_id=model_id,
        latency_ms=latency_ms,
    )


def _parse_retry_after_ms(body: str) -> int:
    try:
        data = json.loads(body)
        msg = str(data.get("error", {}).get("message", "")).lower()
        if "try again in" in msg:
            m = re.search(r"try again in ([0-9.]+)s", msg)
            if m:
                return int(float(m.group(1)) * 1000)
    except Exception:
        pass
    return 3000
