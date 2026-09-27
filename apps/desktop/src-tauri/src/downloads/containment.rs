//! Keeps file deletion and playback inside the folders this device has used
//! for downloads. The index is plain JSON that any corruption or edit can
//! reach, so the root path a record carries is never trusted on its own.

use std::path::{Component, Path, PathBuf, Prefix};

/// Raised instead of deleting when a path resolves outside every approved
/// download root, or is itself a link.
#[derive(Debug, PartialEq, Eq)]
pub struct ContainmentError;

/// The comparable parts of an absolute path: drive or share first, then each
/// normal component, lowercased, with `.` dropped and `..` applied. Verbatim
/// (`\\?\`) and plain forms of the same drive compare equal. Returns None for
/// a relative path, or one whose `..` climbs above its root.
fn lexical_components(path: &Path) -> Option<Vec<String>> {
    let mut parts: Vec<String> = Vec::new();
    let mut rooted = false;
    for component in path.components() {
        match component {
            Component::Prefix(prefix) => {
                let head = match prefix.kind() {
                    Prefix::Disk(letter) | Prefix::VerbatimDisk(letter) => {
                        format!("{}:", (letter as char).to_ascii_lowercase())
                    }
                    Prefix::UNC(server, share) | Prefix::VerbatimUNC(server, share) => format!(
                        r"\\{}\{}",
                        server.to_string_lossy().to_lowercase(),
                        share.to_string_lossy().to_lowercase()
                    ),
                    _ => return None,
                };
                parts.push(head);
            }
            Component::RootDir => rooted = true,
            Component::CurDir => {}
            Component::ParentDir => {
                // Never climb above the drive or share itself.
                if parts.len() <= 1 {
                    return None;
                }
                parts.pop();
            }
            Component::Normal(value) => parts.push(value.to_string_lossy().to_lowercase()),
        }
    }
    if !rooted || parts.is_empty() {
        return None;
    }
    Some(parts)
}

/// True when both name the same location, compared without touching disk.
pub fn same_path(left: &Path, right: &Path) -> bool {
    match (lexical_components(left), lexical_components(right)) {
        (Some(left), Some(right)) => left == right,
        _ => false,
    }
}

/// True when `candidate` sits inside one of the roots, compared component by
/// component so a sibling folder with a shared prefix does not match. Purely
/// lexical: a caller about to delete must resolve links first
/// ([`ensure_within_roots`]), or a junction inside a root escapes it.
pub fn is_within_approved_root(candidate: &Path, roots: &[PathBuf]) -> bool {
    let Some(target) = lexical_components(candidate) else {
        return false;
    };
    roots.iter().any(|root| {
        lexical_components(root)
            .is_some_and(|prefix| prefix.len() <= target.len() && target.starts_with(&prefix))
    })
}

/// Drops the `\\?\` prefix `canonicalize` adds, so paths shown to the user
/// and stored in config read the way Explorer writes them.
pub fn strip_verbatim(path: PathBuf) -> PathBuf {
    let text = path.to_string_lossy();
    if let Some(rest) = text.strip_prefix(r"\\?\UNC\") {
        return PathBuf::from(format!(r"\\{rest}"));
    }
    if let Some(rest) = text.strip_prefix(r"\\?\") {
        return PathBuf::from(rest);
    }
    path
}

/// The path Windows actually resolves to, with junctions and symbolic links
/// followed. None when the object cannot be opened, which includes the
/// ordinary case of a file that is not there.
pub fn real_path(path: &Path) -> Option<PathBuf> {
    std::fs::canonicalize(path).ok().map(strip_verbatim)
}

/// A folder the user picked may itself be a junction. Keeping both the
/// literal and the resolved form means such a choice still matches, while a
/// link planted underneath a root resolves out of the set.
pub fn resolve_roots(roots: &[PathBuf]) -> Vec<PathBuf> {
    let mut resolved = Vec::with_capacity(roots.len() * 2);
    for root in roots {
        resolved.push(root.clone());
        if let Some(real) = real_path(root) {
            resolved.push(real);
        }
    }
    resolved
}

/// Refuses a path that is outside the roots lexically, is a link itself, or
/// resolves outside them through a linked parent.
pub fn ensure_within_roots(
    path: &Path,
    resolved_roots: &[PathBuf],
) -> Result<(), ContainmentError> {
    if !is_within_approved_root(path, resolved_roots) {
        return Err(ContainmentError);
    }
    if std::fs::symlink_metadata(path).is_ok_and(|meta| meta.file_type().is_symlink()) {
        return Err(ContainmentError);
    }
    // The file is usually already gone. Judge it by the folder that would
    // hold it, so a linked parent is still caught.
    let effective = real_path(path).or_else(|| {
        let parent = real_path(path.parent()?)?;
        Some(parent.join(path.file_name()?))
    });
    if let Some(effective) = effective {
        if !is_within_approved_root(&effective, resolved_roots) {
            return Err(ContainmentError);
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn roots(values: &[&str]) -> Vec<PathBuf> {
        values.iter().map(PathBuf::from).collect()
    }

    #[test]
    fn a_file_inside_a_root_is_contained() {
        let approved = roots(&[r"D:\Halo\Downloads"]);
        assert!(is_within_approved_root(
            Path::new(r"D:\Halo\Downloads\film.mkv"),
            &approved
        ));
        assert!(is_within_approved_root(
            Path::new(r"d:\halo\downloads\Film.MKV"),
            &approved
        ));
    }

    #[test]
    fn a_sibling_that_shares_a_prefix_is_not_contained() {
        let approved = roots(&[r"D:\Halo\Downloads"]);
        assert!(!is_within_approved_root(
            Path::new(r"D:\Halo\Downloads2\film.mkv"),
            &approved
        ));
        assert!(!is_within_approved_root(
            Path::new(r"D:\Halo\film.mkv"),
            &approved
        ));
    }

    #[test]
    fn climbing_out_with_parent_components_is_not_contained() {
        let approved = roots(&[r"D:\Halo\Downloads"]);
        assert!(!is_within_approved_root(
            Path::new(r"D:\Halo\Downloads\..\..\Windows\system.ini"),
            &approved
        ));
        assert!(!is_within_approved_root(
            Path::new(r"D:\..\..\x"),
            &approved
        ));
    }

    #[test]
    fn relative_paths_and_relative_roots_never_match() {
        assert!(!is_within_approved_root(
            Path::new(r"film.mkv"),
            &roots(&[r"D:\Halo"])
        ));
        assert!(!is_within_approved_root(
            Path::new(r"D:\Halo\film.mkv"),
            &roots(&["Halo"])
        ));
    }

    #[test]
    fn verbatim_and_plain_forms_of_one_drive_agree() {
        let approved = roots(&[r"\\?\D:\Halo"]);
        assert!(is_within_approved_root(
            Path::new(r"D:\Halo\film.mkv"),
            &approved
        ));
        assert!(same_path(
            Path::new(r"\\?\D:\Halo\a.mkv"),
            Path::new(r"d:\halo\.\A.mkv")
        ));
        assert_eq!(
            strip_verbatim(PathBuf::from(r"\\?\D:\Halo")),
            PathBuf::from(r"D:\Halo")
        );
        assert_eq!(
            strip_verbatim(PathBuf::from(r"\\?\UNC\nas\share\Halo")),
            PathBuf::from(r"\\nas\share\Halo")
        );
    }

    #[test]
    fn a_missing_file_inside_a_real_root_passes_the_resolved_check() {
        let root = std::env::temp_dir().join(format!("halo-containment-{}", std::process::id()));
        std::fs::create_dir_all(&root).unwrap();
        let resolved = resolve_roots(std::slice::from_ref(&root));
        assert_eq!(
            ensure_within_roots(&root.join("gone.mkv"), &resolved),
            Ok(())
        );
        assert_eq!(
            ensure_within_roots(&std::env::temp_dir().join("elsewhere.mkv"), &resolved),
            Err(ContainmentError)
        );
        let _ = std::fs::remove_dir(root);
    }
}
