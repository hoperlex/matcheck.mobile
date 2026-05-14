package com.example.matcheckmobile.presentation.screens.receipt

import androidx.compose.runtime.Composable
import com.example.matcheckmobile.presentation.screens.common.OperationFormScreen
import com.example.matcheckmobile.presentation.viewmodel.ReceiptViewModel
import com.example.matcheckmobile.presentation.viewmodel.matcheckViewModel

@Composable
fun ReceiptScreen(onBack: () -> Unit, onSaved: () -> Unit) {
    val vm: ReceiptViewModel = matcheckViewModel()
    OperationFormScreen(
        title = "Приёмка материала",
        viewModel = vm,
        onBack = onBack,
        onSaved = onSaved,
    )
}
