fn main() {
    // Mark the library NODELETE so it stays mapped once loaded. Every plugin (re)load
    // loads a fresh copy of the library (see LibraryLoader.java); threads the previous
    // copy leaves behind (songbird's scheduler etc.) would crash the JVM if the JVM's
    // dlclose, run when the old plugin classloader is garbage collected, unmapped it.
    // Windows gets the same guarantee at runtime (lib.rs `pin_library`).
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("linux") {
        println!("cargo:rustc-cdylib-link-arg=-Wl,-z,nodelete");
    }
}
