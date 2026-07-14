from pydantic import Field, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    jarvis_host: str = "0.0.0.0"
    jarvis_port: int = 8100
    jarvis_app_token: str = ""
    control_base_url: str = "http://127.0.0.1:8000"
    vision_base_url: str = "http://127.0.0.1:8200"
    vision_poll_interval_seconds: float = Field(default=0.5, gt=0)
    vision_stale_after_seconds: float = Field(default=3.0, gt=0)
    vision_forget_after_seconds: float = Field(default=5.0, gt=0)
    vision_stable_frames: int = Field(default=3, ge=1)
    deepseek_api_key: str = ""
    deepseek_base_url: str = "https://api.deepseek.com"
    deepseek_model: str = "deepseek-chat"
    database_path: str = "data/jarvis.db"
    request_timeout_seconds: float = 20.0
    event_cooldown_seconds: int = 15

    @model_validator(mode="after")
    def validate_vision_timing(self):
        if self.vision_stale_after_seconds <= self.vision_poll_interval_seconds:
            raise ValueError("vision stale timeout must exceed poll interval")
        if self.vision_forget_after_seconds < self.vision_stale_after_seconds:
            raise ValueError(
                "vision forget timeout must not be shorter than stale timeout"
            )
        return self

    @property
    def deepseek_configured(self) -> bool:
        return bool(self.deepseek_api_key.strip())
