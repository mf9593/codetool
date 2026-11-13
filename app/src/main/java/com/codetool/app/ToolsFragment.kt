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
 * 工具页：脚本库 / 包管理 / AI 模型 / Skill / MCP / 备份，同页 tab 切换。
 * 内嵌 ViewPager2，六个子 tab。首页只做状态展示，管理/破坏性入口都放这里。
 */
class ToolsFragment : Fragment() {

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
                1 -> "包管理"
                2 -> "AI 模型"
                3 -> "Skill"
                4 -> "MCP"
                else -> "备份"
            }
        }.attach()
        return v
    }

    private class InnerAdapter(fa: FragmentActivity) : FragmentStateAdapter(fa) {
        override fun getItemCount() = 6
        override fun createFragment(position: Int): Fragment = when (position) {
            0 -> ScriptsFragment()
            1 -> PackagesFragment()
            2 -> AiModelsFragment()
            3 -> SkillsFragment()
            4 -> McpFragment()
            else -> RootfsBackupFragment()
        }
    }
}
