# Vendored native dependencies (not committed)

## `mpv/libmpv-2.dll`

Runtime-loaded by the shell (`src-tauri/src/mpv.rs`); dev builds look here,
packaged builds expect it beside the exe.

Pinned source: `mpv-dev-lgpl-x86_64` from
https://github.com/zhongfly/mpv-winbuild/releases (LGPL variant on purpose —
libmpv must stay a replaceable dynamic library). Download the `.7z`, extract
`libmpv-2.dll` into `vendor/mpv/`.

Last known-good: `mpv-dev-lgpl-x86_64-20260717-git-94335ab87a.7z`.
