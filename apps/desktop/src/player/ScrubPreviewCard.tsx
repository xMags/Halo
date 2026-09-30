import { useCallback, useEffect, useRef, useState } from 'react'
import { formatClock } from '../format'
import { scrubPreviewOffset, shouldRequestScrubPreview } from './playerLogic'
import { requestScrubPreview } from './scrubPreview'

/** The native card: 200 wide, a 112-tall picture, and the time under it. */
const CARD_WIDTH = 200

/**
 * How long a frame may take before the skeleton replaces the picture. A local
 * file answers inside this, so dragging over one never flickers; a stream
 * opening or seeking over the network does not, so it shows a skeleton.
 */
const SKELETON_DELAY_MS = 150

interface HoverState {
  seconds: number
  offsetX: number
}

/**
 * Follows the pointer over the seek bar: shows the time under it and asks the
 * shell for a frame there, skipping moves too small to reach a new keyframe.
 * Only the latest request's answer counts; older ones are superseded. The
 * last picture is kept when the pointer leaves, so re-entering at the same
 * place shows something at once.
 */
export function useScrubPreview() {
  const [hover, setHover] = useState<HoverState | null>(null)
  /** The canvas holds the answer to the latest request that has answered. */
  const [hasImage, setHasImage] = useState(false)
  /** The latest request has not answered yet. */
  const [loading, setLoading] = useState(false)
  const [skeleton, setSkeleton] = useState(false)
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const lastRequested = useRef<number | null>(null)
  const latestToken = useRef(0)

  useEffect(() => {
    if (!loading) {
      setSkeleton(false)
      return
    }
    const timer = setTimeout(() => setSkeleton(true), SKELETON_DELAY_MS)
    return () => clearTimeout(timer)
  }, [loading])

  const track = useCallback((seconds: number, pointerX: number, trackWidth: number) => {
    setHover({ seconds, offsetX: scrubPreviewOffset(pointerX, CARD_WIDTH, trackWidth) })
    if (!shouldRequestScrubPreview(seconds, lastRequested.current)) return
    lastRequested.current = seconds
    const token = ++latestToken.current
    setLoading(true)
    void requestScrubPreview(seconds)
      .catch(() => null)
      .then((frame) => {
        // A newer request is out; this answer is for a place the pointer left.
        if (token !== latestToken.current) return
        setLoading(false)
        const canvas = canvasRef.current
        if (!frame || !canvas) {
          // No picture for this place: the plain box, never a stale frame.
          setHasImage(false)
          return
        }
        if (canvas.width !== frame.width || canvas.height !== frame.height) {
          canvas.width = frame.width
          canvas.height = frame.height
        }
        canvas.getContext('2d')?.putImageData(new ImageData(frame.pixels, frame.width, frame.height), 0, 0)
        setHasImage(true)
      })
  }, [])

  const leave = useCallback(() => setHover(null), [])

  return { hover, hasImage, skeleton, canvasRef, track, leave }
}

export function ScrubPreviewCard({
  preview,
}: {
  preview: ReturnType<typeof useScrubPreview>
}) {
  const { hover, hasImage, skeleton, canvasRef } = preview
  return (
    <div
      className="pscrub"
      style={{ transform: `translateX(${hover?.offsetX ?? 0}px)`, visibility: hover ? 'visible' : 'hidden' }}
      aria-hidden
    >
      <div className="pscrub-picture">
        {/* Opacity, not visibility: a child set visible would stay on screen
            after the card hides, stranded at the card's reset offset. */}
        <canvas ref={canvasRef} style={{ opacity: hasImage && !skeleton ? 1 : 0 }} />
        {skeleton && (
          <div className="pscrub-skeleton">
            {/* The player's loading ring on a faint track, so it reads as
                loading at a glance whatever point its arc is at. */}
            <svg className="pscrub-ring" width={28} height={28} viewBox="0 0 48 48" aria-hidden>
              <circle className="pscrub-ring-track" cx="24" cy="24" r="20" />
              <circle className="pscrub-ring-arc" cx="24" cy="24" r="20" />
            </svg>
          </div>
        )}
      </div>
      <div className="pscrub-time">{hover ? formatClock(hover.seconds) : ''}</div>
    </div>
  )
}
