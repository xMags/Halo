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
use super::{write_json, DownloadRecord};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::fs;
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

/// What launch did with the old default folder.
#[derive(Debug, PartialEq, Eq)]
pub enum MoveOutcome {
    /// Nothing to move, or it could not be moved; every record is as it was.
    Stayed,
    /// The folder is at `to`. `changed` says whether any record was pointed
    /// there this time. Once the index is saved the caller calls
    /// [finish_move]; until then every launch points the records there again.
    Moved { to: PathBuf, changed: bool },
}

#[derive(Serialize, Deserialize)]
struct MoveMarker {
    to: String,
}

/// Moves the old default folder `legacy` to `new` and points the records
/// whose files went with it there.
///
/// The destination is written to `marker` (durably) before the rename, and
/// an interrupted move is finished only toward that recorded place, never
/// toward whatever the default happens to be on a later launch, and never
/// into a folder this code did not move the old one into.
pub fn move_legacy(
    legacy: &Path,
    new: &Path,
    marker: &Path,
    entries: &mut HashMap<String, DownloadRecord>,
) -> MoveOutcome {
    let (destination, recorded) = match read_marker(marker) {
        Some(recorded) => (recorded, true),
        None => {
            if !legacy.is_dir()
                || same_path(legacy, new)
                || is_within_approved_root(new, &[legacy.to_path_buf()])
            {
                return MoveOutcome::Stayed;
            }
            let document = MoveMarker {
                to: new.to_string_lossy().into_owned(),
            };
            if write_json(marker, &document).is_err() {
                return MoveOutcome::Stayed;
            }
            (new.to_path_buf(), false)
        }
    };
    // Whether a record moves only when its file is at the destination. After
    // a clean rename the destination is the folder renamed here, which did
    // not exist or was empty before, so every record inside the old folder
    // moves, a queued one with no bytes yet included (left behind, it would
    // recreate the old folder in app data and download there).
    let mut only_with_files = false;
    if legacy.is_dir() && !move_folder(legacy, &destination) {
        // A marker written just now before a refused rename: nothing moved.
        if !recorded || !destination.is_dir() {
            let _ = fs::remove_file(marker);
            return MoveOutcome::Stayed;
        }
        // An earlier launch moved the folder, its index was never saved, and
        // the old folder has come back since. Both now hold files, so only a
        // record whose file is at the destination is pointed there; the rest
        // stay in the old folder, which stays approved while it exists.
        only_with_files = true;
    } else if legacy.exists() || !destination.is_dir() {
        let _ = fs::remove_file(marker);
        return MoveOutcome::Stayed;
    }
    let rebase = |root: &str, file_name: &str| {
        if only_with_files {
            rebased_if_present(root, file_name, legacy, &destination)
        } else {
            rebased(Path::new(root), legacy, &destination).map(|moved| moved.to_string_lossy().into_owned())
        }
    };
    let mut changed = false;
    for entry in entries.values_mut() {
        if let Some(root) = rebase(&entry.root_path, &entry.file_name) {
            entry.root_path = root;
            changed = true;
        }
        if let Some(backup) = entry.replacement.as_mut() {
            if let Some(root) = rebase(&backup.root_path, &backup.file_name) {
                backup.root_path = root;
                changed = true;
            }
        }
    }
    MoveOutcome::Moved {
        to: destination,
        changed,
    }
}

/// Forgets a finished move, once the index naming the new place is saved.
pub fn finish_move(marker: &Path) {
    let _ = fs::remove_file(marker);
}

fn read_marker(marker: &Path) -> Option<PathBuf> {
    let raw = fs::read(marker).ok()?;
    let document: MoveMarker = serde_json::from_slice(&raw).ok()?;
    Some(PathBuf::from(document.to)).filter(|path| path.is_absolute())
}

/// `root` moved from `legacy` to `destination`, when it lay inside `legacy`
/// and the record's file (or its part file) is at the new place.
fn rebased_if_present(root: &str, file_name: &str, legacy: &Path, destination: &Path) -> Option<String> {
    let moved = rebased(Path::new(root), legacy, destination)?;
    let target = moved.join(file_name);
    let mut part = target.clone().into_os_string();
    part.push(".part");
    (target.is_file() || Path::new(&part).is_file()).then(|| moved.to_string_lossy().into_owned())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::downloads::tests::record;

    fn scratch(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("halo-default-folder-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    /// One record named `file` in `root`.
    fn entries_in(root: &Path, file: &str) -> HashMap<String, DownloadRecord> {
        let mut entry = record(root.to_string_lossy().into_owned());
        entry.file_name = file.to_string();
        [(entry.job_id.clone(), entry)].into()
    }

    fn root_of(entries: &HashMap<String, DownloadRecord>) -> PathBuf {
        PathBuf::from(&entries.values().next().unwrap().root_path)
    }

    struct Layout {
        base: PathBuf,
        legacy: PathBuf,
        new: PathBuf,
        marker: PathBuf,
    }

    fn layout(name: &str) -> Layout {
        let base = scratch(name);
        let legacy = base.join("appdata").join("downloads");
        let new = base.join("Videos").join("Halo");
        let marker = base.join("appdata").join("moved.json");
        fs::create_dir_all(base.join("appdata")).unwrap();
        fs::create_dir_all(base.join("Videos")).unwrap();
        Layout { base, legacy, new, marker }
    }

    #[test]
    fn the_default_is_videos_unless_it_is_synced() {
        let videos = PathBuf::from(r"C:\Users\a\Videos");
        let home = PathBuf::from(r"C:\Users\a");
        assert_eq!(choose(Some(videos.clone()), Some(home.clone()), &[]), Some(videos.join("Halo")));
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
        let at = layout("move");
        fs::create_dir_all(&at.legacy).unwrap();
        fs::write(at.legacy.join("movie.mkv"), b"video").unwrap();
        fs::write(at.legacy.join("other.mkv.part"), b"part").unwrap();
        fs::write(at.legacy.join("other.mkv.part.segments"), b"plan").unwrap();
        let mut entries = entries_in(&at.legacy, "movie.mkv");

        let outcome = move_legacy(&at.legacy, &at.new, &at.marker, &mut entries);

        assert_eq!(outcome, MoveOutcome::Moved { to: at.new.clone(), changed: true });
        assert!(!at.legacy.exists());
        for name in ["movie.mkv", "other.mkv.part", "other.mkv.part.segments"] {
            assert!(at.new.join(name).is_file(), "{name} moved with the folder");
        }
        assert!(same_path(&root_of(&entries), &at.new));
        assert!(at.marker.is_file(), "kept until the index is saved");
        finish_move(&at.marker);
        assert!(!at.marker.exists());
        let _ = fs::remove_dir_all(at.base);
    }

    #[test]
    fn an_interrupted_move_is_finished_only_toward_where_it_went() {
        let at = layout("interrupted");
        fs::create_dir_all(&at.legacy).unwrap();
        fs::write(at.legacy.join("movie.mkv"), b"video").unwrap();
        let mut first = entries_in(&at.legacy, "movie.mkv");
        move_legacy(&at.legacy, &at.new, &at.marker, &mut first);
        // The index save failed; on the next launch the default resolves elsewhere.
        let elsewhere = at.base.join("Profile").join("Halo Downloads");
        let mut entries = entries_in(&at.legacy, "movie.mkv");

        let outcome = move_legacy(&at.legacy, &elsewhere, &at.marker, &mut entries);

        assert_eq!(outcome, MoveOutcome::Moved { to: at.new.clone(), changed: true });
        assert!(same_path(&root_of(&entries), &at.new));
        let _ = fs::remove_dir_all(at.base);
    }

    #[test]
    fn an_old_folder_deleted_by_hand_rebases_nothing_into_the_users_folder() {
        let at = layout("deleted");
        // No marker: this code never moved anything there.
        fs::create_dir_all(&at.new).unwrap();
        fs::write(at.new.join("movie.mkv"), b"a file of the user").unwrap();
        let mut entries = entries_in(&at.legacy, "movie.mkv");

        assert_eq!(move_legacy(&at.legacy, &at.new, &at.marker, &mut entries), MoveOutcome::Stayed);
        assert!(same_path(&root_of(&entries), &at.legacy));
        let _ = fs::remove_dir_all(at.base);
    }

    #[test]
    fn a_queued_record_with_no_bytes_yet_moves_with_the_folder() {
        let at = layout("queued");
        fs::create_dir_all(&at.legacy).unwrap();
        let mut entries = entries_in(&at.legacy, "queued.mkv");

        let outcome = move_legacy(&at.legacy, &at.new, &at.marker, &mut entries);

        assert_eq!(outcome, MoveOutcome::Moved { to: at.new.clone(), changed: true });
        assert!(same_path(&root_of(&entries), &at.new));
        let _ = fs::remove_dir_all(at.base);
    }

    #[test]
    fn an_old_folder_that_came_back_moves_only_records_whose_files_went() {
        let at = layout("returned");
        fs::create_dir_all(&at.legacy).unwrap();
        fs::write(at.legacy.join("moved.mkv"), b"video").unwrap();
        let mut first = entries_in(&at.legacy, "moved.mkv");
        move_legacy(&at.legacy, &at.new, &at.marker, &mut first);
        // That run's index saves all failed, and the old folder was made again.
        fs::create_dir_all(&at.legacy).unwrap();
        fs::write(at.legacy.join("stayed.mkv"), b"video").unwrap();
        let mut moved = record(at.legacy.to_string_lossy().into_owned());
        moved.job_id = "moved".into();
        moved.file_name = "moved.mkv".into();
        let mut stayed = record(at.legacy.to_string_lossy().into_owned());
        stayed.job_id = "stayed".into();
        stayed.file_name = "stayed.mkv".into();
        let mut entries: HashMap<String, DownloadRecord> =
            [("moved".to_string(), moved), ("stayed".to_string(), stayed)].into();

        let outcome = move_legacy(&at.legacy, &at.new, &at.marker, &mut entries);

        assert_eq!(outcome, MoveOutcome::Moved { to: at.new.clone(), changed: true });
        assert!(same_path(Path::new(&entries["moved"].root_path), &at.new));
        assert!(same_path(Path::new(&entries["stayed"].root_path), &at.legacy));
        assert!(at.marker.is_file(), "kept until the index is saved");
        let _ = fs::remove_dir_all(at.base);
    }

    #[test]
    fn a_folder_that_cannot_be_renamed_is_left_working_where_it_is() {
        let at = layout("blocked");
        fs::create_dir_all(&at.legacy).unwrap();
        fs::write(at.legacy.join("movie.mkv"), b"video").unwrap();
        // Something of the user's already sits at the new place.
        fs::create_dir_all(&at.new).unwrap();
        fs::write(at.new.join("theirs.txt"), b"keep").unwrap();
        let mut entries = entries_in(&at.legacy, "movie.mkv");

        assert_eq!(move_legacy(&at.legacy, &at.new, &at.marker, &mut entries), MoveOutcome::Stayed);

        assert!(at.legacy.join("movie.mkv").is_file());
        assert!(at.new.join("theirs.txt").is_file());
        assert!(!at.marker.exists());
        assert!(same_path(&root_of(&entries), &at.legacy));
        let _ = fs::remove_dir_all(at.base);
    }

    #[test]
    fn records_outside_the_old_folder_are_left_alone() {
        let at = layout("chosen");
        let chosen = at.base.join("D-Films");
        fs::create_dir_all(&at.legacy).unwrap();
        fs::create_dir_all(&chosen).unwrap();
        fs::write(chosen.join("movie.mkv"), b"video").unwrap();
        let mut entries = entries_in(&chosen, "movie.mkv");

        let outcome = move_legacy(&at.legacy, &at.new, &at.marker, &mut entries);
        assert_eq!(outcome, MoveOutcome::Moved { to: at.new.clone(), changed: false });
        assert!(same_path(&root_of(&entries), &chosen));
        let _ = fs::remove_dir_all(at.base);
    }
}
