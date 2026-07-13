from typing import Any, Dict, Optional

import httpx


class JarvisClientError(RuntimeError):
    pass


class JarvisVisionClient:
    def __init__(
        self,
        base_url: str,
        token: str,
        timeout: float = 10.0,
        transport: Optional[httpx.BaseTransport] = None,
    ) -> None:
        self.base_url = base_url.rstrip("/")
        self.headers = {"Authorization": "Bearer %s" % token}
        self.timeout = timeout
        self.transport = transport

    def post_vision_event(self, event: Dict[str, Any]) -> Dict[str, Any]:
        try:
            with httpx.Client(
                base_url=self.base_url,
                headers=self.headers,
                timeout=self.timeout,
                transport=self.transport,
            ) as client:
                response = client.post("/api/v1/vision-events", json=event)
                response.raise_for_status()
                return response.json()
        except httpx.HTTPError as exc:
            raise JarvisClientError("vision event request failed") from exc
