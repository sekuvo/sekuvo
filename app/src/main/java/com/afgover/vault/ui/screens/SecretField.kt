package com.afgover.vault.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import com.afgover.vault.R

/**
 * Parola/PIN alanı — göz simgesiyle **göster/gizle**. Yıldızlı alanda tek
 * harflik hata bütün girişi baştan yazdırıyordu; gösterme seçeneği bunu
 * bitiriyor. Varsayılan yine gizli: omuz üstünden okumaya karşı.
 */
@Composable
fun SecretField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    numeric: Boolean = false
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation =
            if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        // Klavye türü göz açıkken de parola kalır: `KeyboardType.Text` sistem
        // klavyesine (Gboard, Samsung) alanı sıradan metin diye bildirir ve
        // klavye yazılanı öğrenme sözlüğüne, oradan buluta taşıyabilir.
        // Maske ekranı korur, klavye türü klavyeyi.
        keyboardOptions = KeyboardOptions(
            keyboardType = if (numeric) KeyboardType.NumberPassword else KeyboardType.Password,
            autoCorrectEnabled = false
        ),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = stringResource(
                        if (visible) R.string.main_hide else R.string.main_show
                    )
                )
            }
        },
        modifier = modifier
    )
}
