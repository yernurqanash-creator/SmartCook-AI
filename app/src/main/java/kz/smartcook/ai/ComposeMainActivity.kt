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
        var showSettings by remember { mutableStateOf(false) }
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
                LazyColumn(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item { Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Column { Text("SmartCook", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = Green); Text("Готовим из того, что есть") }; Text("⚙", fontSize = 24.sp, modifier = Modifier.clickable { showSettings = true }) } }
                    item { Card(colors = CardDefaults.cardColors(containerColor = Mint), shape = RoundedCornerShape(24.dp)) { Column(Modifier.padding(20.dp)) { Text("Умный помощник на кухне", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Green); Text("Сфотографируйте продукты — AI распознает их и подберёт рецепты.", color = Green) } } }
                    item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Button({ if (ContextCompat.checkSelfPermission(this@ComposeMainActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) camera.launch(null) else permission.launch(Manifest.permission.CAMERA) }, Modifier.weight(1f)) { Text("Камера") }; OutlinedButton({ gallery.launch("image/*") }, Modifier.weight(1f)) { Text("Галерея") } } }
                    bitmap?.let { item { Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().height(190.dp)) { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } } }
                    item { OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), minLines = 2, label = { Text("Ингредиенты") }, placeholder = { Text("Исправьте список после распознавания") }) }
                    item { Button({ busy = true; scope.launch { runCatching { send(url, payload, text) }.onSuccess { output = it }.onFailure { output = it.message ?: "Ошибка сервера" }; busy = false } }, modifier = Modifier.fillMaxWidth().height(56.dp), enabled = !busy && (payload != null || text.isNotBlank()), shape = RoundedCornerShape(18.dp)) { if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White) else Text("Найти рецепты", fontSize = 17.sp, fontWeight = FontWeight.Bold) } }
                    if (output.isNotBlank()) item { Text(output, color = if (output.startsWith("Ошибка")) Accent else Green, fontSize = 16.sp) }
                }
                if (showSettings) UrlDialog(url, { url = it }, { getPreferences(0).edit().putString("url", url.trim().trimEnd('/')).apply(); showSettings = false; output = "Адрес сохранён: " + url }, { showSettings = false })
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
