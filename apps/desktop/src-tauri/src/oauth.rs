//! One-shot loopback listener for the OAuth authorization-code redirect
//! (RFC 8252 native-app flow). The port is fixed because Authentik registers
//! exact redirect URIs — no wildcard ports.

use std::io::{Read, Write};
use std::net::TcpListener;
use std::time::{Duration, Instant};

/// Must match `OAUTH_CALLBACK_PORT` in src/oidc.ts and the redirect URI
/// registered on the IdP: http://127.0.0.1:17871/callback
pub const CALLBACK_PORT: u16 = 17871;

const TIMEOUT: Duration = Duration::from_secs(300);

const RESPONSE_PAGE: &str = "<!doctype html><html><head><meta charset=\"utf-8\"><title>Halo</title></head>\
<body style=\"background:#0a0c11;color:#f4f6fb;font-family:'Segoe UI',sans-serif;display:grid;place-items:center;height:100vh;margin:0\">\
<div style=\"text-align:center\"><h2>Signed in to Halo</h2><p style=\"color:#8b93a5\">You can close this tab and return to the app.</p></div>\
</body></html>";

/// Blocks until the IdP redirects the browser to the loopback callback, then
/// returns the request path (with query string) for the JS side to parse.
/// One redirect per call; times out after five minutes.
pub fn wait_for_callback() -> Result<String, String> {
    let listener = TcpListener::bind(("127.0.0.1", CALLBACK_PORT))
        .map_err(|e| format!("cannot bind 127.0.0.1:{CALLBACK_PORT}: {e} (is another sign-in pending?)"))?;
    listener
        .set_nonblocking(true)
        .map_err(|e| e.to_string())?;

    let deadline = Instant::now() + TIMEOUT;
    loop {
        match listener.accept() {
            Ok((mut stream, _)) => {
                let _ = stream.set_nonblocking(false);
                let _ = stream.set_read_timeout(Some(Duration::from_secs(5)));
                let mut buf = [0u8; 8192];
                let n = stream.read(&mut buf).unwrap_or(0);
                let request = String::from_utf8_lossy(&buf[..n]);
                let path = request
                    .lines()
                    .next()
                    .and_then(|line| line.split_whitespace().nth(1))
                    .unwrap_or("/")
                    .to_string();

                // The JS side aborts a failed launch by hitting /cancel, which
                // frees the port instead of leaving it bound until the timeout.
                if path.starts_with("/cancel") {
                    let _ = stream.write_all(b"HTTP/1.1 200 OK\r\ncontent-length: 0\r\nconnection: close\r\n\r\n");
                    return Err("sign-in cancelled".into());
                }
                // Browsers also ask for /favicon.ico; only the callback ends the wait.
                if !path.starts_with("/callback") {
                    let _ = stream.write_all(b"HTTP/1.1 404 Not Found\r\ncontent-length: 0\r\nconnection: close\r\n\r\n");
                    continue;
                }
                let _ = stream.write_all(
                    format!(
                        "HTTP/1.1 200 OK\r\ncontent-type: text/html; charset=utf-8\r\ncontent-length: {}\r\nconnection: close\r\n\r\n{}",
                        RESPONSE_PAGE.len(),
                        RESPONSE_PAGE
                    )
                    .as_bytes(),
                );
                return Ok(path);
            }
            Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                if Instant::now() > deadline {
                    return Err("sign-in timed out waiting for the browser redirect".into());
                }
                std::thread::sleep(Duration::from_millis(250));
            }
            Err(e) => return Err(e.to_string()),
        }
    }
}
