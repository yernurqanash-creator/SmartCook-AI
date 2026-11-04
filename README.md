# SmartCook AI — Android MVP + FastAPI

Android жобасының бастапқы коды. **Бұл ZIP — APK емес және дайын тексерілген релиз емес.**

## Не істейді
- Android-та галереядан азық-түлік суретін таңдау
- Қолмен ингредиент енгізу немесе танылғандарды нақтылауға арналған мәтін өрісі
- FastAPI арқылы Gemini Vision моделіне жіберу
- 3 рецептті, уақытын, жетіспейтін өнімдерді және дайындау қадамдарын көрсету

## Backend-ті қосу (Python 3.10+)
```bash
cd backend
python -m venv .venv
# Windows:
.venv\Scripts\activate
# macOS/Linux: source .venv/bin/activate
pip install -r requirements.txt
copy .env.example .env   # Windows; macOS/Linux: cp .env.example .env
# .env файлына GEMINI_API_KEY енгізіңіз
uvicorn main:app --host 0.0.0.0 --port 8000
```
Тексеру: http://localhost:8000/health

## Android Studio
1. `SmartCookAI_Android_MVP` түбір папкасын **Open** арқылы ашыңыз.
2. Gradle Sync жасаңыз (интернет және Android SDK қажет).
3. Emulator немесе өз телефоныңызда Run басыңыз.
4. Emulator үшін backend URL: `http://10.0.2.2:8000`.
5. Нақты телефон үшін ноутбук пен телефон бір Wi-Fi желісінде болуы керек; URL: `http://НОУТБУК_IP:8000`.
6. Windows Firewall-да 8000 портына рұқсат беру қажет болуы мүмкін.
7. Build > Build APK(s) арқылы APK құрастырыңыз.

## Ескертулер
- Gemini API key **Android APK ішінде емес, тек backend/.env ішінде** сақталады.
- `.env` файлын GitHub-қа жүктемеңіз.
- MVP үшін жергілікті HTTP рұқсат етілген; жария өнімде HTTPS және аутентификация қажет.
- AI API интернетсіз жұмыс істемейді; тегін квота/қолжетімділік провайдерге байланысты.
- AI жіберетін рецепттерді пайдаланар алдында тексеріңіз: аллергия мен тағам қауіпсіздігі маңызды.
- Бұл код ортасында толық Android build жасалмады, өйткені Android SDK/Gradle wrapper жоқ.
