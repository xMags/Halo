# pnpm patches

## expo-libvlc-player@7.1.2

Applied via `pnpm-workspace.yaml` `patchedDependencies`. Two kinds of hunks —
know which is which before bumping the package version, because pnpm will
refuse to apply the patch against a new release and every hunk must be
re-evaluated by hand:

**Upstreamable additions** (small, additive, worth PRing upstream so we can
drop them):

- `subtitleDelayMs` prop (module + view + TS types, both platforms) — live
  SPU delay via `setSpuDelay` / `currentVideoSubTitleDelay`.

**Permanent divergence** (a behavioral fork of the library's Android
renderer — re-porting this is the expensive part of any upgrade):

- `USE_TEXTURE_VIEW` flipped to `false`: Android renders through SurfaceView
  so libvlc gets a separate subtitle surface (ASS subtitles are not cropped by
  Fill mode) and better decode performance.
- SurfaceView layout plumbing: explicit measure/layout passes
  (`layoutPlayerLayout`, `prepareSurfaceViews`) because VLCVideoLayout's
  stubs don't inflate/fill under RN's layout system on their own.
- `setContentFit` rewritten from TextureView matrix transforms to view
  scaleX/scaleY, with SurfaceView aspect-ratio compensation (SurfaceView
  fills its bounds before transforms; contain/cover must counter-scale one
  axis).
- Snapshot path handles both TextureView and SurfaceView sources.

iOS hunks are additive-only; Android is where the fork lives. If upstream ever
exposes SurfaceView rendering behind a config option, adopt it and shrink this
patch to the additive hunks.
