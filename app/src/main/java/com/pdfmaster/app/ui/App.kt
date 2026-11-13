package com.pdfmaster.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable

@Composable
fun App(vm: MainVm) {
    BackHandler(enabled = vm.stack.size > 1 || vm.running) { vm.back() }
    AnimatedContent(targetState = vm.screen, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "nav") { s ->
        when (s) {
            Screen.Home -> HomeScreen(vm)
            is Screen.Form -> ToolScreen(vm, s.tool)
            is Screen.Editor -> EditorScreen(vm, s.tool, s.file)
            is Screen.Organize -> OrganizeScreen(vm, s.file)
            is Screen.Done -> ResultScreen(vm, s.tool, s.result)
        }
    }
}
