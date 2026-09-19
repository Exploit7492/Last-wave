sed -i 's/Icons.Filled.Favorite/Icons.Filled.ThumbUp/g' app/src/main/java/com/lastwave/app/ui/feed/FeedScreen.kt
# We want to change only the first occurrence for isYtLikedTile
sed -i 's/Icons.Filled.PlayArrow/Icons.Filled.PlayCircle/g' app/src/main/java/com/lastwave/app/ui/feed/FeedScreen.kt
sed -i 's/maxLines = 2,/maxLines = 1,/g' app/src/main/java/com/lastwave/app/ui/feed/FeedScreen.kt
