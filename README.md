# Simple Voice Chat Discord Bridge

> ⚠️ **Warning**
>
> This is not an official addon. **Please don't go to the Simple Voice Chat discord server for support! Instead, please use [GitHub issues](https://github.com/amsam0/voicechat-discord/issues)
> for support.** I'll try to provide support as soon as possible but there is no guarantee for how long it will take.


Simple Voice Chat Discord Bridge is a server-side plugin for Paper/Purpur that creates a method of voice communication between Minecraft players and Discord users via Discord Channels and SVC Groups. The plugin automatically creates temporary Discord voice channels for each Simple Voice Chat group, allowing Discord users to join and participate in voice chat without being in game.

- Automatically creates Discord voice channels for SVC groups
- Real-time Opus audio processing with jitter buffering
- Text chat integration between Discord and Minecraft

# Setup

## Requirements
- [Simple Voice Chat](https://modrinth.com/mod/simple-voice-chat) 2.4.11+
- Paper/Purpur server

## Installation
1. Download from Github Actions
2. Place in `plugins` folder and restart server
3. Configure `plugins/voicechat-discord/config.yml`

## Setting up a bot
<sub>This guide is based off of and uses images from [DiscordSRV's Basic Setup guide](https://docs.discordsrv.com/installation/basic-setup/#setting-up-the-bot).</sub>

First, create an application at [discord.com/developers/applications](https://discord.com/developers/applications) by clicking `New Application`. Choose the name that you want your bot to be called.

![](https://docs.discordsrv.com/images/create_application.png)

On the left, click `Bot` and click `Add Bot` and confirm with `Yes, do it!`.

![](https://docs.discordsrv.com/images/create_bot.png)

Copy the token and disable `Public Bot`.

![](https://docs.discordsrv.com/images/copy_token.png)

Now, open [the configuration file](#finding-the-configuration-file) with a text editor. Replace `DISCORD_BOT_TOKEN_HERE` with the token you copied. It should look something like this:
## Configuration
```yaml
category_id: YOUR_CATEGORY_ID_HERE
bot_tokens:
  - YOUR_BOT_TOKEN_HERE
  - SECOND_BOT_TOKEN_HERE  # Optional: multiple concurrent groups
bot_user_ids:
  - BOT_USER_ID_HERE       # Bots to ignore for join/leave messages
debug_level: 1             # 0-3, higher = more verbose
```

> Each bot token allows one concurrent group with Discord integration.

# Usage

## How It Works
1. Player creates a SVC group (no password)
2. Discord voice channel auto-created in the category in the configuration
3. Discord users can join the channel and talk with Minecraft players
4. When all members leave in game/group disbands, the Discord channel is auto-deleted

## Commands
- `/dvcgroup stop` - Stop Discord bot, delete channel (group owner only)
- `/dvcgroup restart` - Restart bot without deleting channel (group owner only)  
- `/dvcgroup reloadconfig` - Reload the config
- `/dvcgroupmsg <message>` - Send message to Discord channel and group members

## Audio Features
- Jitter buffering somewhat compensates for network issues
- Each Discord user gets individual volume controls in SVC
- Automatic audio mixing for multiple speakers (both Minecraft -> Discord and Discord -> Minecraft)

# Troubleshooting

**"No available Discord bots"** - All bots in use by other groups. Add more tokens or wait.

**Bot can't create channel** - Check bot permissions and category ID in config.

**Audio issues** - Lower debug_level, check server resources, verify network connectivity.

**Discord users can't hear MC players or vice versa** - Try `/dvcgroup restart`, verify group exists, check console errors.

## Debug Levels
- `0` - No debug
- `1` - Basic info (recommended) 
- `2` - Verbose (troubleshooting)
- `3` - Very verbose (development)


# Technical Info

**Architecture**: Java core + Rust audio engine connected via JNI

**Audio Pipeline**: 
- MC microphone → PCM Encoding → Combination into one channel → Opus Encoding Via Songbird/Symphonia → Discord
- Discord Microphone → Forwarding to MC players via individual SVC Static Voice Channels

**Current Compatibility**: MC 1.21.7+, SVC 2.4.11+, Java 21+, Paper/Purpur, Windows/Linux

---

**Download**: [Modrinth](https://modrinth.com/plugin/simple-voice-chat-discord-bridge) | **Source**: [GitHub](https://github.com/amsam0/voicechat-discord) | **Support**: [Issues](https://github.com/amsam0/voicechat-discord/issues)

**Authors**: Carter4242 | **Simple Voice Chat Discord Bridge**: amsam0, Totobird | **Simple Voice Chat**: henkelmax
