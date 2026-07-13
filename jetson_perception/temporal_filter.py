from dataclasses import dataclass, field
from typing import Dict, Tuple


@dataclass
class TemporalConfirmationFilter:
    required_frames: int = 3
    _hits: Dict[Tuple[str, str], int] = field(default_factory=dict)

    def observe(self, class_name: str, track_id: str) -> bool:
        key = (class_name.casefold(), track_id)
        self._hits[key] = self._hits.get(key, 0) + 1
        return self._hits[key] >= self.required_frames

    def reset(self, class_name: str, track_id: str) -> None:
        self._hits.pop((class_name.casefold(), track_id), None)

    def clear(self) -> None:
        self._hits.clear()
