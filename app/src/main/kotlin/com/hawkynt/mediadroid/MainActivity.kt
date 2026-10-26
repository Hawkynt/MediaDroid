package com.hawkynt.mediadroid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.hawkynt.mediadroid.data.StateStore
import com.hawkynt.mediadroid.ui.MediaDroidApp
import com.hawkynt.mediadroid.ui.theme.MediaDroidTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = StateStore(this)
        setContent {
            MediaDroidTheme {
                MediaDroidApp(store)
            }
        }
    }
}
