package dev.amsam0.voicechatdiscord;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Extracts the bundled native library and loads it.
 * <p>
 * Reload-safe: the JVM binds a native library file to ONE classloader for as long
 * as that classloader lives, and a plugin reload creates a new classloader while
 * the old one is still alive. So every load extracts its own uniquely named copy.
 * The old copy stays mapped for the rest of the process (the library is built
 * NODELETE on Linux and pins itself on Windows), which keeps any threads it left
 * behind safe.
 * <p>
 * Originally based on https://github.com/henkelmax/opus4j (used with permission).
 */
public final class LibraryLoader {
    private static final String OS_NAME = System.getProperty("os.name").toLowerCase();
    private static final String OS_ARCH = System.getProperty("os.arch").toLowerCase();

    private LibraryLoader() {
    }

    public static void load(String libraryName) throws Exception {
        boolean windows = OS_NAME.contains("win");
        if (!windows && !OS_NAME.contains("nux")) {
            throw new Exception("Unsupported operating system: " + OS_NAME);
        }
        String platform = (windows ? "windows" : "linux") + "-" + architecture();
        String fileName = windows ? libraryName + ".dll" : "lib" + libraryName + ".so";
        String resourcePath = "natives/" + platform + "/" + fileName;

        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "voicechat-discord-natives");
        Files.createDirectories(dir);
        deleteStaleCopies(dir);

        Path copy = Files.createTempFile(dir, libraryName + "-", windows ? ".dll" : ".so");
        try (InputStream in = LibraryLoader.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                Files.deleteIfExists(copy);
                throw new Exception("Could not find " + libraryName + " natives for platform " + platform);
            }
            Files.copy(in, copy, StandardCopyOption.REPLACE_EXISTING);
        }

        try {
            System.load(copy.toAbsolutePath().toString());
        } catch (UnsatisfiedLinkError e) {
            Files.deleteIfExists(copy);
            throw new Exception("Could not load " + libraryName + " natives for " + platform, e);
        }
        // Linux keeps the mapping after the file is unlinked. On Windows the delete
        // fails while the DLL is loaded; deleteStaleCopies sweeps it up later.
        copy.toFile().delete();
    }

    private static String architecture() {
        return switch (OS_ARCH) {
            case "i386", "i486", "i586", "i686", "x86", "x86_32" -> "x86";
            case "amd64", "x86_64", "x86-64" -> "x64";
            case "aarch64", "arm64" -> "aarch64";
            default -> OS_ARCH;
        };
    }

    /**
     * Best effort: copies still loaded by a running JVM can't be deleted on Windows.
     * Skips fresh files so a second JVM sharing the temp dir is never raced mid-load.
     */
    private static void deleteStaleCopies(Path dir) {
        File[] files = dir.toFile().listFiles();
        if (files == null) return;
        long cutoff = System.currentTimeMillis() - 60_000;
        for (File file : files) {
            if (file.lastModified() < cutoff) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
    }
}
