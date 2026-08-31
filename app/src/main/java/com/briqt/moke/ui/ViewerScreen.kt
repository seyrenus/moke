package com.briqt.moke.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.briqt.moke.R
import com.briqt.moke.terminal.sftp.GitDiff
import com.briqt.moke.terminal.sftp.RemotePath
import com.briqt.moke.ui.theme.MokeMono
import kotlinx.coroutines.launch

/** 展平后的渲染行：文本模式一行一项；diff 模式 = 文件头 + hunk 头 + 改动行。搜索按行文本匹配。 */
private sealed interface ViewerRow {
    data class TextLine(val index: Int, val text: String) : ViewerRow
    data class FileHeader(val file: GitDiff.DiffFile) : ViewerRow
    data class DiffRow(val file: GitDiff.DiffFile, val line: GitDiff.DiffLine) : ViewerRow
}

/** 高亮与搜索命中用的配色（随明暗主题取色，见 [diffPalette]）。 */
private data class ViewerPalette(
    val comment: Color,
    val string: Color,
    val number: Color,
    val keyword: Color,
    val matchBg: Color,
    val matchCurrentBg: Color,
    val addFg: Color,
    val delFg: Color,
)

private fun viewerPalette(dark: Boolean) = if (dark) {
    ViewerPalette(
        comment = Color(0xFF8A9A7B), string = Color(0xFFB5CEA8), number = Color(0xFF9CBFDF),
        keyword = Color(0xFFD0A8E8),
        matchBg = Color(0x66E0B000), matchCurrentBg = Color(0xAAE07000),
        addFg = Color(0xFF81C784), delFg = Color(0xFFE57373),
    )
} else {
    ViewerPalette(
        comment = Color(0xFF6B7F5E), string = Color(0xFF4A7A2E), number = Color(0xFF2B5F9E),
        keyword = Color(0xFF8A3FC7),
        matchBg = Color(0x66FFD54F), matchCurrentBg = Color(0xAAFF9E40),
        addFg = Color(0xFF2E7D32), delFg = Color(0xFFC62828),
    )
}

/** diff 行背景：加/删行各取前景色低透明度铺底，浅深主题自动成立。 */
private fun ViewerPalette.addBg() = addFg.copy(alpha = 0.14f)
private fun ViewerPalette.delBg() = delFg.copy(alpha = 0.14f)

/**
 * 文件查看器：文本内容与 git diff 两种内容类型共用一个壳（顶栏 / 搜索 / 换行开关 / 刷新）。
 * 一切内容都是打开时的快照（设计共识），刷新走顶栏手动触发。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    state: ViewerUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    /** 二进制文件的下载引导（触发后由外层回文件页看进度）；null = 不提供。 */
    onDownloadBinary: (() -> Unit)?,
) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val hScroll = rememberScrollState()

    var wrap by remember { mutableStateOf(true) }
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var matchIndex by remember { mutableIntStateOf(0) }

    val palette = viewerPalette(MaterialTheme.colorScheme.surface.luminance() < 0.5f)

    val content = state.content
    val rows: List<ViewerRow> = remember(content) {
        when (val c = content) {
            is ViewerContent.TextFile -> c.lines.mapIndexed { i, t -> ViewerRow.TextLine(i, t) }
            is ViewerContent.Diff -> buildList {
                c.files.forEach { f ->
                    add(ViewerRow.FileHeader(f))
                    f.hunks.forEach { h ->
                        add(ViewerRow.DiffRow(f, GitDiff.DiffLine(GitDiff.DiffLineKind.HUNK, h.header)))
                        h.lines.forEach { add(ViewerRow.DiffRow(f, it)) }
                    }
                }
            }
            null -> emptyList()
        }
    }
    val gutterDigits = maxOf(2, (content as? ViewerContent.TextFile)?.lines?.size?.toString()?.length ?: 2)

    val searchable = content is ViewerContent.Diff ||
        (content is ViewerContent.TextFile && !content.binary)
    val matchRows = remember(rows, query) {
        if (query.isEmpty()) emptyList()
        else rows.mapIndexedNotNull { idx, row ->
            val text = when (row) {
                is ViewerRow.TextLine -> row.text
                is ViewerRow.FileHeader -> row.file.path
                is ViewerRow.DiffRow -> row.line.text
            }
            if (text.contains(query, ignoreCase = true)) idx else null
        }
    }

    fun scrollToMatch(i: Int) {
        val idx = matchRows.getOrNull(i) ?: return
        scope.launch { listState.scrollToItem(idx) }
    }
    fun stepMatch(delta: Int) {
        if (matchRows.isEmpty()) return
        matchIndex = ((matchIndex + delta) % matchRows.size + matchRows.size) % matchRows.size
        scrollToMatch(matchIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            when (val t = state.target) {
                                is ViewerTarget.RepoDiff -> stringResource(R.string.viewer_diff_title)
                                is ViewerTarget.File -> RemotePath.name(t.entry.path)
                                is ViewerTarget.FileDiff -> RemotePath.name(t.entry.path)
                                null -> ""
                            },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                        )
                        Text(
                            when (val t = state.target) {
                                is ViewerTarget.RepoDiff -> t.dir
                                is ViewerTarget.File -> t.entry.path
                                is ViewerTarget.FileDiff -> t.entry.path
                                null -> ""
                            },
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = MokeMono,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (searchable) {
                        IconButton(onClick = {
                            searchOpen = !searchOpen
                            if (!searchOpen) { query = ""; matchIndex = 0 }
                        }) {
                            Icon(
                                Icons.Filled.Search,
                                contentDescription = stringResource(R.string.viewer_search),
                                tint = if (searchOpen) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    IconButton(
                        onClick = {
                            wrap = !wrap
                        },
                        enabled = searchable,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.WrapText,
                            contentDescription = stringResource(R.string.viewer_toggle_wrap),
                            tint = if (wrap) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = {
                            val text = rows.joinToString("\n") { row ->
                                when (row) {
                                    is ViewerRow.TextLine -> row.text
                                    is ViewerRow.FileHeader -> row.file.path
                                    is ViewerRow.DiffRow -> row.line.text
                                }
                            }
                            clipboard.setText(AnnotatedString(text))
                        },
                        enabled = searchable,
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.viewer_copy_all))
                    }
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.files_refresh))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (searchOpen && searchable) {
                SearchBar(
                    query = query,
                    matchCount = matchRows.size,
                    currentIndex = matchIndex,
                    onQuery = {
                        query = it
                        matchIndex = 0
                    },
                    onPrev = { stepMatch(-1) },
                    onNext = { stepMatch(1) },
                    onClose = { searchOpen = false; query = ""; matchIndex = 0 },
                )
            }
            Notices(state)
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.error.isNotBlank() -> CenteredMessage(state.error, isError = true)
                content is ViewerContent.TextFile && content.binary -> BinaryPanel(
                    sizeText = RemotePath.formatSize(content.sizeBytes),
                    onDownload = onDownloadBinary,
                )
                content is ViewerContent.Diff && content.files.isEmpty() -> CenteredMessage(
                    if (state.target is ViewerTarget.FileDiff) stringResource(R.string.viewer_file_no_changes)
                    else stringResource(R.string.viewer_no_changes),
                )
                content != null -> LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (wrap) Modifier else Modifier.horizontalScroll(hScroll)),
                ) {
                    items(rows.size) { i ->
                        when (val row = rows[i]) {
                            is ViewerRow.TextLine -> TextLineRow(
                                row = row,
                                gutterDigits = gutterDigits,
                                wrap = wrap,
                                query = query,
                                isCurrentMatch = searchOpen && matchRows.getOrNull(matchIndex) == i,
                                palette = palette,
                            )
                            is ViewerRow.FileHeader -> FileHeaderRow(row.file, palette)
                            is ViewerRow.DiffRow -> DiffLineRow(row, wrap, palette)
                        }
                    }
                }
            }
        }
    }

    // 新查询有命中时跳到第一处（next/prev 由按钮自己滚）。
    LaunchedEffect(query) {
        if (searchOpen && query.isNotEmpty() && matchRows.isNotEmpty()) scrollToMatch(0)
    }
}

@Composable
private fun SearchBar(
    query: String,
    matchCount: Int,
    currentIndex: Int,
    onQuery: (String) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                placeholder = { Text(stringResource(R.string.viewer_search_hint), style = MaterialTheme.typography.bodySmall) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (matchCount == 0) "0" else "${currentIndex + 1}/$matchCount",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = MokeMono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            IconButton(onClick = onPrev, enabled = matchCount > 0) {
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.viewer_search_prev))
            }
            IconButton(onClick = onNext, enabled = matchCount > 0) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.viewer_search_next))
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.viewer_search_close))
            }
        }
    }
}

/** 截断 / 未展开未跟踪文件等顶部横幅。 */
@Composable
private fun Notices(state: ViewerUiState) {
    val notices = buildList {
        (state.content as? ViewerContent.TextFile)?.let { c ->
            if (c.truncated) add(
                stringResource(R.string.viewer_truncated_head, RemotePath.formatSize(FileViewerController.TEXT_CAP_BYTES.toLong()))
            )
        }
        (state.content as? ViewerContent.Diff)?.let { c -> addAll(c.notices) }
    }
    if (notices.isEmpty()) return
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            notices.forEach {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CenteredMessage(message: String, isError: Boolean = false) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BinaryPanel(sizeText: String, onDownload: (() -> Unit)?) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.InsertDriveFile,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.viewer_binary, sizeText),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp, bottom = 16.dp),
        )
        if (onDownload != null) {
            androidx.compose.material3.Button(onClick = onDownload) {
                Text(stringResource(R.string.files_download))
            }
        }
    }
}

/** 单行文本：装订线行号 + 正文（正则高亮 + 搜索命中底色），行内可选中复制。 */
@Composable
private fun TextLineRow(
    row: ViewerRow.TextLine,
    gutterDigits: Int,
    wrap: Boolean,
    query: String,
    isCurrentMatch: Boolean,
    palette: ViewerPalette,
) {
    val annotated = remember(row.text, query, isCurrentMatch, palette) {
        buildAnnotatedString {
            append(row.text)
            CodeHighlight.highlight(row.text).forEach { s ->
                val color = when (s.kind) {
                    SpanKind.COMMENT -> palette.comment
                    SpanKind.STRING -> palette.string
                    SpanKind.NUMBER -> palette.number
                    SpanKind.KEYWORD -> palette.keyword
                }
                addStyle(SpanStyle(color = color), s.start, s.end)
            }
            if (query.isNotEmpty()) {
                val lower = row.text.lowercase()
                val q = query.lowercase()
                var from = 0
                while (true) {
                    val i = lower.indexOf(q, from)
                    if (i < 0) break
                    addStyle(
                        SpanStyle(background = if (isCurrentMatch) palette.matchCurrentBg else palette.matchBg),
                        i, i + q.length,
                    )
                    from = i + q.length
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth()) {
        Text(
            (row.index + 1).toString().padStart(gutterDigits),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = MokeMono,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
            modifier = Modifier.padding(start = 12.dp, end = 10.dp),
        )
        SelectionContainer {
            Text(
                annotated,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = MokeMono,
                softWrap = wrap,
                maxLines = if (wrap) Int.MAX_VALUE else 1,
                modifier = if (wrap) {
                    Modifier.weight(1f).padding(end = 12.dp)
                } else {
                    Modifier.padding(end = 12.dp)
                },
            )
        }
    }
}

@Composable
private fun FileHeaderRow(file: GitDiff.DiffFile, palette: ViewerPalette) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                file.path,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = MokeMono,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!file.isBinary) {
                Text(
                    "+${file.added}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = MokeMono,
                    color = palette.addFg,
                    modifier = Modifier.padding(start = 8.dp),
                )
                Text(
                    "−${file.deleted}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = MokeMono,
                    color = palette.delFg,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
        Row {
            if (file.isNew) Badge(stringResource(R.string.viewer_tag_new))
            if (file.isDeleted) Badge(stringResource(R.string.viewer_tag_deleted))
            if (file.isBinary) Badge(stringResource(R.string.viewer_tag_binary))
            if (file.oldPath.isNotBlank() && file.path != file.oldPath) {
                Badge(stringResource(R.string.viewer_tag_renamed, file.oldPath))
            }
        }
    }
}

@Composable
private fun Badge(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(top = 4.dp, end = 6.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.small)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun DiffLineRow(row: ViewerRow.DiffRow, wrap: Boolean, palette: ViewerPalette) {
    val line = row.line
    when (line.kind) {
        GitDiff.DiffLineKind.HUNK -> Text(
            line.text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = MokeMono,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            softWrap = wrap,
            maxLines = if (wrap) Int.MAX_VALUE else 1,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )
        GitDiff.DiffLineKind.META -> Text(
            stringResource(R.string.viewer_no_newline),
            style = MaterialTheme.typography.bodySmall,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.padding(start = 36.dp, top = 1.dp, bottom = 1.dp),
        )
        else -> {
            val (fg, bg) = when (line.kind) {
                GitDiff.DiffLineKind.ADD -> palette.addFg to palette.addBg()
                GitDiff.DiffLineKind.DEL -> palette.delFg to palette.delBg()
                else -> MaterialTheme.colorScheme.onSurface to Color.Transparent
            }
            Row(Modifier.fillMaxWidth().background(bg)) {
                Text(
                    when (line.kind) {
                        GitDiff.DiffLineKind.ADD -> "+"
                        GitDiff.DiffLineKind.DEL -> "−"
                        else -> " "
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = MokeMono,
                    color = fg,
                    modifier = Modifier.padding(start = 12.dp, end = 9.dp),
                )
                SelectionContainer {
                    Text(
                        line.text,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = MokeMono,
                        color = fg,
                        softWrap = wrap,
                        maxLines = if (wrap) Int.MAX_VALUE else 1,
                        modifier = if (wrap) {
                            Modifier.weight(1f).padding(end = 12.dp)
                        } else {
                            Modifier.padding(end = 12.dp)
                        },
                    )
                }
            }
        }
    }
}
