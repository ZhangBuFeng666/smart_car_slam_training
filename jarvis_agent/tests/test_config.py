from jarvis_agent.config import Settings


SETTING_ENV_VARS = (
    "JARVIS_HOST",
    "JARVIS_PORT",
    "JARVIS_APP_TOKEN",
    "CONTROL_BASE_URL",
    "DEEPSEEK_API_KEY",
    "DEEPSEEK_BASE_URL",
    "DEEPSEEK_MODEL",
    "DATABASE_PATH",
    "REQUEST_TIMEOUT_SECONDS",
    "EVENT_COOLDOWN_SECONDS",
)


def clear_settings_environment(monkeypatch):
    for env_var in SETTING_ENV_VARS:
        monkeypatch.delenv(env_var, raising=False)


def test_settings_use_defaults_without_deepseek_api_key(monkeypatch):
    clear_settings_environment(monkeypatch)

    settings = Settings(_env_file=None)

    assert settings.jarvis_host == "0.0.0.0"
    assert settings.jarvis_port == 8100
    assert settings.jarvis_app_token == ""
    assert settings.control_base_url == "http://127.0.0.1:8000"
    assert settings.deepseek_api_key == ""
    assert settings.deepseek_base_url == "https://api.deepseek.com"
    assert settings.deepseek_model == "deepseek-chat"
    assert settings.database_path == "data/jarvis.db"
    assert settings.request_timeout_seconds == 20.0
    assert settings.event_cooldown_seconds == 15
    assert settings.deepseek_configured is False


def test_settings_detect_nonblank_deepseek_api_key(monkeypatch):
    clear_settings_environment(monkeypatch)
    monkeypatch.setenv("DEEPSEEK_API_KEY", "configured-key")

    settings = Settings(_env_file=None)

    assert settings.deepseek_configured is True


def test_settings_treat_whitespace_deepseek_api_key_as_unconfigured(monkeypatch):
    clear_settings_environment(monkeypatch)
    monkeypatch.setenv("DEEPSEEK_API_KEY", "  \t  ")

    settings = Settings(_env_file=None)

    assert settings.deepseek_configured is False


def test_settings_allow_jarvis_port_environment_override(monkeypatch):
    clear_settings_environment(monkeypatch)
    monkeypatch.setenv("JARVIS_PORT", "8200")

    settings = Settings(_env_file=None)

    assert settings.jarvis_port == 8200
