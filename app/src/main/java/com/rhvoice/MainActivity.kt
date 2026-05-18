package com.rhvoice

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.rhvoice.ui.AppNav
import com.rhvoice.ui.theme.RhVoiceTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RhVoiceTheme {
                AppNav()
            }
        }
    }
}
