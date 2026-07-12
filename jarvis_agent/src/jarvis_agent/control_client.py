from typing import Any, Dict
from urllib.parse import quote

import httpx

from jarvis_agent.config import Settings


class ControlServiceError(RuntimeError):
    pass


class ControlClient:
    def __init__(self, settings: Settings) -> None:
        self.base_url = settings.control_base_url.rstrip("/")
        self.timeout = min(settings.request_timeout_seconds, 5.0)

    async def health(self) -> Dict[str, Any]:
        return await self._get("/health")

    async def status(self) -> Dict[str, Any]:
        return await self._get("/status")

    async def start_task(self, task: str) -> Dict[str, Any]:
        return await self._post("/task/%s/start" % quote(task, safe=""))

    async def stop_task(self, task: str) -> Dict[str, Any]:
        return await self._post("/task/%s/stop" % quote(task, safe=""))

    async def stop_all(self) -> Dict[str, Any]:
        return await self._post("/stop/all")

    async def emergency_stop(self) -> Dict[str, Any]:
        return await self._post("/stop/all")

    async def _get(self, path: str) -> Dict[str, Any]:
        return await self._request("GET", path)

    async def _post(self, path: str) -> Dict[str, Any]:
        return await self._request("POST", path)

    async def _request(self, method: str, path: str) -> Dict[str, Any]:
        try:
            async with httpx.AsyncClient(
                base_url=self.base_url, timeout=self.timeout
            ) as client:
                response = await client.request(method, path)
                response.raise_for_status()
                if not response.content:
                    return {}
                return response.json()
        except (httpx.HTTPError, ValueError) as exc:
            raise ControlServiceError("control service request failed") from exc
