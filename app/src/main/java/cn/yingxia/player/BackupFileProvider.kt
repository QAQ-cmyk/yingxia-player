package cn.yingxia.player

import androidx.core.content.FileProvider

/** Separate from the debug fixture provider so the two authorities do not merge. */
class BackupFileProvider : FileProvider()
