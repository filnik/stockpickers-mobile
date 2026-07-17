package app.stockpickers.kmp.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.stockpickers.kmp.ui.StockpickersRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Theme + navigation live in commonMain — see StockpickersRoot.
        setContent { StockpickersRoot() }
    }
}
