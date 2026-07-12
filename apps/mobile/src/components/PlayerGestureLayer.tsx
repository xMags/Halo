import { useEffect, useRef, useState } from 'react'
import { PanResponder, StyleSheet, Text, useWindowDimensions, View } from 'react-native'
import { Ionicons } from '@expo/vector-icons'
import * as Brightness from 'expo-brightness'
import { VolumeManager } from 'react-native-volume-manager'
import { clamp01 } from '../format'
import { colors, radius, spacing } from '../theme'
import type { VideoFitMode } from './PlayerVideo.types'

const DOUBLE_TAP_MS = 280
const TAP_MOVE_LIMIT = 14
const SWIPE_THRESHOLD = 20
const PINCH_THRESHOLD = 0.12

interface Props {
  disabled: boolean
  onToggleControls: () => void
  onSeek: (seconds: number) => void
  onFitModeChange: (mode: VideoFitMode) => void
  onInteractionStart: () => void
  onInteractionEnd: () => void
}

interface HudState {
  icon: keyof typeof Ionicons.glyphMap
  label: string
  value?: number
}

export function PlayerGestureLayer({
  disabled,
  onToggleControls,
  onSeek,
  onFitModeChange,
  onInteractionStart,
  onInteractionEnd,
}: Props) {
  const { width, height } = useWindowDimensions()
  // The PanResponder is created once; refs mirror everything it reads so its
  // handlers never close over stale values.
  const latest = useRef({ width, height, onToggleControls, onSeek, onFitModeChange, onInteractionStart, onInteractionEnd })
  latest.current = { width, height, onToggleControls, onSeek, onFitModeChange, onInteractionStart, onInteractionEnd }

  const [hud, setHud] = useState<HudState | null>(null)
  const hudHideTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const brightness = useRef(0.5)
  const volume = useRef(0.5)
  const gestureStartValue = useRef(0.5)
  const gestureStartDy = useRef(0)
  const gestureSide = useRef<'brightness' | 'volume'>('brightness')
  // 'idle' until a gesture commits; once 'pinch', single-touch moves are
  // ignored for the rest of the gesture (lifting one finger mid-pinch must not
  // fall through to a brightness/volume swipe with the accumulated dy).
  const gestureMode = useRef<'idle' | 'swipe' | 'pinch'>('idle')
  const baselineCaptured = useRef(false)
  const lastShownPercent = useRef<number | null>(null)
  const pinchStartDistance = useRef<number | null>(null)
  const pinchHandled = useRef(false)
  const lastTap = useRef<{ time: number; side: 'left' | 'right' } | null>(null)
  const singleTapTimer = useRef<ReturnType<typeof setTimeout> | null>(null)

  const cancelHudHide = () => {
    if (hudHideTimer.current === null) return
    clearTimeout(hudHideTimer.current)
    hudHideTimer.current = null
  }

  const hideHudAfter = (ms: number) => {
    cancelHudHide()
    hudHideTimer.current = setTimeout(() => {
      hudHideTimer.current = null
      setHud(null)
    }, ms)
  }

  useEffect(() => {
    void Brightness.getBrightnessAsync().then((value) => {
      brightness.current = value
    })
    // Hardware buttons / Control Center change volume outside our gestures;
    // the listener keeps the swipe baseline honest.
    void VolumeManager.getVolume().then(({ volume: value }) => {
      volume.current = value
    })
    const volumeSubscription = VolumeManager.addVolumeListener((result) => {
      volume.current = result.volume
    })
    return () => {
      volumeSubscription.remove()
      if (singleTapTimer.current !== null) clearTimeout(singleTapTimer.current)
      if (hudHideTimer.current !== null) clearTimeout(hudHideTimer.current)
    }
  }, [])

  const panResponder = useRef(
    PanResponder.create({
      onStartShouldSetPanResponder: () => true,
      onMoveShouldSetPanResponder: () => true,
      onPanResponderGrant: (event) => {
        gestureMode.current = 'idle'
        baselineCaptured.current = false
        lastShownPercent.current = null
        pinchStartDistance.current = null
        pinchHandled.current = false
        // Clear any HUD from the previous gesture: its pending hide is being
        // cancelled here, and a gesture that ends as a tap never schedules a
        // new one — without this the pill sticks forever.
        cancelHudHide()
        setHud(null)
        gestureSide.current = event.nativeEvent.locationX < latest.current.width / 2 ? 'brightness' : 'volume'
        // iOS auto-brightness drifts outside our control (there is no change
        // listener); a refresh at gesture start beats a mount-time snapshot.
        void Brightness.getBrightnessAsync().then((value) => {
          brightness.current = value
        })
        latest.current.onInteractionStart()
      },
      onPanResponderMove: (event, gesture) => {
        if (event.nativeEvent.touches.length >= 2 || gestureMode.current === 'pinch') {
          gestureMode.current = 'pinch'
          const [firstTouch, secondTouch] = event.nativeEvent.touches
          if (!firstTouch || !secondTouch) return
          const distance = touchDistance(firstTouch, secondTouch)
          if (pinchStartDistance.current === null) {
            pinchStartDistance.current = distance
            return
          }
          if (pinchHandled.current || pinchStartDistance.current < 1) return

          const scale = distance / pinchStartDistance.current
          if (scale >= 1 + PINCH_THRESHOLD) {
            pinchHandled.current = true
            latest.current.onFitModeChange('cover')
            setHud({ icon: 'scan-outline', label: 'Fill screen' })
          } else if (scale <= 1 - PINCH_THRESHOLD) {
            pinchHandled.current = true
            latest.current.onFitModeChange('contain')
            setHud({ icon: 'contract-outline', label: 'Fit to screen' })
          }
          return
        }
        if (Math.abs(gesture.dy) < SWIPE_THRESHOLD || Math.abs(gesture.dy) < Math.abs(gesture.dx)) return
        gestureMode.current = 'swipe'
        if (!baselineCaptured.current) {
          baselineCaptured.current = true
          gestureStartValue.current = gestureSide.current === 'brightness' ? brightness.current : volume.current
          gestureStartDy.current = gesture.dy
        }
        const travel = gesture.dy - gestureStartDy.current
        const value = clamp01(gestureStartValue.current - travel / Math.max(latest.current.height * 0.7, 1))
        const percent = Math.round(value * 100)
        // One native call + HUD update per visible percent step, not per move
        // event (~60-120 Hz).
        if (percent === lastShownPercent.current) return
        lastShownPercent.current = percent
        if (gestureSide.current === 'brightness') {
          brightness.current = value
          void Brightness.setBrightnessAsync(value)
          setHud({ icon: 'sunny', label: `${percent}%`, value })
        } else {
          volume.current = value
          void VolumeManager.setVolume(value, { type: 'music', showUI: false })
          setHud({ icon: value === 0 ? 'volume-mute' : 'volume-high', label: `${percent}%`, value })
        }
      },
      onPanResponderRelease: (event, gesture) => {
        latest.current.onInteractionEnd()
        pinchStartDistance.current = null
        pinchHandled.current = false
        if (gestureMode.current !== 'idle') {
          gestureMode.current = 'idle'
          hideHudAfter(500)
          return
        }
        if (Math.abs(gesture.dx) > TAP_MOVE_LIMIT || Math.abs(gesture.dy) > TAP_MOVE_LIMIT) return

        const side = event.nativeEvent.locationX < latest.current.width / 2 ? 'left' : 'right'
        const now = Date.now()
        const previous = lastTap.current
        if (previous && previous.side === side && now - previous.time <= DOUBLE_TAP_MS) {
          if (singleTapTimer.current !== null) clearTimeout(singleTapTimer.current)
          singleTapTimer.current = null
          lastTap.current = null
          const seconds = side === 'left' ? -10 : 10
          latest.current.onSeek(seconds)
          setHud({ icon: side === 'left' ? 'play-back' : 'play-forward', label: `${Math.abs(seconds)} seconds` })
          hideHudAfter(650)
          return
        }

        lastTap.current = { time: now, side }
        singleTapTimer.current = setTimeout(() => {
          lastTap.current = null
          singleTapTimer.current = null
          latest.current.onToggleControls()
        }, DOUBLE_TAP_MS)
      },
      onPanResponderTerminate: () => {
        gestureMode.current = 'idle'
        pinchStartDistance.current = null
        pinchHandled.current = false
        cancelHudHide()
        setHud(null)
        latest.current.onInteractionEnd()
      },
    }),
  ).current

  return (
    <View
      style={styles.touchSurface}
      pointerEvents={disabled ? 'none' : 'auto'}
      {...panResponder.panHandlers}
    >
      {hud ? (
        <View style={styles.hud} pointerEvents="none">
          <Ionicons name={hud.icon} size={30} color={colors.text} />
          <Text style={styles.hudLabel}>{hud.label}</Text>
          {hud.value !== undefined ? (
            <View style={styles.meter}>
              <View style={[styles.meterFill, { width: `${hud.value * 100}%` }]} />
            </View>
          ) : null}
        </View>
      ) : null}
    </View>
  )
}

function touchDistance(
  first: { pageX: number; pageY: number },
  second: { pageX: number; pageY: number },
): number {
  return Math.hypot(second.pageX - first.pageX, second.pageY - first.pageY)
}

const styles = StyleSheet.create({
  touchSurface: {
    ...StyleSheet.absoluteFill,
    alignItems: 'center',
    justifyContent: 'center',
  },
  hud: {
    minWidth: 132,
    alignItems: 'center',
    gap: spacing.xs,
    paddingHorizontal: spacing.lg,
    paddingVertical: spacing.md,
    borderRadius: radius.lg,
    backgroundColor: colors.overlayPill,
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: 'rgba(255,255,255,0.2)',
  },
  hudLabel: { color: colors.text, fontSize: 14, fontWeight: '700' },
  meter: {
    width: 92,
    height: 4,
    overflow: 'hidden',
    borderRadius: radius.pill,
    backgroundColor: 'rgba(255,255,255,0.22)',
  },
  meterFill: { height: '100%', borderRadius: radius.pill, backgroundColor: colors.accent },
})
