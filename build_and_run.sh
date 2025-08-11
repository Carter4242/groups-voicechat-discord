# Build Rust native library
cd core/src/main/rust
cargo build || { echo "cargo build failed. Press enter to continue..."; read; }
cd -

# Copy DLL to resources
cp core/target/debug/voicechat_discord.dll core/src/main/resources/natives/windows-x64/

# Parse optional --no-exit flag
NO_EXIT=""
for arg in "$@"; do
	if [ "$arg" = "--no-exit" ]; then
		NO_EXIT="--no-exit"
	fi
done

# Build Java project and run server
./gradlew build && ./run_server.sh paper 1.21.7 $NO_EXIT
