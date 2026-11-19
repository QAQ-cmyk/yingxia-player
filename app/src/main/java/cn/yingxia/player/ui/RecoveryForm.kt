package cn.yingxia.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import cn.yingxia.player.vault.RecoveryAction
import cn.yingxia.player.vault.maxRecoveryPromptLength
import cn.yingxia.player.vault.recoveryQuestions
import cn.yingxia.player.vault.validRecoveryPrompts
import kotlinx.coroutines.CancellationException

@Composable
internal fun RecoveryForm(action: RecoveryAction, loadPrompts: suspend () -> List<String>, busy: Boolean, modifier: Modifier, onCancel: () -> Unit,
                         onSubmit: (CharArray, List<String>, List<CharArray>) -> Unit) {
    val drafts = remember { mutableStateListOf("", "", "") }
    val answers = remember { mutableStateListOf("", "", "") }
    val enroll = action == RecoveryAction.ENROLL
    val reset = action == RecoveryAction.RESET
    var editing by remember { mutableStateOf(enroll) }
    BackHandler(enabled = enroll && !editing && !busy) { editing = true }
    if (editing) {
        RecoveryQuestionEditor(drafts, modifier, onEdit = { index, value ->
            if (drafts[index].trim() != value.trim()) answers[index] = ""
            drafts[index] = value
        }, onNext = { editing = false }, onCancel = onCancel)
        return
    }
    var stored by remember { mutableStateOf(emptyList<String>()) }
    var loadFailed by remember { mutableStateOf(false) }
    if (reset && stored.isEmpty()) {
        LaunchedEffect(Unit) {
            try { stored = loadPrompts() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { loadFailed = true }
        }
        Column(modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (loadFailed) "无法读取本机密保问题，请使用原密码解锁。" else "正在读取密保问题…")
            TextButton(onClick = onCancel) { Text("返回") }
        }
        return
    }
    val prompts = if (enroll) drafts.map { it.trim() } else stored
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var showAnswers by remember { mutableStateOf(false) }
    val title = when (action) {
        RecoveryAction.ENROLL -> "填写三个答案"
        RecoveryAction.DISABLE -> "关闭密保问题"
        RecoveryAction.RESET -> "重设保险箱密码"
    }
    val clean = answers.map { it.trim() }
    val validAnswers = when (action) {
        RecoveryAction.ENROLL -> clean.all { it.length >= 4 } && clean.distinct().size == 3
        RecoveryAction.RESET -> clean.size == 3 && clean.none { it.isEmpty() }
        RecoveryAction.DISABLE -> true
    }
    val keyboard = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password)
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(when (action) {
            RecoveryAction.ENROLL -> "答对全部三题才能重设密码，请分别写下只有你知道的短语，每个至少四个字符。避免使用生日、姓名等容易猜到的信息。"
            RecoveryAction.DISABLE -> "请输入当前密码。关闭后，这三个答案将无法再用来重设密码。"
            RecoveryAction.RESET -> "请按原顺序回答全部三题，区分大小写。重设只在当前设备生效，原有视频会保留。"
        }, style = MaterialTheme.typography.bodyMedium)
        if (action != RecoveryAction.DISABLE) {
            prompts.forEachIndexed { index, prompt ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(prompt, style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("recovery-prompt-" + index))
                    OutlinedTextField(answers[index], { answers[index] = it.take(96) },
                        Modifier.fillMaxWidth().testTag("recovery-answer-" + index), label = { Text("第 " + (index + 1) + " 题答案") },
                        enabled = !busy, singleLine = true, keyboardOptions = keyboard,
                        visualTransformation = if (showAnswers) VisualTransformation.None else PasswordVisualTransformation())
                }
            }
            TextButton(onClick = { showAnswers = !showAnswers }) { Text(if (showAnswers) "隐藏答案" else "查看填写的答案") }
            if (enroll) TextButton(enabled = !busy, onClick = { editing = true }) { Text("修改问题") }
            if (enroll && clean.filter { it.isNotEmpty() }.let { it.size != it.distinct().size })
                Text("请为三道题填写不同的答案", color = MaterialTheme.colorScheme.error)
        }
        OutlinedTextField(password, { password = it.take(128) }, Modifier.fillMaxWidth().testTag("recovery-password"),
            label = { Text(if (reset) "新密码（至少八个字符）" else "当前保险箱密码") }, enabled = !busy, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = keyboard)
        if (reset) OutlinedTextField(confirm, { confirm = it.take(128) }, Modifier.fillMaxWidth().testTag("recovery-confirm"),
            label = { Text("再次输入新密码") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = keyboard)
        Button(onClick = {
            val credential = password.toCharArray()
            password = ""
            onSubmit(credential, prompts, if (action == RecoveryAction.DISABLE) emptyList() else clean.map { it.toCharArray() })
        }, enabled = !busy && password.length >= 8 && validAnswers && (!reset || confirm == password),
            modifier = Modifier.fillMaxWidth().testTag("recovery-submit")) { Text(when (action) {
                RecoveryAction.ENROLL -> "开启密保"
                RecoveryAction.DISABLE -> "确认关闭"
                RecoveryAction.RESET -> "保存新密码"
            }) }
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("取消") }
    }
}

@Composable
private fun RecoveryQuestionEditor(drafts: List<String>, modifier: Modifier, onEdit: (Int, String) -> Unit,
                                   onNext: () -> Unit, onCancel: () -> Unit) {
    val prompts = drafts.map { it.trim() }
    var menu by remember { mutableIntStateOf(-1) }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("写下你的密保问题", style = MaterialTheme.typography.titleLarge)
        Text("每题最多 80 个字符。锁定时可以看到题目，请把答案留到下一步填写。",
            style = MaterialTheme.typography.bodyMedium)
        drafts.forEachIndexed { index, draft ->
            val duplicate = prompts[index].isNotEmpty() && prompts.take(index).contains(prompts[index])
            OutlinedTextField(value = draft, onValueChange = { value ->
                if (value.length <= maxRecoveryPromptLength && value.none(Char::isISOControl)) onEdit(index, value)
            }, modifier = Modifier.fillMaxWidth().testTag("recovery-question-" + index),
                label = { Text("问题 " + (index + 1)) }, placeholder = { Text("输入你自己的问题") },
                minLines = 1, maxLines = 3, isError = duplicate,
                supportingText = if (duplicate) { { Text("请换一道不同的问题") } } else null,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next))
            Box {
                TextButton(onClick = { menu = index }, modifier = Modifier.testTag("recovery-presets-" + index)) { Text("从题库选") }
                DropdownMenu(expanded = menu == index, onDismissRequest = { menu = -1 }) {
                    recoveryQuestions.values.forEach { prompt ->
                        DropdownMenuItem(text = { Text(prompt) }, enabled = prompt !in prompts || prompt == prompts[index],
                            onClick = { onEdit(index, prompt); menu = -1 })
                    }
                }
            }
        }
        Button(onClick = onNext, enabled = validRecoveryPrompts(prompts),
            modifier = Modifier.fillMaxWidth().testTag("recovery-next")) { Text("下一步，填写答案") }
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("取消") }
    }
}
