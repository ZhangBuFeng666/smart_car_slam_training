from jarvis_agent.config import Settings


def test_settings_use_defaults_without_deepseek_api_key(monkeypatch):
    monkeypatch.delenv("DEEPSEEK_API_KEY", raising=False)

    settings = Settings()

    assert settings.jarvis_port == 8100
    assert settings.control_base_url == "http://127.0.0.1:8000"
    assert settings.deepseek_configured is False


def test_settings_detect_nonblank_deepseek_api_key(monkeypatch):
    monkeypatch.setenv("DEEPSEEK_API_KEY", "configured-key")

    settings = Settings()

    assert settings.deepseek_configured is True


def test_settings_allow_jarvis_port_environment_override(monkeypatch):
    monkeypatch.setenv("JARVIS_PORT", "8200")

    settings = Settings()

    assert settings.jarvis_port == 8200
