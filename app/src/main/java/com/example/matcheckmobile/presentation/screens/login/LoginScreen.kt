package com.example.matcheckmobile.presentation.screens.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.matcheckmobile.MatcheckApplication
import com.example.matcheckmobile.data.repository.LoginError
import com.example.matcheckmobile.presentation.viewmodel.LoginViewModel

@Composable
fun LoginScreen(onLoggedIn: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as MatcheckApplication).container
    val viewModel: LoginViewModel = viewModel(factory = LoginViewModel.factory(container))
    val state by viewModel.state.collectAsStateWithLifecycle()
    var passwordVisible by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Бренд «MatCheck КПП» убран по запросу — на логин-экране
            // достаточно тагалайна «Учёт движения материалов». Иконка/имя
            // приложения (`su10`) подписаны системой над инпутами.
            Text(
                text = "Учёт движения материалов",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 32.dp),
            )

            OutlinedTextField(
                value = state.email,
                onValueChange = viewModel::onEmailChanged,
                label = { Text("Email") },
                singleLine = true,
                enabled = !state.isSubmitting,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = state.password,
                onValueChange = viewModel::onPasswordChanged,
                label = { Text("Пароль") },
                singleLine = true,
                enabled = !state.isSubmitting,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (passwordVisible) "Скрыть пароль" else "Показать пароль",
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            state.error?.let { error ->
                Spacer(Modifier.height(16.dp))
                Text(
                    text = errorMessage(error),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(32.dp))
            Button(
                onClick = { viewModel.submit(onLoggedIn) },
                enabled = !state.isSubmitting && state.email.isNotBlank() && state.password.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
            ) {
                if (state.isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(24.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("Войти", style = MaterialTheme.typography.titleLarge)
                }
            }
        }
    }

    // Смена аккаунта при непустой очереди: вход не выполняется, пока инспектор
    // не решит судьбу неотправленного. Молча стирать нельзя — это его работа.
    state.pendingSwitch?.let { pending ->
        AlertDialog(
            onDismissRequest = viewModel::cancelAccountSwitch,
            title = { Text("На планшете есть неотправленные данные") },
            text = {
                Column {
                    Text(
                        "Прошлый аккаунт не успел отправить на сервер: " +
                            pending.describe() + ".",
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Вход под другим аккаунтом удалит эти данные с планшета. " +
                            "Сначала попробуйте отправить их на сервер.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    state.switchError?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                // Карантин чужого объекта синхронизацией не лечится — кнопку
                // показываем только когда отправлять действительно есть что.
                if (pending.syncCanHelp) {
                    TextButton(
                        onClick = viewModel::syncBeforeSwitch,
                        enabled = !state.isSyncingBeforeSwitch,
                    ) {
                        Text(if (state.isSyncingBeforeSwitch) "Отправляем…" else "Отправить на сервер")
                    }
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = viewModel::cancelAccountSwitch) { Text("Отмена") }
                    TextButton(
                        onClick = { viewModel.confirmWipeAndLogin(onLoggedIn) },
                        enabled = !state.isSyncingBeforeSwitch,
                    ) {
                        Text("Удалить и войти", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
        )
    }
}

private fun errorMessage(error: LoginError): String = when (error) {
    LoginError.InvalidCredentials -> "Неверный email или пароль"
    LoginError.AccountInactive -> "Аккаунт деактивирован"
    LoginError.AccountLocked -> "Аккаунт временно заблокирован после 10 неудачных попыток. Попробуйте через 30 минут."
    LoginError.TooManyAttempts -> "Слишком много попыток. Подождите немного."
    LoginError.WeakPassword -> "Пароль слишком простой"
    LoginError.Network -> "Нет соединения с сервером"
    LoginError.ServerError -> "Ошибка на сервере. Попробуйте позже."
    // Обычно до текста не доходит: ViewModel открывает диалог с выбором.
    // Строка — запасной вариант, если диалог почему-то не показан.
    is LoginError.UnsentDataOnDevice ->
        "На планшете осталась неотправленная работа прошлого аккаунта " +
            "(${error.pending.describe()})."
    is LoginError.WipeIncomplete ->
        "Не удалось полностью очистить данные прошлого аккаунта " +
            "(осталось файлов: ${error.failedFiles}). Вход отменён, попробуйте ещё раз."
    is LoginError.Unknown -> error.message?.takeIf { it.isNotBlank() } ?: "Не удалось войти"
}
