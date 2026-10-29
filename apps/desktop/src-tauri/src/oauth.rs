//! One-shot loopback listener for the OAuth authorization-code redirect
//! (RFC 8252 native-app flow). The port is fixed because Authentik registers
//! exact redirect URIs — no wildcard ports.

use std::io::{Read, Write};
use std::net::TcpListener;
use std::time::{Duration, Instant};

/// Must match `OAUTH_CALLBACK_PORT` in src/auth/loopbackSignIn.ts and the
/// redirect URI registered with each provider: http://127.0.0.1:17871/callback
pub const CALLBACK_PORT: u16 = 17871;

const TIMEOUT: Duration = Duration::from_secs(300);

/// The tab the browser lands on, in the sign-in screen's look; see the file.
const PAGE_TEMPLATE: &str = include_str!("callback_page.html");

const CHECK_ICON: &str = r#"<svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12.5l4.5 4.5L19 7.5"/></svg>"#;
const CROSS_ICON: &str = r#"<svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round"><path d="M7 7l10 10M17 7L7 17"/></svg>"#;

/// The page for this redirect. A provider that refused or failed sends an
/// `error` parameter instead of a code; that tab must not claim a sign-in the
/// app is about to report as declined. Every value filled in is a constant,
/// so nothing from the request reaches the page.
fn callback_page(path: &str) -> String {
    let failed = path
        .split_once('?')
        .map(|(_, query)| query.split('&').any(|pair| pair == "error" || pair.starts_with("error=")))
        .unwrap_or(false);
    let (tone, icon, heading, accent, body) = if failed {
        (
            "#ff99a4",
            CROSS_ICON,
            "Sign-in",
            "didn’t finish.",
            "Nothing was signed in. Head back to Halo to try again, or keep using it without an account.",
        )
    } else {
        (
            "#6ccb5f",
            CHECK_ICON,
            "You’re signed in",
            "to Halo.",
            "Halo carries on by itself. You can close this tab and head back to the app.",
        )
    };
    PAGE_TEMPLATE
        .replace("{{TONE}}", tone)
        .replace("{{ICON}}", icon)
        .replace("{{HEADING}}", heading)
        .replace("{{ACCENT}}", accent)
        .replace("{{BODY}}", body)
        .replace("{{STATUS}}", "RETURN TO HALO")
}

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
                let page = callback_page(&path);
                let _ = stream.write_all(
                    format!(
                        "HTTP/1.1 200 OK\r\ncontent-type: text/html; charset=utf-8\r\ncache-control: no-store\r\ncontent-length: {}\r\nconnection: close\r\n\r\n{}",
                        page.len(),
                        page
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

#[cfg(test)]
mod tests {
    use super::callback_page;

    #[test]
    fn a_code_gets_the_signed_in_page() {
        let page = callback_page("/callback?code=abc&state=xyz");
        assert!(page.contains("You’re signed in"));
        assert!(!page.contains("{{"));
    }

    #[test]
    fn a_provider_error_gets_the_unfinished_page_and_nothing_from_the_request() {
        let page = callback_page("/callback?error=access_denied&error_description=%3Cscript%3E&state=xyz");
        assert!(page.contains("didn’t finish"));
        assert!(!page.contains("access_denied"));
        assert!(!page.contains("script%3E"));
    }

    #[test]
    fn only_the_error_key_counts() {
        assert!(callback_page("/callback?code=error_x&state=s").contains("You’re signed in"));
    }
}
