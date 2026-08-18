package dev.pnptracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.pnptracker.AppInfo
import dev.pnptracker.resources.Res
import dev.pnptracker.resources.app_status_running
import dev.pnptracker.resources.app_version_label
import org.jetbrains.compose.resources.stringResource

@Composable
fun AppRoot(appInfo: AppInfo) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = stringResource(Res.string.app_status_running),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(Res.string.app_version_label, appInfo.version),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
