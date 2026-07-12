import { useEffect, useRef, useState } from 'react'
import { PanResponder, StyleSheet, Text, useWindowDimensions, View } from 'react-native'
import { Ionicons } from '@expo/vector-icons'
import * as Brightness from 'expo-brightness'
import { VolumeManager } from 'react-native-volume-manager'
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
  const dimensions = useRef({ width, height })
  dimensions.current = { width, height }
  const callbacks = useRef({ onToggleControls, onSeek, onFitModeChange, onInteractionStart, onInteractionEnd })
  callbacks.current = { onToggleControls, onSeek, onFitModeChange, onInteractionStart, onInteractionEnd }
  const [hud, setHud] = useState<HudState | null>(null)
  const brightness = useRef(0.5)
  const volume = useRef(0.5)
  const gestureStartValue = useRef(0.5)
  const gestureSide = useRef<'brightness' | 'volume'>('brightness')
  const moved = useRef(false)
  const pinchStartDistance = useRef<number | null>(null)
  const pinchHandled = useRef(false)
  const lastTap = useRef<{ time: number; side: 'left' | 'right' } | null>(null)
  const singleTapTimer = useRef<ReturnType<typeof setTimeout> | null>(null)

  useEffect(() => {
    void Brightness.getBrightnessAsync().then((value) => {
      brightness.current = value
    })
    void VolumeManager.getVolume().then(({ volume: value }) => {
      volume.current = value
    })
    void VolumeManager.showNativeVolumeUI({ enabled: false })
    return () => {
      if (singleTapTimer.current !== null) clearTimeout(singleTapTimer.current)
      void VolumeManager.showNativeVolumeUI({ enabled: true })
    }
  }, [])

  const panResponder = useRef(
    PanResponder.create({
      onStartShouldSetPanResponder: () => true,
      onMoveShouldSetPanResponder: () => true,
      onPanResponderGrant: (event) => {
        moved.current = false
        pinchStartDistance.current = null
        pinchHandled.current = false
        gestureSide.current = event.nativeEvent.locationX < dimensions.current.width / 2 ? 'brightness' : 'volume'
        gestureStartValue.current = gestureSide.current === 'brightness' ? brightness.current : volume.current
        callbacks.current.onInteractionStart()
      },
      onPanResponderMove: (event, gesture) => {
        if (event.nativeEvent.touches.length >= 2) {
          moved.current = true
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
            callbacks.current.onFitModeChange('cover')
            setHud({ icon: 'scan-outline', label: 'Fill screen' })
          } else if (scale <= 1 - PINCH_THRESHOLD) {
            pinchHandled.current = true
            callbacks.current.onFitModeChange('contain')
            setHud({ icon: 'contract-outline', label: 'Fit to screen' })
          }
          return
        }
        if (Math.abs(gesture.dy) < SWIPE_THRESHOLD || Math.abs(gesture.dy) < Math.abs(gesture.dx)) return
        moved.current = true
        const value = clamp(gestureStartValue.current - gesture.dy / Math.max(dimensions.current.height * 0.7, 1))
        if (gestureSide.current === 'brightness') {
          brightness.current = value
          void Brightness.setBrightnessAsync(value)
          setHud({ icon: 'sunny', label: `${Math.round(value * 100)}%`, value })
        } else {
          volume.current = value
          void VolumeManager.setVolume(value, { type: 'music', showUI: false })
          setHud({ icon: value === 0 ? 'volume-mute' : 'volume-high', label: `${Math.round(value * 100)}%`, value })
        }
      },
      onPanResponderRelease: (event, gesture) => {
        callbacks.current.onInteractionEnd()
        pinchStartDistance.current = null
        pinchHandled.current = false
        if (moved.current) {
          setTimeout(() => setHud(null), 500)
          return
        }
        if (Math.abs(gesture.dx) > TAP_MOVE_LIMIT || Math.abs(gesture.dy) > TAP_MOVE_LIMIT) return

        const side = event.nativeEvent.locationX < dimensions.current.width / 2 ? 'left' : 'right'
        const now = Date.now()
        const previous = lastTap.current
        if (previous && previous.side === side && now - previous.time <= DOUBLE_TAP_MS) {
          if (singleTapTimer.current !== null) clearTimeout(singleTapTimer.current)
          singleTapTimer.current = null
          lastTap.current = null
          const seconds = side === 'left' ? -10 : 10
          callbacks.current.onSeek(seconds)
          setHud({ icon: side === 'left' ? 'play-back' : 'play-forward', label: `${Math.abs(seconds)} seconds` })
          setTimeout(() => setHud(null), 650)
          return
        }

        lastTap.current = { time: now, side }
        singleTapTimer.current = setTimeout(() => {
          lastTap.current = null
          singleTapTimer.current = null
          callbacks.current.onToggleControls()
        }, DOUBLE_TAP_MS)
      },
      onPanResponderTerminate: () => {
        moved.current = false
        pinchStartDistance.current = null
        pinchHandled.current = false
        setHud(null)
        callbacks.current.onInteractionEnd()
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

function clamp(value: number): number {
  return Math.max(0, Math.min(1, value))
}

function touchDistance(
  first: { pageX: number; pageY: number },
  second: { pageX: number; pageY: number },
): number {
  return Math.hypot(second.pageX - first.pageX, second.pageY - first.pageY)
}

const styles = StyleSheet.create({
  touchSurface: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
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
    backgroundColor: 'rgba(5,7,12,0.82)',
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
