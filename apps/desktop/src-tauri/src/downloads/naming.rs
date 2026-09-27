//! Names for files the engine writes. Everything here is derived from
//! addon-supplied text, so each value is reduced to a single safe file name
//! component before it reaches the filesystem.

use std::path::{Component, Path};

const VIDEO_EXTENSIONS: [&str; 7] = ["mp4", "mkv", "webm", "avi", "m4v", "mov", "ts"];
const SUBTITLE_EXTENSIONS: [&str; 4] = ["srt", "vtt", "ass", "ssa"];
const MAX_STEM_CHARS: usize = 80;
const MAX_LANGUAGE_CHARS: usize = 24;

/// True for exactly one ordinary path component: no separators, drive,
/// root, `.` or `..`.
pub fn is_safe_file_name(name: &str) -> bool {
    let mut components = Path::new(name).components();
    matches!(
        (components.next(), components.next()),
        (Some(Component::Normal(_)), None)
    ) && !name.contains(['/', '\\', ':'])
}

/// `<release stem>-<source fingerprint>-<account>.<ext>`. The folder is shared
/// by every account on the device, so the name carries both the source and
/// the account: without the account segment two accounts saving the same
/// source URL would resolve to one file. `attempt` > 1 adds a numeric suffix
/// for the rare name that is already taken.
pub fn download_file_name(
    release_name: Option<&str>,
    video_id: &str,
    source_fingerprint: &str,
    account_key: &str,
    attempt: u32,
) -> String {
    let raw = release_name.unwrap_or(video_id);
    let leaf = raw.rsplit(['/', '\\']).next().unwrap_or(raw);
    let cleaned: String = leaf
        .chars()
        .map(|c| {
            if c.is_ascii_alphanumeric() || matches!(c, '.' | '-' | '_') {
                c
            } else {
                '_'
            }
        })
        .collect();
    let cleaned = cleaned.trim_matches('.');
    let cleaned = if cleaned.is_empty() { "video" } else { cleaned };
    let path = Path::new(cleaned);
    let extension = path
        .extension()
        .and_then(|value| value.to_str())
        .map(str::to_ascii_lowercase)
        .filter(|value| VIDEO_EXTENSIONS.contains(&value.as_str()))
        .unwrap_or_else(|| "mkv".to_string());
    let stem = path
        .file_stem()
        .and_then(|value| value.to_str())
        .filter(|value| !value.is_empty())
        .unwrap_or("video");
    let stem: String = stem.chars().take(MAX_STEM_CHARS).collect();
    let source = &source_fingerprint[..source_fingerprint.len().min(12)];
    let account = &account_key[..account_key.len().min(8)];
    if attempt > 1 {
        format!("{stem}-{source}-{account}-{attempt}.{extension}")
    } else {
        format!("{stem}-{source}-{account}.{extension}")
    }
}

/// A language code reduced to letters, digits, `-` and `_`, so it can sit in
/// a sidecar file name. An addon controls this value.
pub fn safe_language(language: &str) -> String {
    let cleaned: String = language
        .chars()
        .map(|c| {
            if c.is_ascii_alphanumeric() || matches!(c, '-' | '_') {
                c
            } else {
                '_'
            }
        })
        .take(MAX_LANGUAGE_CHARS)
        .collect();
    if cleaned.is_empty() {
        "und".to_string()
    } else {
        cleaned
    }
}

/// The sidecar's extension, read from the URL path and ignoring any query or
/// fragment. Anything that is not a known subtitle format is saved as SRT.
pub fn subtitle_extension(url: &str) -> &'static str {
    let path = url.split(['?', '#']).next().unwrap_or(url);
    let extension = Path::new(path)
        .extension()
        .and_then(|value| value.to_str())
        .map(str::to_ascii_lowercase);
    SUBTITLE_EXTENSIONS
        .into_iter()
        .find(|known| extension.as_deref() == Some(*known))
        .unwrap_or("srt")
}

#[cfg(test)]
mod tests {
    use super::*;

    const SOURCE: &str = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    const ACCOUNT: &str = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210";

    #[test]
    fn file_names_are_single_safe_components_with_known_extensions() {
        let name = download_file_name(Some("../../bad name.exe"), "movie:1", SOURCE, ACCOUNT, 1);
        assert!(is_safe_file_name(&name));
        assert_eq!(name, "bad_name-0123456789ab-fedcba98.mkv");
        let name = download_file_name(
            Some(r"Show\S01E01.1080p.MP4"),
            "tt1:1:1",
            SOURCE,
            ACCOUNT,
            1,
        );
        assert_eq!(name, "S01E01.1080p-0123456789ab-fedcba98.mp4");
    }

    #[test]
    fn the_account_segment_separates_two_accounts_saving_one_source() {
        let first = download_file_name(Some("film.mkv"), "movie:1", SOURCE, ACCOUNT, 1);
        let second = download_file_name(Some("film.mkv"), "movie:1", SOURCE, SOURCE, 1);
        assert_ne!(first, second);
        assert!(download_file_name(Some("film.mkv"), "m", SOURCE, ACCOUNT, 3).ends_with("-3.mkv"));
    }

    #[test]
    fn a_video_id_stands_in_for_a_missing_release_name() {
        let name = download_file_name(None, "tt0903747:1:1", SOURCE, ACCOUNT, 1);
        assert_eq!(name, "tt0903747_1_1-0123456789ab-fedcba98.mkv");
    }

    #[test]
    fn only_one_plain_component_is_a_safe_file_name() {
        assert!(is_safe_file_name("film.mkv"));
        assert!(!is_safe_file_name(""));
        assert!(!is_safe_file_name(".."));
        assert!(!is_safe_file_name("sub/film.mkv"));
        assert!(!is_safe_file_name(r"sub\film.mkv"));
        assert!(!is_safe_file_name(r"C:film.mkv"));
        assert!(!is_safe_file_name(r"C:\film.mkv"));
    }

    #[test]
    fn subtitle_languages_cannot_carry_path_separators() {
        assert_eq!(safe_language(r"..\..\evil"), "______evil");
        assert_eq!(safe_language("pt-BR"), "pt-BR");
        assert_eq!(safe_language(""), "und");
        assert_eq!(safe_language(&"x".repeat(100)).len(), MAX_LANGUAGE_CHARS);
    }

    #[test]
    fn subtitle_extensions_ignore_queries_and_default_to_srt() {
        assert_eq!(
            subtitle_extension("https://s.test/a.ASS?token=1.srt"),
            "ass"
        );
        assert_eq!(subtitle_extension("https://s.test/a.vtt#t=1"), "vtt");
        assert_eq!(subtitle_extension("https://s.test/download/12345"), "srt");
        assert_eq!(subtitle_extension("https://s.test/a.exe"), "srt");
    }
}
