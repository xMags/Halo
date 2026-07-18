// Dev helper: drive the running app's WebView2 over the Chrome DevTools
// Protocol — no OS-level input injection. Requires the app to be launched with
// WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS=--remote-debugging-port=9223 (dev only).
//
// Usage:
//   node scripts/cdp.mjs eval "<js expression>"       # prints the result JSON
//   node scripts/cdp.mjs shot <out.png>               # screenshot of the page
import { writeFileSync } from 'node:fs'

const PORT = 9223
const [, , mode, arg] = process.argv

const pages = await (await fetch(`http://127.0.0.1:${PORT}/json`)).json()
const page = pages.find((p) => p.type === 'page')
if (!page) {
  console.error('no page target; is the app running with the debug port?')
  process.exit(1)
}

const ws = new WebSocket(page.webSocketDebuggerUrl)
await new Promise((resolve, reject) => {
  ws.onopen = resolve
  ws.onerror = reject
})

let nextId = 1
function send(method, params = {}) {
  const id = nextId++
  ws.send(JSON.stringify({ id, method, params }))
  return new Promise((resolve, reject) => {
    const onMessage = (event) => {
      const msg = JSON.parse(event.data)
      if (msg.id !== id) return
      ws.removeEventListener('message', onMessage)
      if (msg.error) reject(new Error(msg.error.message))
      else resolve(msg.result)
    }
    ws.addEventListener('message', onMessage)
  })
}

if (mode === 'eval') {
  const result = await send('Runtime.evaluate', {
    expression: arg,
    awaitPromise: true,
    returnByValue: true,
  })
  if (result.exceptionDetails) {
    console.error('EXCEPTION:', JSON.stringify(result.exceptionDetails, null, 2))
    process.exit(1)
  }
  console.log(JSON.stringify(result.result.value ?? null))
} else if (mode === 'shot') {
  const { data } = await send('Page.captureScreenshot', { format: 'png' })
  writeFileSync(arg, Buffer.from(data, 'base64'))
  console.log(`saved ${arg}`)
} else {
  console.error('usage: cdp.mjs eval "<expr>" | cdp.mjs shot <out.png>')
  process.exit(1)
}
ws.close()
