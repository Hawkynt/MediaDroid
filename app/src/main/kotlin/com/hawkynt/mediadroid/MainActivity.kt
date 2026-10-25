package com.hawkynt.mediadroid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import com.hawkynt.mediadroid.data.StateStore
import com.hawkynt.mediadroid.ui.MediaDroidApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = StateStore(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                MediaDroidApp(store)
            }
        }
    }
}
