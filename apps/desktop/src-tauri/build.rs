fn main() {
    // tauri_build emits its own rerun-if-changed set, which switches off
    // cargo's default "rerun when anything in the package changes". The app
    // icon is not in that set, so without this line a changed icon.ico is
    // silently left out of the exe until something else forces a rebuild.
    println!("cargo:rerun-if-changed=icons/icon.ico");
    tauri_build::build()
}
