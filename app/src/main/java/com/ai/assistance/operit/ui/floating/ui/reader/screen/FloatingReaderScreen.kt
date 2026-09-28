package com.ai.assistance.operit.ui.floating.ui.reader.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.operit.data.model.ChatTurnOptions
import com.ai.assistance.operit.data.model.PromptFunctionType
import com.ai.assistance.operit.ui.floating.FloatContext
import com.ai.assistance.operit.ui.floating.FloatingMode
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

private val readerClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
}

private const val MIN_LINE_LENGTH = 12

private val NAV_WORDS =
    listOf(
        "上一章",
        "下一章",
        "下一节",
        "返回目录",
        "章节目录",
        "加入书架",
        "投推荐票",
        "手机版",
        "本章未完",
        "点击下一页",
        "请记住本站",
        "天才一秒",
        "无弹窗",
        "最新网址",
        "全文阅读",
        "温馨提示",
        "版权所有",
        "举报",
        "章节报错",
        "字体大小",
        "背景颜色",
        "扫码",
        "APP阅读"
    )

private val CONTENT_SELECTORS =
    listOf(
        "#content",
        "#chaptercontent",
        "#chapter-content",
        "#booktext",
        "#htmlContent",
        "#nr1",
        "#nr",
        ".content",
        ".chaptercontent",
        ".read-content",
        ".showtxt",
        ".yd_text2"
    )

private data class ChapterContent(
    val title: String,
    val paragraphs: List<String>,
    val nextUrl: String?
)

private fun pickContent(doc: Document): Element? {
    for (selector in CONTENT_SELECTORS) {
        val element = doc.selectFirst(selector)
        if (element != null && element.text().length >= 200) return element
    }
    val loose =
        doc.select(
            "[id*=content], [class*=content], [id*=chapter], [class*=chapter], [id*=read], [class*=read]"
        )
    val looseBest = loose.maxByOrNull { it.text().length }
    if (looseBest != null && looseBest.text().length >= 300) return looseBest
    var bestScore = 0
    var bestElement: Element? = null
    for (candidate in doc.select("div, article, section")) {
        val score = candidate.select("p").sumOf { it.text().length }
        if (score > bestScore) {
            bestScore = score
            bestElement = candidate
        }
    }
    if (bestElement != null && bestScore >= 200) return bestElement
    return doc.body()
}

private fun decodeEntities(input: String): String {
    var text =
        input
            .replace("&nbsp;", " ")
            .replace("&ldquo;", "\u201c")
            .replace("&rdquo;", "\u201d")
            .replace("&hellip;", "\u2026")
            .replace("&mdash;", "\u2014")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
    val numeric = Regex("&#(\\d{1,6});")
    text =
        numeric.replace(text) { match ->
            val code = match.groupValues[1].toIntOrNull()
            if (code == null || code <= 0) match.value else String(Character.toChars(code))
        }
    return text
}

private fun extractParagraphs(container: Element): List<String> {
    val withBreaks =
        container
            .html()
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</(p|div|h[1-6]|li|tr|section)>"), "\n")
    val plain = withBreaks.replace(Regex("<[^>]*>"), "")
    return decodeEntities(plain)
        .replace("\u00a0", " ")
        .replace("\r", "")
        .split("\n")
        .map { it.trim() }
        .filter { it.length >= MIN_LINE_LENGTH }
        .filter { line -> NAV_WORDS.none { line.contains(it, ignoreCase = true) } }
}

private fun findNextUrl(doc: Document): String? {
    for (anchor in doc.select("a")) {
        val label = anchor.text().trim()
        if (label.contains("下一章") || label.contains("下一页") || label.contains("下一节")) {
            val url = anchor.absUrl("href")
            if (url.isNotBlank()) return url
        }
    }
    return null
}

private fun fetchChapter(url: String): Result<ChapterContent> {
    return try {
        val request =
            Request.Builder()
                .url(url)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                )
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .build()
        readerClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}")
            }
            val html = response.body?.string().orEmpty()
            if (html.isBlank()) {
                throw IllegalStateException("页面是空的")
            }
            val doc = Jsoup.parse(html, url)
            doc.select("script, style, noscript, iframe, nav, header, footer").remove()
            val container = pickContent(doc) ?: throw IllegalStateException("没找到正文")
            val title = doc.title().trim().ifBlank { "正文" }
            Result.success(
                ChapterContent(
                    title = title,
                    paragraphs = extractParagraphs(container),
                    nextUrl = findNextUrl(doc)
                )
            )
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}

@Composable
fun FloatingReaderScreen(floatContext: FloatContext) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val listState = rememberLazyListState()

    var urlText by remember { mutableStateOf("") }
    var chapterTitle by remember { mutableStateOf("") }
    var paragraphs by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedIndex by remember { mutableStateOf(-1) }
    var isLoading by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("贴上小说网址，点抓正文") }
    var nextUrl by remember { mutableStateOf<String?>(null) }
    var urlFocused by remember { mutableStateOf(false) }

    LaunchedEffect(urlFocused) {
        floatContext.onInputFocusRequest?.invoke(urlFocused)
    }

    fun loadChapter(target: String) {
        val url = target.trim()
        if (url.isBlank()) {
            statusText = "网址是空的"
            return
        }
        if (isLoading) return
        isLoading = true
        statusText = "抓取中…"
        scope.launch {
            val result = withContext(Dispatchers.IO) { fetchChapter(url) }
            result
                .onSuccess { chapter ->
                    chapterTitle = chapter.title
                    paragraphs = chapter.paragraphs
                    nextUrl = chapter.nextUrl
                    selectedIndex = -1
                    statusText =
                        if (chapter.paragraphs.isEmpty()) {
                            "没抓到正文，换个网址试试"
                        } else {
                            "抓到 ${chapter.paragraphs.size} 段，点一段选中"
                        }
                    if (chapter.paragraphs.isNotEmpty()) {
                        listState.scrollToItem(0)
                    }
                }
                .onFailure { error ->
                    statusText = "抓取失败：${error.message ?: "未知错误"}"
                }
            isLoading = false
        }
    }

    fun sendSelected() {
        val index = selectedIndex
        if (index < 0 || index >= paragraphs.size) return
        val text = paragraphs[index]
        val core =
            try {
                floatContext.chatService?.getChatCore()
            } catch (_: Exception) {
                null
            }
        if (core == null) {
            statusText = "聊天没连上，发不出去"
            return
        }
        try {
            core.sendUserMessage(
                promptFunctionType = PromptFunctionType.CHAT,
                messageTextOverride = text,
                turnOptions = ChatTurnOptions(hideUserMessage = true)
            )
            statusText = "已发第 ${index + 1} 段"
        } catch (e: Exception) {
            statusText = "发送失败：${e.message ?: "未知错误"}"
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier =
                        Modifier.fillMaxWidth()
                            .height(40.dp)
                            .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MenuBook,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Box(
                        modifier =
                            Modifier.weight(1f)
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        floatContext.onMove(
                                            dragAmount.x,
                                            dragAmount.y,
                                            floatContext.windowScale
                                        )
                                    }
                                },
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text(
                            text = chapterTitle.ifBlank { "共读小窗" },
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(
                        onClick = { floatContext.onModeChange(FloatingMode.BALL) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "收起",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        onClick = { floatContext.onClose() },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "关闭",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedTextField(
                        value = urlText,
                        onValueChange = { urlText = it },
                        modifier =
                            Modifier.weight(1f)
                                .onFocusChanged { urlFocused = it.isFocused },
                        placeholder = { Text("贴小说网址", style = MaterialTheme.typography.bodySmall) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { loadChapter(urlText) })
                    )
                    TextButton(onClick = { loadChapter(urlText) }, enabled = !isLoading) {
                        Text(if (isLoading) "…" else "抓正文")
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (paragraphs.isEmpty()) {
                        Text(
                            text = "正文会显示在这里。点一段选中，再点下面「发这段」，我就能看到。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.padding(16.dp)
                        )
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
                        ) {
                            itemsIndexed(paragraphs) { index, line ->
                                val selected = index == selectedIndex
                                Text(
                                    text = line,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontSize = 15.sp,
                                    lineHeight = 24.sp,
                                    color =
                                        if (selected) {
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                    modifier =
                                        Modifier.fillMaxWidth()
                                            .padding(vertical = 3.dp)
                                            .background(
                                                color =
                                                    if (selected) {
                                                        MaterialTheme.colorScheme
                                                            .primaryContainer
                                                    } else {
                                                        Color.Transparent
                                                    },
                                                shape = RoundedCornerShape(8.dp)
                                            )
                                            .clickable { selectedIndex = index }
                                            .padding(horizontal = 8.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = {
                            val target = nextUrl
                            if (target != null) {
                                urlText = target
                                loadChapter(target)
                            }
                        },
                        enabled = nextUrl != null && !isLoading
                    ) {
                        Text("下一章")
                    }
                    Button(
                        onClick = { sendSelected() },
                        enabled = selectedIndex >= 0,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Send,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("发这段", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        Box(
            modifier =
                Modifier.align(Alignment.BottomEnd)
                    .size(24.dp)
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            val newWidth =
                                floatContext.windowWidthState +
                                    with(density) { dragAmount.x.toDp() }
                            val newHeight =
                                floatContext.windowHeightState +
                                    with(density) { dragAmount.y.toDp() }
                            floatContext.onResize(newWidth, newHeight)
                        }
                    },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier =
                    Modifier.size(10.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(3.dp)
                        )
            )
        }
    }
}
