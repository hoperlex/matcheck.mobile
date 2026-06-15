package com.example.matcheckmobile.presentation.viewmodel

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.matcheckmobile.MatcheckApplication

object MatcheckViewModelFactories {
    val Factory = viewModelFactory {
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
        initializer {
            val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MatcheckApplication
            DocumentsViewModel(app.container)
        }
        initializer {
            val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MatcheckApplication
            DocumentDetailsViewModel(app.container, createSavedStateHandle())
        }
        initializer {
            val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MatcheckApplication
            ReceiptSessionViewModel(app.container, createSavedStateHandle())
        }
        initializer {
            val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MatcheckApplication
            Stage1FormViewModel(app.container, createSavedStateHandle())
        }
        initializer {
            val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MatcheckApplication
            Stage2ListViewModel(app.container)
        }
        initializer {
            val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MatcheckApplication
            Stage2FormViewModel(app.container, createSavedStateHandle())
        }
        initializer {
            val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MatcheckApplication
            DispatchSessionViewModel(app.container)
        }
        initializer {
            val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MatcheckApplication
            IntakeUpdSelectViewModel(app.container)
        }
        initializer {
            val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MatcheckApplication
            IntakeStagesViewModel(app.container)
        }
        initializer {
            val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MatcheckApplication
            SavedReceiptsViewModel(app.container)
        }
        initializer {
            val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MatcheckApplication
            DispatchUpdSelectViewModel(app.container)
        }
        initializer {
            val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MatcheckApplication
            DispatchStagesViewModel(app.container)
        }
        initializer {
            val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MatcheckApplication
            DispatchStage1FormViewModel(app.container, createSavedStateHandle())
        }
        initializer {
            val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MatcheckApplication
            DispatchStage2ListViewModel(app.container)
        }
        initializer {
            val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MatcheckApplication
            DispatchStage2FormViewModel(app.container, createSavedStateHandle())
        }
        initializer {
            val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MatcheckApplication
            ManualEntryFormViewModel(app.container)
        }
        initializer {
            val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MatcheckApplication
            ManualDispatchFormViewModel(app.container)
        }
        initializer {
            val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MatcheckApplication
            ArchiveIntakeListViewModel(app.container)
        }
        initializer {
            val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MatcheckApplication
            ArchiveIntakeDetailViewModel(app.container, createSavedStateHandle())
        }
        initializer {
            val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MatcheckApplication
            ArchiveDispatchListViewModel(app.container)
        }
        initializer {
            val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MatcheckApplication
            ArchiveDispatchDetailViewModel(app.container, createSavedStateHandle())
        }
    }
}

@Composable
inline fun <reified VM : ViewModel> matcheckViewModel(): VM =
    viewModel(factory = MatcheckViewModelFactories.Factory)
