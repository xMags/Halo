import { showDialog } from '../components/Dialog'
import { dismissActionError } from './downloadsStore'

/** The question asked before a finished download is deleted, wherever it is deleted from. */
export function confirmDeleteFromDevice(): Promise<boolean> {
  return showDialog({
    title: 'Delete from device?',
    body: 'This permanently removes the video and its subtitle sidecar from this device.',
    primary: 'Delete',
    close: 'Cancel',
  })
}

/**
 * Reports a download action that failed away from the Downloads page, whose
 * error bar would otherwise show it only on the next visit. Once the dialog
 * has said it, the bar has nothing left to say.
 */
export async function reportDownloadActionFailure(failure: string | null): Promise<void> {
  if (!failure) return
  dismissActionError()
  await showDialog({ title: 'Download action failed', body: failure, close: 'Close' })
}
