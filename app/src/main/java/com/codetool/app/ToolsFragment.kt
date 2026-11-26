package com.codetool.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

/**
 * 工具页：脚本库 / AI 模型 / 包管理 / 定时任务 / Skill / MCP / Git / 下载 / 备份，同页 tab 切换。
 * 内嵌 ViewPager2，九个子 tab。首页只做状态展示，管理/破坏性入口都放这里。
 */
class ToolsFragment : Fragment() {

    companion object {
        const val TAB_AI_MODELS = 1
        const val TAB_SCHEDULED = 3
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_tools, container, false)
        val tabs = v.findViewById<TabLayout>(R.id.toolTabs)
        val pager = v.findViewById<ViewPager2>(R.id.toolPager)
        pager.adapter = InnerAdapter(requireActivity())
        TabLayoutMediator(tabs, pager) { tab, pos ->
            tab.text = when (pos) {
                0 -> "脚本库"
                1 -> "AI 模型"
                2 -> "包管理"
                3 -> "定时任务"
                4 -> "Skill"
                5 -> "MCP"
                6 -> "Git"
                7 -> "下载"
                else -> "备份"
            }
        }.attach()
        return v
    }

    /** 供子页面（如 AI 模型页的定时任务入口）切换到指定 tab。 */
    fun openTab(index: Int) {
        (view?.findViewById<ViewPager2>(R.id.toolPager))?.currentItem = index
    }

    private class InnerAdapter(fa: FragmentActivity) : FragmentStateAdapter(fa) {
        override fun getItemCount() = 9
        override fun createFragment(position: Int): Fragment = when (position) {
            0 -> ScriptsFragment()
            1 -> AiModelsFragment()
            2 -> PackagesFragment()
            3 -> ScheduledTasksFragment()
            4 -> SkillsFragment()
            5 -> McpFragment()
            6 -> GitFragment()
            7 -> DownloadsFragment()
            else -> RootfsBackupFragment()
        }
    }
}
