import Foundation
import UIKit

/// Adapted from MPVKit 0.41.0's demo `MetalLayer`. Two workarounds are
/// preserved on purpose:
///
/// - MoltenVK forcibly sets `drawableSize` to 1x1 to complete presentation;
///   letting that through causes flicker and can leave the size stuck at 1x1.
///   https://github.com/mpv-player/mpv/pull/13651
/// - EDR activation only works from the main thread.
final class MetalLayer: CAMetalLayer {
    override var drawableSize: CGSize {
        get { super.drawableSize }
        set {
            if Int(newValue.width) > 1 && Int(newValue.height) > 1 {
                super.drawableSize = newValue
            }
        }
    }

    // iOS 16+ only; below the app's 15.1 floor the property does not exist,
    // so there is nothing to guard there.
    @available(iOS 16.0, *)
    override var wantsExtendedDynamicRangeContent: Bool {
        get { super.wantsExtendedDynamicRangeContent }
        set {
            if Thread.isMainThread {
                super.wantsExtendedDynamicRangeContent = newValue
            } else {
                DispatchQueue.main.sync {
                    super.wantsExtendedDynamicRangeContent = newValue
                }
            }
        }
    }
}
