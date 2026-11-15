package com.imagefolder.open

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.imagefolder.open.ui.ImageFolderApp
import com.imagefolder.open.ui.theme.ImageFolderTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ImageFolderTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ImageFolderApp()
                }
            }
        }
    }
}
