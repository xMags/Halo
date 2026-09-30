# pnpm patches

**After editing a patch, re-run `pod install` in `apps/mobile/ios`.** Each
patch edit changes the pnpm patch hash, which moves the package to a new
`.pnpm/…patch_hash=…` directory; CocoaPods bakes the *resolved* path into the
Pods project, so without a fresh `pod install` Xcode silently keeps compiling
the previous patched copy.

## expo-libvlc-player@7.1.2

Applied via `pnpm-workspace.yaml` `patchedDependencies`. Two kinds of hunks —
know which is which before bumping the package version, because pnpm will
refuse to apply the patch against a new release and every hunk must be
re-evaluated by hand:

**Upstreamable additions/fixes** (small, worth PRing upstream so we can
drop them):

- `subtitleDelayMs` prop (module + view + TS types, both platforms) — live
  SPU delay via `setSpuDelay` / `currentVideoSubTitleDelay`.
- iOS buffering scale normalization: VLCKit reports [0.0, 1.0] where
  Android's libvlc reports 0–100; the view multiplies by 100 so JS sees one
  scale. Without it, iOS consumers of `onBuffering` stay "buffering" forever.
- iOS zombie-player fix: `PictureInPictureDrawable.expoView` made weak (the
  strong back-reference was a retain cycle — unmounted views never deinit'd,
  so their players kept playing forever), plus `willMove(toWindow:)` teardown
  so unmount stops playback immediately instead of waiting for deinit.
- iOS fit modes via `AVSampleBufferDisplayLayer.videoGravity` (the iOS
  counterpart of the Android subtitle-surface work below). VLC's apple vout
  inserts — only once frames start rendering, seconds after first play — a
  container sublayer holding the video (an AVSampleBufferDisplayLayer that
  VLC frames to the aspect-fit rect) and a separate full-size sibling layer
  for subtitles. The patch sizes the video layer to the drawable and maps
  contain/cover/fill onto resizeAspect/resizeAspectFill/resize, polling until
  the layer exists. Subtitles never move because they live on the sibling
  layer. Hard-won constraints, do not regress them: the vout adds NO
  subviews (layers only, and late); `setCropRatioWithNumerator` is a silent
  no-op (crop is unimplemented in this vout); and VLC-managed views/layers
  must never carry transforms — VLC rewrites frames on placement changes,
  and frame-writes under an active transform displace the video into a
  corner until the next placement pass (e.g. a rotation).

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
