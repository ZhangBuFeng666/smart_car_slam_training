from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    jarvis_host: str = "0.0.0.0"
    jarvis_port: int = 8100
    jarvis_app_token: str = ""
    control_base_url: str = "http://127.0.0.1:8000"
    deepseek_api_key: str = ""
    deepseek_base_url: str = "https://api.deepseek.com"
    deepseek_model: str = "deepseek-chat"
    database_path: str = "data/jarvis.db"
    request_timeout_seconds: float = 20.0
    event_cooldown_seconds: int = 15

    @property
    def deepseek_configured(self) -> bool:
        return bool(self.deepseek_api_key.strip())
