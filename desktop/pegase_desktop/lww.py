"""Last-write-wins — même règle que NotepadSyncApplier / MemorySyncApplier."""

from __future__ import annotations


def should_apply(remote_ts_ms: int, local_updated_at_ms: int) -> bool:
    if remote_ts_ms <= 0:
        return False
    return remote_ts_ms >= (local_updated_at_ms or 0)


def merged_version(remote_ts_ms: int, payload_updated_at_ms: int) -> int:
    return max(remote_ts_ms, payload_updated_at_ms or 0)
