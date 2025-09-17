

pluginVersion="3.0.11"
fabricLoaderVersion="0.16.13"
export RUST_BACKTRACE="full"

minecraftVersion="$2"
platform="$1"

yellow="\033[0;33m"
green="\033[0;32m"
red="\033[0;31m"
clear="\033[0m"
NO_EXIT=""
if [ "$3" == "--no-exit" ]; then
  NO_EXIT=1
fi

if [ "$minecraftVersion" == "" ]; then
  echo -e "${red}Please specify a minecraft version${clear}"
  exit 1
fi

if [ "$platform" == "paper" ]; then
  echo -e "${yellow}Setting up Paper server on version $minecraftVersion${clear}"

  # Make server directories
  mkdir -p "paper/run/$minecraftVersion/plugins/voicechat-discord"

  # Get server jar (based on https://docs.papermc.io/misc/downloads-api/#downloading-the-latest-stable-build)
  file="paper/run/$minecraftVersion/server.jar"
  if [ ! -f $file ]; then
    url=$(curl -s -H "User-Agent: voicechat-discord/0.0.0 (https://github.com/amsam0/voicechat-discord)" "https://fill.papermc.io/v3/projects/paper/versions/$minecraftVersion/builds" | jq -r '.[0].downloads."server:default".url')

    echo -n -e "${yellow}Downloading server jar from ${clear}$url${yellow}..."
    curl -s -o $file $url
    echo -e "downloaded${clear}"
  else
    echo -e "${green}Server jar already downloaded${clear}"
  fi

  # Download voicechat
  file="paper/run/$minecraftVersion/plugins/voicechat-bukkit.jar"
  if [ ! -f $file ]; then
    url="https://cdn.modrinth.com/data/9eGKb6K1/versions/tfqjss5m/voicechat-bukkit-2.5.36.jar"

    echo -e -n "${yellow}Downloading voicechat from ${clear}$url${yellow}..."
    curl -s -o $file $url
    echo -e "downloaded${clear}"
  else
    echo -e "${green}voicechat already downloaded${clear}"
  fi

  # Copy config
  from="config.yml"
  to="paper/run/$minecraftVersion/plugins/voicechat-discord/config.yml"
  cp $from $to
  echo -e "${green}Copied config from $from to $to${clear}"

  # Copy mod
  from="paper/build/libs/voicechat-discord-paper-$pluginVersion.jar"
  to="paper/run/$minecraftVersion/plugins/voicechat-discord-paper.jar"
  cp $from $to
  echo -e "${green}Copied plugin from $from to $to${clear}"
else
  echo -e "${red}Unknown platform $platform${clear}"
  exit 1
fi

echo -e "${green}Running version $minecraftVersion on platform $platform${clear}"
cd "$platform/run/$minecraftVersion"
java -Xms4G -Xmx4G -jar server.jar --nogui
exit_code=$?

if [ $exit_code -ne 0 ]; then
  echo -e "${red}Server crashed or exited with error code $exit_code${clear}"
  echo -e "${yellow}Check for crash logs (e.g., hs_err_pid*.log) in this directory.${clear}"
fi

echo -e "${yellow}Server stopped. Press enter to exit...${clear}"
read
