package com.autovice.reader.ui.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.autovice.reader.work.ScrapeWorker

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScraperDialog(
    workManager: WorkManager,
    onDismiss: () -> Unit,
) {
    var url by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 48.dp),
        ) {
            Text("Import from Syosetu", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = url,
                onValueChange = { url = it; error = null },
                label = { Text("Syosetu URL") },
                placeholder = { Text("https://ncode.syosetu.com/n0000aa/") },
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = {
                    val validUrl = validateSyosetuUrl(url)
                    if (validUrl == null) {
                        error = "Invalid URL. Must be a ncode.syosetu.com URL"
                        return@Button
                    }
                    val request = OneTimeWorkRequestBuilder<ScrapeWorker>()
                        .setInputData(workDataOf(ScrapeWorker.KEY_NOVEL_URL to validUrl))
                        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                        .build()
                    workManager.enqueue(request)
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Import")
            }
        }
    }
}

private fun validateSyosetuUrl(url: String): String? {
    val trimmed = url.trim()
    val regex = Regex("""https?://ncode\.syosetu\.com/n[0-9a-z]+/?""")
    return if (regex.matches(trimmed)) {
        if (trimmed.endsWith("/")) trimmed else "$trimmed/"
    } else null
}
