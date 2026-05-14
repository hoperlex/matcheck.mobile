package com.example.matcheckmobile.presentation.screens.receipt

import androidx.compose.runtime.Composable

@Composable
fun ReceiptScreen(onBack: () -> Unit, onSaved: () -> Unit) {
    ReceiptSessionFlow(onBack = onBack, onSaved = onSaved)
}
