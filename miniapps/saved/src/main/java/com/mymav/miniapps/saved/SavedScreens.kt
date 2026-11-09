package com.mymav.miniapps.saved

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.mymav.glasses.api.GlassesSession

private val CardBg = Color(0xFFF4F2EE)

@Composable
private fun FileImage(path: String, modifier: Modifier) {
    val bmp = remember(path) {
        runCatching {
            val o = BitmapFactory.Options().apply { inSampleSize = 2 }
            BitmapFactory.decodeFile(path, o)?.asImageBitmap()
        }.getOrNull()
    }
    if (bmp != null) {
        Image(bitmap = bmp, contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop)
    }
}

@Composable
private fun VideoClip(path: String) {
    AndroidView(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(RoundedCornerShape(14.dp)),
        factory = { ctx ->
            VideoView(ctx).apply {
                setVideoPath(path)
                val mc = MediaController(ctx)
                mc.setAnchorView(this)
                setMediaController(mc)
                setOnPreparedListener { it.seekTo(100) }
            }
        },
    )
}

// ---------------------------------------------------------------------------------------------
// Recipe Book
// ---------------------------------------------------------------------------------------------

@Composable
fun RecipeBookMiniScreen(session: GlassesSession, onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember {
        SavedApps.install(context)
        SavedApps.recipesStore!!
    }
    var items by remember { mutableStateOf(store.load()) }
    var openId by remember { mutableStateOf<String?>(null) }
    val open = items.firstOrNull { it.id == openId }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (open == null) {
            Text("Recipes", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Recipes MyMav found for you. Ask the avatar for a recipe, then press SAVE (or say \"save this recipe\").")
            if (items.isEmpty()) {
                Text("Nothing saved yet.", color = Color(0xFF6B7280))
            }
            items.forEach { r ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(CardBg)
                        .border(1.dp, Color(0xFFFF9800).copy(alpha = 0.45f), RoundedCornerShape(18.dp))
                        .clickable { openId = r.id }
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val p = r.imagePath
                    if (p != null) {
                        FileImage(p, Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)))
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(r.name, fontWeight = FontWeight.SemiBold, color = Color(0xFF1A1A1A))
                        if (r.meta.isNotBlank()) Text(r.meta, fontSize = 12.sp, color = Color(0xFF666666))
                        Text("${r.ingredients.size} ingredients", fontSize = 12.sp, color = Color(0xFF666666))
                    }
                }
            }
            Button(
                onClick = { RecipeBookMiniApp.pushHud(session) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Push list to glasses HUD") }
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back to home") }
        } else {
            Text(open.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            if (open.meta.isNotBlank()) Text(open.meta, color = Color(0xFF6B7280))
            val p = open.imagePath
            if (p != null) {
                FileImage(p, Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(16.dp)))
            }
            Text("Ingredients", fontWeight = FontWeight.SemiBold)
            open.ingredients.forEach { Text("• $it") }
            Text("Method", fontWeight = FontWeight.SemiBold)
            Text(open.steps)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { openId = null }) { Text("All recipes") }
                OutlinedButton(onClick = {
                    store.delete(open.id)
                    items = store.load()
                    openId = null
                }) { Text("Delete") }
            }
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back to home") }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Notepad
// ---------------------------------------------------------------------------------------------

@Composable
fun NotepadMiniScreen(session: GlassesSession, onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember {
        SavedApps.install(context)
        SavedApps.notesStore!!
    }
    var items by remember { mutableStateOf(store.load()) }
    // null = list, "new" = new note, otherwise the id being viewed/edited.
    var mode by remember { mutableStateOf<String?>(null) }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var atts by remember { mutableStateOf(listOf<NoteAttachment>()) }
    var link by remember { mutableStateOf("") }

    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri> ->
        val added = uris.mapNotNull { u -> store.copyUri(u, "image")?.let { NoteAttachment("image", it) } }
        atts = atts + added
    }
    val pickVideos = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri> ->
        val added = uris.mapNotNull { u -> store.copyUri(u, "video")?.let { NoteAttachment("video", it) } }
        atts = atts + added
    }

    fun startEdit(n: SavedNote?) {
        title = n?.title.orEmpty()
        body = n?.body.orEmpty()
        atts = n?.attachments.orEmpty()
        link = ""
        mode = n?.id ?: "new"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (mode == null) {
            Text("Notepad", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Notes, pictures and videos. MyMav saves answers here when you press SAVE or say \"take a note ...\".")
            Button(onClick = { startEdit(null) }, modifier = Modifier.fillMaxWidth()) { Text("New note") }
            if (items.isEmpty()) Text("No notes yet.", color = Color(0xFF6B7280))
            items.forEach { n ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(CardBg)
                        .border(1.dp, Color(0xFFFFC107).copy(alpha = 0.5f), RoundedCornerShape(18.dp))
                        .clickable { startEdit(n) }
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(n.title, fontWeight = FontWeight.SemiBold, color = Color(0xFF1A1A1A))
                    if (n.body.isNotBlank()) {
                        Text(n.body.take(140), fontSize = 13.sp, color = Color(0xFF444444))
                    }
                    val imgs = n.attachments.count { it.kind == "image" }
                    val vids = n.attachments.count { it.kind == "video" }
                    val links = n.attachments.count { it.kind == "link" }
                    val tags = listOfNotNull(
                        if (imgs > 0) "$imgs photo" + (if (imgs > 1) "s" else "") else null,
                        if (vids > 0) "$vids video" + (if (vids > 1) "s" else "") else null,
                        if (links > 0) "$links link" + (if (links > 1) "s" else "") else null,
                    )
                    if (tags.isNotEmpty()) Text(tags.joinToString(" · "), fontSize = 12.sp, color = Color(0xFF666666))
                }
            }
            Button(
                onClick = { NotepadMiniApp.pushHud(session) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Push list to glasses HUD") }
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back to home") }
        } else {
            Text(
                if (mode == "new") "New note" else "Edit note",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = body,
                onValueChange = { body = it },
                label = { Text("Note") },
                modifier = Modifier.fillMaxWidth().height(180.dp),
            )
            atts.forEachIndexed { i, a ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    when (a.kind) {
                        "image" -> FileImage(a.ref, Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(14.dp)))
                        "video" -> VideoClip(a.ref)
                        else -> Text(
                            a.ref,
                            color = Color(0xFF1565C0),
                            modifier = Modifier.clickable {
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(a.ref)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }
                            },
                        )
                    }
                    OutlinedButton(onClick = { atts = atts.filterIndexed { j, _ -> j != i } }) { Text("Remove") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickImages.launch("image/*") }) { Text("Add photos") }
                OutlinedButton(onClick = { pickVideos.launch("video/*") }) { Text("Add videos") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it },
                    label = { Text("Video / web link") },
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = {
                    val l = link.trim()
                    if (l.isNotEmpty()) {
                        atts = atts + NoteAttachment("link", l)
                        link = ""
                    }
                }) { Text("Add") }
            }
            Button(
                onClick = {
                    val t = title.trim().ifBlank { body.trim().take(30).ifBlank { "Note" } }
                    val existing = items.firstOrNull { it.id == mode }
                    if (existing != null) {
                        store.upsert(existing.copy(title = t, body = body, attachments = atts))
                    } else {
                        store.create(t, body, atts)
                    }
                    items = store.load()
                    mode = null
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save note") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { mode = null; items = store.load() }) { Text("Cancel") }
                if (mode != "new") {
                    OutlinedButton(onClick = {
                        store.delete(mode!!)
                        items = store.load()
                        mode = null
                    }) { Text("Delete") }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Shopping list
// ---------------------------------------------------------------------------------------------

@Composable
fun ShoppingMiniScreen(session: GlassesSession, onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember {
        SavedApps.install(context)
        SavedApps.shoppingStore!!
    }
    var items by remember { mutableStateOf(store.load()) }
    var draft by remember { mutableStateOf("") }
    fun refresh() { items = store.load() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Shopping", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Say \"add milk to my shopping list\" or \"add all ingredients to my shopping list\" after a recipe.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = draft, onValueChange = { draft = it }, singleLine = true,
                label = { Text("Add item") }, modifier = Modifier.weight(1f),
            )
            Button(onClick = {
                com.mymav.glasses.api.MymavKitchen.splitItems(draft).let { store.add(it) }
                draft = ""; refresh()
            }) { Text("Add") }
        }
        if (items.isEmpty()) Text("Nothing on the list.", color = Color(0xFF6B7280))
        items.forEach { it0 ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(CardBg)
                    .clickable { store.toggle(it0.name); refresh() }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    (if (it0.done) "\u2611  " else "\u2610  ") + it0.name,
                    color = if (it0.done) Color(0xFF9CA3AF) else Color(0xFF1A1A1A),
                    textDecoration = if (it0.done) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                )
                Text("Remove", color = Color(0xFFB91C1C), fontSize = 12.sp,
                    modifier = Modifier.clickable { store.delete(it0.name); refresh() })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { store.clearDone(); refresh() }) { Text("Clear ticked") }
            OutlinedButton(onClick = { store.clearAll(); refresh() }) { Text("Clear all") }
        }
        Button(onClick = { ShoppingMiniApp.pushHud(session) }, modifier = Modifier.fillMaxWidth()) { Text("Push list to glasses HUD") }
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back to home") }
    }
}
