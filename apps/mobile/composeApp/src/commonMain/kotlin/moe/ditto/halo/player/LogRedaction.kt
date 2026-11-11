package moe.ditto.halo.player

/**
 * A player log line with every link cut down to its scheme and host.
 *
 * mpv names the file it opens ("Playing: <url>", "Failed to open <url>."), and
 * a stream link carries the debrid token or addon key that pays for it. The
 * system log is readable by bug reports and by anything with log access, so a
 * line goes there only in this form. The host stays: which server refused is
 * what a diagnosis needs, and it is not a secret.
 */
internal fun redactLinks(line: String): String = LinkInLog.replace(line) { match ->
    "${match.groupValues[1]}${match.groupValues[2]}/…"
}

private val LinkInLog = Regex("""([a-zA-Z][a-zA-Z0-9+.-]*://)(?:[^/\s@]*@)?([^/\s?#]+)\S*""")
