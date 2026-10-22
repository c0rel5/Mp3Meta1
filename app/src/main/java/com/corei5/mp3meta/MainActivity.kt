package com.corei5.mp3meta

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.AndroidArtwork
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

// ---------- данные ----------
data class Meta(
    val title: String = "", val artist: String = "", val album: String = "",
    val albumArtist: String = "", val year: String = "", val genre: String = "",
    val track: String = "", val cover: ByteArray? = null
)

class Track(val uri: Uri, val file: String, m0: Meta) { var m by mutableStateOf(m0) }
data class Album(val id: String, val name: String, val artist: String, val cover: ByteArray?)

// ---------- работа с тегами ----------
object Tags {
    private fun copy(c: Context, u: Uri): File {
        val f = File.createTempFile("w", ".mp3", c.cacheDir)
        c.contentResolver.openInputStream(u)!!.use { i -> f.outputStream().use { i.copyTo(it) } }
        return f
    }

    fun read(c: Context, u: Uri): Meta {
        val f = copy(c, u)
        return try {
            val t = AudioFileIO.read(f).tag ?: return Meta()
            Meta(
                t.getFirst(FieldKey.TITLE), t.getFirst(FieldKey.ARTIST), t.getFirst(FieldKey.ALBUM),
                t.getFirst(FieldKey.ALBUM_ARTIST), t.getFirst(FieldKey.YEAR), t.getFirst(FieldKey.GENRE),
                t.getFirst(FieldKey.TRACK), t.firstArtwork?.binaryData
            )
        } finally { f.delete() }
    }

    fun write(c: Context, u: Uri, m: Meta, setCover: Boolean) {
        val f = copy(c, u)
        try {
            val af = AudioFileIO.read(f)
            val t = af.tagOrCreateAndSetDefault
            fun s(k: FieldKey, v: String) { if (v.isBlank()) t.deleteField(k) else t.setField(k, v) }
            s(FieldKey.TITLE, m.title); s(FieldKey.ARTIST, m.artist); s(FieldKey.ALBUM, m.album)
            s(FieldKey.ALBUM_ARTIST, m.albumArtist); s(FieldKey.YEAR, m.year)
            s(FieldKey.GENRE, m.genre); s(FieldKey.TRACK, m.track)
            if (setCover) {
                t.deleteArtworkField()
                m.cover?.let {
                    val a = AndroidArtwork()
                    a.binaryData = it; a.mimeType = "image/jpeg"; a.pictureType = 3
                    t.setField(a)
                }
            }
            af.commit()
            c.contentResolver.openOutputStream(u, "wt")!!.use { o -> f.inputStream().use { it.copyTo(o) } }
        } finally { f.delete() }
    }
}

fun coverFrom(c: Context, u: Uri): ByteArray? {
    val b = c.contentResolver.openInputStream(u)!!.use { BitmapFactory.decodeStream(it) } ?: return null
    val s = 1000f / maxOf(b.width, b.height)
    val r = if (s < 1f) Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true) else b
    val o = ByteArrayOutputStream()
    r.compress(Bitmap.CompressFormat.JPEG, 92, o)
    return o.toByteArray()
}

fun toast(c: Context, s: String) = Toast.makeText(c, s, Toast.LENGTH_SHORT).show()

class Store(val c: Context) {
    val tracks = mutableStateListOf<Track>()
    val albums = mutableStateListOf<Album>()
    private val sp = c.getSharedPreferences("a", 0)

    init {
        try {
            val j = JSONArray(sp.getString("al", "[]"))
            for (i in 0 until j.length()) {
                val o = j.getJSONObject(i); val id = o.getString("id")
                val f = File(c.filesDir, "$id.jpg")
                albums.add(Album(id, o.getString("n"), o.getString("a"), if (f.exists()) f.readBytes() else null))
            }
        } catch (e: Exception) { }
    }

    fun put(a: Album) {
        val i = albums.indexOfFirst { it.id == a.id }
        if (i >= 0) albums[i] = a else albums.add(a)
        a.cover?.let { File(c.filesDir, "${a.id}.jpg").writeBytes(it) }
        val arr = JSONArray(albums.map { JSONObject().put("id", it.id).put("n", it.name).put("a", it.artist) })
        sp.edit().putString("al", arr.toString()).apply()
    }

    fun add(uris: List<Uri>) = CoroutineScope(Dispatchers.IO).launch {
        uris.forEach { u ->
            if (tracks.none { it.uri == u }) {
                val n = try { name(u) } catch (e: Throwable) { "track.mp3" }
                if (!n.endsWith(".mp3", true)) {
                    withContext(Dispatchers.Main) { toast(c, "Не mp3: $n") }
                    return@forEach
                }
                val t = Track(u, n, Meta())
                withContext(Dispatchers.Main) { tracks.add(t) }
                try {
                    val m = Tags.read(c, u)
                    withContext(Dispatchers.Main) { t.m = m }
                } catch (e: Throwable) {
                    withContext(Dispatchers.Main) { toast(c, "Теги не прочитались: ${e.message}") }
                }
            }
        }
    }

    private fun name(u: Uri): String = c.contentResolver.query(u, null, null, null, null)?.use {
        if (it.moveToFirst()) it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
    } ?: "track.mp3"
}

// ---------- Metro UI ----------
val Accents = listOf(0xFF0050EF, 0xFFD80073, 0xFF60A917, 0xFFFA6800, 0xFF00ABA9, 0xFFAA00FF).map { Color(it) }
val Dark = Color(0xFF1F1F1F)

class PickAudio : ActivityResultContracts.OpenMultipleDocuments() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
}

class MainActivity : ComponentActivity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val st = Store(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accents[0])) {
                Surface(Modifier.fillMaxSize(), color = Color.Black) { App(st) }
            }
        }
    }
}

@Composable fun MButton(t: String, col: Color, mod: Modifier = Modifier, on: () -> Unit) {
    Box(mod.background(col).clickable(onClick = on).padding(horizontal = 20.dp, vertical = 14.dp), Alignment.Center) {
        Text(t.uppercase(), color = Color.White, fontSize = 14.sp)
    }
}

@Composable fun F(l: String, v: String, on: (String) -> Unit) {
    Text(l, color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
    TextField(
        v, on, Modifier.fillMaxWidth(), shape = RectangleShape, singleLine = true,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.White, unfocusedContainerColor = Color(0xFFCCCCCC),
            focusedTextColor = Color.Black, unfocusedTextColor = Color.Black,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            cursorColor = Color.Black
        )
    )
}

@Composable fun Cover(b: ByteArray?, mod: Modifier, col: Color) {
    val img = remember(b) { b?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
    Box(mod.background(col), contentAlignment = Alignment.Center) {
        if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text("♪", color = Color.White, fontSize = 28.sp)
    }
}

@Composable fun Head(t: String) =
    Text(t, color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Light, modifier = Modifier.padding(vertical = 8.dp))

@Composable fun App(st: Store) {
    val c = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var et by remember { mutableStateOf<Track?>(null) }
    var ea by remember { mutableStateOf<Album?>(null) }
    BackHandler(et != null || ea != null) { et = null; ea = null }
    val pick = rememberLauncherForActivityResult(PickAudio()) { uris ->
        uris.forEach {
            try { c.contentResolver.takePersistableUriPermission(it, 3) }
            catch (e: Exception) { try { c.contentResolver.takePersistableUriPermission(it, 1) } catch (e2: Exception) { } }
        }
        st.add(uris)
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp)) {
        when {
            et != null -> TrackEditor(et!!) { et = null }
            ea != null -> AlbumEditor(st, ea!!) { ea = null }
            else -> {
                Text("MP3 ТЕГИ", color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    listOf("треки", "альбомы", "настройки").forEachIndexed { i, t ->
                        Text(
                            t, fontSize = 34.sp, fontWeight = FontWeight.Light,
                            color = if (tab == i) Color.White else Color(0xFF666666),
                            modifier = Modifier.clickable { tab = i }.padding(end = 20.dp, bottom = 8.dp)
                        )
                    }
                }
                when (tab) {
                    0 -> Tracks(st, { et = it }) { pick.launch(arrayOf("*/*")) }
                    1 -> Albums(st) { ea = it }
                    else -> Settings()
                }
            }
        }
    }
}

@Composable fun Tracks(st: Store, open: (Track) -> Unit, add: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f)) {
            items(st.tracks) { t ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).background(Dark).clickable { open(t) }) {
                    Cover(t.m.cover, Modifier.size(72.dp), Accents[st.tracks.indexOf(t) % Accents.size])
                    Column(Modifier.padding(12.dp)) {
                        Text(t.m.title.ifBlank { t.file }, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Light, maxLines = 1)
                        Text(t.m.artist, color = Color.Gray, maxLines = 1)
                    }
                }
            }
        }
        MButton("+ добавить mp3", Accents[0], Modifier.fillMaxWidth().padding(vertical = 12.dp), add)
    }
}

@Composable fun Albums(st: Store, open: (Album) -> Unit) {
    LazyVerticalGrid(
        GridCells.Fixed(2), horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item {
            Box(
                Modifier.aspectRatio(1f).background(Accents[0])
                    .clickable { open(Album(UUID.randomUUID().toString(), "", "", null)) },
                Alignment.Center
            ) { Text("+", color = Color.White, fontSize = 64.sp, fontWeight = FontWeight.Light) }
        }
        items(st.albums) { a ->
            Box(Modifier.aspectRatio(1f).clickable { open(a) }) {
                Cover(a.cover, Modifier.fillMaxSize(), Accents[(st.albums.indexOf(a) + 1) % Accents.size])
                Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color(0x99000000)).padding(8.dp)) {
                    Text(a.name, color = Color.White, fontSize = 16.sp, maxLines = 1)
                    Text(a.artist, color = Color.LightGray, fontSize = 12.sp, maxLines = 1)
                }
            }
        }
    }
}

@Composable fun TrackEditor(t: Track, close: () -> Unit) {
    val c = LocalContext.current
    val sc = rememberCoroutineScope()
    var m by remember { mutableStateOf(t.m) }
    var coverChanged by remember { mutableStateOf(false) }
    val img = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u ->
        if (u != null) { m = m.copy(cover = coverFrom(c, u)); coverChanged = true }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Head("редактор")
        Cover(m.cover, Modifier.size(160.dp), Accents[0])
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MButton("обложка", Accents[1]) { img.launch("image/*") }
            MButton("убрать", Dark) { m = m.copy(cover = null); coverChanged = true }
        }
        F("название", m.title) { m = m.copy(title = it) }
        F("исполнитель", m.artist) { m = m.copy(artist = it) }
        F("альбом", m.album) { m = m.copy(album = it) }
        F("исполнитель альбома", m.albumArtist) { m = m.copy(albumArtist = it) }
        F("год", m.year) { m = m.copy(year = it) }
        F("жанр", m.genre) { m = m.copy(genre = it) }
        F("номер трека", m.track) { m = m.copy(track = it) }
        MButton("сохранить", Accents[2], Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
            sc.launch(Dispatchers.IO) {
                try {
                    Tags.write(c, t.uri, m, coverChanged)
                    withContext(Dispatchers.Main) { t.m = m; toast(c, "Сохранено"); close() }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { toast(c, "Ошибка: ${e.message}") }
                }
            }
        }
    }
}

@Composable fun AlbumEditor(st: Store, a0: Album, close: () -> Unit) {
    val c = LocalContext.current
    val sc = rememberCoroutineScope()
    var a by remember { mutableStateOf(a0) }
    val sel = remember { mutableStateListOf<Track>() }
    val img = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u ->
        if (u != null) a = a.copy(cover = coverFrom(c, u))
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Head("альбом")
            Cover(a.cover, Modifier.size(160.dp), Accents[1])
            MButton("обложка", Accents[1], Modifier.padding(top = 8.dp)) { img.launch("image/*") }
            F("название альбома", a.name) { a = a.copy(name = it) }
            F("исполнитель", a.artist) { a = a.copy(artist = it) }
            Text("треки (порядок нажатия = номер)", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
        }
        items(st.tracks) { t ->
            val i = sel.indexOf(t)
            Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp)
                    .background(if (i >= 0) Accents[1] else Dark)
                    .clickable { if (i >= 0) sel.remove(t) else sel.add(t) }.padding(14.dp)
            ) {
                Text(if (i >= 0) "${i + 1}" else "·", color = Color.White, fontSize = 18.sp, modifier = Modifier.width(32.dp))
                Text(t.m.title.ifBlank { t.file }, color = Color.White, fontSize = 18.sp, maxLines = 1)
            }
        }
        item {
            MButton("сохранить и применить", Accents[2], Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
                st.put(a)
                if (sel.isEmpty()) close() else sc.launch(Dispatchers.IO) {
                    try {
                        for ((i, t) in sel.withIndex()) {
                            val m = t.m.copy(album = a.name, albumArtist = a.artist, track = "${i + 1}", cover = a.cover ?: t.m.cover)
                            Tags.write(c, t.uri, m, a.cover != null)
                            withContext(Dispatchers.Main) { t.m = m }
                        }
                        withContext(Dispatchers.Main) { toast(c, "Альбом применён"); close() }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) { toast(c, "Ошибка: ${e.message}") }
                    }
                }
            }
        }
    }
}

@Composable fun Settings() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text("автор", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
        Row(Modifier.fillMaxWidth().background(Dark)) {
            Image(painterResource(R.drawable.avatar), null, Modifier.size(120.dp), contentScale = ContentScale.Crop)
            Column(Modifier.padding(16.dp).align(Alignment.CenterVertically)) {
                Text("corei5", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Light)
                Text("создатель", color = Color.Gray, fontSize = 14.sp)
            }
        }
        Text("о приложении", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
        Column(Modifier.fillMaxWidth().background(Accents[0]).padding(16.dp)) {
            Text("MP3 Теги", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Light)
            Text("версия 1.1", color = Color.LightGray, fontSize = 14.sp)
        }
    }
}
