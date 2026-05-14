package com.example.matcheckmobile.presentation.screens.dispatch

import androidx.compose.runtime.Composable
import com.example.matcheckmobile.presentation.screens.common.OperationFormScreen
import com.example.matcheckmobile.presentation.viewmodel.DispatchViewModel
import com.example.matcheckmobile.presentation.viewmodel.matcheckViewModel

@Composable
fun DispatchScreen(onBack: () -> Unit, onSaved: () -> Unit) {
    val vm: DispatchViewModel = matcheckViewModel()
    OperationFormScreen(
        title = "Выезд материала",
        viewModel = vm,
        onBack = onBack,
        onSaved = onSaved,
    )
}
