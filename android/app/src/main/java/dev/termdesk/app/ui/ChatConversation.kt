package dev.termdesk.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import androidx.compose.runtime.rememberCoroutineScope
import dev.termdesk.app.data.ImageTools
import dev.termdesk.app.data.PosterRenderer
import dev.termdesk.app.data.PosterStyles
import dev.termdesk.app.data.PosterText
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.outlined.Add
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import dev.termdesk.app.data.ChatDraft
import dev.termdesk.app.data.ChatDraftStore
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.termdesk.app.data.ChatEvent
import dev.termdesk.app.data.ChatCommands
import dev.termdesk.app.data.ChatModels
import dev.termdesk.app.data.UploadedFile
import dev.termdesk.app.data.ChatInfo


/** The conversation itself: transcript above, composer pinned below. */
@Composable
internal fun Conversation(
    chat: ChatInfo,
    events: List<ChatEvent>,
    sending: Boolean,
    models: ChatModels?,
    commands: ChatCommands?,
    lastUpload: UploadedFile?,
    draftScope: String,
    onClaimChat: (String, Boolean) -> Unit,
    onUploadFile: (android.net.Uri, String) -> Unit,
    onClearUpload: () -> Unit,
    onSend: (String, String) -> Unit,
    /** 手机本地做完一件事之后写一行系统提示（见 AgentClient.note）。 */
    onNote: (String) -> Unit = {},
    /** 海报画好之后把卡片放进当前对话（见 AgentClient.poster）。 */
    onPoster: (String, String) -> Unit = { _, _ -> },
    onCancel: (String) -> Unit,
    onConfigure: (String, String?, String?) -> Unit,
    onSetMode: (String, String) -> Unit,
    onClose: (String) -> Unit,
    connected: Boolean,
    pendingSends: Int = 0,
    pendingDropped: Int = 0,
    /** Open a produced file's folder in the file section (see `DeliverableLine`). */
    onOpenFile: ((String) -> Unit)? = null,
    /** 点产出物时在当前页直接看结果；为 null 时退回上面的「打开所在文件夹」。 */
    onPreviewFile: ((String, String) -> Unit)? = null,
    /** 「产出文件」卡片上的下载：把这一份存到手机上。 */
    onDownloadPath: ((String, String) -> Unit)? = null,
) {
    // What this conversation can run as, both of them the kernel's own words:
    // the model list and the permission / agent modes it declared.
    val modelOptions = models?.models.orEmpty().map { it.id to it.label }
    val modeOptions = models?.modes.orEmpty().map { it.id to it.label }
    // The chat summary carries the mode the PC has actually applied, so it wins
    // over the mode the list was fetched with (a change since then is real).
    val chosenMode = chat.mode.ifBlank { models?.currentMode.orEmpty() }.ifBlank { null }
    val chosenModelLabel = when {
        chat.model.isNotBlank() -> models?.labelOf(chat.model) ?: chat.model
        models?.current != null -> models.labelOf(models.current!!)
        else -> "默认"
    }
    val chosenModeLabel = chosenMode?.let { id -> modeOptions.firstOrNull { it.first == id }?.second ?: id } ?: "默认"
    var modelPickerOpen by remember(chat.id) { mutableStateOf(false) }
    var modePickerOpen by remember(chat.id) { mutableStateOf(false) }
    // The file picked with `+`, once the PC has actually got it. The prompt then
    // carries its path, which every kernel can read - no kernel-specific image
    // block is invented here.
    val context = LocalContext.current
    val draftStore = remember(context) { ChatDraftStore(context.getSharedPreferences("termdesk.chat-drafts", Context.MODE_PRIVATE)) }
    val savedDraft = remember(draftScope, chat.id) { draftStore.read(draftScope, chat.id) }
    var draft by remember(draftScope, chat.id) { mutableStateOf(TextFieldValue(savedDraft.text)) }
    var attachment by remember(draftScope, chat.id) { mutableStateOf(savedDraft.attachment) }
    /** 文本与光标一起改，并落盘（远程带来的草稿持久化）。 */
    fun updateDraftValue(value: TextFieldValue) {
        draft = value
        draftStore.write(draftScope, chat.id, ChatDraft(draft.text, attachment))
    }
    /** 只要文本：清空输入框、插入标记时用。 */
    fun updateDraft(value: String) {
        updateDraftValue(caretAtEnd(value))
    }
    fun updateAttachment(value: UploadedFile?) {
        attachment = value
        draftStore.write(draftScope, chat.id, ChatDraft(draft.text, attachment))
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onUploadFile(uri, chat.cwd)
    }
    // 相册单独一个入口：用户心里"相册"和"文件"本来就是分开的两件事，
    // 只给一个文件选择器，人会以为选不了照片。
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) onUploadFile(uri, chat.cwd)
    }
    /** 输入框左边那个加号点开之后，先问一句"相册还是文件"。 */
    var attachChooserOpen by remember(chat.id) { mutableStateOf(false) }
    LaunchedEffect(lastUpload?.path) {
        val done = lastUpload ?: return@LaunchedEffect
        if (done.path.startsWith(chat.cwd.trimEnd('\\', '/'))) {
            updateAttachment(done)
            onClearUpload()
        }
    }
    // Fresh state per chat: opening or switching a conversation always lands at
    // its end, never at a leftover scroll offset from the previous one.
    val listState = key(chat.id) { rememberLazyListState() }
    // ── 「@网页生成」这条路的界面状态 ──────────────────────────────────
    // 骨架资源包内嵌在 APK 里（assets/templates/web/），发消息时把选中的那一份
    // 整份装配进指令。读它要 Context（上面已取过一次），所以在这里记住，别在每次重组时重读。
    val skeletonOf: (PageTemplate) -> String = remember(context) {
        { template -> WebTemplates.readSkeleton(context.assets, template) }
    }
    // 选完 @网页生成 之后要挑的那套骨架；非 null 就是面板开着。
    var templatePanel by remember(chat.id) { mutableStateOf<List<PageTemplate>?>(null) }
    // 发送之前欠用户的那几次询问：配色、主题。点发送不等于发出去。
    var colorPanel by remember(chat.id) { mutableStateOf(false) }
    var themePanel by remember(chat.id) { mutableStateOf(false) }
    var themeInput by remember(chat.id) { mutableStateOf("") }
    // 要点和主题一样，是**一个自己的小输入框**，不是那一大段指令全文。
    // 「看全文」才是看全文的地方；点「要点」只该给一个能打字的地方。
    var pointsPanel by remember(chat.id) { mutableStateOf(false) }
    var pointsInput by remember(chat.id) { mutableStateOf("") }
    // 主题这一问固定问一次；问过就不再挡路，用户跳过也照样能发。
    var themeAsked by remember(chat.id) { mutableStateOf(false) }

    // ── 「@PPT生成」那条路的界面状态 ────────────────────────────────────
    // 蓝图资源包同样内嵌在 APK 里（assets/templates/ppt/），读它也要 Context，
    // 所以和骨架一样在这里取一次、记住。
    val blueprintOf: (DeckTemplate) -> String = remember(context) {
        { deck -> DeckTemplates.readBlueprint(context.assets, deck) }
    }
    // 选完 @PPT生成 之后要挑的那套蓝图；非 null 就是面板开着。
    var deckPanel by remember(chat.id) { mutableStateOf<List<DeckTemplate>?>(null) }
    // 页数和主题一样是「本地固定问一次」：跳过就用模板的默认页数，不挡发送。
    var pagesPanel by remember(chat.id) { mutableStateOf(false) }
    var pagesInput by remember(chat.id) { mutableStateOf("") }
    var pagesAsked by remember(chat.id) { mutableStateOf(false) }
    // 大纲面板：rows 记页数区间，inputs 是每条**可以单独改**的「章节|内容」。
    // 两者分开是有意的——输入框里只给能改的两样，页数区间由 App 保证首尾相接，
    // 用户不会一不小心把页码改乱。
    var outlinePanel by remember(chat.id) { mutableStateOf(false) }
    var outlineRows by remember(chat.id) { mutableStateOf<List<OutlineRow>>(emptyList()) }
    var outlineInputs by remember(chat.id) { mutableStateOf<List<String>>(emptyList()) }

    /** 开一块面板就关掉其余所有面板：真机上它们叠起来会把屏幕撑爆。 */
    fun closeComposerPanels(keep: () -> Unit, others: Array<() -> Unit>) {
        others.forEach { it() }
        keep()
    }

    // ── 「@文案撰写」那条路：一张悬浮表单 ─────────────────────────────
    // 非 null 就是弹窗开着；值就是那一类产出物的字段定义（类型/语气有哪些选项）。
    var copyFormFor by remember(chat.id) { mutableStateOf<ChatMention?>(null) }
    var copyType by remember(chat.id) { mutableStateOf("") }
    var copyPoint by remember(chat.id) { mutableStateOf("") }
    var copyTone by remember(chat.id) { mutableStateOf("") }
    // 类型那一栏的"下拉框"复用已有的搜索式选择器（和选模型、选权限模式同一个）。
    var copyTypePicker by remember(chat.id) { mutableStateOf(false) }

    /** 把表单三栏拼成草稿正文——和用户手打 `键：值` 一模一样，所以之后手改那一行也生效。 */
    fun copyDraftText(mentionId: String, type: String, point: String, tone: String): String {
        val lines = ArrayList<String>()
        lines.add("@" + mentionId)
        if (type.isNotBlank()) lines.add(Copywriting.MARK_TYPE + "：" + type)
        if (point.isNotBlank()) lines.add(Copywriting.MARK_POINT + "：" + point)
        if (tone.isNotBlank()) lines.add(Copywriting.MARK_TONE + "：" + tone)
        return lines.joinToString("\n")
    }

    /** 打开表单，并把草稿里已经填过的值带回去（用户改完再打开不该从零开始）。 */
    fun openCopyForm(mention: ChatMention, from: MentionDraft) {
        val pairs: Map<String, String> = DraftTokens.pairs(from.free)
        val fields: CopyFieldSet = mention.copy ?: Copywriting.FIELDS
        copyType = pairs[Copywriting.MARK_TYPE].orEmpty().ifBlank { fields.defaultType }
        copyPoint = pairs[Copywriting.MARK_POINT].orEmpty()
        copyTone = pairs[Copywriting.MARK_TONE].orEmpty().ifBlank { fields.defaultTone }
        copyFormFor = mention
    }

    /**
     * 表单点「开始生成」：拼指令 → **直接发出去** → 关掉弹窗、清空输入框。
     *
     * 用户要的就是这个手感：填完点一下就走，不用再回输入框按一次发送。
     */
    fun submitCopyForm() {
        val mention: ChatMention = copyFormFor ?: return
        val text: String = copyDraftText(mention.id, copyType, copyPoint, copyTone)
        val expanded: String = ChatMentions.expand(text, skeletonOf, blueprintOf)
        onSend(chat.id, expanded)
        updateDraft("")
        copyFormFor = null
    }

    // ── 「@海报生成」那条路：也是全程在手机上画 ──────────────────────────
    var posterFormOpen by remember(chat.id) { mutableStateOf(false) }
    var posterStyleId by remember(chat.id) { mutableStateOf(PosterStyles.fallback.id) }
    var posterTitle by remember(chat.id) { mutableStateOf("") }
    var posterSubtitle by remember(chat.id) { mutableStateOf("") }
    var posterScene by remember(chat.id) { mutableStateOf("") }
    var posterBusy by remember(chat.id) { mutableStateOf(false) }
    var posterNote by remember(chat.id) { mutableStateOf("") }
    /** 点开看大图的那张（null = 没在看）。 */
    var posterViewing by remember(chat.id) { mutableStateOf<String?>(null) }
    /** 当前这张海报的配方：右上角那些快捷键就是拿它再画一遍。 */
    var posterRecipe by remember(chat.id) { mutableStateOf<PosterRecipe?>(null) }
    val posterScope = rememberCoroutineScope()

    /** 画一张。首次生成、换风格、点快捷调整——全都走这里，只有一份绘制入口。 */
    fun paintPoster(recipe: PosterRecipe) {
        if (posterBusy) return
        posterBusy = true
        posterRecipe = recipe
        posterScope.launch {
            val style = PosterStyles.byId(recipe.styleId) ?: PosterStyles.fallback
            val out = runCatching {
                PosterRenderer.render(
                    context = context,
                    request = PosterRenderer.Request(
                        style = style,
                        title = recipe.title,
                        subtitle = recipe.subtitle,
                        scene = recipe.scene,
                        adjust = recipe.adjust,
                    ),
                )
            }.getOrNull()
            posterBusy = false
            if (out == null) {
                onNote("海报没画出来：底图读不到，请再试一次。")
            } else {
                onPoster(PosterText.caption(recipe.title), out.file)
                posterNote = if (out.tweaks.isEmpty()) "" else "已调：" + out.tweaks.joinToString(" ")
            }
        }
    }

    /** 表单点「生成海报」：风格按用户选的来，用户没选就按标题与场景自动挑。 */
    fun submitPosterForm() {
        val style = PosterStyles.byId(posterStyleId)
            ?: PosterStyles.pick(posterTitle + " " + posterScene)
        posterFormOpen = false
        updateDraft("")
        paintPoster(
            PosterRecipe(
                styleId = style.id,
                title = posterTitle,
                subtitle = posterSubtitle,
                scene = posterScene,
                adjust = PosterRenderer.Adjust(),
            ),
        )
    }

    /** 右上角竖排那几个键：在现有配方上叠一次调整，立刻重画。 */
    fun tweakPoster(tweak: PosterRenderer.Tweak) {
        val current = posterRecipe ?: return
        paintPoster(current.copy(adjust = current.adjust.apply(tweak)))
    }

    /** 右下角那个下载键：写进手机相册。 */
    fun downloadPoster(file: String) {
        posterScope.launch {
            val where = withContext(Dispatchers.IO) { PosterRenderer.export(context, file) }
            posterNote = if (where == null) "保存失败，请检查存储权限。" else "已保存到" + where + "。"
        }
    }

    // ── 「@图片处理」那条路：全程在手机上做完，不走内核 ──────────────────
    var imageFormOpen by remember(chat.id) { mutableStateOf(false) }
    var imageUris by remember(chat.id) { mutableStateOf<List<Uri>>(emptyList()) }
    var imageQuality by remember(chat.id) { mutableStateOf(80) }
    var imageFormat by remember(chat.id) { mutableStateOf("JPG") }
    var imageWatermark by remember(chat.id) { mutableStateOf("") }
    var imageBusy by remember(chat.id) { mutableStateOf(false) }
    var imageProgress by remember(chat.id) { mutableStateOf(0 to 0) }
    val imageScope = rememberCoroutineScope()

    /**
     * 打开三张内联表单（海报 / 图片 / 文案）里的一张，并关掉其余所有面板。
     *
     * 表单从悬浮窗搬进面板区之后，它们和模板、配色、要点那些面板躺在同一个滚动列里：
     * 两块叠着半屏就放不下，用户正要填的那块会被顶到看不见的地方，所以谁打开谁独占。
     */
    fun openInlineForm(keep: () -> Unit) {
        closeComposerPanels(
            keep = keep,
            others = arrayOf(
                { templatePanel = null }, { colorPanel = false }, { themePanel = false },
                { pointsPanel = false }, { deckPanel = null }, { pagesPanel = false },
                { outlinePanel = false }, { posterFormOpen = false },
                { imageFormOpen = false }, { copyFormFor = null },
                { copyTypePicker = false },
            ),
        )
    }

    /**
     * 选图用的是**系统的文件选择器**（`OpenMultipleDocuments`），不是只读相册。
     *
     * 用户要求能从「本地相册 / 内存库 / 微信文件 / QQ文件」里挑。能同时够到这几个来源的
     * 只有系统那套 DocumentsUI：相册、内部存储，以及微信、QQ 注册的文件提供程序都会出现在
     * 它的侧栏里。MIME 限定成图片类型，所以列表里只会列图片。
     */
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { picked ->
        if (picked.isNotEmpty()) imageUris = picked.take(30)
    }

    /** 点「开始处理」：进度条转起来 → 后台批量处理 → 往对话里写一行系统提示 → 关窗。 */
    fun submitImageForm() {
        if (imageUris.isEmpty() || imageBusy) return
        imageBusy = true
        imageProgress = 0 to imageUris.size
        imageScope.launch {
            val result = ImageTools.process(
                context = context,
                uris = imageUris,
                options = ImageTools.Options(imageQuality, imageFormat, imageWatermark),
            ) { done, total ->
                // 回调跑在 IO 线程上；Compose 的状态要回主线程写。
                imageScope.launch { imageProgress = done to total }
            }
            val line = ImageTools.noteText(result)
            onNote(line)
            imageBusy = false
            // 一张都没成：**把表单留在原地**，让用户直接再点一次「开始处理」。
            // 上一版无论成败都关窗，失败之后想重试只能从头再选一遍图（还没有重试入口）。
            if (result.saved > 0) {
                imageFormOpen = false
                imageUris = emptyList()
                updateDraft("")
            }
        }
    }

    /** 文案表单填全了没有：类型和卖点都得有。没填全就先弹表单，不发半截指令。 */
    fun copyFormFilled(md: MentionDraft): Boolean {
        val pairs: Map<String, String> = DraftTokens.pairs(md.free)
        return !pairs[Copywriting.MARK_TYPE].isNullOrBlank() &&
            !pairs[Copywriting.MARK_POINT].isNullOrBlank()
    }

    /** 大纲一条的可编辑写法：`章节|这几页讲什么`。 */
    fun rowText(row: OutlineRow): String = row.section + "|" + row.content

    /** 把用户改过的一行拆回 章节 + 内容；没写竖线就整行当章节名，内容留着不动。 */
    fun parseRowText(text: String, fallback: OutlineRow): OutlineRow {
        val at: Int = text.indexOf('|')
        if (at < 0) return fallback.copy(section = text.trim().ifBlank { fallback.section })
        return fallback.copy(
            section = text.substring(0, at).trim().ifBlank { fallback.section },
            content = text.substring(at + 1).trim(),
        )
    }

    /**
     * 本地铺大纲：模板章节 + 用户要点 + 用户要的页数。
     *
     * 用户要的是「快速形成大纲」——所以这件事整个在本地做完，不花模型回合，
     * 他点开大纲的那一刻就已经有东西可改了。
     */
    fun composeOutlineRows(md: MentionDraft): List<OutlineRow> {
        val deck: DeckTemplate = md.deck ?: return emptyList()
        val sections: List<DeckSection> = DeckTemplates.parseSections(blueprintOf(deck))
        return DeckTemplates.compose(sections, md.theme, md.points, md.effectivePages())
    }
    // 「将展开成完整指令」是否展开显示全文。
    var previewOpen by remember(chat.id) { mutableStateOf(false) }
    // Follow the stream only while the user is already at (or has returned to)
    // the bottom. Scrolling up hands control back to the reader immediately.
    var stickToBottom by remember(chat.id) { mutableStateOf(true) }
    var programmaticScroll by remember(chat.id) { mutableStateOf(false) }

    /**
     * 把草稿**当作最终指令**发出去。
     *
     * 抽成函数的原因：「发送」和 PPT 大纲面板上的「确认，开始生成」是同一件事的两条入口。
     * 上一版后者只把大纲写回输入框、从不发送，用户点完"开始生成"发现什么都没发生
     * （摘要多出一行「N 条大纲」，消息还留在输入框里，得再点一次发送）。
     */
    fun sendDraftExpanded(text: String) {
        // 「@模板」在这里展开：内核读到的就是这段完整指令，
        // transcript 显示的也是同一份内容，所以这个入口决定的是
        // 「内核真的会去写文件」，而不是只往输入框里塞几个字。
        val expanded = ChatMentions.expand(text, skeletonOf, blueprintOf)
        // The attachment travels as a path in the prompt: every kernel
        // can read a file, and the transcript then shows what was sent.
        val full = attachment?.let { "[附件] ${it.name} → ${it.path}\n$expanded" } ?: expanded
        onSend(chat.id, full)
        updateDraft("")
        updateAttachment(null)
        previewOpen = false
        themeAsked = false
        pagesAsked = false
    }

    /** 打开指定的那一问；返回 true 表示真的开了面板。 */
    fun openAsk(ask: String, md: MentionDraft): Boolean {
        // 先把所有面板关掉：两块面板叠在一起时，屏幕矮一点就会把后开的那块挤出可视区。
        templatePanel = null; colorPanel = false; themePanel = false; pointsPanel = false
        deckPanel = null; pagesPanel = false; outlinePanel = false
        when (ask) {
            MentionDraft.LOCAL_COLORS -> {
                colorPanel = true
                return true
            }
            MentionDraft.LOCAL_THEME -> {
                themeAsked = true
                themeInput = md.theme.ifBlank { md.free }
                themePanel = true
                return true
            }
            MentionDraft.LOCAL_PAGES -> {
                pagesAsked = true
                pagesInput = md.pages
                pagesPanel = true
                return true
            }
        }
        return false
    }

    /** PPT 的大纲确认：本地铺一份（不花模型回合），面板一开就有东西可改。 */
    fun openOutlineAsk(md: MentionDraft) {
        if (!md.needsOutlineAsk()) return
        if (outlineInputs.isEmpty()) {
            val fresh = composeOutlineRows(md)
            outlineRows = fresh
            outlineInputs = fresh.map { "" }
        }
        templatePanel = null; colorPanel = false; themePanel = false; pointsPanel = false
        deckPanel = null; pagesPanel = false
        outlinePanel = true
    }

    /**
     * 本地追问是一条**链**：答完这一问就接着问下一问，最后落到大纲确认。
     *
     * 每一问的「已问过」标记（[themeAsked] / [pagesAsked]）都算数，所以用户跳过的那一问
     * 不会又被问一次；而用户答过之后 `nextAsk()` 自己就不再返回它了。
     */
    fun advanceAsk() {
        val md = MentionDraft.parse(draft.text)
        for (ask in md.mention?.localAsks.orEmpty()) {
            when (ask) {
                MentionDraft.LOCAL_COLORS -> if (md.needsColorAsk()) {
                    openAsk(ask, md)
                    return
                }
                MentionDraft.LOCAL_THEME -> if (md.needsThemeAsk() && !themeAsked) {
                    openAsk(ask, md)
                    return
                }
                MentionDraft.LOCAL_PAGES -> if (md.needsPagesAsk() && !pagesAsked) {
                    openAsk(ask, md)
                    return
                }
            }
        }
        // 基础信息齐了：PPT 还差大纲那道闸门——直接铺一份给他改，改完点「确认，开始生成」。
        openOutlineAsk(md)
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling ->
                if (scrolling && !programmaticScroll) {
                    stickToBottom = listState.isNearBottom()
                }
            }
    }

    val lastSeq = events.lastOrNull()?.seq ?: 0
    val lastLen = events.lastOrNull()?.text?.length ?: 0

    // Folded once per event list: a turn's plumbing (tool calls, results, reasoning) becomes one
    // line per run instead of a screenful, and it is folded here rather than inside the list
    // because a `LazyListScope` is not a place where state can be remembered.
    val rows = remember(events) { foldTranscript(events) }
    // 过程行整类不画：思考、工具、命令、步骤、内核日志。要的是"作品生成中…"加最终产物，
    // 不是几屏绿色文本框。
    //
    // 作品任务（由 @ 模板发起的对话，首条用户消息是【任务：…】）还要更狠一点：**模型说的
    // 话也不画**。用户原话——"聊天区域要干净，没做完就是作品生成中，做完了就是作品已完成，
    // 下面是生成的东西"。
    val deliverableTask = remember(events) {
        events.firstOrNull { it.kind == "message" && it.role == "user" }
            ?.text?.trimStart()?.startsWith("【任务：") == true
    }
    val shownRows = remember(rows, deliverableTask) {
        rows.filterNot { row ->
            isProcessRow(row) ||
                (deliverableTask && row is TranscriptRow.One && row.event.isAssistant)
        }
    }
    val deliverableDone = deliverableTask && !chat.isRunning && !sending &&
        events.any { it.isDeliverable }

    // Open / switch: snap to the last item's bottom (not merely its top).
    LaunchedEffect(chat.id) {
        programmaticScroll = true
        listState.scrollToBottomNow()
        programmaticScroll = false
        stickToBottom = true
    }

    // Stream follow: only when the reader is still parked at the end.
    LaunchedEffect(chat.id, lastSeq, lastLen, events.size) {
        if (events.isEmpty() || !stickToBottom) return@LaunchedEffect
        programmaticScroll = true
        listState.animateScrollToBottom()
        programmaticScroll = false
    }

    Column(Modifier.fillMaxSize()) {
        if (chat.ownership.isBusy) {
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("由 "+(chat.ownership.owner?.deviceName ?: "另一台设备")+" 控制", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = { onClaimChat(chat.id, true) }, enabled = connected && !chat.isRunning) { Text(if (chat.isRunning) "生成中" else "接管") }
            }
        }
        TranscriptPane(
            events = events,
            rows = shownRows,
            deliverableDone = deliverableDone,
            running = chat.isRunning,
            sending = sending,
            listState = listState,
            modifier = Modifier.weight(1f),
            onOpenFile = onOpenFile,
            onPreviewFile = onPreviewFile,
            onPosterOpen = { posterViewing = it },
            onPosterDownload = { downloadPoster(it) },
            onPosterTweak = { tweakPoster(it) },
            onDownloadPath = onDownloadPath,
        )

        run {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Attachment first: a long model name used to push it off the
                // right edge, where a scrolled row hid it entirely.
                ComposerChip("＋", "附件", withChevron = false) { filePicker.launch(arrayOf(MIME_ANY)) }
                if (modelOptions.isNotEmpty()) {
                    // The model name is the long one, so it is the chip that gives
                    // way: the attachment and permission entries stay on screen.
                    ComposerChip("模型", chosenModelLabel, modifier = Modifier.weight(1f)) { modelPickerOpen = true }
                }
                if (modeOptions.isNotEmpty()) {
                    ComposerChip("权限", chosenModeLabel) { modePickerOpen = true }
                }
            }
        }

        // The file that is about to be sent, and how to take it back off.
        attachment?.let { file ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "附件 " + file.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { updateAttachment(null) }, modifier = Modifier.size(26.dp)) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "移除附件",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
        }

        // ── 「@」模板：菜单 + 已套用提示 ─────────────────────────────────
        // 模板是纯文本的：选中哪一项，输入框里就出现 @标记；发送时在 onSend 里
        // 展开成完整指令发给内核。没有隐藏状态——删掉 @标记，下面的提示行自己消失。
        val mentionQuery = remember(draft.text) { ChatMentions.query(draft.text) }
        val mentionMatches = remember(mentionQuery) {
            mentionQuery?.let { ChatMentions.match(it) }.orEmpty()
        }
        val appliedMentions = remember(draft.text) { ChatMentions.applied(draft.text) }
        // 这一段文本将被装配成什么，只算一次。展开是纯字符串操作，但骨架有好几 KB，
        // 放在每次重组里重算是白烧电。
        val mentionDraft = remember(draft.text) { MentionDraft.parse(draft.text) }
        val expandedPreview = remember(draft.text) {
            ChatMentions.expand(draft.text, skeletonOf, blueprintOf)
        }

        // 明说会展开成什么，不做暗箱：用户按下发送之前就知道内核将收到一整段指令。
        //
        // 这里原来只画一个 badge（「WEB」）就说"将展开成完整指令"，正文一个字都不给看。
        // 用户在手机上打完主题，看不见它去了哪里——这正是「发送要求后在自动生成的字
        // 里面没有体现」。现在这一行给出三样东西：装配摘要、三个能点回来重选的入口、
        // 以及能就地展开的指令全文。
        // 面板区整体可滚、并给一个高度上限。
        //
        // 真机上暴露的问题：模板面板和配色面板同时开着、软键盘又弹起来的时候，剩余高度
        // 根本放不下这一叠面板；而 Column 对放不下的子项是**裁掉**、不是让它滚，于是
        // 配色色板被压成一条边，连颜色名字都看不见。这里让它自己滚——屏幕多矮都够得着。
        //
        // 上限取「半屏」而不是写死 300dp：写死的那一版在真机上只放得下 @ 菜单的前 4 项，
        // 第 5 项「表格生成」起被裁在输入框后面，用户会以为菜单里根本没有那几条。
        val panelMaxHeight = (LocalConfiguration.current.screenHeightDp * 0.5f).dp
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = panelMaxHeight)
                .verticalScroll(rememberScrollState()),
        ) {
        if (appliedMentions.isNotEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "将展开成完整指令：",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    appliedMentions.forEach { mention ->
                        Text(
                            text = mention.badge,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            maxLines = 1,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    Text(
                        text = if (previewOpen) "收起" else "看全文",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { previewOpen = !previewOpen }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }

                // 网页类才有骨架和配色，所以这三行只在这种时候出现。
                val template = mentionDraft.template
                val schemes = mentionDraft.mention?.templates.orEmpty()
                val deck = mentionDraft.deck
                val decks = mentionDraft.mention?.decks.orEmpty()
                // 表格那条路没有"挑一套模板"这一步：体裁由 App 按主题自己挑。
                // 这里算出来只是为了在芯片上把它显示给用户看。
                val tableKinds = mentionDraft.mention?.tables.orEmpty()
                val tableKind = if (tableKinds.isEmpty()) null
                else TableKinds.pick(draft.text, tableKinds)
                // 用悬浮表单收需求的那一类（目前只有文案撰写）。
                val copyMention = mentionDraft.mention?.takeIf { it.copy != null }
                if (template != null && schemes.isNotEmpty()) {
                    // 真正要发给模型的那份骨架：槽位已经在本地填过了。摘要里的
                    // 「还有几处交给模型补」就是数它，而不是数指令原文里的说明文字。
                    val filledSkeleton = remember(template, draft.text) {
                        WebTemplates.fill(
                            template,
                            skeletonOf(template),
                            mentionDraft.theme,
                            mentionDraft.points,
                            DraftTokens.pairs(mentionDraft.free),
                            mentionDraft.colors ?: ColorSchemes.fallback,
                        )
                    }
                    Text(
                        text = WebTemplates.summaryLine(
                            template,
                            mentionDraft.colors,
                            mentionDraft.theme,
                            mentionDraft.points,
                        ) + remainingHint(filledSkeleton),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        // 骨架 / 配色 / 主题这几块是**先后步骤**，不是三块并排的设置项。
                        // 每次只留一块开着：真机上几块叠在一起时，总高度超出屏幕，
                        // 后面那块（往往是用户正要答的配色）就被挤到看不见的地方去了。
                        // 所以下面每一个芯片都负责把**其余所有面板**关掉。
                        ComposerChip("骨架", template.id) {
                            closeComposerPanels(
                                keep = { templatePanel = schemes },
                                others = arrayOf(
                                    { colorPanel = false }, { themePanel = false },
                                    { pointsPanel = false }, { deckPanel = null },
                                    { pagesPanel = false }, { outlinePanel = false },
                                ),
                            )
                        }
                        ComposerChip("配色", mentionDraft.colors?.id ?: "未选") {
                            closeComposerPanels(
                                keep = { colorPanel = true },
                                others = arrayOf(
                                    { templatePanel = null }, { themePanel = false },
                                    { pointsPanel = false }, { deckPanel = null },
                                    { pagesPanel = false }, { outlinePanel = false },
                                ),
                            )
                        }
                        ComposerChip(
                            "主题",
                            mentionDraft.theme.ifBlank { "未填" },
                        ) {
                            themeInput = mentionDraft.theme.ifBlank { mentionDraft.free }
                            closeComposerPanels(
                                keep = { themePanel = true },
                                others = arrayOf(
                                    { templatePanel = null }, { colorPanel = false },
                                    { pointsPanel = false }, { deckPanel = null },
                                    { pagesPanel = false }, { outlinePanel = false },
                                ),
                            )
                        }
                        ComposerChip(
                            "要点",
                            WebTemplates.bulletPoints(mentionDraft.points).size.let {
                                if (it == 0) "未填" else it.toString() + " 条"
                            },
                        ) {
                            // 这里原来是 previewOpen = true —— 点「要点」弹出的是那一大段指令全文，
                            // 既没法打字、也不是用户想要的东西。改成和主题一样的一个小输入框。
                            pointsInput = mentionDraft.points
                            closeComposerPanels(
                                keep = { pointsPanel = true },
                                others = arrayOf(
                                    { templatePanel = null }, { colorPanel = false },
                                    { themePanel = false }, { deckPanel = null },
                                    { pagesPanel = false }, { outlinePanel = false },
                                ),
                            )
                        }
                    }
                } else if (deck != null && decks.isNotEmpty()) {
                    // ── PPT：同一套逻辑，但问的是页数，而且多一道「大纲」 ──────────
                    Text(
                        text = DeckTemplates.summaryLine(
                            deck,
                            mentionDraft.colors,
                            mentionDraft.theme,
                            mentionDraft.pages,
                            mentionDraft.outline,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        // 「自动」两个字是给用户看的凭据：这套模板是 App 按主题挑的，
                        // 不是随手下发的默认值。不满意就点开换一套。
                        ComposerChip("模板", "自动：" + deck.id) {
                            closeComposerPanels(
                                keep = { deckPanel = decks },
                                others = arrayOf(
                                    { templatePanel = null }, { colorPanel = false },
                                    { themePanel = false }, { pointsPanel = false },
                                    { pagesPanel = false }, { outlinePanel = false },
                                ),
                            )
                        }
                        ComposerChip("配色", mentionDraft.colors?.id ?: "未选") {
                            closeComposerPanels(
                                keep = { colorPanel = true },
                                others = arrayOf(
                                    { templatePanel = null }, { themePanel = false },
                                    { pointsPanel = false }, { deckPanel = null },
                                    { pagesPanel = false }, { outlinePanel = false },
                                ),
                            )
                        }
                        ComposerChip("主题", mentionDraft.theme.ifBlank { "未填" }) {
                            themeInput = mentionDraft.theme.ifBlank { mentionDraft.free }
                            closeComposerPanels(
                                keep = { themePanel = true },
                                others = arrayOf(
                                    { templatePanel = null }, { colorPanel = false },
                                    { pointsPanel = false }, { deckPanel = null },
                                    { pagesPanel = false }, { outlinePanel = false },
                                ),
                            )
                        }
                        ComposerChip(
                            "页数",
                            mentionDraft.pages.ifBlank {
                                deck.defaultPages.toString() + "（默认）"
                            },
                        ) {
                            pagesInput = mentionDraft.pages
                            closeComposerPanels(
                                keep = { pagesPanel = true },
                                others = arrayOf(
                                    { templatePanel = null }, { colorPanel = false },
                                    { themePanel = false }, { pointsPanel = false },
                                    { deckPanel = null }, { outlinePanel = false },
                                ),
                            )
                        }
                        ComposerChip(
                            "要点",
                            WebTemplates.bulletPoints(mentionDraft.points).size.let {
                                if (it == 0) "未填" else it.toString() + " 条"
                            },
                        ) {
                            pointsInput = mentionDraft.points
                            closeComposerPanels(
                                keep = { pointsPanel = true },
                                others = arrayOf(
                                    { templatePanel = null }, { colorPanel = false },
                                    { themePanel = false }, { deckPanel = null },
                                    { pagesPanel = false }, { outlinePanel = false },
                                ),
                            )
                        }
                        ComposerChip(
                            "大纲",
                            if (mentionDraft.outline.isEmpty()) "未确认"
                            else mentionDraft.outline.size.toString() + " 条",
                        ) {
                            // 点开就等于「我要看一眼/改一下」：还没铺过就先本地铺一份，
                            // 不要给用户一个空面板让他自己从零写。
                            if (outlineInputs.isEmpty()) {
                                val fresh = composeOutlineRows(mentionDraft)
                                outlineRows = fresh
                                outlineInputs = fresh.map { "" }
                            }
                            closeComposerPanels(
                                keep = { outlinePanel = true },
                                others = arrayOf(
                                    { templatePanel = null }, { colorPanel = false },
                                    { themePanel = false }, { pointsPanel = false },
                                    { deckPanel = null }, { pagesPanel = false },
                                ),
                            )
                        }
                    }
                } else if (tableKind != null) {
                    // ── 表格：只问主题，别的都不问 ──────────────────────────
                    // 表格不需要配色、不需要页数、也不需要确认大纲——那是给"要排版的
                    // 长东西"准备的。所以这里只有两个芯片：改主题、补要点。
                    Text(
                        text = "表格生成 · " + tableKind.id + " · " +
                            (if (mentionDraft.theme.isBlank()) "主题未填" else "主题 " + mentionDraft.theme),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        // 「经验」两个字是给用户看的凭据：表头不是随便来的，
                        // 是按主题自己挑的一种常见体裁。
                        ComposerChip("体裁", "经验：" + tableKind.id) {
                            closeComposerPanels(
                                keep = { },
                                others = arrayOf(
                                    { templatePanel = null }, { colorPanel = false },
                                    { themePanel = false }, { pointsPanel = false },
                                    { deckPanel = null }, { pagesPanel = false },
                                    { outlinePanel = false },
                                ),
                            )
                        }
                        ComposerChip("主题", mentionDraft.theme.ifBlank { "未填" }) {
                            themeInput = mentionDraft.theme.ifBlank { mentionDraft.free }
                            closeComposerPanels(
                                keep = { themePanel = true },
                                others = arrayOf(
                                    { templatePanel = null }, { colorPanel = false },
                                    { pointsPanel = false }, { deckPanel = null },
                                    { pagesPanel = false }, { outlinePanel = false },
                                ),
                            )
                        }
                        ComposerChip(
                            "要点",
                            WebTemplates.bulletPoints(mentionDraft.points).size.let {
                                if (it == 0) "未填" else it.toString() + " 条"
                            },
                        ) {
                            pointsInput = mentionDraft.points
                            closeComposerPanels(
                                keep = { pointsPanel = true },
                                others = arrayOf(
                                    { templatePanel = null }, { colorPanel = false },
                                    { themePanel = false }, { deckPanel = null },
                                    { pagesPanel = false }, { outlinePanel = false },
                                ),
                            )
                        }
                    }
                } else if (copyMention != null) {
                    // ── 文案：摘要 + 一个「重填」入口 ────────────────────────
                    // 表单收完需求就发走了，所以这里只留两件事：看一眼刚才填了什么、
                    // 想改就再点开表单（表单会把已填的值带回来）。
                    val pairs: Map<String, String> = DraftTokens.pairs(mentionDraft.free)
                    val type: String = pairs[Copywriting.MARK_TYPE].orEmpty().ifBlank { "未选" }
                    val point: String = pairs[Copywriting.MARK_POINT].orEmpty()
                    Text(
                        text = "文案撰写 · " + type + " · " +
                            (if (point.isBlank()) "卖点未填" else "卖点 " + point),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ComposerChip("文案表单", type) {
                            // 表单是面板区里的一块，所以先把它之外的面板全关掉，免得叠着。
                            openInlineForm(keep = { openCopyForm(copyMention, mentionDraft) })
                        }
                        ComposerChip(
                            "要点",
                            WebTemplates.bulletPoints(mentionDraft.points).size.let {
                                if (it == 0) "未填" else it.toString() + " 条"
                            },
                        ) {
                            pointsInput = mentionDraft.points
                            closeComposerPanels(
                                keep = { pointsPanel = true },
                                others = arrayOf(
                                    { templatePanel = null }, { colorPanel = false },
                                    { themePanel = false }, { deckPanel = null },
                                    { pagesPanel = false }, { outlinePanel = false },
                                ),
                            )
                        }
                    }
                }

                if (previewOpen) {
                    Text(
                        text = expandedPreview,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(top = 6.dp),
                    )
                }
            }
        }

        if (mentionQuery != null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    // 这里**不再**自己套一层 220dp 的视口：那层视口正好只放得下 4 行，
                    // 第 5 项「表格生成」起就被裁在输入框后面，而内层又把滚动吃掉，
                    // 于是用户以为菜单里只有 4 条。现在只留外层那一个视口，并给出项数提示。
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                if (mentionMatches.isEmpty()) {
                    // 空结果也要给人一条出路：把现有模板列出来，而不是留一片空白。
                    Text(
                        text = "没有这个模板",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 2.dp),
                    )
                    Text(
                        text = "现在有：" + ChatMentions.all.joinToString("、") { "@" + it.title },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
                    )
                } else {
                    Text(
                        text = "共 " + mentionMatches.size + " 项 · 面板可上下滑动",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp, top = 6.dp),
                    )
                    mentionMatches.forEach { mention ->
                        // 光标跟着插入内容走：不指定的话它会留在用户敲 @ 的地方，
                        // 下一句主题就打到 @ 和标记之间去了。
                        MentionRow(mention) {
                            val inserted = ChatMentions.insertion(draft.text, mention)
                            draft = TextFieldValue(inserted.text, TextRange(inserted.caret))
                            val picked = MentionDraft.parse(inserted.text)
                            var opened = false
                            // 带资源包的条目（网页生成）接着问骨架、再问配色、再问主题；
                            // 其余条目到这里就结束了，用户直接打字即可。
                            if (mention.templates.isNotEmpty()) {
                                // 从 @ 菜单进来时只开骨架那一块；配色是挑完骨架之后的下一步，
                                // 由骨架选择自己把它打开，不在这里一起铺开。
                                templatePanel = mention.templates
                                colorPanel = false
                                themePanel = false
                                opened = true
                            }
                            // 三张表单（文案 / 图片 / 海报）现在都内联在面板区里，点完就地展开；
                            // 开之前先把其余面板连同另外两张表单一起关掉，真机上叠着放不下。
                            if (mention.copy != null) {
                                openInlineForm(keep = { openCopyForm(mention, picked) })
                                opened = true
                            }
                            // 图片处理是纯本地操作，点完直接开它自己的表单。
                            if (mention.image) {
                                openInlineForm(keep = { imageFormOpen = true })
                                opened = true
                            }
                            // 海报生成也一样：点完开表单，界面上绝不出现底图本身。
                            if (mention.poster) {
                                posterStyleId = PosterStyles.pick(picked.theme + " " + picked.free).id
                                if (posterTitle.isBlank()) posterTitle = picked.theme
                                openInlineForm(keep = { posterFormOpen = true })
                                opened = true
                            }
                            // 剩下几条（PPT / 表格 / 网页之外的本地追问）选完就把**第一问**
                            // 直接问出来。上一版只有点「发送」才问，用户选完模板面对的是
                            // 一片安静，只能自己去点芯片。
                            if (!opened) picked.nextAsk()?.let { ask -> openAsk(ask, picked) }
                        }
                    }
                }
            }
        }

        // ── 骨架选择：资源包里有几套就列几套 ──────────────────────────────
        // 「我们不会只用这个模板，之后会有多个模板进行选择」——所以这里不是二选一，
        // 而是把 assets/templates/web/ 里已经内嵌的那几套全列出来。
        templatePanel?.let { options ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = "选一套骨架（内嵌在 App 里，离线可用）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 2.dp),
                )
                options.forEach { option ->
                    TemplateRow(option) {
                        val next = MentionDraft.withTemplate(MentionDraft.parse(draft.text), option)
                        draft = caretAtEnd(MentionDraft.render(next))
                        templatePanel = null
                        // 挑完骨架接着固定问配色：这是发送之前的那一次。
                        colorPanel = true
                    }
                }
            }
        }

        // ── 配色：发送之前固定问这一次 ───────────────────────────────────
        if (colorPanel) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(top = 8.dp, bottom = 4.dp),
            ) {
                Text(
                    text = "先用哪套配色？选完会写进上面那行字里，之后还能手改。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
                )
                // 8 套配色一行放 4 套、分两行摆完。
                // 上一版是单行 + horizontalScroll：屏幕上只看得见 3 套半（第 4 套「暖橙」被右
                // 边缘裁掉），又没有任何"还能横滑"的提示，用户读到的就是"只有 4 套"。
                ColorSchemes.all.chunked(4).forEach { line ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        line.forEach { scheme ->
                            ColorChip(scheme, selected = mentionDraft.colors?.id == scheme.id) {
                                val next = MentionDraft.withColors(MentionDraft.parse(draft.text), scheme)
                                draft = caretAtEnd(MentionDraft.render(next))
                                colorPanel = false
                                // 接着问下一问：网页那条是主题，PPT 那条是主题之后还有页数。
                                advanceAsk()
                            }
                        }
                    }
                }
                TextButton(
                    onClick = {
                        // 「先不选」也要落成文本里的一套具体配色，否则下次点发送还会再拦一次。
                        val next = MentionDraft.withColors(MentionDraft.parse(draft.text), ColorSchemes.fallback)
                        draft = caretAtEnd(MentionDraft.render(next))
                        colorPanel = false
                        advanceAsk()
                    },
                    modifier = Modifier.padding(start = 4.dp),
                ) {
                    Text("用默认的「" + ColorSchemes.fallback.id + "」", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        // ── 主题：本地固定问的第二个问题 ────────────────────────────────
        if (themePanel) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp),
            ) {
                Text(
                    text = "这次的主题是什么？写完会直接填进骨架的大标题（不想填就点跳过，交给模型拟）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OutlinedTextField(
                        value = themeInput,
                        onValueChange = { themeInput = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("例如：校园开放日", style = MaterialTheme.typography.bodySmall) },
                        textStyle = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        shape = RoundedCornerShape(9.dp),
                    )
                    TextButton(
                        onClick = {
                            val md = MentionDraft.parse(draft.text)
                            val typed = themeInput.trim()
                            if (typed.isNotEmpty()) {
                                draft = caretAtEnd(MentionDraft.render(MentionDraft.withTheme(md, typed)))
                            }
                            themePanel = false
                            // 答完主题接着问下一问（PPT 是配色，网页是配色，表格就没有了）。
                            advanceAsk()
                        },
                    ) {
                        Text("确定", style = MaterialTheme.typography.labelSmall)
                    }
                }
                TextButton(
                    onClick = {
                        themePanel = false
                        advanceAsk()
                    },
                    modifier = Modifier.padding(start = 4.dp),
                ) {
                    Text("跳过，交给模型拟", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        if (pointsPanel) {
            PointsDemandPanel(
                value = pointsInput,
                onValueChange = { pointsInput = it },
                onConfirm = {
                    val md = MentionDraft.parse(draft.text)
                    val typed = pointsInput.trim()
                    if (typed.isNotEmpty()) {
                        draft = caretAtEnd(MentionDraft.render(MentionDraft.withPoints(md, typed)))
                    }
                    pointsPanel = false
                },
                onSkip = { pointsPanel = false },
            )
        }

        // ── PPT：模板选择（默认那一套是 App 按主题自动挑的） ──────────────
        deckPanel?.let { options ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = "选一套版式（内嵌在 App 里，离线可用；「自动」那套是 App 按你写的主题挑的）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 2.dp),
                )
                options.forEach { option ->
                    DeckRow(option) {
                        // 换模板要**连带作废旧大纲**：章节结构换了，旧大纲的页数分配
                        // 和章节名就不再对得上，留着比没有更糟。
                        val next = MentionDraft.withOutline(
                            MentionDraft.withDeck(MentionDraft.parse(draft.text), option),
                            emptyList(),
                        )
                        draft = caretAtEnd(MentionDraft.render(next))
                        deckPanel = null
                        outlineRows = emptyList()
                        outlineInputs = emptyList()
                    }
                }
            }
        }

        // ── PPT：页数 ──────────────────────────────────────────────────
        if (pagesPanel) {
            val deckNow = MentionDraft.parse(draft.text).deck
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp),
            ) {
                Text(
                    text = "这次做几页？页数决定大纲里每部分分到几页" +
                        "（章节比页数还多时会以章节为准，封面和结尾不能没有）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // 预设直接给数字，省得在手机上敲键盘——大多数人就用这几个。
                    (deckNow?.pagePresets ?: emptyList()).forEach { preset ->
                        val picked = pagesInput.trim() == preset.toString()
                        Text(
                            text = preset.toString() + " 页",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (picked) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .clickable { pagesInput = preset.toString() }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OutlinedTextField(
                        value = pagesInput,
                        // 只收数字：页数是个数，让人先打错再报错不如根本打不进去。
                        onValueChange = { typed -> pagesInput = typed.filter { it.isDigit() }.take(2) },
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text(
                                "页数，例如 " + (deckNow?.defaultPages ?: 12),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        textStyle = MaterialTheme.typography.bodySmall,
                        singleLine = true,
                        shape = RoundedCornerShape(9.dp),
                    )
                    TextButton(
                        onClick = {
                            val n: Int? = pagesInput.trim().toIntOrNull()
                            if (n != null && n > 0) {
                                // 页数一改，旧大纲的分配就不成立了，同样作废重铺。
                                val next = MentionDraft.withOutline(
                                    MentionDraft.withPages(MentionDraft.parse(draft.text), n),
                                    emptyList(),
                                )
                                draft = caretAtEnd(MentionDraft.render(next))
                                outlineRows = emptyList()
                                outlineInputs = emptyList()
                            }
                            pagesPanel = false
                            // 页数是最后一问：接着把大纲铺出来给他确认。
                            advanceAsk()
                        },
                    ) {
                        Text("确定", style = MaterialTheme.typography.labelSmall)
                    }
                }
                TextButton(
                    onClick = {
                        pagesPanel = false
                        advanceAsk()
                    },
                    modifier = Modifier.padding(start = 4.dp),
                ) {
                    Text(
                        "跳过，用模板默认页数",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }

        // ── 三个内联表单：贴着输入框就地展开，不再是独立悬浮窗 ────────────────
        // 用户点名"不要单独设置悬浮 UI 窗口，都在文本框那里弹出操作就可以"：悬浮窗正好压住
        // 聊天气泡，而这里是「网页生成」那套追问用的同一块地方。三张表单互斥，同时只开一块。

        // 海报生成：风格 + 主标题 + 副标题 + 场景。
        if (posterFormOpen) {
            PosterFormDialog(
                styleId = posterStyleId,
                title = posterTitle,
                subtitle = posterSubtitle,
                scene = posterScene,
                busy = posterBusy,
                onStyle = { posterStyleId = it },
                onTitle = { posterTitle = it },
                onSubtitle = { posterSubtitle = it },
                onScene = { posterScene = it },
                onCancel = { posterFormOpen = false },
                onStart = { submitPosterForm() },
            )
        }

        // 图片处理：选图、质量、格式、水印，点一下就在手机上做完。
        if (imageFormOpen) {
            ImageFormDialog(
                pickedCount = imageUris.size,
                quality = imageQuality,
                format = imageFormat,
                watermark = imageWatermark,
                busy = imageBusy,
                progress = imageProgress,
                onPickImages = { imagePicker.launch(arrayOf("image/*")) },
                onQuality = { imageQuality = it },
                onFormat = { imageFormat = it },
                onWatermark = { imageWatermark = it },
                onCancel = { imageFormOpen = false },
                onStart = { submitImageForm() },
            )
        }

        // 文案类型：原来是 ChoicePickerDialog（带搜索框的独立浮层，压住聊天气泡）。
        // 现在就是面板里的一行 chips——点一下直接选中，不用先弹窗再收回。
        if (copyFormFor != null && copyTypePicker) {
            val typeFields: CopyFieldSet = copyFormFor?.copy ?: Copywriting.FIELDS
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    text = "选一个文案类型 · 点一下就用",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    typeFields.types.forEach { option ->
                        ComposerChip(option, if (option == copyType) "已选" else "点我") {
                            copyType = option
                            copyTypePicker = false
                        }
                    }
                }
            }
        }

        // 文案撰写：类型、卖点、语气三栏，填完点「开始生成」直接拼指令发出去。
        copyFormFor?.let { mention ->
            CopyFormDialog(
                fields = mention.copy ?: Copywriting.FIELDS,
                type = copyType,
                point = copyPoint,
                tone = copyTone,
                onPickType = { copyTypePicker = true },
                onPoint = { copyPoint = it },
                onTone = { copyTone = it },
                onCancel = { copyFormFor = null },
                onStart = { submitCopyForm() },
            )
        }

        // ── PPT：大纲（逐条可改；确认之后才真的发出去） ──────────────────
        if (outlinePanel) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 340.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp),
            ) {
                Text(
                    text = "确认一下大纲：左边是页数，右边写「章节|这几页讲什么」。" +
                        "灰字是模板建议，直接打字就覆盖；不改就用建议的内容。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
                // 「确认」放在**面板顶部**：底部那份要滚到底才能点到，而面板里全是输入框，
                // 手指落在输入框上拖动只会选中文字、滚不动，于是按钮永远够不着（真机实测）。
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            val md = MentionDraft.parse(draft.text)
                            val rows = outlineRows.mapIndexed { index, row ->
                                parseRowText(outlineInputs.getOrNull(index).orEmpty(), row)
                            }
                            val confirmed = MentionDraft.render(MentionDraft.withOutline(md, rows))
                            draft = caretAtEnd(confirmed)
                            outlineRows = rows
                            outlinePanel = false
                            sendDraftExpanded(confirmed)
                        },
                    ) {
                        Text(
                            "确认，开始生成",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                outlineInputs.forEachIndexed { index, value ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(bottom = 4.dp),
                    ) {
                        Text(
                            text = outlineRows.getOrNull(index)?.range.orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            modifier = Modifier.width(40.dp),
                        )
                        OutlinedTextField(
                            value = value,
                            onValueChange = { typed ->
                                // 只改这一条：其余各条原样不动——「单独修改某条」就是这个意思。
                                val list = outlineInputs.toMutableList()
                                if (index in list.indices) {
                                    list[index] = typed
                                    outlineInputs = list
                                }
                            },
                            modifier = Modifier.weight(1f),
                            placeholder = {
                                // 提示就是模板建议的那一条，但**只是灰字**：用户直接打字即可，
                                // 不用先删掉预填内容（用户原话："还要把那些删掉再写自己要的"）。
                                Text(
                                    text = outlineRows.getOrNull(index)?.let { rowText(it) }
                                        ?: "章节|这几页讲什么",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                                )
                            },
                            textStyle = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            shape = RoundedCornerShape(9.dp),
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = {
                            // 重铺一份：用户把某几条改乱了、或者改了主题要点想从头来。
                            val fresh = composeOutlineRows(MentionDraft.parse(draft.text))
                            outlineRows = fresh
                            outlineInputs = fresh.map { "" }
                        },
                    ) {
                        Text("重新生成大纲", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        }

        // `/` opens the conversation's own command panel. It lists what TermDesk
        // can actually do to THIS conversation - not a pretend copy of a kernel's
        // menu - and everything in it does something real when tapped.
        if (draft.text.startsWith("/") && !draft.text.contains(' ') && !draft.text.contains('\n')) {
            Column(
                Modifier
                    .fillMaxWidth()
                    // The panel now carries the kernel's own commands as well as the
                    // four local ones, and with the keyboard up the lower rows were
                    // simply cut off. Cap it and let it scroll instead of hiding
                    // entries the user was told exist.
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState())
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                SlashRow("模型", if (modelOptions.isEmpty()) "此助手未提供模型列表" else chosenModelLabel, modelOptions.isNotEmpty()) {
                    updateDraft("")
                    modelPickerOpen = true
                }
                SlashRow("权限模式", if (modeOptions.isEmpty()) "此助手不支持切换权限模式" else chosenModeLabel, modeOptions.isNotEmpty()) {
                    updateDraft("")
                    modePickerOpen = true
                }
                SlashRow("附件", "上传至当前设备，随消息发送", true) {
                    updateDraft("")
                    filePicker.launch(arrayOf("*/*"))
                }
                SlashRow("关闭会话", "结束这个对话", true) {
                    updateDraft("")
                    onClose(chat.id)
                }
                // Commands the PC's kernel actually declares (DSH: /compact,
                // /export, /goal, /permission, ...). Tapping one puts the line in
                // the box instead of running it: compacting a conversation by
                // accident is not undoable, so the last step stays the user's.
                val declared = commands
                if (declared != null && declared.commands.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    for (command in declared.commands) {
                        val hint = command.hint?.takeIf { it.isNotBlank() }
                            ?: command.description.takeIf { it.isNotBlank() }
                            ?: "由 " + chat.engine + " 执行"
                        SlashRow(command.name, hint, true) {
                            updateDraft("/" + command.name + " ")
                        }
                    }
                } else if (!declared?.note.isNullOrBlank()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    SlashRow("命令", declared?.note ?: "", false) {}
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Composer(
            draft = draft,
            onDraftChange = { updateDraftValue(it) },
            onAttach = { attachChooserOpen = true },
            sending = sending,
            running = chat.isRunning,
            // No `connected` here. With the link down this used to grey the arrow out and swallow
            // the tap, which is precisely the state the offline queue exists for: the message is
            // held on the phone, the "待发 N 条 · 连上就发" line explains it, and it goes out when
            // the link returns. A dead button taught the opposite.
            canSend = !chat.ownership.isBusy && (chat.ready || chat.isRunning || chat.status != "stopped"),
            pendingCount = pendingSends,
            droppedCount = pendingDropped,
            onSend = {
                val text = draft.text.trim()
                if (text.isNotEmpty()) {
                    // 点了发送不等于发出去：这一次必须先把欠用户的那个问题问掉。
                    // 欠哪几问、什么顺序，由模板自己的 localAsks 决定（[MentionDraft.nextAsk]）：
                    //   · 「配色」是硬拦——它决定整页的取色，缺了就只能靠模型猜；
                    //   · 「主题」「页数」只固定问一次，用户跳过就照发；
                    //   · 「大纲」是 PPT 特有的硬拦——用户逐条改过的东西没确认之前
                    //     不能发，否则模型会自己重排一遍，他改的那几条就白改了。
                    val pending = MentionDraft.parse(text)
                    val ask: String? = pending.nextAsk()
                    when {
                        ask == MentionDraft.LOCAL_COLORS -> {
                            colorPanel = true; templatePanel = null; themePanel = false
                            pointsPanel = false; deckPanel = null; pagesPanel = false
                            outlinePanel = false
                        }
                        ask == MentionDraft.LOCAL_THEME && !themeAsked -> {
                            themeAsked = true
                            themeInput = pending.theme.ifBlank { pending.free }
                            themePanel = true; templatePanel = null; colorPanel = false
                            pointsPanel = false; deckPanel = null; pagesPanel = false
                            outlinePanel = false
                        }
                        ask == MentionDraft.LOCAL_PAGES && !pagesAsked -> {
                            pagesAsked = true
                            pagesInput = pending.pages
                            pagesPanel = true; templatePanel = null; colorPanel = false
                            themePanel = false; pointsPanel = false; deckPanel = null
                            outlinePanel = false
                        }
                        // 海报生成也是纯本地操作：点发送就等于"我要做海报"，开内联表单。
                        pending.mention?.poster == true -> {
                            openInlineForm(keep = { posterFormOpen = true })
                        }
                        // 图片处理是纯本地操作：不该把 `@图片处理` 当消息发出去，
                        // 点发送就等价于"我要处理图片"，把内联表单开出来。
                        pending.mention?.image == true -> {
                            openInlineForm(keep = { imageFormOpen = true })
                        }
                        // 文案：表单没填全就先把它开出来，不要发一段没头没尾的指令出去。
                        // 走到这里通常是因为用户是手打 `@文案撰写` 进来的，没经过菜单。
                        pending.mention?.copy != null && !copyFormFilled(pending) -> {
                            openInlineForm(keep = { openCopyForm(pending.mention!!, pending) })
                        }
                        pending.needsOutlineAsk() -> {
                            // PPT 走到这里说明基础信息齐了：**在本地铺一份大纲**给用户改。
                            // 不花模型回合，点一下就有东西可看、可改——这就是「快速形成大纲」。
                            if (outlineInputs.isEmpty()) {
                                val fresh = composeOutlineRows(pending)
                                outlineRows = fresh
                                outlineInputs = fresh.map { "" }
                            }
                            outlinePanel = true; templatePanel = null; colorPanel = false
                            themePanel = false; pointsPanel = false; deckPanel = null
                            pagesPanel = false
                        }
                        else -> {
                            sendDraftExpanded(text)
                        }
                    }
                }
            },
            onCancel = { onCancel(chat.id) },
        )

        // 加号点开的小选择：先问一句"相册还是文件"，再跳对应的系统选择器。
        if (attachChooserOpen) {
            AlertDialog(
                onDismissRequest = { attachChooserOpen = false },
                title = { Text("上传附件", style = MaterialTheme.typography.titleSmall) },
                text = {
                    Column(Modifier.fillMaxWidth()) {
                        Text(
                            text = "选一个来源，下一步会跳到手机对应的选择界面。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))
                        ComposerChip("相册", "照片 / 截图") {
                            attachChooserOpen = false
                            photoPicker.launch("image/*")
                        }
                        Spacer(Modifier.height(6.dp))
                        ComposerChip("文件", "内存 / 微信 / QQ") {
                            attachChooserOpen = false
                            filePicker.launch(arrayOf(MIME_ANY))
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { attachChooserOpen = false }) { Text("取消") }
                },
            )
        }

        // 海报生成的表单已经搬进上面的面板区；这里只剩"点开看大图"——
        // 那是看和下载的动作，整屏一张图才看得清，和填需求不是一回事。
        posterViewing?.let { file ->
            PosterPreviewDialog(
                file = file,
                onClose = { posterViewing = null },
                onDownload = { downloadPoster(file) },
                onTweak = { tweakPoster(it) },
            )
        }

        if (modelPickerOpen) {
            ChoicePickerDialog(
                title = "选择模型 · " + chat.engine,
                options = modelOptions,
                selected = chat.model,
                onDismiss = { modelPickerOpen = false },
                onPick = { id ->
                    modelPickerOpen = false
                    onConfigure(chat.id, id, chat.effort.ifBlank { null })
                },
            )
        }
        if (modePickerOpen) {
            ChoicePickerDialog(
                title = "权限模式 · " + chat.engine,
                options = modeOptions,
                selected = chosenMode.orEmpty(),
                onDismiss = { modePickerOpen = false },
                onPick = { id ->
                    modePickerOpen = false
                    onSetMode(chat.id, id)
                },
            )
        }
    }
}

/** Label + horizontally scrollable choices, one line each. */
@Composable
internal fun InlineChips(
    label: String,
    items: List<String>,
    selected: String,
    labelOf: (String) -> String = { it },
    onSelect: (String) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.width(44.dp),
        )
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items.forEach { item ->
                val isSelected = item == selected
                Text(
                    text = labelOf(item),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface,
                        )
                        .clickable { onSelect(item) }
                        .padding(horizontal = 9.dp, vertical = 5.dp),
                )
            }
        }
    }
}

/** One line in the `/` panel: what it is, what it currently is, and what it does. */
@Composable
internal fun SlashRow(label: String, hint: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "/" + label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.outline,
            // A fixed 96dp column broke names longer than it ("permission" came
            // out as "/", "permissio", "n"). The label takes the width it needs
            // and the hint gets the rest, so a long command stays one line.
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(min = 96.dp).padding(end = 10.dp),
        )
        Text(
            hint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 会话的正文区：过程行不画，作品任务连模型的话也不画，只剩结论与产物。
 *
 * 单独成函数不只是为了可读：这段并进 Conversation 之后那个方法有一千多行，
 * ART 的校验器会直接拒绝整个类（VerifyError），debug 包一打开会话就崩。
 */
@Composable
private fun TranscriptPane(
    events: List<ChatEvent>,
    rows: List<TranscriptRow>,
    deliverableDone: Boolean,
    running: Boolean,
    sending: Boolean,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    onOpenFile: ((String) -> Unit)?,
    onPreviewFile: ((String, String) -> Unit)?,
    onPosterOpen: (String) -> Unit,
    onPosterDownload: (String) -> Unit,
    onPosterTweak: ((PosterRenderer.Tweak) -> Unit)?,
    onDownloadPath: ((String, String) -> Unit)?,
) {
    Box(modifier) {
        if (events.isEmpty()) {
            Text(
                "发送消息开始对话",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 10.dp, end = 10.dp, top = 10.dp, bottom = 10.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                // 作品做完之后先给一行结论，下面才是产物卡片。
                if (deliverableDone) {
                    item(key = "td-done") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                "✓ 作品已完成",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
                items(rows, key = { it.key }) { row ->
                    when (row) {
                        is TranscriptRow.One -> ChatEventRow(
                            event = row.event,
                            onOpenFile = onOpenFile,
                            onPreviewFile = onPreviewFile,
                            onPosterOpen = onPosterOpen,
                            onPosterDownload = onPosterDownload,
                            onPosterTweak = onPosterTweak,
                            onDownloadFile = onDownloadPath,
                        )
                        is TranscriptRow.Run -> ProcessRunLine(row.events, onOpenFile, onPreviewFile)
                    }
                }
                // 正在生成：只留这一行。过程（工具、思考、命令、步骤、内核日志）整类不画，
                // 用户要的是"作品生成中…"，不是几屏绿色文本框；生成完了这行自己就没了。
                if (running || sending) {
                    item(key = "td-working") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                "作品生成中…",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
