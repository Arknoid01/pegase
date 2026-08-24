"""Tests provider chain et parsing."""

from __future__ import annotations

import json
import unittest

from pegase_desktop.llm.client import parse_completion
from pegase_desktop.llm.config import LlmKeys, UserProfile
from pegase_desktop.llm.provider_chain import build_chain, models_for


class ProviderChainTest(unittest.TestCase):
    def test_build_chain_order(self):
        keys = LlmKeys(groq="g1", cerebras="c1", openrouter="o1")
        chain = build_chain(keys, UserProfile())
        self.assertEqual([p.id for p in chain], ["groq", "cerebras", "openrouter"])

    def test_groq_models_fallback(self):
        from pegase_desktop.llm.provider_chain import LlmProvider

        p = LlmProvider(
            id="groq",
            display_name="Groq",
            model_id="qwen/qwen3-32b",
            api_key="k",
            url="http://x",
        )
        models = models_for(p)
        self.assertEqual(len(models), 2)
        self.assertEqual(models[1][0], "openai/gpt-oss-120b")

    def test_parse_completion_strips_thinking(self):
        body = json.dumps(
            {
                "choices": [
                    {
                        "message": {
                            "content": "<think>x</think> Salut !"
                        }
                    }
                ]
            }
        )
        self.assertEqual(parse_completion(body), "Salut !")

    def test_stream_delta(self):
        from pegase_desktop.llm.client import extract_stream_delta

        line = json.dumps({"choices": [{"delta": {"content": "Bon"}}]})
        self.assertEqual(extract_stream_delta(line), "Bon")


if __name__ == "__main__":
    unittest.main()
