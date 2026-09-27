//! Download transfers follow redirects themselves rather than letting the
//! HTTP stack do it, because the stack keeps custom source headers across
//! origins. Every hop is resolved and judged here.

use reqwest::Url;

pub const MAX_REDIRECTS: usize = 5;
const MAX_LOCATION_LENGTH: usize = 32_768;

pub fn is_redirect_status(status: u16) -> bool {
    matches!(status, 301 | 302 | 303 | 307 | 308)
}

#[derive(Debug)]
pub struct RedirectTarget {
    pub url: Url,
    /// False when the hop leaves the current origin, which is the caller's
    /// signal to stop forwarding the source's request headers.
    pub same_origin: bool,
}

fn same_origin(left: &Url, right: &Url) -> bool {
    left.scheme().eq_ignore_ascii_case(right.scheme())
        && left
            .host_str()
            .zip(right.host_str())
            .is_some_and(|(a, b)| a.eq_ignore_ascii_case(b))
        && left.port_or_known_default() == right.port_or_known_default()
}

/// Resolves one Location header against the URL that produced it. None when
/// the hop must not be followed at all: too many hops, an unusable Location, a
/// scheme that is not http(s), or a step down out of https.
pub fn next_redirect_target(
    current: &Url,
    location: &str,
    hops_followed: usize,
) -> Option<RedirectTarget> {
    if hops_followed >= MAX_REDIRECTS
        || location.is_empty()
        || location.len() > MAX_LOCATION_LENGTH
        || location.contains('\0')
    {
        return None;
    }
    let next = current.join(location).ok()?;
    let scheme = next.scheme();
    if next.host_str().is_none_or(str::is_empty)
        || !matches!(scheme, "http" | "https")
        || (current.scheme() == "https" && scheme != "https")
    {
        return None;
    }
    let same_origin = same_origin(current, &next);
    Some(RedirectTarget {
        url: next,
        same_origin,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn url(value: &str) -> Url {
        Url::parse(value).unwrap()
    }

    #[test]
    fn a_relative_location_stays_on_the_origin() {
        let target = next_redirect_target(&url("https://cdn.test/a/b"), "/c?d=1", 0).unwrap();
        assert_eq!(target.url.as_str(), "https://cdn.test/c?d=1");
        assert!(target.same_origin);
    }

    #[test]
    fn another_host_or_port_is_a_new_origin() {
        let current = url("https://cdn.test/a");
        assert!(
            !next_redirect_target(&current, "https://other.test/a", 0)
                .unwrap()
                .same_origin
        );
        assert!(
            !next_redirect_target(&current, "https://cdn.test:8443/a", 0)
                .unwrap()
                .same_origin
        );
        assert!(
            next_redirect_target(&current, "https://CDN.test:443/b", 0)
                .unwrap()
                .same_origin
        );
    }

    #[test]
    fn stepping_down_from_https_or_to_another_scheme_is_refused() {
        let current = url("https://cdn.test/a");
        assert!(next_redirect_target(&current, "http://cdn.test/a", 0).is_none());
        assert!(next_redirect_target(&current, "file:///C:/Windows/win.ini", 0).is_none());
        assert!(next_redirect_target(&url("http://cdn.test/a"), "https://cdn.test/a", 0).is_some());
    }

    #[test]
    fn the_hop_limit_and_malformed_locations_stop_the_chain() {
        let current = url("https://cdn.test/a");
        assert!(next_redirect_target(&current, "/b", MAX_REDIRECTS).is_none());
        assert!(next_redirect_target(&current, "", 0).is_none());
        assert!(next_redirect_target(&current, "/b\0c", 0).is_none());
        assert!(is_redirect_status(307));
        assert!(!is_redirect_status(304));
    }
}
