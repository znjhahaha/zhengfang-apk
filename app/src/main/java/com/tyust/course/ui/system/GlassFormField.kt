package com.tyust.course.ui.system

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/** Stable labels and a filled editing surface for plugin and full-page forms. */
@Composable
fun GlassFormField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    singleLine: Boolean = true,
    password: Boolean = false,
    supportingText: String? = null,
    isError: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    trailing: (@Composable () -> Unit)? = null
) {
    var reveal by rememberSaveable { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        GlassTextField(value, onValueChange, Modifier.fillMaxWidth().semantics { contentDescription = label },
            placeholder = placeholder, enabled = enabled, singleLine = singleLine,
            minHeight = if (singleLine) 52.dp else 96.dp, maxLines = if (singleLine) 1 else 6,
            keyboardOptions = keyboardOptions, keyboardActions = keyboardActions, isError = isError,
            visualTransformation = if (password && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
            trailing = {
                when {
                    trailing != null -> trailing()
                    password -> IconButton(onClick = { reveal = !reveal }, enabled = enabled, modifier = Modifier.size(40.dp)) {
                        Icon(if (reveal) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            if (reveal) "隐藏密码" else "显示密码", Modifier.size(20.dp))
                    }
                    value.isNotEmpty() && enabled -> IconButton(onClick = { onValueChange("") }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.Close, "清空" + label, Modifier.size(18.dp))
                    }
                }
            })
        supportingText?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
