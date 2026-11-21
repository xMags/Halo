//! The pictures saved beside a finished video so its card needs no network:
//! the title's logo and its landscape art. Named after the video, in the names
//! Kodi and Jellyfin read, so each video owns its own pair and no two
//! downloads ever share one.
//!
//! Nothing from the network is written as it came: a picture is decoded by
//! Windows Imaging Component (so anything that is not a real image is
//! refused), scaled down to what a card needs, and encoded anew as PNG (the
//! logo, which keeps its transparency) or JPEG (the art).

use futures_util::StreamExt;
use std::path::{Path, PathBuf};
use std::time::Duration;
use url::Url;

/// The most a picture may weigh, fetched or saved.
pub const MAX_SIDECAR_BYTES: usize = 10 * 1024 * 1024;
/// The widest a saved picture is kept: twice the card's width, for a dense display.
const LOGO_MAX_WIDTH: u32 = 800;
const ART_MAX_WIDTH: u32 = 1280;
const ART_JPEG_QUALITY: f32 = 0.88;
const FETCH_TIMEOUT: Duration = Duration::from_secs(30);
const MAX_REDIRECTS: usize = 5;
const METAHUB_LOGO_PREFIX: &str = "https://images.metahub.space/logo/medium/";

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum SidecarKind {
    /// `<video stem>-clearlogo.png`
    Logo,
    /// `<video stem>-fanart.jpg`
    Art,
}

impl SidecarKind {
    pub const ALL: [SidecarKind; 2] = [SidecarKind::Logo, SidecarKind::Art];

    fn suffix(self) -> &'static str {
        match self {
            SidecarKind::Logo => "-clearlogo.png",
            SidecarKind::Art => "-fanart.jpg",
        }
    }

    pub fn parse(value: &str) -> Option<SidecarKind> {
        match value {
            "logo" => Some(SidecarKind::Logo),
            "art" => Some(SidecarKind::Art),
            _ => None,
        }
    }
}

/// Where a video's picture is saved: beside it, named after its stem.
pub fn sidecar_path(root: &Path, file_name: &str, kind: SidecarKind) -> Option<PathBuf> {
    let stem = Path::new(file_name).file_stem()?.to_str()?;
    if stem.is_empty() {
        return None;
    }
    Some(root.join(format!("{stem}{}", kind.suffix())))
}

/// Where a picture is written before it is renamed over [`sidecar_path`].
pub fn sidecar_temp_path(path: &Path) -> PathBuf {
    let mut value = path.as_os_str().to_owned();
    value.push(".tmp");
    PathBuf::from(value)
}

/// The same logo Stremio shows, by the title's IMDb id. Other ids have none.
pub fn metahub_logo_url(meta_id: &str) -> Option<String> {
    let digits = meta_id.strip_prefix("tt")?;
    let valid = (1..=12).contains(&digits.len()) && digits.bytes().all(|b| b.is_ascii_digit());
    valid.then(|| format!("{METAHUB_LOGO_PREFIX}{meta_id}/img"))
}

/// Only https addresses with a host are fetched.
pub fn is_sidecar_source_url(url: &str) -> bool {
    !url.is_empty()
        && url.len() <= 4096
        && Url::parse(url).is_ok_and(|parsed| parsed.scheme() == "https" && parsed.host_str().is_some())
}

/// The client pictures are fetched with: https at every hop, a few redirects
/// at most, and a deadline, so a picture can never hold a download up.
pub fn sidecar_client() -> Result<reqwest::Client, String> {
    reqwest::Client::builder()
        .redirect(reqwest::redirect::Policy::custom(|attempt| {
            if attempt.previous().len() >= MAX_REDIRECTS || attempt.url().scheme() != "https" {
                attempt.stop()
            } else {
                attempt.follow()
            }
        }))
        .connect_timeout(FETCH_TIMEOUT)
        .timeout(FETCH_TIMEOUT)
        .build()
        .map_err(|error| format!("picture client unavailable: {error}"))
}

/// Fetches a picture and turns it into the bytes to save. Fails on any
/// address other than https, a body over [`MAX_SIDECAR_BYTES`], a failed
/// request or an undecodable image.
pub async fn fetch_sidecar(client: &reqwest::Client, url: &str, kind: SidecarKind) -> Result<Vec<u8>, String> {
    if !is_sidecar_source_url(url) {
        return Err("The picture's address is not one to fetch.".into());
    }
    let response = client.get(url).send().await.map_err(|error| error.to_string())?;
    if !response.status().is_success() {
        return Err(format!("The picture answered {}.", response.status()));
    }
    if response
        .content_length()
        .is_some_and(|length| length > MAX_SIDECAR_BYTES as u64)
    {
        return Err("The picture is too large.".into());
    }
    let mut body = Vec::new();
    let mut stream = response.bytes_stream();
    while let Some(chunk) = stream.next().await {
        let chunk = chunk.map_err(|error| error.to_string())?;
        if body.len() + chunk.len() > MAX_SIDECAR_BYTES {
            return Err("The picture is too large.".into());
        }
        body.extend_from_slice(&chunk);
    }
    if body.is_empty() {
        return Err("The picture was empty.".into());
    }
    tokio::task::spawn_blocking(move || transcode(&body, kind))
        .await
        .map_err(|error| error.to_string())?
}

/// Decodes `bytes`, scales the picture down to the kind's width, and encodes
/// it anew. Runs COM, so it belongs on a blocking thread.
pub fn transcode(bytes: &[u8], kind: SidecarKind) -> Result<Vec<u8>, String> {
    let _com = wic::ComApartment::enter()?;
    let output = wic::transcode(bytes, kind).map_err(|error| format!("The picture could not be read: {error}"))?;
    if output.is_empty() || output.len() > MAX_SIDECAR_BYTES {
        return Err("The saved picture came out empty or too large.".into());
    }
    Ok(output)
}

mod wic {
    use super::{SidecarKind, ART_JPEG_QUALITY, ART_MAX_WIDTH, LOGO_MAX_WIDTH, MAX_SIDECAR_BYTES};
    use windows::core::{Interface, PWSTR};
    use windows::Win32::Foundation::HGLOBAL;
    use windows::Win32::Graphics::Imaging::{
        CLSID_WICImagingFactory, GUID_ContainerFormatJpeg, GUID_ContainerFormatPng, GUID_WICPixelFormat24bppBGR,
        GUID_WICPixelFormat32bppBGRA, IWICBitmapSource, IWICImagingFactory, WICBitmapDitherTypeNone,
        WICBitmapEncoderNoCache, WICBitmapInterpolationModeFant, WICBitmapPaletteTypeCustom,
        WICDecodeMetadataCacheOnDemand,
    };
    use windows::Win32::System::Com::StructuredStorage::{CreateStreamOnHGlobal, IPropertyBag2, PROPBAG2};
    use windows::Win32::System::Com::{
        CoCreateInstance, CoInitializeEx, CoUninitialize, IStream, CLSCTX_INPROC_SERVER, COINIT_MULTITHREADED,
        STATFLAG_NONAME, STATSTG, STREAM_SEEK_SET,
    };
    use windows::Win32::System::Variant::{VARIANT, VT_R4};

    /// COM for the current thread, for as long as this lives.
    pub struct ComApartment {
        initialized: bool,
    }

    impl ComApartment {
        pub fn enter() -> Result<Self, String> {
            // S_FALSE (already initialised here) still has to be balanced;
            // RPC_E_CHANGED_MODE means another apartment owns the thread, which
            // works for WIC without an uninitialise of our own.
            let result = unsafe { CoInitializeEx(None, COINIT_MULTITHREADED) };
            Ok(Self {
                initialized: result.is_ok(),
            })
        }
    }

    impl Drop for ComApartment {
        fn drop(&mut self) {
            if self.initialized {
                unsafe { CoUninitialize() };
            }
        }
    }

    pub fn transcode(bytes: &[u8], kind: SidecarKind) -> windows::core::Result<Vec<u8>> {
        unsafe {
            let factory: IWICImagingFactory = CoCreateInstance(&CLSID_WICImagingFactory, None, CLSCTX_INPROC_SERVER)?;
            let input = factory.CreateStream()?;
            input.InitializeFromMemory(bytes)?;
            let decoder = factory.CreateDecoderFromStream(&input, std::ptr::null(), WICDecodeMetadataCacheOnDemand)?;
            let frame = decoder.GetFrame(0)?;
            let (mut width, mut height) = (0u32, 0u32);
            frame.GetSize(&mut width, &mut height)?;
            if width == 0 || height == 0 {
                return Err(windows::core::Error::from_hresult(windows::Win32::Foundation::E_INVALIDARG));
            }
            let limit = match kind {
                SidecarKind::Logo => LOGO_MAX_WIDTH,
                SidecarKind::Art => ART_MAX_WIDTH,
            };
            let scaled_width = width.min(limit);
            let scaled_height = ((u64::from(height) * u64::from(scaled_width)) / u64::from(width)).max(1) as u32;

            // The logo keeps its transparency; the art is opaque, as JPEG must be.
            let format = match kind {
                SidecarKind::Logo => GUID_WICPixelFormat32bppBGRA,
                SidecarKind::Art => GUID_WICPixelFormat24bppBGR,
            };
            let converter = factory.CreateFormatConverter()?;
            converter.Initialize(&frame, &format, WICBitmapDitherTypeNone, None, 0.0, WICBitmapPaletteTypeCustom)?;
            let scaler = factory.CreateBitmapScaler()?;
            scaler.Initialize(&converter, scaled_width, scaled_height, WICBitmapInterpolationModeFant)?;
            let source: IWICBitmapSource = scaler.cast()?;

            let output: IStream = CreateStreamOnHGlobal(HGLOBAL::default(), true)?;
            let container = match kind {
                SidecarKind::Logo => GUID_ContainerFormatPng,
                SidecarKind::Art => GUID_ContainerFormatJpeg,
            };
            let encoder = factory.CreateEncoder(&container, std::ptr::null())?;
            encoder.Initialize(&output, WICBitmapEncoderNoCache)?;
            let mut target = None;
            let mut options: Option<IPropertyBag2> = None;
            encoder.CreateNewFrame(&mut target, &mut options)?;
            let target = target.ok_or_else(|| windows::core::Error::from_hresult(windows::Win32::Foundation::E_FAIL))?;
            if kind == SidecarKind::Art {
                if let Some(options) = options.as_ref() {
                    let mut name: Vec<u16> = "ImageQuality".encode_utf16().chain(std::iter::once(0)).collect();
                    let property = PROPBAG2 {
                        pstrName: PWSTR(name.as_mut_ptr()),
                        ..Default::default()
                    };
                    let mut value = VARIANT::default();
                    (*value.Anonymous.Anonymous).vt = VT_R4;
                    (*value.Anonymous.Anonymous).Anonymous.fltVal = ART_JPEG_QUALITY;
                    options.Write(1, &property, &value)?;
                }
            }
            target.Initialize(options.as_ref())?;
            target.SetSize(scaled_width, scaled_height)?;
            let mut written_format = format;
            target.SetPixelFormat(&mut written_format)?;
            target.WriteSource(&source, std::ptr::null())?;
            target.Commit()?;
            encoder.Commit()?;

            let mut stat = STATSTG::default();
            output.Stat(&mut stat, STATFLAG_NONAME)?;
            let size = usize::try_from(stat.cbSize).unwrap_or(usize::MAX);
            if size == 0 || size > MAX_SIDECAR_BYTES {
                return Err(windows::core::Error::from_hresult(windows::Win32::Foundation::E_FAIL));
            }
            output.Seek(0, STREAM_SEEK_SET, None)?;
            let mut result = vec![0u8; size];
            let mut read = 0u32;
            output.Read(result.as_mut_ptr().cast(), size as u32, Some(&mut read)).ok()?;
            result.truncate(read as usize);
            Ok(result)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pictures_are_named_after_the_video_beside_it() {
        let root = Path::new(r"C:\Halo\Severance");
        assert_eq!(
            sidecar_path(root, "S02E03-abc-def.mkv", SidecarKind::Logo),
            Some(root.join("S02E03-abc-def-clearlogo.png"))
        );
        assert_eq!(
            sidecar_path(root, "S02E03-abc-def.mkv", SidecarKind::Art),
            Some(root.join("S02E03-abc-def-fanart.jpg"))
        );
        assert_eq!(
            sidecar_temp_path(&root.join("a-fanart.jpg")),
            root.join("a-fanart.jpg.tmp")
        );
        assert_eq!(sidecar_path(root, "", SidecarKind::Art), None);
    }

    #[test]
    fn only_imdb_ids_have_a_metahub_logo() {
        assert_eq!(
            metahub_logo_url("tt0133093").as_deref(),
            Some("https://images.metahub.space/logo/medium/tt0133093/img")
        );
        assert_eq!(metahub_logo_url("kitsu:1"), None);
        assert_eq!(metahub_logo_url("tt"), None);
        assert_eq!(metahub_logo_url("tt12/../x"), None);
    }

    #[test]
    fn only_https_addresses_are_fetched() {
        assert!(is_sidecar_source_url("https://image.tmdb.org/t/p/w1280/a.jpg"));
        assert!(!is_sidecar_source_url("http://image.tmdb.org/a.jpg"));
        assert!(!is_sidecar_source_url("file:///C:/Windows/win.ini"));
        assert!(!is_sidecar_source_url(""));
        assert!(!is_sidecar_source_url(&format!("https://a.test/{}", "x".repeat(5000))));
    }

    /// A small opaque PNG written by WIC itself, so the test needs no fixture file.
    fn sample_png(width: u32, height: u32) -> Vec<u8> {
        use windows::Win32::Graphics::Imaging::{
            CLSID_WICImagingFactory, GUID_ContainerFormatPng, GUID_WICPixelFormat32bppBGRA, IWICImagingFactory,
            WICBitmapEncoderNoCache,
        };
        use windows::Win32::Foundation::HGLOBAL;
        use windows::Win32::System::Com::StructuredStorage::CreateStreamOnHGlobal;
        use windows::Win32::System::Com::{CoCreateInstance, CLSCTX_INPROC_SERVER, STATFLAG_NONAME, STATSTG, STREAM_SEEK_SET};
        let _com = wic::ComApartment::enter().unwrap();
        unsafe {
            let factory: IWICImagingFactory =
                CoCreateInstance(&CLSID_WICImagingFactory, None, CLSCTX_INPROC_SERVER).unwrap();
            let pixels: Vec<u8> = (0..width * height).flat_map(|i| [i as u8, 0x40, 0x80, 0xff]).collect();
            let bitmap = factory
                .CreateBitmapFromMemory(width, height, &GUID_WICPixelFormat32bppBGRA, width * 4, &pixels)
                .unwrap();
            let stream = CreateStreamOnHGlobal(HGLOBAL::default(), true).unwrap();
            let encoder = factory.CreateEncoder(&GUID_ContainerFormatPng, std::ptr::null()).unwrap();
            encoder.Initialize(&stream, WICBitmapEncoderNoCache).unwrap();
            let mut frame = None;
            encoder.CreateNewFrame(&mut frame, std::ptr::null_mut()).unwrap();
            let frame = frame.unwrap();
            frame.Initialize(None).unwrap();
            frame.WriteSource(&bitmap, std::ptr::null()).unwrap();
            frame.Commit().unwrap();
            encoder.Commit().unwrap();
            let mut stat = STATSTG::default();
            stream.Stat(&mut stat, STATFLAG_NONAME).unwrap();
            stream.Seek(0, STREAM_SEEK_SET, None).unwrap();
            let mut bytes = vec![0u8; stat.cbSize as usize];
            let mut read = 0u32;
            stream.Read(bytes.as_mut_ptr().cast(), bytes.len() as u32, Some(&mut read)).ok().unwrap();
            bytes.truncate(read as usize);
            bytes
        }
    }

    #[test]
    fn a_picture_is_written_anew_as_png_or_jpeg() {
        let png = sample_png(1600, 900);
        let logo = transcode(&png, SidecarKind::Logo).unwrap();
        assert!(logo.starts_with(b"\x89PNG\r\n\x1a\n"));
        let art = transcode(&png, SidecarKind::Art).unwrap();
        assert!(art.starts_with(&[0xff, 0xd8, 0xff]));
        // Scaled to the art's width: the JPEG's frame header names 1280 x 720.
        let width_at = art.windows(2).position(|pair| pair == [0xff, 0xc0]).unwrap() + 7;
        assert_eq!(u16::from_be_bytes([art[width_at], art[width_at + 1]]), 1280);
        assert_eq!(u16::from_be_bytes([art[width_at - 2], art[width_at - 1]]), 720);
    }

    #[test]
    fn anything_that_is_not_an_image_is_refused() {
        assert!(transcode(b"<html>not a picture</html>", SidecarKind::Art).is_err());
        assert!(transcode(&[], SidecarKind::Logo).is_err());
    }
}
