//! Moves download state out of roaming app data, where earlier builds kept
//! it, into local app data.
//!
//! Roaming app data follows a Windows account between machines on managed
//! networks and is meant for small settings; the default download folder holds
//! whole videos. The move runs at launch before the engine reads anything, and
//! it is safe to repeat, so a launch that stops partway finishes on the next.

use super::containment::{is_within_approved_root, same_path};
use super::{
    load_config, remember_root, write_json, PersistedConfig, PersistedIndex, CONFIG_FILE,
    DOWNLOAD_DIR, INDEX_FILE, VAULT_DIR,
};
use std::fs;
use std::io::ErrorKind;
use std::path::{Component, Path, PathBuf};

/// Moves the state in `old` to `new`. An error means everything is still
/// usable in `old`, so the caller can keep working from there.
///
/// State already in `new` wins and is never merged with an older copy. The
/// request vault is copied before anything else changes; the default download
/// folder is renamed, which is instant on one drive, and the stored paths that
/// pointed into it are rewritten. When the folder cannot be renamed (another
/// drive, or a non-empty folder already at the target), the videos stay where
/// they are and their paths are left alone, which is still a working state.
pub fn move_roaming_state(old: &Path, new: &Path) -> Result<(), String> {
    // On macOS and Linux both app-data paths are the same folder.
    if same_path(old, new) || !has_state(old) {
        return Ok(());
    }
    if new.join(INDEX_FILE).exists() || new.join(CONFIG_FILE).exists() {
        return Ok(());
    }

    let mut index = read_index(&old.join(INDEX_FILE))?;
    let mut config = load_config(&old.join(CONFIG_FILE));
    fs::create_dir_all(new).map_err(|e| format!("local app data unavailable: {e}"))?;
    let copied_requests = copy_vault(&old.join(VAULT_DIR), &new.join(VAULT_DIR))?;

    let old_folder = old.join(DOWNLOAD_DIR);
    let new_folder = new.join(DOWNLOAD_DIR);
    let renamed_now = move_folder(&old_folder, &new_folder);
    // Also true when an earlier launch renamed it and stopped before writing
    // the index, which is what makes a repeat finish the job.
    if !old_folder.exists() {
        rebase_paths(index.as_mut(), config.as_mut(), &old_folder, &new_folder);
    }

    if let Err(error) = write_state(new, index.as_ref(), config.as_ref()) {
        let _ = fs::remove_file(new.join(INDEX_FILE));
        let _ = fs::remove_file(new.join(CONFIG_FILE));
        if renamed_now {
            // Put the videos back where the old index expects them.
            let _ = fs::rename(&new_folder, &old_folder);
        }
        return Err(error);
    }

    // The new copies are in place; what is left here is only duplicates.
    let _ = fs::remove_file(old.join(INDEX_FILE));
    let _ = fs::remove_file(old.join(CONFIG_FILE));
    let old_vault = old.join(VAULT_DIR);
    for name in &copied_requests {
        let _ = fs::remove_file(old_vault.join(name));
    }
    // Neither removal touches a folder that still holds anything.
    let _ = fs::remove_dir(&old_vault);
    let _ = fs::remove_dir(old);
    Ok(())
}

fn has_state(dir: &Path) -> bool {
    [INDEX_FILE, CONFIG_FILE, DOWNLOAD_DIR, VAULT_DIR]
        .iter()
        .any(|name| dir.join(name).exists())
}

/// The index as stored, entries untouched. An index this build cannot read is
/// an error, so it stays where the engine will report it.
fn read_index(path: &Path) -> Result<Option<PersistedIndex>, String> {
    let raw = match fs::read(path) {
        Ok(raw) => raw,
        Err(error) if error.kind() == ErrorKind::NotFound => return Ok(None),
        Err(error) => return Err(format!("download index is unreadable: {error}")),
    };
    let index: PersistedIndex =
        serde_json::from_slice(&raw).map_err(|e| format!("download index is unreadable: {e}"))?;
    if index.version != 1 {
        return Err("download index version is unsupported".into());
    }
    Ok(Some(index))
}

/// Copies each protected request file and returns the names copied. They are
/// DPAPI-sealed to the Windows user, not to a folder, so a copy still opens.
fn copy_vault(from: &Path, to: &Path) -> Result<Vec<std::ffi::OsString>, String> {
    let entries = match fs::read_dir(from) {
        Ok(entries) => entries,
        Err(error) if error.kind() == ErrorKind::NotFound => return Ok(Vec::new()),
        Err(error) => return Err(format!("download vault unreadable: {error}")),
    };
    fs::create_dir_all(to).map_err(|e| format!("download vault unavailable: {e}"))?;
    let mut copied = Vec::new();
    for entry in entries {
        let entry = entry.map_err(|e| format!("download vault unreadable: {e}"))?;
        let is_file = entry.file_type().is_ok_and(|kind| kind.is_file());
        if !is_file {
            continue;
        }
        fs::copy(entry.path(), to.join(entry.file_name()))
            .map_err(|e| format!("download request could not be copied: {e}"))?;
        copied.push(entry.file_name());
    }
    Ok(copied)
}

/// Renames `from` to `to` and says whether it did. An empty folder at `to`,
/// which a fresh launch creates, is replaced; anything else there is kept.
fn move_folder(from: &Path, to: &Path) -> bool {
    if !from.is_dir() {
        return false;
    }
    if to.exists() && fs::remove_dir(to).is_err() {
        return false;
    }
    fs::rename(from, to).is_ok()
}

/// Points every stored path inside `from` at the same place inside `to`.
fn rebase_paths(
    index: Option<&mut PersistedIndex>,
    config: Option<&mut PersistedConfig>,
    from: &Path,
    to: &Path,
) {
    let rebase = |value: &mut String| {
        if let Some(path) = rebased(Path::new(value.as_str()), from, to) {
            *value = path.to_string_lossy().into_owned();
        }
    };
    if let Some(index) = index {
        for entry in &mut index.entries {
            rebase(&mut entry.root_path);
            if let Some(backup) = entry.replacement.as_mut() {
                rebase(&mut backup.root_path);
            }
        }
    }
    let Some(config) = config else {
        return;
    };
    if let Some(directory) = config.directory.as_mut() {
        rebase(directory);
    }
    if let Some(roots) = config.approved_roots.as_mut() {
        // Rebuilt back to front through remember_root, which keeps the order
        // and drops a duplicate that two old roots may now share.
        let mut rebuilt: Vec<PathBuf> = Vec::new();
        for root in roots.iter().rev() {
            let mut value = root.clone();
            rebase(&mut value);
            remember_root(&mut rebuilt, Path::new(&value));
        }
        *roots = rebuilt
            .iter()
            .map(|root| root.to_string_lossy().into_owned())
            .collect();
    }
}

/// `path` moved from inside `from` to the same place inside `to`, or None
/// when it is not inside `from`. A path written with `.` or `..` is left
/// alone rather than guessed at; the engine never stores one.
fn rebased(path: &Path, from: &Path, to: &Path) -> Option<PathBuf> {
    let plain = |value: &Path| {
        value.components().all(|component| {
            matches!(
                component,
                Component::Prefix(_) | Component::RootDir | Component::Normal(_)
            )
        })
    };
    if !plain(path) || !plain(from) || !is_within_approved_root(path, &[from.to_path_buf()]) {
        return None;
    }
    // Containment compares without regard to case, so the part below `from`
    // is taken by position rather than by matching its text.
    let depth = from.components().count();
    Some(
        path.components()
            .skip(depth)
            .fold(to.to_path_buf(), |joined, part| joined.join(part)),
    )
}

fn write_state(
    dir: &Path,
    index: Option<&PersistedIndex>,
    config: Option<&PersistedConfig>,
) -> Result<(), String> {
    if let Some(config) = config {
        write_json(&dir.join(CONFIG_FILE), config)?;
    }
    if let Some(index) = index {
        write_json(&dir.join(INDEX_FILE), index)?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::downloads::tests::{record, scratch_dir};

    fn write_old_state(old: &Path, roots: &[&Path], directory: Option<&Path>, record_root: &Path) {
        fs::create_dir_all(old.join(DOWNLOAD_DIR)).unwrap();
        fs::create_dir_all(old.join(VAULT_DIR)).unwrap();
        fs::write(old.join(VAULT_DIR).join("job.bin"), b"sealed").unwrap();
        let index = PersistedIndex {
            version: 1,
            entries: vec![record(record_root.to_string_lossy().into_owned())],
        };
        write_json(&old.join(INDEX_FILE), &index).unwrap();
        let config = PersistedConfig {
            directory: directory.map(|path| path.to_string_lossy().into_owned()),
            approved_roots: Some(
                roots
                    .iter()
                    .map(|root| root.to_string_lossy().into_owned())
                    .collect(),
            ),
        };
        write_json(&old.join(CONFIG_FILE), &config).unwrap();
    }

    fn stored_index(dir: &Path) -> PersistedIndex {
        serde_json::from_slice(&fs::read(dir.join(INDEX_FILE)).unwrap()).unwrap()
    }

    fn stored_config(dir: &Path) -> PersistedConfig {
        serde_json::from_slice(&fs::read(dir.join(CONFIG_FILE)).unwrap()).unwrap()
    }

    #[test]
    fn the_default_folder_moves_with_its_paths_rewritten() {
        let base = scratch_dir("relocate-default");
        let old = base.join("roaming");
        let new = base.join("local");
        let old_folder = old.join(DOWNLOAD_DIR);
        write_old_state(&old, &[&old_folder], None, &old_folder);
        fs::write(old_folder.join("movie.mkv"), b"video").unwrap();

        move_roaming_state(&old, &new).unwrap();

        let new_folder = new.join(DOWNLOAD_DIR);
        assert_eq!(fs::read(new_folder.join("movie.mkv")).unwrap(), b"video");
        assert_eq!(fs::read(new.join(VAULT_DIR).join("job.bin")).unwrap(), b"sealed");
        assert!(same_path(
            Path::new(&stored_index(&new).entries[0].root_path),
            &new_folder
        ));
        let config = stored_config(&new);
        assert_eq!(config.directory, None);
        let roots = config.approved_roots.unwrap();
        assert_eq!(roots.len(), 1);
        assert!(same_path(Path::new(&roots[0]), &new_folder));
        assert!(!old.exists(), "the emptied roaming folder is removed");
        let _ = fs::remove_dir_all(base);
    }

    #[test]
    fn a_folder_the_user_chose_elsewhere_is_left_alone() {
        let base = scratch_dir("relocate-chosen");
        let old = base.join("roaming");
        let new = base.join("local");
        let chosen = base.join("Videos").join("Halo");
        fs::create_dir_all(&chosen).unwrap();
        write_old_state(&old, &[&chosen, &old.join(DOWNLOAD_DIR)], Some(&chosen), &chosen);

        move_roaming_state(&old, &new).unwrap();

        assert!(same_path(
            Path::new(&stored_index(&new).entries[0].root_path),
            &chosen
        ));
        let config = stored_config(&new);
        assert!(same_path(Path::new(&config.directory.unwrap()), &chosen));
        let roots = config.approved_roots.unwrap();
        assert!(same_path(Path::new(&roots[0]), &chosen));
        assert!(same_path(Path::new(&roots[1]), &new.join(DOWNLOAD_DIR)));
        let _ = fs::remove_dir_all(base);
    }

    #[test]
    fn a_chosen_folder_inside_the_default_one_moves_with_it() {
        let base = scratch_dir("relocate-nested");
        let old = base.join("roaming");
        let new = base.join("local");
        let nested = old.join(DOWNLOAD_DIR).join("Films");
        fs::create_dir_all(&nested).unwrap();
        write_old_state(&old, &[&nested], Some(&nested), &nested);

        move_roaming_state(&old, &new).unwrap();

        let moved = new.join(DOWNLOAD_DIR).join("Films");
        assert!(moved.is_dir());
        assert!(same_path(
            Path::new(&stored_config(&new).directory.unwrap()),
            &moved
        ));
        let _ = fs::remove_dir_all(base);
    }

    #[test]
    fn videos_stay_put_when_the_local_folder_is_already_in_use() {
        let base = scratch_dir("relocate-occupied");
        let old = base.join("roaming");
        let new = base.join("local");
        let old_folder = old.join(DOWNLOAD_DIR);
        write_old_state(&old, &[&old_folder], None, &old_folder);
        fs::write(old_folder.join("movie.mkv"), b"video").unwrap();
        fs::create_dir_all(new.join(DOWNLOAD_DIR)).unwrap();
        fs::write(new.join(DOWNLOAD_DIR).join("other.mkv"), b"other").unwrap();

        move_roaming_state(&old, &new).unwrap();

        assert!(old_folder.join("movie.mkv").exists());
        assert!(same_path(
            Path::new(&stored_index(&new).entries[0].root_path),
            &old_folder
        ));
        assert!(!old.join(INDEX_FILE).exists());
        let _ = fs::remove_dir_all(base);
    }

    #[test]
    fn a_repeat_after_an_interrupted_move_finishes_it() {
        let base = scratch_dir("relocate-resume");
        let old = base.join("roaming");
        let new = base.join("local");
        let old_folder = old.join(DOWNLOAD_DIR);
        write_old_state(&old, &[&old_folder], None, &old_folder);
        // An earlier launch renamed the folder and stopped before the index.
        fs::create_dir_all(&new).unwrap();
        fs::rename(&old_folder, new.join(DOWNLOAD_DIR)).unwrap();

        move_roaming_state(&old, &new).unwrap();

        assert!(same_path(
            Path::new(&stored_index(&new).entries[0].root_path),
            &new.join(DOWNLOAD_DIR)
        ));
        let _ = fs::remove_dir_all(base);
    }

    #[test]
    fn existing_local_state_is_never_overwritten() {
        let base = scratch_dir("relocate-existing");
        let old = base.join("roaming");
        let new = base.join("local");
        let old_folder = old.join(DOWNLOAD_DIR);
        write_old_state(&old, &[&old_folder], None, &old_folder);
        fs::create_dir_all(&new).unwrap();
        fs::write(new.join(INDEX_FILE), br#"{"version":1,"entries":[]}"#).unwrap();

        move_roaming_state(&old, &new).unwrap();

        assert!(stored_index(&new).entries.is_empty());
        assert!(old.join(INDEX_FILE).exists());
        assert!(old_folder.exists());
        let _ = fs::remove_dir_all(base);
    }

    #[test]
    fn an_unreadable_index_stops_the_move_before_anything_changes() {
        let base = scratch_dir("relocate-unreadable");
        let old = base.join("roaming");
        let new = base.join("local");
        fs::create_dir_all(old.join(DOWNLOAD_DIR)).unwrap();
        fs::write(old.join(INDEX_FILE), b"not json").unwrap();

        assert!(move_roaming_state(&old, &new).is_err());
        assert!(old.join(DOWNLOAD_DIR).exists());
        assert!(!new.join(INDEX_FILE).exists());
        let _ = fs::remove_dir_all(base);
    }

    #[test]
    fn the_same_folder_is_not_moved_onto_itself() {
        let base = scratch_dir("relocate-same");
        write_old_state(&base, &[&base.join(DOWNLOAD_DIR)], None, &base.join(DOWNLOAD_DIR));

        move_roaming_state(&base, &base).unwrap();

        assert!(base.join(INDEX_FILE).exists());
        assert!(base.join(DOWNLOAD_DIR).exists());
        let _ = fs::remove_dir_all(base);
    }

    #[test]
    fn rebasing_ignores_case_and_leaves_outside_paths_alone() {
        let from = Path::new(r"C:\Users\Dev\AppData\Roaming\halo\downloads");
        let to = Path::new(r"C:\Users\Dev\AppData\Local\halo\downloads");
        assert_eq!(
            rebased(Path::new(r"c:\users\dev\appdata\roaming\HALO\Downloads\Films"), from, to),
            Some(PathBuf::from(r"C:\Users\Dev\AppData\Local\halo\downloads\Films"))
        );
        assert_eq!(rebased(Path::new(r"D:\Halo"), from, to), None);
        assert_eq!(
            rebased(Path::new(r"C:\Users\Dev\AppData\Roaming\halo\downloads2"), from, to),
            None
        );
        assert_eq!(
            rebased(Path::new(r"C:\Users\Dev\AppData\Roaming\halo\downloads\..\x"), from, to),
            None
        );
    }
}
