import re

with open("app/src/main/java/com/lastwave/app/ui/feed/FeedScreen.kt", "r") as f:
    content = f.read()

# Define the container template
container_start = """                            Surface(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                shape = RoundedCornerShape(24.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f),
                            ) {
                                Column(modifier = Modifier.padding(vertical = 12.dp)) {"""
container_end = """                                }
                            }"""

def wrap_section(content, item_key, has_let_block=False):
    # Find the start of the item block
    key_str = f'item(key = "{item_key}") {{'
    idx = content.find(key_str)
    if idx == -1: return content
    
    start_idx = idx + len(key_str)
    
    # Find the matching closing brace for the item block
    brace_count = 1
    end_idx = start_idx
    while brace_count > 0 and end_idx < len(content):
        if content[end_idx] == '{': brace_count += 1
        elif content[end_idx] == '}': brace_count -= 1
        end_idx += 1
    
    # the content inside the item block
    item_content = content[start_idx:end_idx-1]
    
    # special case for quick_tiles: add spacer
    if item_key == "quick_tiles":
        item_content = item_content.replace(
            'FeedSectionHeader(title = "Quick access")',
            'FeedSectionHeader(title = "Quick access")\n                                    Spacer(modifier = Modifier.height(12.dp))'
        )
    
    wrapped = f"\n{container_start}{item_content}\n{container_end}\n                        "
    
    return content[:start_idx] + wrapped + content[end_idx-1:]

sections_to_wrap = [
    "quick_tiles",
    "quick_picks",
    "because_you_listen_to",
    "jump_back_in",
    "mixed_for_you",
    "top_artists",
    "heavy_rotation",
    "albums_in_rotation",
    "trending_charts",
    "new_releases",
    "friends_activity"
]

for section in sections_to_wrap:
    content = wrap_section(content, section)

with open("app/src/main/java/com/lastwave/app/ui/feed/FeedScreen.kt", "w") as f:
    f.write(content)

print("Done")
