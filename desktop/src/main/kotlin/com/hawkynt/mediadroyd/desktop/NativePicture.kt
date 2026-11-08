package com.hawkynt.mediadroyd.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.window.DialogWindow
import com.hawkynt.mediadroyd.engine.desktop.GlStage
import com.hawkynt.mediadroyd.engine.desktop.PictureWindow
import com.hawkynt.mediadroyd.engine.desktop.Presentation
import com.hawkynt.mediadroyd.player.PicturePlacement
import com.sun.jna.Function
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import java.awt.Dialog
import java.awt.GraphicsDevice
import java.awt.GraphicsEnvironment
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

/**
 * The player's picture shown by the stage itself (Presentation.NATIVE, README "Platform support"): the stage's window
 * (PictureWindow) put inside the player's window as a child — an X11 child window on Linux, a Win32 child on Windows —
 * so it moves, hides and goes full screen with it; Compose's controls in a see-through window over it ([overlay]), kept
 * on the player window's content as that moves and resizes (PicturePlacement.overlay). Null from [of] where it cannot
 * be had, and the player then draws the picture read back, in its own window, as before.
 */
class NativePicture private constructor(private val owner: Window) {

    private var shown: PictureWindow? = null
    private var stage: GlStage? = null

    /** The see-through window the controls are in, once made. */
    var overlay: ComposeDialog? = null
        private set

    private val follow = object : ComponentAdapter() {
        override fun componentMoved(e: ComponentEvent) = place()
        override fun componentResized(e: ComponentEvent) = place()
        override fun componentShown(e: ComponentEvent) = place()
    }

    // The player's window taken to the front (its title bar clicked, switched to): the controls with the keys.
    private val focus = object : WindowAdapter() {
        override fun windowGainedFocus(e: WindowEvent) {
            overlay?.takeIf { it.isVisible }?.let {
                it.toFront()
                it.requestFocus()
            }
        }

        override fun windowStateChanged(e: WindowEvent) = place()
    }

    /**
     * [stage]'s own window opened and put inside the player's window, sized to its content and shown: the window the
     * stage presents in from now on. Null where it cannot be had — the player then reads back.
     */
    fun attach(stage: GlStage): PictureWindow? {
        detach()
        val opened = stage.openWindow() ?: return null
        val embedded = runCatching { WindowEmbedding.embed(opened.handle, owner) }.getOrDefault(false)
        if (!embedded) {
            stage.closeWindow()
            return null
        }
        this.stage = stage
        shown = opened
        owner.addComponentListener(follow)
        owner.addWindowFocusListener(focus)
        owner.addWindowStateListener(focus)
        place()
        opened.show()
        return opened
    }

    /** The stage's window taken out again (the player closed); the stage presents nowhere until attached again. */
    fun detach() {
        owner.removeComponentListener(follow)
        owner.removeWindowFocusListener(focus)
        owner.removeWindowStateListener(focus)
        if (shown != null) stage?.closeWindow()
        shown = null
        stage = null
    }

    /** The overlay and the picture window laid over the player window's content as it is now. */
    fun place() {
        val insets = owner.insets
        val scale = owner.graphicsConfiguration?.defaultTransform?.scaleX ?: 1.0
        val at = PicturePlacement.overlay(
            owner.x, owner.y, owner.width, owner.height, insets.top, insets.left, insets.bottom, insets.right, scale,
        )
        overlay?.setBounds(at.x, at.y, at.width.coerceAtLeast(1), at.height.coerceAtLeast(1))
        shown?.resize(at.pixelWidth, at.pixelHeight)
    }

    internal fun overlayMade(dialog: ComposeDialog) {
        overlay = dialog
        place()
    }

    companion object {
        /**
         * The native picture for the player in [owner] where it can be had: Presentation.NATIVE chosen, X11 or Win32
         * underneath, and windows that can be see-through (a compositor on Linux); else null.
         */
        fun of(owner: Window): NativePicture? {
            if (Presentation.chosen() != Presentation.NATIVE || GraphicsEnvironment.isHeadless()) return null
            if (!WindowEmbedding.supported) return null
            val device = owner.graphicsConfiguration?.device
                ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
            val translucent = GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT
            if (!device.isWindowTranslucencySupported(translucent)) return null
            // Java answers yes wherever X11 has a visual with alpha — under Xvfb, say, where nothing blends it and the
            // controls' clear pixels would come out black over the picture.
            if (!runCatching { WindowEmbedding.blended() }.getOrDefault(false)) return null
            return NativePicture(owner)
        }
    }
}

/**
 * The player's controls ([content]) in [native]'s see-through window over the picture: owned by the player's window,
 * so it stays above it and goes with it; modeless, so the player's own window keeps its title bar and close button.
 */
@Composable
fun PictureOverlay(native: NativePicture, owner: Window, content: @Composable () -> Unit) {
    DialogWindow(
        visible = true,
        onPreviewKeyEvent = { false },
        onKeyEvent = { false },
        create = {
            ComposeDialog(owner, Dialog.ModalityType.MODELESS).apply {
                title = owner.let { (it as? java.awt.Frame)?.title } ?: "MediaDroyd"
                isUndecorated = true
                isTransparent = true
                isResizable = false
                native.overlayMade(this)
            }
        },
        dispose = { it.dispose() },
    ) {
        content()
    }
    DisposableEffect(native) {
        onDispose { native.detach() }
    }
}

/**
 * A window of another toolkit's (the stage's GLFW window) made a child of an AWT window, at its content's top left:
 * XReparentWindow on X11 (Xlib's manual), SetParent with the child style on Win32 (Microsoft's documentation of
 * SetParent: WS_CHILD set, WS_POPUP cleared, before the call).
 */
internal object WindowEmbedding {
    private val os = System.getProperty("os.name")
    private val windows = os.startsWith("Windows")

    /** Whether this system's windows can be embedded: X11 (XWayland under Wayland) or Win32. */
    val supported: Boolean = windows || os.startsWith("Linux")

    /**
     * Whether see-through windows are blended over what is under them: on X11 when a compositing manager holds the
     * screen's `_NET_WM_CM_S0` selection (the EWMH's "Compositing Managers" section), on Windows always (DWM composes
     * every window since Windows 8).
     */
    fun blended(): Boolean {
        if (windows) return true
        val display = Function.getFunction("X11", "XOpenDisplay").invokePointer(arrayOf<Any?>(null)) ?: return false
        try {
            val screen = Function.getFunction("X11", "XDefaultScreen").invokeInt(arrayOf(display))
            val atom = Function.getFunction("X11", "XInternAtom")
                .invoke(NativeLong::class.java, arrayOf(display, "_NET_WM_CM_S$screen", 0)) as NativeLong
            val owner = Function.getFunction("X11", "XGetSelectionOwner")
                .invoke(NativeLong::class.java, arrayOf(display, atom)) as NativeLong
            return owner.toLong() != 0L
        } finally {
            Function.getFunction("X11", "XCloseDisplay").invokeInt(arrayOf(display))
        }
    }

    /** [child] (an X11 window or HWND) made a child of [parent]'s native window; false where that failed. */
    fun embed(child: Long, parent: Window): Boolean {
        if (child == 0L || !parent.isDisplayable) return false
        return embed(child, Native.getWindowID(parent))
    }

    /** [child] made a child of the native window [parent] (an X11 window or HWND); false where that failed. */
    fun embed(child: Long, parent: Long): Boolean {
        if (child == 0L || parent == 0L) return false
        return if (windows) win32(child, parent) else x11(child, parent)
    }

    private fun x11(child: Long, parent: Long): Boolean {
        val open = Function.getFunction("X11", "XOpenDisplay")
        val display = open.invokePointer(arrayOf<Any?>(null)) ?: return false
        try {
            Function.getFunction("X11", "XReparentWindow")
                .invokeInt(arrayOf(display, NativeLong(child), NativeLong(parent), 0, 0))
            Function.getFunction("X11", "XSync").invokeInt(arrayOf(display, 0))
        } finally {
            Function.getFunction("X11", "XCloseDisplay").invokeInt(arrayOf(display))
        }
        return true
    }

    private fun win32(child: Long, parent: Long): Boolean {
        val hwnd = Pointer(child)
        val style = Function.getFunction("user32", "GetWindowLongPtrW").invokeLong(arrayOf(hwnd, GWL_STYLE))
        val childStyle = (style and WS_POPUP.inv() and WS_CAPTION.inv() and WS_THICKFRAME.inv()) or
            WS_CHILD or WS_DISABLED or WS_CLIPSIBLINGS
        Function.getFunction("user32", "SetWindowLongPtrW").invokeLong(arrayOf(hwnd, GWL_STYLE, childStyle))
        Function.getFunction("user32", "SetParent").invokePointer(arrayOf(hwnd, Pointer(parent)))
        // SetParent answers the parent before, none for a window that was a top-level one: asked again instead.
        val now = Function.getFunction("user32", "GetParent").invokePointer(arrayOf(hwnd))
        if (now == null || Pointer.nativeValue(now) != parent) return false
        // Above Compose's own canvas, its sibling now (X11 puts a reparented window on top by itself).
        Function.getFunction("user32", "SetWindowPos").invokeInt(
            arrayOf(hwnd, Pointer.NULL, 0, 0, 0, 0, SWP_NOSIZE or SWP_NOMOVE or SWP_NOACTIVATE),
        )
        return true
    }

    private const val SWP_NOSIZE = 0x0001
    private const val SWP_NOMOVE = 0x0002
    private const val SWP_NOACTIVATE = 0x0010

    private const val GWL_STYLE = -16
    private const val WS_POPUP = 0x80000000L
    private const val WS_CHILD = 0x40000000L
    private const val WS_DISABLED = 0x08000000L
    private const val WS_CLIPSIBLINGS = 0x04000000L
    private const val WS_CAPTION = 0x00C00000L
    private const val WS_THICKFRAME = 0x00040000L
}
