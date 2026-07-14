import pytest
from pydantic import ValidationError

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
    "VISION_BASE_URL",
    "VISION_POLL_INTERVAL_SECONDS",
    "VISION_STALE_AFTER_SECONDS",
    "VISION_FORGET_AFTER_SECONDS",
    "VISION_STABLE_FRAMES",
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
    assert settings.vision_base_url == "http://127.0.0.1:8200"
    assert settings.vision_poll_interval_seconds == 0.5
    assert settings.vision_stale_after_seconds == 3.0
    assert settings.vision_forget_after_seconds == 5.0
    assert settings.vision_stable_frames == 3
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


def test_settings_allow_vision_environment_overrides(monkeypatch):
    clear_settings_environment(monkeypatch)
    monkeypatch.setenv("VISION_BASE_URL", "http://127.0.0.1:8300")
    monkeypatch.setenv("VISION_POLL_INTERVAL_SECONDS", "0.25")
    monkeypatch.setenv("VISION_STALE_AFTER_SECONDS", "1.5")
    monkeypatch.setenv("VISION_FORGET_AFTER_SECONDS", "1.5")
    monkeypatch.setenv("VISION_STABLE_FRAMES", "4")

    settings = Settings(_env_file=None)

    assert settings.vision_base_url == "http://127.0.0.1:8300"
    assert settings.vision_poll_interval_seconds == 0.25
    assert settings.vision_stale_after_seconds == 1.5
    assert settings.vision_forget_after_seconds == 1.5
    assert settings.vision_stable_frames == 4


@pytest.mark.parametrize(
    ("name", "value"),
    [
        ("VISION_POLL_INTERVAL_SECONDS", "0"),
        ("VISION_STALE_AFTER_SECONDS", "0.2"),
        ("VISION_STALE_AFTER_SECONDS", "0.5"),
        ("VISION_FORGET_AFTER_SECONDS", "2"),
        ("VISION_STABLE_FRAMES", "0"),
    ],
)
def test_settings_reject_invalid_vision_timing(monkeypatch, name, value):
    clear_settings_environment(monkeypatch)
    monkeypatch.setenv(name, value)

    with pytest.raises(ValidationError):
        Settings(_env_file=None)
