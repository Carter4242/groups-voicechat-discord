# Simple Voice Chat Discord Bridge Changelog

This will mirror https://modrinth.com/plugin/simple-voice-chat-discord-bridge/changelog

## 3.0.11

- The supported Minecraft versions have been drastically narrowed down.
  - We now support:
    - 1.19.2
    - 1.20.1
    - 1.21.1
    - 1.21.4
    - 1.21.5 (newly supported)
    - 1.21.8 (newly supported)
  - All other Minecraft versions are not considered supported. This list is based on [Simple Voice Chat's supported versions](https://modrepo.de/minecraft/voicechat/wiki/supported_versions). Future releases will follow any changes made to that list of supported versions.
  - Thanks to the smaller range of supported Minecraft versions, manually testing all supported versions is now feasible, and all versions have been tested on both Fabric and Paper.
- The update checker has been simplified and improved so that it will be resilient if major changes such as releasing a separate jar for every supported Minecraft version (as opposed to the current method of releasing a single jar for all supported versions) are made in the future.
- Log messages on Fabric are now prefixed with `[voicechat-discord]`.
- Attempting to shut down natives that have not been loaded will no longer cause the plugin to fail to shut down.
- The Fabric Permissions API has been upgraded to version 0.3.3 in order to fix compatibility issues with recent Minecraft versions.
- Due to recent GitHub deprecations, Linux natives will now be built on Ubuntu 22.04. This means that there may be errors on hosting providers using old glibc versions. If you encounter this issue, please see [this StackOverflow post](https://stackoverflow.com/questions/72513993/how-to-install-glibc-2-29-or-higher-in-ubuntu-18-04).

Code changes: https://github.com/amsam0/voicechat-discord/compare/3.0.10...3.0.11

## 3.0.10

- Fix a crash that could occur when initializing the Tokio runtime with only one core
- Prevent initializing and then immediately shutting down the Tokio runtime on shutdown

Code changes: https://github.com/amsam0/voicechat-discord/compare/3.0.9...3.0.10

## 3.0.9

- (Paper) Hopefully fix all broken versions (so 1.19-1.21.3), for real this time

Code changes: https://github.com/amsam0/voicechat-discord/compare/3.0.8...3.0.9

## 3.0.8

- (Paper) Fix 1.21.4

Code changes: https://github.com/amsam0/voicechat-discord/compare/3.0.7...3.0.8

## 3.0.7

- (Paper) Attempt to fix <1.21.3

Code changes: https://github.com/amsam0/voicechat-discord/compare/3.0.6...3.0.7

## 3.0.6

- Update to support 1.12.3+; thanks to AlexDerProGamer for their initial PR

Code changes: https://github.com/amsam0/voicechat-discord/compare/3.0.5...3.0.6

## 3.0.5

- Add thread names to make future debugging easier
- Try to reduce tokio runtime overhead while idle

Code changes: https://github.com/amsam0/voicechat-discord/compare/3.0.4...3.0.5

## 3.0.4

- Adjust error message for when an audio sender can't be registered to hopefully make it less confusing

Code changes: https://github.com/amsam0/voicechat-discord/compare/3.0.3...3.0.4

## 3.0.3

- Fix crash when running /dvc start by forcing ring to be used for cryptography instead of
  aws-lc ([#59](https://github.com/amsam0/voicechat-discord/issues/59))

Code changes: https://github.com/amsam0/voicechat-discord/compare/3.0.2...3.0.3

## 3.0.2

- (Paper) Fix the bot not disconnecting from Discord when the player leaves the
  game ([#57](https://github.com/amsam0/voicechat-discord/issues/57))
    - This also fixes issues with the update checker not alerting operators of an update on Paper
- Fix the bot not disconnecting from Discord when the server stops
- (Fabric) Fix the addon's shutdown process blocking the server from shutting down
- Add failsafe if an error occurs during the bot stop process

Code changes: https://github.com/amsam0/voicechat-discord/compare/3.0.1...3.0.2

## 3.0.1

- Hopefully add compatibility for older glibc
  versions ([#54](https://github.com/amsam0/voicechat-discord/issues/54))
- (Paper) Fix getEntityPosition failing ([#56](https://github.com/amsam0/voicechat-discord/issues/56))

Code changes: https://github.com/amsam0/voicechat-discord/compare/3.0.0...3.0.1

## 3.0.0

- Major internal changes, which should result in better stability and performance
    - The JDA Java discord library is no longer used and it has been replaced by the Serenity and Songbird Rust discord
      libraries
    - This means that the plugin requires some native libraries, which unfortunately increased the JAR size
    - The advantage is that SSL is bundled with the libraries instead of requiring Java's SSL,
      fixing [#11](https://github.com/amsam0/voicechat-discord/issues/11)
    - The new implementation should be faster and less prone to getting into a buggy state
- Many fixes to fix support for 1.20.3 and later
    - On the Fabric side, usage of JSON to convert between adventure and native component classes was removed. Now,
      components are manually converted which should be much more robust and slightly more performant
    - On the Paper side, in 1.20.6 and later, the new Commands API is used, and in <1.20.6, reflection is used in more
      places due to Paperweight's new mapping behavior breaking stuff
- Require Java 21 (the addon still supports 1.19.4)

Code changes: https://github.com/amsam0/voicechat-discord/compare/2.1.1...3.0.0

## 2.1.1

- (Fabric) Fixed [#25](https://github.com/amsam0/voicechat-discord/issues/25) - **/dvc now works correctly on
  1.20+!** Sorry this took so long to fix; I actually fixed it almost a month ago but never made a release.
- Fixed a minor punctuation issue with the message about Simple Voice Chat not being new enough
- Increased minimum Minecraft version to 1.19.4 from 1.19.2

Code changes: https://github.com/amsam0/voicechat-discord/compare/2.1.0...2.1.1

## 2.1.0

This update has some new features and bugfixes. The minimum Simple Voice Chat version has been increased to 2.4.11.

- Reduce volume of whispering players in the audio that goes to discord
- Don't allow players to use `/dvc group` when groups are disabled
- Fixed tab complete on Paper
- Switch to using adventure and minimessage for messages. This means that we no longer use the legacy formatting codes,
  and some messages will have colors in the console!

Code changes: https://github.com/amsam0/voicechat-discord/compare/2.0.1...2.1.0

## 2.0.1

This update fixes one of the major issues with 2.0.0. If you are on Fabric, you probably didn't experience it, but you
should still update because of the other fixes and improvements.

- Fixed [#22](https://github.com/amsam0/voicechat-discord/issues/22)
- Switch to a simpler volume adjustment method. While this seems to work fine, please report any issues with the audio
  going to Discord!
- Slight improvement: packets with a volume less than or equal to 0 (which ends up being silent) won't be sent to
  Discord. This can happen when we receive packets out of the distance of the player
- Improve reset watcher to be slower, this may fix some audio related issues
- Make NMS usage and reflection on Paper safer and hopefully future proof it more

Code changes: https://github.com/amsam0/voicechat-discord/compare/2.0.0...2.0.1

## 2.0.0

Huge thanks to [Totobird](https://github.com/Totobird-Creations) for being a huge help with this update! Their PR was
the main reason I started working on it again.

- **All commands have been moved to subcommands on the `/dvc` command**
    - See https://github.com/amsam0/voicechat-discord#using-it-in-game for docs
    - `/startdiscordvoicechat` was moved to `/dvc start`
        - Running `/dvc start` while in a voice chat session restarts the session
    - New subcommand: `/dvc stop`
        - Only usable while currently in a discord voice chat session
        - Disconnects the bot and stops the session
    - New subcommand: `/dvc group`
        - See https://github.com/amsam0/voicechat-discord#dvc-group for docs
    - New subcommand: `/dvc togglewhisper`
        - Allows mod users to whisper
    - New subcommand: `/dvc reloadconfig`
        - Only usable by operators or players with the `voicechat-discord.reload-config` permission
        - Stops all sessions and reloads the config
    - New subcommand: `/dvc checkforupdate`
        - Only usable by operators
        - Checks for a new update using the GitHub API. If one is found, finds the version on Modrinth and links to the
          version page.
- **Group support** (`/dvc group`)
    - See https://github.com/amsam0/voicechat-discord#dvc-group for docs
- **Whispering support** (`/dvc togglewhisper`)
- Added support for people using the mod to hear static/entity/locational audio channels
- Use the new audio sender API to improve compatibility with other addons
- [Fabric only] Use the Fabric Permissions API to support mods like LuckPerms for the reload config permission
- Added version checker to ensure the plugin/mod is updated
- Added Simple Voice Chat version checker to ensure we have a new enough version of the mod
- Hopefully fixed [#5](https://github.com/amsam0/voicechat-discord/issues/5)
- Better login failure error handling and logging
- Improvements to messages sent to players to be more clear
- Optional debug logging to hopefully help with debugging issues
- Major refactors and command handling improvements

Code changes: https://github.com/amsam0/voicechat-discord/compare/1.4.0...2.0.0

## 1.4.0

This release should be functionally identical to 1.3.0 on fabric, but it fixed this paper specific
bug: [(#4)](https://github.com/amsam0/voicechat-discord/issues/4) On paper, the plugin
configuration folder is not created

Code changes: https://github.com/amsam0/voicechat-discord/compare/1.3.0...1.4.0

## 1.3.0

- Fixed [#2](https://github.com/amsam0/voicechat-discord/issues/2)
- Dropped Bukkit and Spigot support

Code changes: https://github.com/amsam0/voicechat-discord/compare/1.2.0...1.3.0

## 1.2.0

> ⚠️ **Warning**
>
> This is the last release that supports spigot and bukkit. Later releases require Paper. If you are on Paper or Purpur,
> don't use this release, use the latest release. Only use this release if you
> are using Spigot/CraftBukkit and cannot use Paper or Purpur.

- Fixed some issues with multiple bots ([#1](https://github.com/amsam0/voicechat-discord/issues/1))
- Fixed 2 users being able to start a voice chat with the same bot

Code changes: https://github.com/amsam0/voicechat-discord/compare/1.1.0...1.2.0

## 1.1.0

- Internal changes to support Bukkit and Fabric with the same codebase

Code changes: https://github.com/amsam0/voicechat-discord/compare/1.0.0-build4...1.1.0

## 1.0.0

- Initial release

Code: https://github.com/amsam0/voicechat-discord/tree/1.0.0-build4
