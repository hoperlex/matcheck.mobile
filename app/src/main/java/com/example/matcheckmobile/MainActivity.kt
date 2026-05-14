package com.example.matcheckmobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.matcheckmobile.presentation.navigation.MatcheckNavHost
import com.example.matcheckmobile.ui.theme.MatcheckmobileTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MatcheckmobileTheme {
                MatcheckNavHost()
            }
        }
    }
}
