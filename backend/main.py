import json
import os
from typing import Annotated, Any
from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from pydantic import BaseModel, Field
from fastapi.middleware.cors import CORSMiddleware
from dotenv import load_dotenv
from google import genai
from google.genai import types

load_dotenv()
app = FastAPI(title="SmartCook AI API")
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])

class RecipeRequest(BaseModel):
    ingredients: list[str] = Field(min_length=1, max_length=50)

def _generate(prompt_parts: list[Any]) -> dict:
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
        parsed = json.loads(response.text or "{}")
        if not isinstance(parsed.get("ingredients"), list) or not isinstance(parsed.get("recipes"), list):
            raise ValueError("AI response did not match the expected schema")
        return parsed
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
    parts.append("""Ты помощник по готовке. Определи видимые продукты на фото (не придумывай невидимые),
объедини с перечисленными пользователем продуктами. Предложи ровно 3 реалистичных блюда.
Отдавай исключительно JSON объект без markdown:
{"ingredients":["картофель"], "recipes":[{"name":"...", "minutes":15,
"missing":["..."], "steps":"Короткие безопасные шаги приготовления."}]}
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
