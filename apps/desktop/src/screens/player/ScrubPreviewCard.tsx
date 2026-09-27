import { useCallback, useRef, useState } from 'react'
import { formatClock } from '../../format'
import { scrubPreviewOffset, shouldRequestScrubPreview } from '../../playerLogic'
import { requestScrubPreview } from '../../scrubPreview'

/** The native card: 200 wide, a 112-tall picture, and the time under it. */
const CARD_WIDTH = 200

interface HoverState {
  seconds: number
  offsetX: number
}

/**
 * Follows the pointer over the seek bar: shows the time under it and asks the
 * shell for a frame there, skipping moves too small to reach a new keyframe.
 * The last picture is kept when the pointer leaves, so re-entering at the
 * same place shows something at once.
 */
export function useScrubPreview() {
  const [hover, setHover] = useState<HoverState | null>(null)
  const [hasImage, setHasImage] = useState(false)
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const lastRequested = useRef<number | null>(null)

  const track = useCallback((seconds: number, pointerX: number, trackWidth: number) => {
    setHover({ seconds, offsetX: scrubPreviewOffset(pointerX, CARD_WIDTH, trackWidth) })
    if (!shouldRequestScrubPreview(seconds, lastRequested.current)) return
    lastRequested.current = seconds
    void requestScrubPreview(seconds)
      .then((frame) => {
        const canvas = canvasRef.current
        if (!frame || !canvas) return
        if (canvas.width !== frame.width || canvas.height !== frame.height) {
          canvas.width = frame.width
          canvas.height = frame.height
        }
        canvas.getContext('2d')?.putImageData(new ImageData(frame.pixels, frame.width, frame.height), 0, 0)
        setHasImage(true)
      })
      .catch(() => undefined)
  }, [])

  const leave = useCallback(() => setHover(null), [])

  return { hover, hasImage, canvasRef, track, leave }
}

export function ScrubPreviewCard({
  preview,
}: {
  preview: ReturnType<typeof useScrubPreview>
}) {
  const { hover, hasImage, canvasRef } = preview
  return (
    <div
      className="pscrub"
      style={{ transform: `translateX(${hover?.offsetX ?? 0}px)`, visibility: hover ? 'visible' : 'hidden' }}
      aria-hidden
    >
      <div className="pscrub-picture">
        <canvas ref={canvasRef} style={{ visibility: hasImage ? 'visible' : 'hidden' }} />
      </div>
      <div className="pscrub-time">{hover ? formatClock(hover.seconds) : ''}</div>
    </div>
  )
}
