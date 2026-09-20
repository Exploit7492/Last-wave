import re

with open("app/src/main/java/com/lastwave/app/ui/settings/SettingsScreen.kt", "r") as f:
    content = f.read()

# Pass avatarUrl to LastFmIntegrationCard call site
lastfm_card_call = """                        LastFmIntegrationCard(
                            isConnected = session.isAuthenticated,
                            username = session.username,
                            connecting = authState is com.lastwave.app.data.model.AuthState.Authenticating,
                            awaitingApproval = authState is com.lastwave.app.data.model.AuthState.AwaitingApproval,
                            hasApiKey = session.hasApiKey,"""
                            
lastfm_card_call_replacement = """                        val avatarUrl by viewModel.avatarUrl.collectAsState()
                        LastFmIntegrationCard(
                            isConnected = session.isAuthenticated,
                            username = session.username,
                            avatarUrl = avatarUrl,
                            connecting = authState is com.lastwave.app.data.model.AuthState.Authenticating,
                            awaitingApproval = authState is com.lastwave.app.data.model.AuthState.AwaitingApproval,
                            hasApiKey = session.hasApiKey,"""

content = content.replace(lastfm_card_call, lastfm_card_call_replacement)

# Update LastFmIntegrationCard signature
lastfm_card_sig = """private fun LastFmIntegrationCard(
    isConnected: Boolean,
    username: String,
    connecting: Boolean,"""
    
lastfm_card_sig_replacement = """private fun LastFmIntegrationCard(
    isConnected: Boolean,
    username: String,
    avatarUrl: String?,
    connecting: Boolean,"""
    
content = content.replace(lastfm_card_sig, lastfm_card_sig_replacement)

# Update icon to profile picture
icon_code = """                if (isConnected) {
                    IconBadge(
                        Icons.Filled.CloudSync,
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                } else {"""
                
icon_replacement = """                if (isConnected) {
                    if (avatarUrl != null) {
                        coil.compose.AsyncImage(
                            model = avatarUrl,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                        )
                    } else {
                        IconBadge(
                            Icons.Filled.CloudSync,
                            MaterialTheme.colorScheme.primaryContainer,
                            MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                } else {"""
                
content = content.replace(icon_code, icon_replacement)

# Update YouTube icon
yt_icon_code = """            IconBadge(
                Icons.Filled.PlayCircle,
                MaterialTheme.colorScheme.errorContainer,"""

yt_icon_replacement = """            IconBadge(
                Icons.Filled.SmartDisplay,
                MaterialTheme.colorScheme.errorContainer,"""
                
content = content.replace(yt_icon_code, yt_icon_replacement)

with open("app/src/main/java/com/lastwave/app/ui/settings/SettingsScreen.kt", "w") as f:
    f.write(content)

print("Done")
