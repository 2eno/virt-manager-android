package dev.virtmanager.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import dev.virtmanager.android.VirtManagerApp

@Composable
fun app(): VirtManagerApp = LocalContext.current.applicationContext as VirtManagerApp
