package cn.yingxia.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import cn.yingxia.player.R
import cn.yingxia.player.vault.RecoveryAction
import cn.yingxia.player.vault.localizedRecoveryPresets
import cn.yingxia.player.vault.maxRecoveryPromptLength
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
            Text(stringResource(if (loadFailed) R.string.recovery_load_failed else R.string.recovery_loading))
            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_back)) }
        }
        return
    }
    val prompts = if (enroll) drafts.map { it.trim() } else stored
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var showAnswers by remember { mutableStateOf(false) }
    val title = stringResource(when (action) {
        RecoveryAction.ENROLL -> R.string.recovery_title_enroll
        RecoveryAction.DISABLE -> R.string.recovery_title_disable
        RecoveryAction.RESET -> R.string.recovery_title_reset
    })
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
        Text(stringResource(when (action) {
            RecoveryAction.ENROLL -> R.string.recovery_desc_enroll
            RecoveryAction.DISABLE -> R.string.recovery_desc_disable
            RecoveryAction.RESET -> R.string.recovery_desc_reset
        }), style = MaterialTheme.typography.bodyMedium)
        if (action != RecoveryAction.DISABLE) {
            prompts.forEachIndexed { index, prompt ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(prompt, style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("recovery-prompt-" + index))
                    OutlinedTextField(answers[index], { answers[index] = it.take(96) },
                        Modifier.fillMaxWidth().testTag("recovery-answer-" + index), label = { Text(stringResource(R.string.recovery_answer_label, index + 1)) },
                        enabled = !busy, singleLine = true, keyboardOptions = keyboard,
                        visualTransformation = if (showAnswers) VisualTransformation.None else PasswordVisualTransformation())
                }
            }
            TextButton(onClick = { showAnswers = !showAnswers }) { Text(stringResource(if (showAnswers) R.string.recovery_hide_answers else R.string.recovery_show_answers)) }
            if (enroll) TextButton(enabled = !busy, onClick = { editing = true }) { Text(stringResource(R.string.recovery_edit_questions)) }
            if (enroll && clean.filter { it.isNotEmpty() }.let { it.size != it.distinct().size })
                Text(stringResource(R.string.recovery_duplicate_answers), color = MaterialTheme.colorScheme.error)
        }
        val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
        OutlinedTextField(password, { password = it.take(128) }, Modifier.fillMaxWidth().testTag("recovery-password"),
            label = { Text(stringResource(if (reset) R.string.recovery_new_password_label else R.string.recovery_current_password_label)) }, enabled = !busy, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = keyboard.copy(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { focusManager.clearFocus() }))
        if (reset) OutlinedTextField(confirm, { confirm = it.take(128) }, Modifier.fillMaxWidth().testTag("recovery-confirm"),
            label = { Text(stringResource(R.string.recovery_confirm_new_password_label)) }, enabled = !busy, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = keyboard.copy(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { focusManager.clearFocus() }))
        Button(onClick = {
            focusManager.clearFocus()
            val credential = password.toCharArray()
            val submittedAnswers = if (action == RecoveryAction.DISABLE) emptyList() else clean.map { it.toCharArray() }
            password = ""
            confirm = ""
            onSubmit(credential, prompts, submittedAnswers)
        }, enabled = !busy && password.length >= 8 && validAnswers && (!reset || confirm == password),
            modifier = Modifier.fillMaxWidth().testTag("recovery-submit")) { Text(stringResource(when (action) {
                RecoveryAction.ENROLL -> R.string.recovery_submit_enroll
                RecoveryAction.DISABLE -> R.string.recovery_submit_disable
                RecoveryAction.RESET -> R.string.recovery_submit_reset
            })) }
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_cancel)) }
    }
}

@Composable
private fun RecoveryQuestionEditor(drafts: List<String>, modifier: Modifier, onEdit: (Int, String) -> Unit,
                                   onNext: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val presets = remember(context) { localizedRecoveryPresets(context) }
    val prompts = drafts.map { it.trim() }
    var menu by remember { mutableIntStateOf(-1) }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.recovery_editor_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.recovery_editor_desc),
            style = MaterialTheme.typography.bodyMedium)
        drafts.forEachIndexed { index, draft ->
            val duplicate = prompts[index].isNotEmpty() && prompts.take(index).contains(prompts[index])
            OutlinedTextField(value = draft, onValueChange = { value ->
                if (value.length <= maxRecoveryPromptLength && value.none(Char::isISOControl)) onEdit(index, value)
            }, modifier = Modifier.fillMaxWidth().testTag("recovery-question-" + index),
                label = { Text(stringResource(R.string.recovery_question_label, index + 1)) }, placeholder = { Text(stringResource(R.string.recovery_question_placeholder)) },
                minLines = 1, maxLines = 3, isError = duplicate,
                supportingText = if (duplicate) { { Text(stringResource(R.string.recovery_question_duplicate)) } } else null,
                keyboardOptions = KeyboardOptions(
                    autoCorrectEnabled = false,
                    imeAction = if (index == drafts.lastIndex) ImeAction.Done else ImeAction.Next,
                ),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }))
            Box {
                TextButton(onClick = { menu = index }, modifier = Modifier.testTag("recovery-presets-" + index)) { Text(stringResource(R.string.recovery_pick_preset)) }
                DropdownMenu(expanded = menu == index, onDismissRequest = { menu = -1 }) {
                    presets.forEach { prompt ->
                        DropdownMenuItem(text = { Text(prompt) }, enabled = prompt !in prompts || prompt == prompts[index],
                            onClick = { onEdit(index, prompt); menu = -1 })
                    }
                }
            }
        }
        Button(onClick = onNext, enabled = validRecoveryPrompts(prompts),
            modifier = Modifier.fillMaxWidth().testTag("recovery-next")) { Text(stringResource(R.string.recovery_next_step)) }
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_cancel)) }
    }
}
