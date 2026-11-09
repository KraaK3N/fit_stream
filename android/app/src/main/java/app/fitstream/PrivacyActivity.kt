package app.fitstream

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Shown from Health Connect's permission screen. Text is bundled in the app; nothing is loaded from the network. */
class PrivacyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("What FitStream does with your data", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "FitStream reads steps, distance, heart rate, resting heart rate, sleep, workouts, SpO₂, " +
                                "calories, weight, body fat and BMR from Health Connect. It never writes to Health Connect."
                        )
                        Text(
                            "Heart rate, steps and distance are reduced to daily totals or min/avg/max on the phone. " +
                                "SpO₂ and resting heart rate are reduced to daily averages."
                        )
                        Text(
                            "The data is sent over HTTPS only to the server URL you enter, authenticated with your API key. " +
                                "There is no analytics, crash reporting, advertising or any other third-party service."
                        )
                        Text("Revoke access at any time in Health Connect settings or by uninstalling the app.")
                    }
                }
            }
        }
    }
}
