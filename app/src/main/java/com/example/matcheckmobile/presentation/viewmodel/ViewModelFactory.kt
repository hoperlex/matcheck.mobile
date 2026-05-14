package com.example.matcheckmobile.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.Composable
import com.example.matcheckmobile.MatcheckApplication

object MatcheckViewModelFactories {
    val Factory = viewModelFactory {
        initializer {
            val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MatcheckApplication
            ReceiptViewModel(app.container)
        }
        initializer {
            val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MatcheckApplication
            DispatchViewModel(app.container)
        }
        initializer {
            val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MatcheckApplication
            JournalViewModel(app.container)
        }
        initializer {
            val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MatcheckApplication
            SyncQueueViewModel(app.container)
        }
        initializer {
            val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MatcheckApplication
            MainStatusViewModel(app.container)
        }
        initializer {
            val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MatcheckApplication
            SettingsViewModel(app.container)
        }
        initializer {
            val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MatcheckApplication
            OperationDetailsViewModel(app.container, createSavedStateHandle())
        }
    }
}

@Composable
inline fun <reified VM : ViewModel> matcheckViewModel(): VM =
    viewModel(factory = MatcheckViewModelFactories.Factory)
