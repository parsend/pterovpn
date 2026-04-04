package dev.c0redev.pteraandroid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.c0redev.pteraandroid.theme.PteraTheme
import dev.c0redev.pteraandroid.ui.AppNavGraph

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PteraTheme {
                Surface(modifier = Modifier, color = MaterialTheme.colorScheme.background) {
                    AppNavGraph()
                }
            }
        }
    }
}

