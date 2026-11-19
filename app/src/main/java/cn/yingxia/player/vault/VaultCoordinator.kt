package cn.yingxia.player.vault

import android.content.Context
import cn.yingxia.player.core.PlayerCommand
import cn.yingxia.player.core.VideoItem
import cn.yingxia.player.core.LibraryAccess
import cn.yingxia.player.data.libraryAccess
import cn.yingxia.player.data.canonicalVideoUri
import android.net.Uri
import android.provider.MediaStore
import cn.yingxia.player.data.DeleteOutcome
import cn.yingxia.player.data.LocalFileManager
import cn.yingxia.player.data.LocalVideoRepository
import cn.yingxia.player.playback.Media3Controller
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

internal enum class RecoveryAction { ENROLL, DISABLE, RESET }

data class VaultUiState(
    val recoveryEnabled: Boolean = false,
    val visible: Boolean = false, val configured: Boolean = false, val unlocked: Boolean = false,
    val busy: Boolean = false, val progress: String = "", val message: String? = null,
    val entries: List<VaultEntry> = emptyList(), val importItems: List<VideoItem> = emptyList(),
    val consent: DeleteOutcome.Consent? = null, val consentToken: Long = 0,
    val playing: VaultEntry? = null, val epoch: Long = 0, val damagedRecords: Int = 0,
)

/** One foreground operation at a time; backgrounding cancels IO and destroys the unlocked session. */
class VaultCoordinator(
    private val context: Context, private val repository: LocalVideoRepository, private val files: LocalFileManager,
    private val ordinaryPlayer: Media3Controller, private val scope: CoroutineScope,
    private val settings: cn.yingxia.player.data.SettingsRepository,
) {
    val store = VaultStore(context)
    val state = MutableStateFlow(VaultUiState(configured = store.configured, recoveryEnabled = store.recovery.enabled))
    private var job: Job? = null
    private var previousJob: Job? = null
    private val epoch = AtomicLong(0)
    private var serial = 0L
    private var queue = emptyList<VideoItem>()
    private var consentEntry: String? = null
    private var launchedConsent: DeleteOutcome.Consent? = null
    private var consentAnswer: Boolean? = null
    var releasePlayer: (() -> Unit)? = null
    var playback: VaultPlaybackSession? = null; private set

    fun show(items: List<VideoItem> = emptyList()) {
        if (state.value.busy) return
        state.update { it.copy(visible = true, configured = store.configured, recoveryEnabled = store.recovery.enabled, importItems = items.filter { item -> item.accessible }, message = null) }
        scope.launch { ordinaryPlayer.execute(PlayerCommand.Close) }
    }
    fun close() { lock(); state.update { it.copy(visible = false) } }
    fun lock() {
        epoch.incrementAndGet()
        job?.cancel(); previousJob = job ?: previousJob; job = null
        try { releasePlayer?.invoke() } finally {
            releasePlayer = null; playback = null
            store.lock(); queue = emptyList()
            state.value = VaultUiState(visible = state.value.visible, configured = store.configured, recoveryEnabled = store.recovery.enabled, epoch = epoch.get())
        }
    }
    fun dismissMessage() { state.update { it.copy(message = null) } }
    fun dismissImport() { state.update { it.copy(importItems = emptyList()) } }
    fun requestImport(items: List<VideoItem>) { if (!state.value.busy) state.update { it.copy(importItems = items) } }

    fun unlock(password: CharArray) {
        if (state.value.busy) { password.fill('\u0000'); return }
        run("正在解锁") {
            try {
                withContext(Dispatchers.IO) {
                    if (store.configured) store.unlock(password) else store.setup(password)
                }
                state.update { it.copy(configured = true, unlocked = true) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { store.lock(); state.update { it.copy(unlocked = false) }; error("密码不正确，或保险箱文件损坏；原文件未改动") }
            finally { password.fill('\u0000') }
            refresh()
            if (consentAnswer != null) finishConsent()
            reconcileRemovedSources()
        }
    }

    internal suspend fun recoveryPrompts(): List<String> = withContext(Dispatchers.IO) {
        store.recovery.enrolledPrompts()
    }

    internal fun updateRecovery(action: RecoveryAction, password: CharArray, prompts: List<String>, answers: List<CharArray>) {
        fun clear() { password.fill('\u0000'); answers.forEach { it.fill('\u0000') } }
        val lockedAction = action == RecoveryAction.RESET
        if (!state.value.visible || !state.value.configured || state.value.busy || job?.isActive == true ||
            state.value.unlocked == lockedAction) { clear(); return }
        run("正在本机核验") {
            withContext(Dispatchers.IO) {
                val worker = currentCoroutineContext()
                val beforeCommit: () -> Unit = { worker.ensureActive(); if (!lockedAction) store.requireSession().requireOpen() }
                when (action) {
                    RecoveryAction.ENROLL -> store.recovery.enroll(password, prompts, answers, beforeCommit)
                    RecoveryAction.DISABLE -> store.recovery.disable(password, beforeCommit)
                    RecoveryAction.RESET -> store.recovery.reset(answers, password, beforeCommit)
                }
            }
            val message = when (action) {
                RecoveryAction.ENROLL -> "密保已开启。忘记密码时，可在这台手机上回答三题并重设密码。"
                RecoveryAction.DISABLE -> "密保已关闭，以后需要密码才能打开保险箱。"
                RecoveryAction.RESET -> "密码已重设。请用新密码解锁，然后按需重新设置密保。"
            }
            state.update { it.copy(recoveryEnabled = store.recovery.enabled, message = message) }
        }
        job?.invokeOnCompletion { clear() }
    }

    fun importConfirmed() {
        if (!state.value.unlocked || state.value.busy) return
        val items = state.value.importItems
        if (items.isEmpty()) return
        state.update { it.copy(importItems = emptyList()) }
        if (items.all { it.source == "share" }) {
            run("准备加密") {
                var done = 0
                try {
                    items.forEach { video ->
                        val prior = state.value.entries.firstOrNull { !it.sourceRemoved && it.sourceUri == video.uri && it.sourceId == video.id.value }
                        val alreadySaved = prior != null && try {
                            store.verifyBeforeRemoval(prior)
                            true
                        } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
                        if (!alreadySaved) store.encrypt(video) { progress("${done + 1}/${items.size} · $it") }
                        done++
                    }
                    state.update { it.copy(message = "已加密并校验 $done 个视频。相册原件已保留。") }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { error("已加密 $done/${items.size} 个视频。其余视频未完成，请检查原件和可用空间。相册原件已保留。") }
                finally { if (currentCoroutineContext().isActive) refresh() }
            }
            return
        }
        queue = items
        run("准备加密") { ordinaryPlayer.execute(PlayerCommand.Close); continueImport() }
    }
    private suspend fun continueImport() {
        while (queue.isNotEmpty()) {
            val video = queue.first()
            // A cancelled deletion can be resumed without making another encrypted copy.
            val existing = state.value.entries.firstOrNull { !it.sourceRemoved && !it.manageOriginalInGallery && it.sourceId == video.id.value && it.sourceUri == video.uri }
            val entry = existing ?: store.encrypt(video, ::progress)
            refresh()
            if (!removeOriginal(entry)) return
            queue = queue.drop(1)
        }
        state.update { it.copy(message = "已加密并移除所选相册原件。相册中如有其他副本，仍需单独处理。") }
    }
    fun retryRemoval(id: String) {
        if (!state.value.unlocked) return
        run("校验加密副本与原件") { removeOriginal(withContext(Dispatchers.IO) { store.load(id) }); refresh() }
    }
    private suspend fun removeOriginal(entry: VaultEntry): Boolean {
        check(!entry.manageOriginalInGallery) { "请回来源相册管理原件" }
        progress("校验副本与原件，准备请求移除")
        store.verifyBeforeRemoval(entry)
        val removalUri = withContext(Dispatchers.IO) { canonicalVideoUri(context, Uri.parse(entry.sourceUri))?.toString() ?: entry.sourceUri }
        withContext(Dispatchers.IO) { store.save(entry.copy(removalUri = removalUri)) }
        when (val result = files.delete(entry.original())) {
            DeleteOutcome.Deleted -> finishRemoval(entry)
            is DeleteOutcome.Consent -> {
                consentEntry = entry.id; launchedConsent = result; consentAnswer = null
                state.update { it.copy(consent = result, consentToken = ++serial, busy = true, progress = "请在系统窗口确认移除相册原件") }
                return false
            }
        }
        return true
    }
    fun consentLaunched() { state.update { it.copy(consent = null) } }
    fun consentResult(approved: Boolean) {
        consentAnswer = approved
        scope.launch {
            job?.join()
            if (consentAnswer == null) return@launch
            if (state.value.unlocked) run("核对相册移除结果", allowWaiting = true) { finishConsent() }
            else state.update { it.copy(message = "请重新解锁，核对刚才的相册操作结果") }
        }
    }
    private suspend fun finishConsent() {
        val approved = consentAnswer ?: return
        val id = consentEntry ?: return
        val consent = launchedConsent ?: return
        consentAnswer = null; consentEntry = null; launchedConsent = null
        val entry = withContext(Dispatchers.IO) { store.load(id) }
        if (!approved) {
            queue = emptyList()
            state.update { it.copy(message = "已保留加密副本；未获准移除原件，相册仍可见。可在视频下方继续移除。") }
            refresh(); return
        }
        if (consent.retryDelete) {
            store.verifyBeforeRemoval(entry)
            check(files.delete(entry.original(), afterGrant = true) == DeleteOutcome.Deleted)
        } else files.verifySystemDeletion(consent.mediaUri)
        finishRemoval(entry)
        if (queue.isNotEmpty()) { queue = queue.drop(1); continueImport() }
        else state.update { it.copy(message = "已确认相册原件移除，加密视频可在保险箱播放") }
    }
    private suspend fun finishRemoval(entry: VaultEntry) {
        // Also purge plaintext names/history/bookmarks from the ordinary library and JSON backups.
        repository.forgetEncryptedSource(entry.original())
        withContext(Dispatchers.IO) { store.save(entry.copy(sourceRemoved = true)) }
        refresh(); repository.refresh()
    }
    /** A process may die after Android deletes the source but before our result callback. */
    private suspend fun reconcileRemovedSources() {
        if (libraryAccess(context) != LibraryAccess.FULL) return // Missing rows under partial access are ambiguous.
        state.value.entries.filter { !it.sourceRemoved && Uri.parse(it.removalUri).authority == MediaStore.AUTHORITY }.forEach { entry ->
            val removed = try { files.verifySystemDeletion(entry.removalUri); true }
            catch (e: CancellationException) { throw e } catch (_: Exception) { false }
            if (removed) finishRemoval(entry)
        }
    }
    fun deleteEncrypted(id: String) {
        if (!state.value.unlocked) return
        stopPlaying()
        run("正在移除保险箱副本") {
            withContext(Dispatchers.IO) { store.remove(id) }
            refresh(); state.update { it.copy(message = "已移除保险箱副本，相册中的文件未改动") }
        }
    }
    fun restore(id: String) {
        if (!state.value.unlocked) return
        stopPlaying()
        run("准备恢复到相册") {
            val entry = withContext(Dispatchers.IO) { store.load(id) }
            val result = store.restore(entry, ::progress)
            refresh(); repository.refresh()
            state.update { it.copy(message = result) }
        }
    }
    fun play(entry: VaultEntry) {
        if (state.value.busy || !state.value.unlocked) return
        run("准备安全播放") {
            stopPlaying()
            val records = VaultVideoRepository(store)
            records.initialize(state.value.entries)
            val session = VaultPlaybackSession(context, store, records, settings, ::stopPlaying) { text ->
                if (state.value.unlocked) state.update { it.copy(message = text) }
            }
            playback = session
            releasePlayer = session::close
            try { session.open(entry.id); state.update { it.copy(playing = entry) } }
            catch (e: Exception) { stopPlaying(); throw e }
        }
    }
    fun stopPlaying() {
        val names = playback?.repository?.videos?.value.orEmpty().associate { it.id.value to (it.displayName ?: it.name) }
        try { releasePlayer?.invoke() } finally {
            releasePlayer = null; playback = null
            state.update { current -> current.copy(playing = null, entries = current.entries.map { entry -> names[entry.id]?.let { entry.copy(name = it) } ?: entry }) }
        }
    }
    private suspend fun refresh() {
        val inventory = withContext(Dispatchers.IO) { store.inventory().let { it.copy(entries = it.entries.map(store::recoverRestore)) } }
        state.update { it.copy(entries = inventory.entries, damagedRecords = inventory.damagedRecords) }
    }
    private fun progress(text: String) { if (state.value.unlocked) state.update { it.copy(progress = text) } }
    private fun run(label: String, allowWaiting: Boolean = false, action: suspend () -> Unit) {
        if (job?.isActive == true || state.value.busy && !allowWaiting) return
        val token = epoch.get()
        state.update { it.copy(busy = true, message = null, progress = label) }
        job = scope.launch {
            try {
                previousJob?.join(); previousJob = null
                ensureActive(); action()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                queue = emptyList()
                if (token == epoch.get()) state.update { it.copy(message = e.message ?: "操作未完成，原文件与已完成的加密副本均未主动清除") }
            } finally {
                if (token == epoch.get()) state.update { it.copy(busy = launchedConsent != null && consentAnswer == null, progress = "") }
                else store.lock() // A slow password derivation must not leave a session unlocked after backgrounding.
            }
        }
    }
}
