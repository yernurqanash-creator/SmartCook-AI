import json
import os
from pathlib import Path
from typing import Annotated, Any
from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from pydantic import BaseModel, Field
from fastapi.middleware.cors import CORSMiddleware
from dotenv import load_dotenv
from google import genai
from google.genai import types

# Resolve the secret file from the backend module location, not the shell cwd.
load_dotenv(Path(__file__).with_name(".env"))
app = FastAPI(title="SmartCook AI API")
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])

class RecipeRequest(BaseModel):
    ingredients: list[str] = Field(min_length=1, max_length=50)

class Recipe(BaseModel):
    name: str
    minutes: int = Field(ge=1, le=360)
    ingredients: list[str] = Field(default_factory=list)
    missing: list[str] = Field(default_factory=list)
    steps: list[str] = Field(min_length=1)

class SmartCookResponse(BaseModel):
    ingredients: list[str] = Field(default_factory=list)
    recipes: list[Recipe] = Field(min_length=1, max_length=5)

def _normalize_ai_json(raw: str) -> SmartCookResponse:
    parsed: Any = json.loads(raw)
    # Gemini occasionally returns a JSON-encoded string despite JSON mode.
    while isinstance(parsed, str):
        parsed = json.loads(parsed)
    if not isinstance(parsed, dict):
        raise ValueError("AI response root is not an object")
    for recipe in parsed.get("recipes", []):
        if isinstance(recipe, dict) and isinstance(recipe.get("steps"), str):
            recipe["steps"] = [step.strip() for step in recipe["steps"].split("\n") if step.strip()]
    return SmartCookResponse.model_validate(parsed)

def _generate(prompt_parts: list[Any]) -> SmartCookResponse:
    key = os.getenv("GEMINI_API_KEY")
    if not key:
        raise HTTPException(503, "GEMINI_API_KEY is not configured in backend/.env")
    try:
        client = genai.Client(api_key=key)
        response = client.models.generate_content(
            model=os.getenv("GEMINI_MODEL", "gemini-2.5-flash"),
            contents=prompt_parts,
            config=types.GenerateContentConfig(response_mime_type="application/json"),
        )
        return _normalize_ai_json(response.text or "{}")
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(502, f"AI service unavailable or returned invalid JSON: {str(exc)[:300]}") from exc

@app.get("/health")
def health():
    return {"status": "ok", "ai_configured": bool(os.getenv("GEMINI_API_KEY"))}

@app.post("/analyze")
async def analyze(
    ingredients: Annotated[str, Form()] = "",
    file: UploadFile | None = File(default=None),
):
    if not ingredients.strip() and not file:
        raise HTTPException(400, "Добавьте фото или ингредиенты")
    parts = []
    if ingredients.strip():
        parts.append("Перечисленные пользователем ингредиенты (приоритет): " + ingredients[:1200])
    if file:
        data = await file.read()
        if len(data) > 10_000_000:
            raise HTTPException(413, "Фото превышает 10 MB")
        mime = file.content_type or "image/jpeg"
        if not mime.startswith("image/"):
            raise HTTPException(400, "Только изображения")
        parts.append(types.Part.from_bytes(data=data, mime_type=mime))
    parts.append("""Ты профессиональный помощник по готовке. Если пользователь перечислил ингредиенты,
считай их точно доступными, даже если нет фотографии. Если есть фото — определи только видимые продукты
и объедини их с вручную введённым списком. Подбери ровно 3 готовых, привычных блюда, которые можно
приготовить из доступных продуктов; не называй блюдо просто списком продуктов. Сначала используй максимум
имеющихся ингредиентов, а недостающие укажи отдельно в missing.
Отдавай исключительно JSON объект без markdown:
{"ingredients":["картофель"],"recipes":[{"name":"...","minutes":15,"ingredients":["..."],
"missing":["..."],"steps":["Шаг 1","Шаг 2"]}]}
Пиши по-русски. Отмечай неизвестные или сомнительные ингредиенты осторожно.
Не утверждай, что можно определить свежесть или срок годности по фото.
Для мяса и яиц укажи необходимость полной термообработки при необходимости.
Не предлагай употреблять испорченные продукты.""")
    try:
        return _generate(parts)
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(400, f"Ошибка обработки запроса: {str(exc)[:300]}") from exc

@app.post("/recipes")
def recipes(request: RecipeRequest):
    names = ", ".join(dict.fromkeys(x.strip() for x in request.ingredients if x.strip()))
    if not names:
        raise HTTPException(400, "ingredients must contain at least one non-empty value")
    prompt = f"""Составь ровно 3 практичных рецепта по продуктам: {names}.
Используй максимум доступных продуктов, минимизируй недостающие и пищевые отходы.
Верни только JSON: {{"ingredients":[...],"recipes":[{{"name":"...","minutes":30,"ingredients":[...],"missing":[...],"steps":["шаг 1","шаг 2"]}}]}}.
Пиши по-русски. Не выдумывай продукты, которых нет в списке, как доступные."""
    return _generate([prompt])
