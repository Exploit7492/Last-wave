import re

with open("app/src/main/java/com/lastwave/app/ui/settings/SettingsViewModel.kt", "r") as f:
    content = f.read()

# Add HomeRepository to constructor
content = content.replace(
    "    private val authCallback: com.lastwave.app.data.repository.LastFmAuthCallbackCoordinator,",
    "    private val authCallback: com.lastwave.app.data.repository.LastFmAuthCallbackCoordinator,\n    private val homeRepository: com.lastwave.app.data.repository.HomeRepository,"
)

# Add avatarUrl state
avatar_url_code = """    val session: StateFlow<SessionData> = kotlinx.coroutines.flow.combine("""
avatar_url_replacement = """    private val _avatarUrl = MutableStateFlow<String?>(null)
    val avatarUrl: StateFlow<String?> = _avatarUrl.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            session.collect { sess ->
                if (sess.username.isNotBlank()) {
                    homeRepository.fetchStats(sess.username).onSuccess { stats ->
                        _avatarUrl.value = stats.avatarUrl
                    }
                } else {
                    _avatarUrl.value = null
                }
            }
        }
    }

    val session: StateFlow<SessionData> = kotlinx.coroutines.flow.combine("""

content = content.replace(avatar_url_code, avatar_url_replacement)

with open("app/src/main/java/com/lastwave/app/ui/settings/SettingsViewModel.kt", "w") as f:
    f.write(content)

print("Done")
