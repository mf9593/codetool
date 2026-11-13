package com.codetool.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.codetool.app.runtime.RootfsBackupManager
import com.codetool.app.runtime.RootfsBackupService
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.io.File

/**
 * rootfs 备份 / 恢复（工具页独立 tab）。
 *
 * 规范：破坏性 / 低频管理入口不放首页。真正的 tar 任务由 [RootfsBackupService]
 * 前台服务执行（WakeLock + 进度通知），本页只负责进度展示与操作入口。
 */
class RootfsBackupFragment : Fragment() {

    private lateinit var backupStatus: TextView
    private lateinit var backupProgress: LinearProgressIndicator
    private lateinit var btnBackup: MaterialButton
    private lateinit var btnRestore: MaterialButton
    private lateinit var btnBackupCancel: MaterialButton
    private var receiver: BroadcastReceiver? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_rootfs_backup, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        backupStatus = view.findViewById(R.id.backupStatus)
        backupProgress = view.findViewById(R.id.backupProgress)
        btnBackup = view.findViewById(R.id.btnBackup)
        btnRestore = view.findViewById(R.id.btnRestore)
        btnBackupCancel = view.findViewById(R.id.btnBackupCancel)

        btnBackup.setOnClickListener { startBackupService(null) }
        btnRestore.setOnClickListener { pickRestore() }
        btnBackupCancel.setOnClickListener { cancelBackupService() }
        syncBackupUi(RootfsBackupService.snapshot())

        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val done = intent.getBooleanExtra(RootfsBackupService.EXTRA_DONE, false)
                val err = intent.getStringExtra(RootfsBackupService.EXTRA_ERROR)
                val pct = intent.getIntExtra(RootfsBackupService.EXTRA_PERCENT, -1)
                val msg = intent.getStringExtra(RootfsBackupService.EXTRA_MESSAGE).orEmpty()
                when {
                    done -> {
                        syncBackupUi(null)
                        toast(msg.ifEmpty { "完成" })
                    }
                    err != null -> {
                        syncBackupUi(null)
                        toast(err)
                    }
                    else -> syncBackupUi(RootfsBackupService.Snapshot(pct, msg, ""))
                }
            }
        }
        receiver = r
        requireContext().registerReceiver(
            r,
            IntentFilter(RootfsBackupService.BROADCAST),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onResume() {
        super.onResume()
        if (::backupProgress.isInitialized) syncBackupUi(RootfsBackupService.snapshot())
    }

    override fun onDestroyView() {
        receiver?.let { runCatching { requireContext().unregisterReceiver(it) } }
        receiver = null
        super.onDestroyView()
    }

    private fun toast(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    private fun refreshBackupStatus() {
        val n = RootfsBackupManager.listBackups().size
        backupStatus.text = "备份存于 Download/codetool/backup/ · 现有 $n 个备份"
    }

    /** s == null 空闲；进行中显示进度条、禁用按钮、显示取消。 */
    private fun syncBackupUi(s: RootfsBackupService.Snapshot?) {
        if (!::backupProgress.isInitialized) return
        val busy = s != null
        btnBackup.isEnabled = !busy
        btnRestore.isEnabled = !busy
        btnBackupCancel.visibility = if (busy) View.VISIBLE else View.GONE
        if (!busy) {
            backupProgress.hide()
            refreshBackupStatus()
            return
        }
        backupProgress.show()
        backupStatus.text = s!!.message.ifEmpty { "任务进行中…" }
        if (s.percent < 0) {
            backupProgress.setIndeterminate(true)
        } else {
            backupProgress.setIndeterminate(false)
            backupProgress.setProgressCompat(s.percent.coerceIn(0, 100), true)
        }
    }

    /** 启动前台服务执行备份 / 恢复。restoreFile == null 表示备份。 */
    private fun startBackupService(restoreFile: File?) {
        if (RootfsBackupService.snapshot() != null) {
            toast("有备份/恢复任务进行中")
            return
        }
        val i = Intent(requireContext(), RootfsBackupService::class.java).apply {
            action = if (restoreFile == null) RootfsBackupService.ACTION_BACKUP
            else RootfsBackupService.ACTION_RESTORE
            restoreFile?.let { putExtra(RootfsBackupService.EXTRA_BACKUP_PATH, it.absolutePath) }
        }
        ContextCompat.startForegroundService(requireContext(), i)
        syncBackupUi(
            RootfsBackupService.Snapshot(
                0,
                if (restoreFile == null) "正在准备备份…" else "正在准备恢复…",
                "",
            )
        )
    }

    private fun cancelBackupService() {
        requireContext().startService(
            Intent(requireContext(), RootfsBackupService::class.java)
                .setAction(RootfsBackupService.ACTION_CANCEL)
        )
        toast("正在取消…")
    }

    /** 恢复：列出备份 → 二次确认 → 前台服务。 */
    private fun pickRestore() {
        if (RootfsBackupService.snapshot() != null) {
            toast("有备份/恢复任务进行中")
            return
        }
        val list = RootfsBackupManager.listBackups()
        if (list.isEmpty()) {
            toast("还没有备份，先点「备份」")
            return
        }
        val names = list.map { "${it.name} (${it.length() / 1048576} MB)" }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("选择要恢复的备份")
            .setItems(names) { _, which ->
                val f = list[which]
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("确认恢复（覆盖当前 rootfs）")
                    .setMessage("将用 ${f.name} 覆盖当前 rootfs，当前系统内已安装的软件与配置会被替换。\n失败或取消会自动回滚。\n\n建议恢复后重启 app。")
                    .setPositiveButton("覆盖恢复") { _, _ -> startBackupService(f) }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
