import re

with open("app/src/main/java/com/lastwave/app/ui/settings/SettingsScreen.kt", "r") as f:
    content = f.read()

# Add import
import_playcircle = "import androidx.compose.material.icons.filled.PlayCircle"
import_smartdisplay = "import androidx.compose.material.icons.filled.PlayCircle\nimport androidx.compose.material.icons.filled.SmartDisplay"
content = content.replace(import_playcircle, import_smartdisplay)

# Add avatarUrl state collection
session_state_code = "val session by viewModel.session.collectAsStateWithLifecycle()"
session_state_code_with_avatar = "val session by viewModel.session.collectAsStateWithLifecycle()\n    val avatarUrl by viewModel.avatarUrl.collectAsStateWithLifecycle()"
content = content.replace(session_state_code, session_state_code_with_avatar)

# Add avatarUrl to LastFmIntegrationCard call site
lastfm_card_call = """                    LastFmIntegrationCard(
                        isConnected = isLastFmConnected,
                        username = session.username,
                        connecting = lastFmConnecting,
                        awaitingApproval = lastFmAuthUrl != null,
                        hasApiKey = hasApiKey,"""
                        
lastfm_card_call_replacement = """                    LastFmIntegrationCard(
                        isConnected = isLastFmConnected,
                        username = session.username,
                        avatarUrl = avatarUrl,
                        connecting = lastFmConnecting,
                        awaitingApproval = lastFmAuthUrl != null,
                        hasApiKey = hasApiKey,"""

content = content.replace(lastfm_card_call, lastfm_card_call_replacement)

with open("app/src/main/java/com/lastwave/app/ui/settings/SettingsScreen.kt", "w") as f:
    f.write(content)

print("Done")
