package com.codetool.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.WindowCompat
import com.codetool.app.theme.ThemeManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton

/**
 * 用户协议全屏页。
 *
 * 两种模式：
 * - 首次启动（默认）：显示底部「勾选同意 + 下一步」，下一步在勾选后才可用；
 *   点下一步 → RESULT_OK（MainActivity 记录已同意并继续）；按返回 → RESULT_CANCELED（退出）。
 * - 查看模式（extra VIEW=true，关于页入口）：隐藏底部操作栏，只读浏览，返回即关。
 *
 * 用 [android.app.Activity]（非 AppCompatActivity）+ ThemeManager.apply，与 AboutActivity 同理，
 * 避免 Material3.Light 主题被 AppCompat 拒绝。
 */
class AgreementActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // 必须在 super.onCreate 前应用当前主题（与 AboutActivity 一致；apply 要 AppCompatActivity，故用 setTheme）
        setTheme(ThemeManager.resolveStyle(this))
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_agreement)

        val viewMode = intent.getBooleanExtra(EXTRA_VIEW, false)
        findViewById<MaterialToolbar>(R.id.agreementToolbar).setNavigationOnClickListener {
            finish()  // 查看模式=返回；首启模式=视为不同意（RESULT_CANCELED）
        }
        // 首启模式不给返回箭头（必须走勾选+下一步），查看模式显示返回箭头
        val toolbar = findViewById<MaterialToolbar>(R.id.agreementToolbar)
        toolbar.navigationIcon = if (viewMode) getDrawable(R.drawable.ic_chevron_left) else null

        findViewById<TextView>(R.id.agreementText).text = UserAgreement.loadText(this)

        val actions = findViewById<LinearLayout>(R.id.agreementActions)
        if (viewMode) {
            actions.visibility = View.GONE
        } else {
            val check = findViewById<CheckBox>(R.id.agreementCheck)
            val next = findViewById<MaterialButton>(R.id.agreementNext)
            next.isEnabled = false
            check.setOnCheckedChangeListener { _, isChecked -> next.isEnabled = isChecked }
            next.setOnClickListener {
                UserAgreement.markAccepted(this)
                setResult(RESULT_OK)
                finish()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // 首启模式按返回=不同意（MainActivity 据 RESULT_CANCELED 退出）；查看模式正常返回
        setResult(RESULT_CANCELED)
        super.onBackPressed()
    }

    companion object {
        const val EXTRA_VIEW = "view"

        /** 首启强制同意入口。 */
        fun startFirstLaunch(activity: Activity, requestCode: Int) {
            activity.startActivityForResult(Intent(activity, AgreementActivity::class.java), requestCode)
        }

        /** 关于页查看入口（只读）。 */
        fun startView(activity: Activity) {
            activity.startActivity(Intent(activity, AgreementActivity::class.java).putExtra(EXTRA_VIEW, true))
        }
    }
}
