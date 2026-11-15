package com.codetool.app.util

import android.content.Context
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import io.github.rosemoe.sora.widget.CodeEditor
import org.eclipse.tm4e.core.registry.IThemeSource
import java.nio.charset.StandardCharsets

/**
 * sora-editor 的 TextMate 语法高亮封装（VSCode 同款 grammar，assets/textmate/）。
 *
 * 一次性加载：语法文件（languages.json 里 markdown/shell/json/jsonc）+ 两套主题
 * （dark_plus 深色 / light_plus 浅色）。之后每个编辑器按文件类型（后缀）选 scope
 * 设置高亮，并按 app 当前主题（浅色/深色）选对应 TextMate 主题——**编辑器背景/前景
 * 必须跟随 app 主题，禁止硬编码成某套主题色**（见 dev-conventions「颜色跟随主题」）。
 *
 * 用法：
 *  - Application.onCreate 调 [init]（幂等，重复调用无害）
 *  - 编辑器就位后调 [applyTo]（按扩展名自动选语言 + 当前主题配色）
 */
object TextMateEditor {

    private const val TEXTMATE_ASSET_DIR = "textmate"
    private const val LANGUAGES_FILE = "$TEXTMATE_ASSET_DIR/languages.json"
    // 官方 Demo 验证过的 tm4e 原生格式主题（settings 数组风格），而非 VSCode 的
    // colors+tokenColors 格式——后者 tokenColors 不会映射成 TextMate 颜色表，token 不上色。
    private const val THEME_DARK_FILE = "$TEXTMATE_ASSET_DIR/themes/darcula.json"
    private const val THEME_DARK_NAME = "darcula"
    private const val THEME_LIGHT_FILE = "$TEXTMATE_ASSET_DIR/themes/quietlight.json"
    private const val THEME_LIGHT_NAME = "quietlight"

    @Volatile
    private var initialized = false

    /** 缓存的 TextMateColorScheme：只创建一次，主题切换走 setTheme + invalidate
     *  （官方 Demo 模式：ensure 后 theme 变更通过 ThemeChangeListener 同步）。 */
    @Volatile
    private var colorScheme: TextMateColorScheme? = null

    /** Application.onCreate 调一次（幂等；重复调用直接跳过）。 */
    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val app = context.applicationContext
            try {
                // 注册 assets 提供者（TextMate 内部按相对路径找语法/主题文件）
                FileProviderRegistry.getInstance().addFileProvider(
                    AssetsFileResolver(app.assets)
                )
                // 加载两套主题（深色优先设激活，方便首次 colorScheme 落到深色）。
                // 注意：VSCode 主题文件是 JSONC（带 // 注释 + 尾逗号），tm4e/Gson 要严格
                // JSON，必须先剥注释 + 去尾逗号再喂给解析器，否则 MalformedJsonException。
                // include 的 *_vs.json 已在 assets 里预清洗为严格 JSON（AssetsFileResolver 直读）。
                loadTheme(app, THEME_DARK_FILE, THEME_DARK_NAME, isDark = true)
                loadTheme(app, THEME_LIGHT_FILE, THEME_LIGHT_NAME, isDark = false)
                ThemeRegistry.getInstance().setTheme(themedName(app))
                // 注册 markdown/shell/json/jsonc 语法
                val grammars = GrammarRegistry.getInstance().loadGrammars(LANGUAGES_FILE)
                FileLog.i(app, "TextMateEditor", "init OK: ${grammars.size} grammars")
                initialized = true
            } catch (t: Throwable) {
                // 加载失败必须留痕（文件日志），否则编辑器静默无高亮
                FileLog.e(app, "TextMateEditor", "init 失败: ${t.message}", t)
                initialized = false  // 允许重试，不崩 App
            }
        }
    }

    private fun loadTheme(app: Context, path: String, name: String, isDark: Boolean) {
        val cleaned = Jsonc.stripTrailingCommas(Jsonc.stripComments(themeFileText(app, path)))
        val model = ThemeModel(
            IThemeSource.fromInputStream(cleaned.byteInputStream(StandardCharsets.UTF_8), path, StandardCharsets.UTF_8),
            name
        ).apply { this.isDark = isDark }
        ThemeRegistry.getInstance().loadTheme(model)
    }

    /** 读 assets 文件返回文本（供剥注释后解析）。 */
    private fun themeFileText(context: Context, path: String): String =
        context.assets.open(path).bufferedReader(StandardCharsets.UTF_8).use { it.readText() }

    /** 按 app 主题返回对应 TextMate 主题名（浅色→light_plus，深色→dark_plus）。 */
    private fun themedName(context: Context): String =
        if (com.codetool.app.theme.ThemeManager.currentMode(context) == com.codetool.app.theme.ThemeManager.Mode.DARK)
            THEME_DARK_NAME else THEME_LIGHT_NAME

    /** 给编辑器按文件路径后缀设置对应的 TextMate 语言 + 主题配色（配色跟随 app 主题）。 */
    fun applyTo(editor: CodeEditor, filePath: String, context: Context? = null) {
        try {
            val ctx = context ?: editor.context
            // ① 确保 colorScheme 是 TextMateColorScheme（只创建一次；每次重建会让
            //    Analyzer 持有的 theme 引用与 colorScheme 不一致，token 颜色映射失效）
            if (editor.colorScheme !is TextMateColorScheme) {
                editor.colorScheme = TextMateColorScheme.create(ThemeRegistry.getInstance())
                colorScheme = editor.colorScheme as TextMateColorScheme
            }
            // ② 切换 TextMate 主题跟随 app 主题（浅/深），然后强制重绘
            //    （官方 Demo：只 setTheme + invalidate，不重建 scheme）
            ThemeRegistry.getInstance().setTheme(themedName(ctx))
            editor.invalidate()
            // ③ 按文件类型设置语言
            val scope = scopeFor(filePath) ?: return
            if (editor.editorLanguage !is TextMateLanguage) {
                editor.setEditorLanguage(TextMateLanguage.create(scope, false))
            }
            FileLog.i(ctx, "TextMateEditor", "applyTo $filePath -> scope=$scope theme=${themedName(ctx)} OK")
        } catch (t: Throwable) {
            // 高亮失败不崩编辑器（TextMate 解析某些 grammar 的正则会 fallback）
            FileLog.e(editor.context, "TextMateEditor", "applyTo $filePath 失败: ${t.message}", t)
        }
    }

    /** 清除高亮（编辑器释放/无语言时）。 */
    fun clear(editor: CodeEditor) {
        runCatching { editor.setEditorLanguage(io.github.rosemoe.sora.lang.EmptyLanguage()) }
    }

    /** 文件路径后缀 → TextMate 根 scope；不支持返回 null（编辑器用默认纯文本）。 */
    private fun scopeFor(filePath: String): String? {
        val lower = filePath.lowercase()
        return when {
            lower.endsWith(".md") || lower.endsWith(".markdown") -> "text.html.markdown"
            lower.endsWith(".sh") || lower.endsWith(".bash") || lower.endsWith(".zsh") ->
                "source.shell"
            lower.endsWith(".jsonc") -> "source.json.comments"  // 配置文件（带注释 JSON）
            lower.endsWith(".json") -> "source.json"
            else -> null
        }
    }
}