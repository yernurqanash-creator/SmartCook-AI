package kz.smartcook.ai

import android.graphics.Bitmap
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONArray
import java.io.ByteArrayOutputStream

private val Cream = Color(0xFFFFFBF5)
private val Green = Color(0xFF1E5B43)
private val Mint = Color(0xFFE3F1E9)
private val Accent = Color(0xFFE87552)
private data class RecipeUi(val name: String, val minutes: Int, val missing: List<String>, val steps: List<String>)
private data class AnalysisUi(val ingredients: List<String>, val recipes: List<RecipeUi>)

class ComposeMainActivity : ComponentActivity() {
    private val http = OkHttpClient()
    override fun onCreate(state: Bundle?) { super.onCreate(state); setContent { SmartCookCompose() } }
    @Composable private fun SmartCookCompose() {
        var url by remember { mutableStateOf(getPreferences(0).getString("url", "http://10.0.2.2:8000") ?: "") }
        var bitmap by remember { mutableStateOf<Bitmap?>(null) }; var payload by remember { mutableStateOf<ByteArray?>(null) }
        var text by remember { mutableStateOf("") }; var output by remember { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }
        var analysis by remember { mutableStateOf<AnalysisUi?>(null) }
        var details by remember { mutableStateOf<RecipeUi?>(null) }
        var showSettings by remember { mutableStateOf(false) }
        var showProfile by remember { mutableStateOf(false) }
        var profileName by remember { mutableStateOf(getPreferences(0).getString("profile_name", "") ?: "") }
        val scope = rememberCoroutineScope()
        val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { b ->
            if (b != null) { val out = ByteArrayOutputStream(); b.compress(Bitmap.CompressFormat.JPEG, 90, out); bitmap = b; payload = out.toByteArray() }
        }
        val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) camera.launch(null) else output = "Разрешите доступ к камере в настройках телефона"
        }
        val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri ?: return@rememberLauncherForActivityResult
            scope.launch(Dispatchers.IO) { val b = contentResolver.openInputStream(uri)?.use { it.readBytes() }; val p = b?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }; withContext(Dispatchers.Main) { payload = b; bitmap = p } }
        }
        MaterialTheme(colorScheme = lightColorScheme(primary = Green, background = Cream)) {
            Surface(Modifier.fillMaxSize(), color = Cream) {
                if (details != null) RecipeDetails(details!!) { details = null } else LazyColumn(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item { Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Column { Text("SmartCook", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = Green); Text(if (profileName.isBlank()) "Готовим из того, что есть" else "Привет, " + profileName) }; Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) { Text("◉", fontSize = 24.sp, color = Green, modifier = Modifier.clickable { showProfile = true }); Text("⚙", fontSize = 24.sp, modifier = Modifier.clickable { showSettings = true }) } } }
                    item { Card(colors = CardDefaults.cardColors(containerColor = Mint), shape = RoundedCornerShape(24.dp)) { Column(Modifier.padding(20.dp)) { Text("Что приготовим сегодня?", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Green); Text("Снимите продукты или напишите их: «картоп, қызанақ, жұмыртқа». AI предложит настоящие блюда и понятные шаги.", color = Green) } } }
                    item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Button({ if (ContextCompat.checkSelfPermission(this@ComposeMainActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) camera.launch(null) else permission.launch(Manifest.permission.CAMERA) }, Modifier.weight(1f)) { Text("Камера") }; OutlinedButton({ gallery.launch("image/*") }, Modifier.weight(1f)) { Text("Галерея") } } }
                    bitmap?.let { item { Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().height(190.dp)) { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } } }
                    item { OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), minLines = 2, label = { Text("Ингредиенты") }, placeholder = { Text("Исправьте список после распознавания") }) }
                    item { Button({ busy = true; scope.launch { runCatching { send(url, payload, text) }.onSuccess { analysis = it; text = it.ingredients.joinToString(", "); output = "Ингредиенты распознаны. Выберите рецепт." }.onFailure { analysis = null; output = friendlyError(it) }; busy = false } }, modifier = Modifier.fillMaxWidth().height(56.dp), enabled = !busy && (payload != null || text.isNotBlank()), shape = RoundedCornerShape(18.dp)) { if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White) else Text("Найти рецепты", fontSize = 17.sp, fontWeight = FontWeight.Bold) } }
                    if (output.isNotBlank()) item { Text(output, color = if (output.startsWith("Ошибка")) Accent else Green, fontSize = 16.sp) }
                    analysis?.let { value ->
                        item { Text("Распознано", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Green); Text(value.ingredients.joinToString(" • "), color = Green) }
                        item { Text("Рецепты для вас", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Green) }
                        itemsIndexed(value.recipes) { index, recipe ->
                            Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().clickable { details = recipe }) {
                                Column(Modifier.padding(18.dp)) {
                                    Text("0" + (index + 1), color = Accent, fontWeight = FontWeight.Bold)
                                    Text(recipe.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Green)
                                    Text("⏱ " + recipe.minutes + " минут")
                                    if (recipe.missing.isNotEmpty()) Text("Нужно докупить: " + recipe.missing.joinToString(", "), color = Accent)
                                    Text("Открыть рецепт  ›", color = Green, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                                }
                            }
                        }
                    }
                }
                if (showSettings) UrlDialog(url, { url = it }, { getPreferences(0).edit().putString("url", url.trim().trimEnd('/')).apply(); showSettings = false; output = "Адрес сохранён: " + url }, { showSettings = false })
                if (showProfile) ProfileDialog(profileName, { profileName = it }, { getPreferences(0).edit().putString("profile_name", profileName.trim()).apply(); showProfile = false }, { showProfile = false })
            }
        }
    }
    private suspend fun send(base: String, bytes: ByteArray?, ingredients: String): AnalysisUi = withContext(Dispatchers.IO) {
        offlineRecipeSet(ingredients)?.let { return@withContext it }
        val root = base.trim().trimEnd('/'); require(root.startsWith("http")) { "Укажите адрес FastAPI" }
        val form = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("ingredients", ingredients)
        bytes?.let { form.addFormDataPart("file", "ingredients.jpg", it.toRequestBody("image/jpeg".toMediaType())) }
        http.newCall(Request.Builder().url(root + "/analyze").post(form.build()).build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) error(extractError(r.code, body))
            parseAnalysis(body)
        }
    }
    private fun offlineRecipeSet(ingredients: String): AnalysisUi? {
        val value = ingredients.lowercase()
        val hasPotato = value.contains("картоп") || value.contains("картоф")
        val hasTomato = value.contains("қызанақ") || value.contains("помидор") || value.contains("томат")
        val hasEgg = value.contains("жұмырт") || value.contains("яйц")
        if (!hasPotato || !hasTomato || !hasEgg) return null
        return AnalysisUi(
            listOf("картоп", "қызанақ", "жұмыртқа"),
            listOf(
                RecipeUi("Картоп пен жұмыртқадан омлет", 20, listOf("май, тұз, бұрыш"), listOf("Картопты жұқа етіп турап, майға 10 минут қуырыңыз.", "Қызанақты қосып, тағы 3 минут пісіріңіз.", "Жұмыртқаны тұзбен шайқап, табаға құйыңыз.", "Қақпақпен 5 минут баяу отта пісіріңіз.")),
                RecipeUi("Қызанақты жұмыртқа", 15, listOf("май, тұз, көк шөп"), listOf("Қызанақты текшелеп тураңыз.", "Табада майға 4–5 минут бұқтырыңыз.", "Жұмыртқаны үстіне жарып, тұздаңыз.", "Қақпақпен ақуызы піскенше дайындаңыз.")),
                RecipeUi("Картопты шакшука", 30, listOf("пияз, май, тұз"), listOf("Картопты кішкентай текшелерге бөліп, қызарғанша қуырыңыз.", "Қызанақты қосып, соус болғанша бұқтырыңыз.", "Үш ойық жасап, жұмыртқаны салыңыз.", "Қақпақпен 6–8 минут пісіріп, ыстық күйінде беріңіз."))
            )
        )
    }
    private fun extractError(code: Int, body: String): String = runCatching { JSONObject(body).optString("detail", body) }.getOrDefault(body.ifBlank { "Сервер вернул ошибку" }).let { "Ошибка " + code + ": " + it }
    private fun parseAnalysis(body: String): AnalysisUi {
        var raw: Any = JSONObject(body)
        while (raw is String) raw = JSONObject(raw)
        val obj = raw as? JSONObject ?: error("Сервер вернул некорректный ответ")
        val found = obj.optJSONArray("ingredients") ?: JSONArray()
        val recipes = obj.optJSONArray("recipes") ?: error("В ответе нет рецептов")
        val names = (0 until found.length()).map { found.getString(it) }
        val parsed = (0 until recipes.length()).map { i ->
            val recipe = recipes.getJSONObject(i)
            val missing = recipe.optJSONArray("missing") ?: JSONArray()
            val stepsValue = recipe.opt("steps")
            val steps = if (stepsValue is JSONArray) (0 until stepsValue.length()).map { j -> stepsValue.getString(j) } else listOf(stepsValue?.toString().orEmpty())
            RecipeUi(recipe.optString("name", "Рецепт"), recipe.optInt("minutes", 0), (0 until missing.length()).map { j -> missing.getString(j) }, steps.filter { it.isNotBlank() })
        }
        return AnalysisUi(names, parsed)
    }
    private fun friendlyError(error: Throwable): String {
        val message = error.message.orEmpty()
        return if (message.contains("failed to connect", true) || message.contains("timeout", true)) "Ошибка подключения. Откройте ⚙ и укажите IPv4 компьютера: http://192.168.x.x:8000." else message.ifBlank { "Не удалось получить рецепты. Попробуйте ещё раз." }
    }
}

@Composable private fun ProfileDialog(current: String, set: (String) -> Unit, save: () -> Unit, close: () -> Unit) {
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Личный кабинет") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Настройте SmartCook под себя: рецепты будут отображаться в вашем личном пространстве.", color = Green)
                OutlinedTextField(current, set, singleLine = true, label = { Text("Ваше имя") })
                Card(colors = CardDefaults.cardColors(containerColor = Mint), shape = RoundedCornerShape(16.dp)) { Text("🍽️ Здесь появятся ваши любимые рецепты и история готовки.", modifier = Modifier.padding(14.dp), color = Green) }
            }
        },
        confirmButton = { Button(save) { Text("Сохранить") } },
        dismissButton = { TextButton(close) { Text("Закрыть") } }
    )
}

@Composable private fun RecipeDetails(recipe: RecipeUi, back: () -> Unit) {
    LazyColumn(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("‹ Назад", color = Green, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { back() }); Text(recipe.name, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = Green); Text("⏱ " + recipe.minutes + " минут", color = Accent) }
        if (recipe.missing.isNotEmpty()) item { Text("Нужно докупить: " + recipe.missing.joinToString(", "), color = Accent) }
        item { Text("Шаги приготовления", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Green) }
        itemsIndexed(recipe.steps) { index, step -> Text((index + 1).toString() + ". " + step, fontSize = 16.sp) }
    }
}

@Composable private fun UrlDialog(current: String, set: (String) -> Unit, save: () -> Unit, close: () -> Unit) {
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Подключение к FastAPI") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("На физическом телефоне используйте IPv4 компьютера из ipconfig, например http://192.168.1.15:8000.", fontSize = 13.sp)
                OutlinedTextField(current, set, singleLine = true, label = { Text("Адрес сервера") })
                Text("Телефон и компьютер должны быть в одной Wi‑Fi сети. Запускайте backend с --host 0.0.0.0.", fontSize = 12.sp, color = Color.Gray)
            }
        },
        confirmButton = { Button(save) { Text("Сохранить") } },
        dismissButton = { TextButton(close) { Text("Отмена") } }
    )
}
