package kz.smartcook.ai

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val http = OkHttpClient.Builder().callTimeout(90, TimeUnit.SECONDS).build()
    private var selected: Uri? = null
    private lateinit var photo: ImageView
    private lateinit var result: TextView
    private lateinit var serverUrl: EditText
    private lateinit var ingredients: EditText
    private lateinit var analyzeButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Color.rgb(246, 249, 245))
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(42, 55, 42, 50)
        }
        scroll.addView(layout)
        fun label(text: String, size: Float = 16f): TextView = TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(Color.rgb(25, 70, 46))
            setPadding(0, 10, 0, 14)
        }
        layout.addView(label("🥗 SmartCook AI", 30f).apply { setTypeface(null, Typeface.BOLD) })
        layout.addView(label("Фото ингредиентов → 3 рецепта с помощью AI", 16f))
        photo = ImageView(this).apply {
            setBackgroundColor(Color.rgb(226, 238, 227))
            scaleType = ImageView.ScaleType.CENTER_CROP
            minimumHeight = 430
            setImageResource(android.R.drawable.ic_menu_camera)
        }
        layout.addView(photo, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 440))
        val selectButton = Button(this).apply { text = "📷 Выбрать фото ингредиентов" }
        layout.addView(selectButton)
        selectButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE) }
            startActivityForResult(Intent.createChooser(intent, "Выберите изображение"), 44)
        }
        layout.addView(label("Ингредиенты вручную (для проверки или исправления):"))
        ingredients = EditText(this).apply {
            hint = "Например: картофель, яйца, лук"
            setSingleLine(false)
            minLines = 2
        }
        layout.addView(ingredients)
        layout.addView(label("Адрес FastAPI сервера:"))
        serverUrl = EditText(this).apply {
            setText("http://10.0.2.2:8000")
            setSingleLine(true)
        }
        layout.addView(serverUrl)
        layout.addView(label("Для Android Emulator используйте 10.0.2.2; на телефоне — IP ноутбука в Wi-Fi." , 12f))
        analyzeButton = Button(this).apply { text = "✨ Найти рецепты с AI" }
        layout.addView(analyzeButton)
        result = label("Выберите фото или введите ингредиенты, затем нажмите кнопку.", 16f)
        layout.addView(result)
        analyzeButton.setOnClickListener { analyze() }
        setContentView(scroll)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 44 && resultCode == RESULT_OK) {
            selected = data?.data
            selected?.let {
                try {
                    contentResolver.openInputStream(it)?.use { stream ->
                        photo.setImageBitmap(BitmapFactory.decodeStream(stream))
                    }
                    result.text = "Фото выбрано. Нажмите «Найти рецепты с AI»."
                } catch (e: Exception) { result.text = "Не удалось открыть изображение: ${e.message}" }
            }
        }
    }

    private fun analyze() {
        val uri = selected
        val typed = ingredients.text.toString().trim()
        if (uri == null && typed.isEmpty()) {
            result.text = "Добавьте фото или введите ингредиенты."
            return
        }
        val base = serverUrl.text.toString().trim().trimEnd('/')
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            result.text = "Укажите полный адрес сервера, начиная с http:// или https://"
            return
        }
        analyzeButton.isEnabled = false
        result.text = "AI анализирует ингредиенты…"
        thread {
            try {
                val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("ingredients", typed)
                if (uri != null) {
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalArgumentException("Не удалось прочитать фото")
                    if (bytes.size > 10_000_000) throw IllegalArgumentException("Фото больше 10 MB")
                    val mime = contentResolver.getType(uri)?.takeIf { it.startsWith("image/") } ?: "image/jpeg"
                    body.addFormDataPart("file", "ingredients.jpg", bytes.toRequestBody(mime.toMediaType()))
                }
                val request = Request.Builder().url("$base/analyze").post(body.build()).build()
                http.newCall(request).execute().use { response ->
                    val content = response.body?.string().orEmpty()
                    if (!response.isSuccessful) throw Exception("HTTP ${response.code}: $content")
                    val obj = JSONObject(content)
                    val found = obj.optJSONArray("ingredients")
                    val dishes = obj.optJSONArray("recipes")
                    val output = StringBuilder("🥕 Ингредиенты:\n")
                    if (found != null) for (i in 0 until found.length()) output.append("• ${found.getString(i)}\n")
                    output.append("\n🍽️ Рецепты:\n")
                    if (dishes != null) for (i in 0 until dishes.length()) {
                        val r = dishes.getJSONObject(i)
                        output.append("\n${i + 1}. ${r.optString("name")} (${r.optInt("minutes", 0)} мин)\n")
                        output.append("Не хватает: ${r.optJSONArray("missing")?.let { a -> (0 until a.length()).joinToString(", ") { j -> a.getString(j) } } ?: "—"}\n")
                        output.append("${r.optString("steps")}\n")
                    }
                    runOnUiThread { result.text = output.toString() }
                }
            } catch (e: Exception) {
                runOnUiThread { result.text = "Ошибка: ${e.message}\nПроверьте, запущен ли сервер и доступен ли он с телефона." }
            } finally { runOnUiThread { analyzeButton.isEnabled = true } }
        }
    }
}
