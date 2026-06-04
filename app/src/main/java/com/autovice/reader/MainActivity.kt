package com.autovice.reader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.autovice.reader.data.preferences.ReaderPreferencesRepository
import com.autovice.reader.data.preferences.ReaderTheme
import com.autovice.reader.ui.navigation.AppNavigation
import com.autovice.reader.ui.theme.AutoVoiceTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.map
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var preferencesRepository: ReaderPreferencesRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val theme by preferencesRepository.preferences
                .map { it.theme }
                .collectAsState(initial = ReaderTheme.LIGHT)
            AutoVoiceTheme(darkTheme = theme == ReaderTheme.DARK) {
                AppNavigation()
            }
        }
    }
}
