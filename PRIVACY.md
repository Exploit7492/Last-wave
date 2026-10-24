# Privacy Policy — Last-wave

Last updated: 2026-10-24

## Summary

Last-wave is a music client. It has no analytics or telemetry SDK, no
crash-reporting service, and no advertising. Nothing is uploaded to a server
operated by this project. Your library, playlists, liked songs, history, and
downloads are stored on the device and leave it only when you use a feature that
talks to a service you chose (for example signing in to your own music server).

## What the app stores on your device

- Playlists, liked songs, and listening history.
- Settings and preferences.
- Downloaded audio and cached artwork or metadata.
- Credentials for any music server you connect, and the optional backup/sync
  configuration you set up.

## What is sent, and only when you use those features

- Streaming, artwork, and metadata requests go to the music service you
  configured (for example a Subsonic/Navidrome-style server, Spotify, Deezer, or
  Apple Music if you enable it).
- Optional lyrics lookups go to the configured lyrics provider.
- Optional device-to-device backup or sync moves data over your own local
  network. It does not go through a server operated by this project.

No data is sent to a server operated by this project.

## Permissions

| Permission | Why it is declared | Required? |
| --- | --- | --- |
| `INTERNET` | All network access (streaming, artwork, metadata) | Yes |
| `ACCESS_NETWORK_STATE` | Offline detection and retry policy | Yes |
| `WAKE_LOCK` | Background playback and downloads | Yes |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Playback service | Yes |
| `MODIFY_AUDIO_SETTINGS` | Audio focus and output routing | Yes |
| `POST_NOTIFICATIONS` | Playback notification | No |
| `READ_MEDIA_AUDIO`, `READ_EXTERNAL_STORAGE` | Play audio files already on the device | No |
| `WRITE_EXTERNAL_STORAGE` | Save downloads on older Android versions | No |
| `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE` | Local-network device sync and discovery | No |

## Data deletion

Uninstalling the app removes everything it stored on the device. If you connected
a music server or another account, revoke the app's access from that service's
settings. Delete any backup files you exported.

## Children

The app is not directed at children and does not knowingly collect personal
information from them.

## Changes

Material changes to this policy are recorded in this file's history in the
repository.

## Contact

Open an issue at https://github.com/Exploit7492/Last-wave.
