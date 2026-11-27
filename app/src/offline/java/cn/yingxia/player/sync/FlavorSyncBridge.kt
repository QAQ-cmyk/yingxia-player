package cn.yingxia.player.sync

import cn.yingxia.player.AppGraph

internal fun createFlavorSyncBridge(@Suppress("UNUSED_PARAMETER") graph: AppGraph): SyncExtensionBridge =
    DisabledSyncBridge
