# Vendored native dependencies (not committed)

Halo is GPL-3.0, so it ships mpv's official full (GPL) builds: the ones
mpv.io's installation page points to, from
https://github.com/shinchiro/mpv-winbuild-cmake/releases. Use the `mpv-dev-*`
archive for each architecture, check its SHA-256 against the release's listed
digest, and extract `libmpv-2.dll`. Runtime-loaded by the shell
(`src-tauri/src/mpv.rs`); dev builds look here, packaged builds expect it beside
the exe.

## `mpv/libmpv-2.dll` (x64)

From `mpv-dev-x86_64-<date>-git-<rev>.7z`: the baseline x86-64 build, not the
`-v3` one, which needs AVX2 and would not start on older CPUs.

Last known-good: `mpv-dev-x86_64-20261111-git-bcde6b93cc.7z`
(archive SHA-256 `273c079b11ea5575822f972756f4c9a71149a5c10130353abeb8586738938452`).

## `mpv-arm64/` (ARM64 builds)

- `libmpv-2.dll` from `mpv-dev-aarch64-<date>-git-<rev>.7z`. Last known-good:
  `mpv-dev-aarch64-20261111-git-bcde6b93cc.7z` (archive SHA-256
  `a42c1cebf7366a957c7c321a20df414b80d4a55bbc65ded032d78b749c8d6445`).
- `vulkan-1.dll`: libmpv loads it at start-up, and Windows on ARM does not
  reliably have it, so the ARM64 installer carries the Vulkan loader built
  from Khronos' Vulkan-Loader (Apache-2.0) at tag `vulkan-sdk-1.4.357.0`
  (SHA-256 `b7f40395eecafe38a6aba7529b3c1ec572abca74b70e9c9391e5c62bfe506180`).
  On x64 the graphics driver provides it.

Build the ARM64 installer with the bundle resources pointed here:

```powershell
corepack pnpm tauri build --target aarch64-pc-windows-msvc --config src-tauri/tauri.arm64.conf.json
```

That needs the Rust target (`rustup target add aarch64-pc-windows-msvc`), the
MSVC ARM64 build tools, and `clang` on `PATH` (ring builds its ARM64 assembly
with it).
