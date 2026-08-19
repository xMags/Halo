# Webview UI fonts

`Plus Jakarta Sans` (UI) and `JetBrains Mono` (metadata, counts, file names,
kickers, keyboard hints) are the two faces of the desktop design system. Both
are bundled rather than loaded from Google Fonts: the app must render
identically offline, and a self-hosted client should not phone home on launch.

Each file is the **variable-weight** woff2 (400–800) for one unicode subset —
`latin` covers the UI copy, `latin-ext` covers accented titles coming back from
addon metadata. `@font-face` in `src/index.css` declares the matching
`unicode-range`, so the ext file is only fetched when a page actually needs it.

These are *not* the same fonts as `apps/desktop/fonts/`. That directory is fed
to libass as mpv's `sub-fonts-dir` and its family names are the values of the
synced `subtitleFontFamily` setting; adding a file there changes what the
subtitle font picker can resolve. Keep the two sets separate.

Both families are SIL Open Font License 1.1 (see the OFL files here).
