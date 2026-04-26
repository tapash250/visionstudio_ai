import os
import re
from dataclasses import dataclass

@dataclass
class ModerationResult:
    blocked: bool
    reason: str
    score: float
    categories: list

class ModerationService:
    def __init__(self):
        self.enabled = os.getenv("MODERATION_ENABLED", "true").lower() == "true"
        self.blocked_terms = self._load_blocked_terms()

    def _load_blocked_terms(self) -> set:
        # In production, load from database or file
        return {
            "child", "minor", "underage", "cp", "csam",
            "bestiality", "zoophilia", "necrophilia",
            "non-consensual", "rape", "forced",
        }

    async def check_prompt(self, prompt: str) -> ModerationResult:
        if not self.enabled:
            return ModerationResult(False, "", 0.0, [])

        prompt_lower = prompt.lower()

        # Check blocked terms
        for term in self.blocked_terms:
            if term in prompt_lower:
                return ModerationResult(
                    blocked=True,
                    reason=f"Content violates safety policy",
                    score=1.0,
                    categories=["blocked_term"],
                )

        # Check for prompt injection attempts
        if any(pattern in prompt_lower for pattern in ["ignore previous", "disregard", "system prompt"]):
            return ModerationResult(
                blocked=True,
                reason="Potential prompt injection detected",
                score=0.9,
                categories=["prompt_injection"],
            )

        # In production, call AWS Rekognition or Azure Content Safety
        # For now, return allowed
        return ModerationResult(False, "", 0.0, [])

    async def check_image(self, image_url: str) -> ModerationResult:
        if not self.enabled:
            return ModerationResult(False, "", 0.0, [])

        # In production, call image moderation API
        return ModerationResult(False, "", 0.0, [])
