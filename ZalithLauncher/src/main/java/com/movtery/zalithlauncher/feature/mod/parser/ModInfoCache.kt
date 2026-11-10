package com.movtery.zalithlauncher.feature.mod.parser

/**
 * 模组信息数据缓存
 * @param statKey 快速解析用的 stat 键（文件名|大小|修改时间），旧缓存中为 null
 */
data class ModInfoCache(val fileHash: String, val modInfo: ModInfo, val statKey: String? = null)
