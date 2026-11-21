//! Where downloads go when the user has not chosen a folder, and the one-time
//! move out of the folder earlier builds used.
//!
//! Earlier builds kept the default folder inside the app's local data folder.
//! The uninstaller's "Delete the application data" option removes that folder
//! whole, so it took the user's videos with it. The default is now the user's
//! Videos folder plus `Halo`, or `Halo Downloads` in their profile when Videos
//! is synced by OneDrive (videos are large, and would quietly upload). The
//! index, the request vault and the settings stay in app data: they are app
//! data.
//!
//! The old folder moves at launch, before any transfer can run, as one
//! rename: instant on one drive, and it keeps every video, part file and
//! segment table together. When it cannot be renamed (Videos is on another
//! drive, or something already sits at the new place) nothing is copied or
//! deleted: the files stay where they are and keep working, and only new
//! downloads go to the new default.

use super::containment::{is_within_approved_root, same_path};
use super::relocate::{move_folder, rebased};
use super::DownloadRecord;
use std::collections::HashMap;
use std::path::{Path, PathBuf};

/// The new default's name inside the Videos folder.
const VIDEOS_SUBFOLDER: &str = "Halo";
/// The new default's name inside the profile, when Videos is synced.
const PROFILE_SUBFOLDER: &str = "Halo Downloads";
/// The variables OneDrive sets to the folders it syncs.
const CLOUD_ROOT_VARIABLES: [&str; 3] = ["OneDrive", "OneDriveConsumer", "OneDriveCommercial"];

/// The folders OneDrive syncs for this user, from its environment variables.
pub fn cloud_roots_from_env() -> Vec<PathBuf> {
    CLOUD_ROOT_VARIABLES
        .iter()
        .filter_map(|name| std::env::var_os(name))
        .map(PathBuf::from)
        .filter(|path| path.is_absolute())
        .collect()
}

/// The default downloads folder: Videos plus `Halo`, unless Videos lies in a
/// synced folder, then the profile plus `Halo Downloads`. None when neither
/// known folder resolves; the caller then keeps the old default.
pub fn choose(videos: Option<PathBuf>, home: Option<PathBuf>, cloud_roots: &[PathBuf]) -> Option<PathBuf> {
    let in_profile = || home.as_ref().map(|home| home.join(PROFILE_SUBFOLDER));
    match videos {
        Some(videos) if videos.is_absolute() => {
            if is_within_approved_root(&videos, cloud_roots) {
                in_profile()
            } else {
                Some(videos.join(VIDEOS_SUBFOLDER))
            }
        }
        _ => in_profile(),
    }
    .filter(|path| path.is_absolute())
}

/// Moves the old default folder `legacy` to `new` and points the records
/// inside it there. Safe to repeat: a launch that renamed the folder and
/// stopped before the index was saved finds `legacy` gone and `new` in place,
/// and points the records there then. Records change only once their files
/// are at the new place. Returns whether any record changed.
pub fn move_legacy(legacy: &Path, new: &Path, entries: &mut HashMap<String, DownloadRecord>) -> bool {
    if same_path(legacy, new) || is_within_approved_root(new, &[legacy.to_path_buf()]) {
        return false;
    }
    move_folder(legacy, new);
    if legacy.exists() || !new.is_dir() {
        return false;
    }
    let mut changed = false;
    let mut rebase = |value: &mut String| {
        if let Some(path) = rebased(Path::new(value.as_str()), legacy, new) {
            *value = path.to_string_lossy().into_owned();
            changed = true;
        }
    };
    for entry in entries.values_mut() {
        rebase(&mut entry.root_path);
        if let Some(backup) = entry.replacement.as_mut() {
            rebase(&mut backup.root_path);
        }
    }
    changed
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::downloads::tests::record;
    use std::fs;

    fn scratch(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("halo-default-folder-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    fn entries_in(root: &Path) -> HashMap<String, DownloadRecord> {
        let entry = record(root.to_string_lossy().into_owned());
        [(entry.job_id.clone(), entry)].into()
    }

    #[test]
    fn the_default_is_videos_unless_it_is_synced() {
        let videos = PathBuf::from(r"C:\Users\a\Videos");
        let home = PathBuf::from(r"C:\Users\a");
        assert_eq!(
            choose(Some(videos.clone()), Some(home.clone()), &[]),
            Some(videos.join("Halo"))
        );
        let synced = [PathBuf::from(r"C:\Users\a\OneDrive")];
        assert_eq!(
            choose(Some(PathBuf::from(r"C:\Users\a\OneDrive\Videos")), Some(home.clone()), &synced),
            Some(home.join("Halo Downloads"))
        );
        assert_eq!(choose(None, Some(home.clone()), &[]), Some(home.join("Halo Downloads")));
        assert_eq!(choose(None, None, &[]), None);
    }

    #[test]
    fn the_old_folder_moves_whole_and_its_records_follow() {
        let base = scratch("move");
        let legacy = base.join("appdata").join("downloads");
        let new = base.join("Videos").join("Halo");
        fs::create_dir_all(&legacy).unwrap();
        fs::write(legacy.join("movie.mkv"), b"video").unwrap();
        fs::write(legacy.join("other.mkv.part"), b"part").unwrap();
        fs::write(legacy.join("other.mkv.part.segments"), b"plan").unwrap();
        fs::create_dir_all(new.parent().unwrap()).unwrap();
        let mut entries = entries_in(&legacy);

        assert!(move_legacy(&legacy, &new, &mut entries));

        assert!(!legacy.exists());
        for name in ["movie.mkv", "other.mkv.part", "other.mkv.part.segments"] {
            assert!(new.join(name).is_file(), "{name} moved with the folder");
        }
        assert!(same_path(Path::new(&entries.values().next().unwrap().root_path), &new));
        let _ = fs::remove_dir_all(base);
    }

    #[test]
    fn an_interrupted_move_is_finished_by_the_next_launch() {
        let base = scratch("interrupted");
        let legacy = base.join("appdata").join("downloads");
        let new = base.join("Videos").join("Halo");
        // The folder was renamed, then the app stopped before saving the index.
        fs::create_dir_all(&new).unwrap();
        fs::write(new.join("movie.mkv"), b"video").unwrap();
        let mut entries = entries_in(&legacy);

        assert!(move_legacy(&legacy, &new, &mut entries));
        assert!(same_path(Path::new(&entries.values().next().unwrap().root_path), &new));
        let _ = fs::remove_dir_all(base);
    }

    #[test]
    fn a_folder_that_cannot_be_renamed_is_left_working_where_it_is() {
        let base = scratch("blocked");
        let legacy = base.join("appdata").join("downloads");
        let new = base.join("Videos").join("Halo");
        fs::create_dir_all(&legacy).unwrap();
        fs::write(legacy.join("movie.mkv"), b"video").unwrap();
        // Something of the user's already sits at the new place.
        fs::create_dir_all(&new).unwrap();
        fs::write(new.join("theirs.txt"), b"keep").unwrap();
        let mut entries = entries_in(&legacy);

        assert!(!move_legacy(&legacy, &new, &mut entries));

        assert!(legacy.join("movie.mkv").is_file());
        assert!(new.join("theirs.txt").is_file());
        assert!(same_path(Path::new(&entries.values().next().unwrap().root_path), &legacy));
        let _ = fs::remove_dir_all(base);
    }

    #[test]
    fn records_outside_the_old_folder_are_left_alone() {
        let base = scratch("chosen");
        let legacy = base.join("appdata").join("downloads");
        let new = base.join("Videos").join("Halo");
        let chosen = base.join("D-Films");
        fs::create_dir_all(&legacy).unwrap();
        fs::create_dir_all(new.parent().unwrap()).unwrap();
        let mut entries = entries_in(&chosen);

        assert!(!move_legacy(&legacy, &new, &mut entries));
        assert!(same_path(Path::new(&entries.values().next().unwrap().root_path), &chosen));
        let _ = fs::remove_dir_all(base);
    }
}
