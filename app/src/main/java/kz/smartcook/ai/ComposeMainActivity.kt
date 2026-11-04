package kz.smartcook.ai

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream

private val Cream = Color(0xFFFFFBF5)
private val Green = Color(0xFF1E5B43)
private val Mint = Color(0xFFE3F1E9)
private val Accent = Color(0xFFE87552)

class ComposeMainActivity : ComponentActivity() {
    private val http = OkHttpClient()
    override fun onCreate(state: Bundle?) { super.onCreate(state); setContent { SmartCookCompose() } }
    @Composable private fun SmartCookCompose() {
        var url by remember { mutableStateOf(getPreferences(0).getString("url", "http://10.0.2.2:8000") ?: "") }
        var bitmap by remember { mutableStateOf<Bitmap?>(null) }; var payload by remember { mutableStateOf<ByteArray?>(null) }
        var text by remember { mutableStateOf("") }; var output by remember { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri ?: return@rememberLauncherForActivityResult
            scope.launch(Dispatchers.IO) { val b = contentResolver.openInputStream(uri)?.use { it.readBytes() }; val p = b?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }; withContext(Dispatchers.Main) { payload = b; bitmap = p } }
        }
        val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { b ->
            if (b != null) { val out = ByteArrayOutputStream(); b.compress(Bitmap.CompressFormat.JPEG, 90, out); bitmap = b; payload = out.toByteArray() }
        }
        MaterialTheme(colorScheme = lightColorScheme(primary = Green, background = Cream)) {
            Surface(Modifier.fillMaxSize(), color = Cream) {
                LazyColumn(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item { Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Column { Text("SmartCook", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = Green); Text("Готовим из того, что есть") }; Text("⚙", fontSize = 24.sp, modifier = Modifier.clickable { output = "Адрес сервера: " + url }) } }
                    item { Card(colors = CardDefaults.cardColors(containerColor = Mint), shape = RoundedCornerShape(24.dp)) { Column(Modifier.padding(20.dp)) { Text("Умный помощник на кухне", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Green); Text("Сфотографируйте продукты — AI распознает их и подберёт рецепты.", color = Green) } } }
                    item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Button({ camera.launch(Unit) }, Modifier.weight(1f)) { Text("Камера") }; OutlinedButton({ gallery.launch("image/*") }, Modifier.weight(1f)) { Text("Галерея") } } }
                    bitmap?.let { item { Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().height(190.dp)) { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } } }
                    item { OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), minLines = 2, label = { Text("Ингредиенты") }, placeholder = { Text("Исправьте список после распознавания") }) }
                    item { Button({ busy = true; scope.launch { runCatching { send(url, payload, text) }.onSuccess { output = it }.onFailure { output = it.message ?: "Ошибка сервера" }; busy = false } }, modifier = Modifier.fillMaxWidth().height(56.dp), enabled = !busy && (payload != null || text.isNotBlank()), shape = RoundedCornerShape(18.dp)) { if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White) else Text("Найти рецепты", fontSize = 17.sp, fontWeight = FontWeight.Bold) } }
                    if (output.isNotBlank()) item { Text(output, color = if (output.startsWith("Ошибка")) Accent else Green, fontSize = 16.sp) }
                }
            }
        }
    }
    private suspend fun send(base: String, bytes: ByteArray?, ingredients: String): String = withContext(Dispatchers.IO) {
        val root = base.trim().trimEnd('/'); require(root.startsWith("http")) { "Укажите адрес FastAPI" }
        val form = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("ingredients", ingredients)
        bytes?.let { form.addFormDataPart("file", "ingredients.jpg", it.toRequestBody("image/jpeg".toMediaType())) }
        http.newCall(Request.Builder().url(root + "/analyze").post(form.build()).build()).execute().use { r -> val body = r.body?.string().orEmpty(); if (!r.isSuccessful) error("Ошибка " + r.code + ": " + JSONObject(body).optString("detail", body)); body }
    }
}
