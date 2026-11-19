package cn.yingxia.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import cn.yingxia.player.vault.RecoveryAction
import cn.yingxia.player.vault.recoveryQuestions

@Composable
internal fun RecoveryForm(action: RecoveryAction, storedIds: List<Int>, modifier: Modifier, onCancel: () -> Unit,
                         onSubmit: (CharArray, List<Int>, List<CharArray>) -> Unit) {
    val selected = remember { mutableStateListOf<Int>() }
    val answers = remember { mutableStateMapOf<Int, String>() }
    var choosing by remember { mutableStateOf(action == RecoveryAction.ENROLL) }
    BackHandler(enabled = action == RecoveryAction.ENROLL && !choosing) { choosing = true }
    if (choosing) {
        RecoveryQuestionPicker(selected, modifier, onToggle = { id ->
            if (id in selected) {
                selected.remove(id)
                answers.remove(id)
            } else if (selected.size < 3) selected.add(id)
        }, onNext = { choosing = false }, onCancel = onCancel)
        return
    }
    val ids = if (action == RecoveryAction.ENROLL) selected.toList() else storedIds
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var showAnswers by remember { mutableStateOf(false) }
    val reset = action == RecoveryAction.RESET
    val enroll = action == RecoveryAction.ENROLL
    val title = when (action) {
        RecoveryAction.ENROLL -> "填写三个答案"
        RecoveryAction.DISABLE -> "关闭密保问题"
        RecoveryAction.RESET -> "重设保险箱密码"
    }
    val clean = ids.map { answers[it].orEmpty().trim() }
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
            ids.forEachIndexed { index, id ->
                OutlinedTextField(answers[id].orEmpty(), { answers[id] = it.take(96) },
                    Modifier.fillMaxWidth().testTag("recovery-answer-" + index), label = { Text(recoveryQuestions.getValue(id)) },
                    singleLine = true, keyboardOptions = keyboard,
                    visualTransformation = if (showAnswers) VisualTransformation.None else PasswordVisualTransformation())
            }
            TextButton(onClick = { showAnswers = !showAnswers }) { Text(if (showAnswers) "隐藏答案" else "查看填写的答案") }
            if (enroll) TextButton(onClick = { choosing = true }) { Text("重新选题") }
            if (enroll && clean.filter { it.isNotEmpty() }.let { it.size != it.distinct().size })
                Text("请为三道题填写不同的答案", color = MaterialTheme.colorScheme.error)
        }
        OutlinedTextField(password, { password = it.take(128) }, Modifier.fillMaxWidth().testTag("recovery-password"),
            label = { Text(if (reset) "新密码（至少八个字符）" else "当前保险箱密码") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = keyboard)
        if (reset) OutlinedTextField(confirm, { confirm = it.take(128) }, Modifier.fillMaxWidth().testTag("recovery-confirm"),
            label = { Text("再次输入新密码") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = keyboard)
        Button(onClick = { onSubmit(password.toCharArray(), ids, if (action == RecoveryAction.DISABLE) emptyList() else clean.map { it.toCharArray() }) },
            enabled = password.length >= 8 && validAnswers && (!reset || confirm == password),
            modifier = Modifier.fillMaxWidth().testTag("recovery-submit")) { Text(when (action) {
                RecoveryAction.ENROLL -> "开启密保"
                RecoveryAction.DISABLE -> "确认关闭"
                RecoveryAction.RESET -> "保存新密码"
            }) }
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("取消") }
    }
}

@Composable
private fun RecoveryQuestionPicker(selected: List<Int>, modifier: Modifier, onToggle: (Int) -> Unit,
                                   onNext: () -> Unit, onCancel: () -> Unit) {
    Column(modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text("选择密保问题", style = MaterialTheme.typography.titleLarge)
            Text("选三道你容易记住的问题，下一步再填写答案。", modifier = Modifier.padding(vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium)
            recoveryQuestions.forEach { (id, prompt) ->
                val checked = id in selected
                val enabled = checked || selected.size < 3
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("recovery-question-" + id)
                    .toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle(id) })
                    .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
                    Spacer(Modifier.width(12.dp))
                    Text(prompt, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Button(onClick = onNext, enabled = selected.size == 3, modifier = Modifier.fillMaxWidth()
            .padding(top = 8.dp).testTag("recovery-next")) { Text("下一步，填写答案（" + selected.size + "/3）") }
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("取消") }
    }
}
