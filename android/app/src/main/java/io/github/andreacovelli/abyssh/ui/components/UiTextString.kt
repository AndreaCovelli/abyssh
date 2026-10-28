package io.github.andreacovelli.abyssh.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import io.github.andreacovelli.abyssh.model.UiText

/** Resolves this text against the current resources. */
@Composable
fun UiText.asString(): String = resolve(LocalResources.current)
