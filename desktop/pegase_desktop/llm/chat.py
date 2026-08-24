"""Historique conversation + envoi LLM (streaming + sync Drive)."""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass

from pegase_desktop.context_store import ContextStore
from pegase_desktop.conversation_store import ConversationStore
from pegase_desktop.drive_export import DriveExporter, DriveExportError
from pegase_desktop.llm.client import CancelToken, LlmCancelledError, LlmError
from pegase_desktop.llm.config import load_keys, seed_personality_if_missing, seed_profile_if_missing
from pegase_desktop.llm.multi_backend import MultiProviderBackend
from pegase_desktop.memory_store import MemoryStore
from pegase_desktop.notepad_store import NotepadStore


@dataclass
class ChatResult:
    reply: str
    provider: str = ""
    model: str = ""
    latency_ms: int = 0
    drive_op: str = ""


class ChatEngine:
    def __init__(
        self,
        memory: MemoryStore | None = None,
        notepad: NotepadStore | None = None,
        backend: MultiProviderBackend | None = None,
        conversation: ConversationStore | None = None,
        contexts: ContextStore | None = None,
        drive: DriveExporter | None = None,
    ) -> None:
        from pathlib import Path

        asset = Path(__file__).resolve().parent.parent / "assets" / "pegase-personality.md"
        seed_profile_if_missing()
        seed_personality_if_missing(asset)
        self.memory = memory or MemoryStore()
        self.notepad = notepad or NotepadStore()
        self.contexts = contexts or ContextStore()
        self.conversation = conversation or ConversationStore()
        self.backend = backend or MultiProviderBackend(contexts=self.contexts)
        self.drive = drive or DriveExporter()

    def is_configured(self) -> bool:
        return load_keys().has_any()

    def history(self) -> list[dict[str, str]]:
        return self.conversation.load_history_openai()

    def clear_history(self) -> None:
        payload = self.conversation.clear()
        self._export_conversation(payload)

    def send(
        self,
        user_message: str,
        on_partial: Callable[[str], None] | None = None,
        cancel: CancelToken | None = None,
    ) -> ChatResult:
        text = (user_message or "").strip()
        if not text:
            raise ValueError("message vide")
        if not self.is_configured():
            raise LlmError(
                "Clé API manquante. Crée ~/.pegase/llm/keys.env avec GROQ_API_KEY=gsk_…"
            )

        memories = self.memory.list_injectable(8)
        notes = self.notepad.list_active(8)
        history = self.conversation.load_history_openai()
        try:
            result = self.backend.send(
                history,
                text,
                memories=memories,
                notes=notes,
                on_partial=on_partial,
                cancel=cancel,
            )
        except LlmCancelledError:
            raise
        if not result.text:
            raise LlmError("Réponse vide du modèle")

        payload = self.conversation.append_exchange(text, result.text)
        drive_op = self._export_conversation(payload)
        return ChatResult(
            reply=result.text,
            provider=result.provider_id,
            model=result.model_id,
            latency_ms=result.latency_ms,
            drive_op=drive_op,
        )

    def _export_conversation(self, payload: dict) -> str:
        try:
            op_path = self.drive.export_conversation_upsert(payload)
            from pegase_desktop.sync_status import mark_synced
            mark_synced("conversation")
            return op_path.name
        except DriveExportError:
            return ""
