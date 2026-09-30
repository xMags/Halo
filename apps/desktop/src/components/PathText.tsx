import { Fragment } from 'react'

/**
 * A file-system path that wraps the way the native app's TextBlock wraps it:
 * at a space, or just before a separator, never inside a folder name. A path
 * has no break opportunities of its own, so each separator gets one.
 */
export function PathText({ text }: { text: string }) {
  const parts = text.split(/(?=[\\/])/)
  return (
    <>
      {parts.map((part, index) => (
        <Fragment key={index}>
          {index > 0 && <wbr />}
          {part}
        </Fragment>
      ))}
    </>
  )
}
