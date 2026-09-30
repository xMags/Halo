# Webview UI fonts

`JetBrains Mono` (metadata, counts, file names, kickers, keyboard hints) is the
one bundled face of the desktop design system. Body and display text use the
system's Segoe UI Variable, so nothing is shipped for them. It is bundled
rather than loaded from Google Fonts: the app must render identically offline,
and a self-hosted client should not phone home on launch.

Each file is the **variable-weight** woff2 (400–800) for one unicode subset —
`latin` covers the UI copy, `latin-ext` covers accented titles coming back from
addon metadata. `@font-face` in `src/index.css` declares the matching
`unicode-range`, so the ext file is only fetched when a page actually needs it.

These are *not* the same fonts as `apps/desktop/fonts/`. That directory is fed
to libass as mpv's `sub-fonts-dir` and its family names are the values of the
synced `subtitleFontFamily` setting; adding a file there changes what the
subtitle font picker can resolve. Keep the two sets separate.

JetBrains Mono is SIL Open Font License 1.1 (see the OFL file here).
