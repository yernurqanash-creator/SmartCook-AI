from fastapi.testclient import TestClient
import main

client = TestClient(main.app)

def test_health_does_not_expose_key(monkeypatch):
    monkeypatch.delenv("GEMINI_API_KEY", raising=False)
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok", "ai_configured": False}

def test_analyze_requires_input(monkeypatch):
    monkeypatch.setenv("GEMINI_API_KEY", "test-only")
    response = client.post("/analyze", data={"ingredients": ""})
    assert response.status_code == 400

def test_recipes_validates_ingredients():
    response = client.post("/recipes", json={"ingredients": []})
    assert response.status_code == 422
