package com.streamer.timetable.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.streamer.timetable.R

/**
 * Sign-in for the school's Windows account.
 *
 * The domain field is optional and collapsed by default. The site accepts a bare
 * username, but NTLM implementations vary in how they treat an empty domain, so the
 * field is there as an escape hatch rather than a routine step.
 *
 * Credentials are verified against the server once before being saved, so a typo is
 * caught here rather than silently failing on every future background sync -- which
 * matters, because repeated rejected logons can lock an Active Directory account.
 */
@Composable
fun LoginScreen(
    syncing: Boolean,
    onSignIn: (username: String, password: String, domain: String, onResult: (Boolean, String) -> Unit) -> Unit,
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var domain by remember { mutableStateOf("") }
    var showDomain by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_app_icon),
            contentDescription = null,
            modifier = Modifier
                .size(96.dp)
                // A superellipse rather than rounded corners, matching the shape the
                // launcher masks the app icon into.
                .clip(SquircleShape()),
        )

        Text("Streamer", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Sign in with your StREAM Account to download your timetable for offline use",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )

        OutlinedTextField(
            value = username,
            onValueChange = { username = it; error = null },
            label = { Text("Username") },
            singleLine = true,
            enabled = !syncing,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = password,
            onValueChange = { password = it; error = null },
            label = { Text("Password") },
            singleLine = true,
            enabled = !syncing,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        if (showDomain) {
            OutlinedTextField(
                value = domain,
                onValueChange = { domain = it },
                label = { Text("Domain (optional)") },
                singleLine = true,
                enabled = !syncing,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            TextButton(onClick = { showDomain = true }, enabled = !syncing) {
                Text("Sign-in not working? Add a domain")
            }
        }

        error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }

        if (syncing) {
            CircularProgressIndicator()
        } else {
            Button(
                onClick = {
                    error = null
                    onSignIn(username, password, domain) { ok, message ->
                        if (!ok) error = message
                    }
                },
                enabled = username.isNotBlank() && password.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Sign in")
            }
        }

        Text(
            "Passwords are stored encrypted on this device.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
    }
}
