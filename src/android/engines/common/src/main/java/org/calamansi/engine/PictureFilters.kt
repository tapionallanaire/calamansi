package org.calamansi.engine

import android.app.Activity
import android.app.Application
import android.app.NativeActivity
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.PopupWindow

object PictureFilters {
    private val handler = Handler(Looper.getMainLooper())

    fun uri(context: Context): Uri = Uri.parse("content://${context.packageName}.picture-filter")

    fun selected(context: Context): PictureFilter = PictureFilter.fromId(
        requireNotNull(context.contentResolver.call(uri(context), "get", null, null))
            .getInt("filter")
    )

    fun select(context: Context, filter: PictureFilter) {
        requireNotNull(
            context.contentResolver.call(
                uri(context),
                "set",
                null,
                Bundle().apply { putInt("filter", filter.id) }
            )
        )
    }

    fun attach(view: View) {
        if (view.getTag(R.id.calamansi_picture_filter_binding) != null) return
        val binding = CanvasBinding(view)
        view.setTag(R.id.calamansi_picture_filter_binding, binding)
        view.addOnAttachStateChangeListener(binding)
        if (view.isAttachedToWindow) binding.onViewAttachedToWindow(view)
    }

    /** NativeActivity owns its entire window buffer: use a separate untouchable UI window. */
    fun attachNativeWindow(activity: NativeActivity) {
        val root = activity.window.decorView
        if (root.getTag(R.id.calamansi_picture_filter_binding) != null) return
        val binding = NativeWindowBinding(activity, root)
        root.setTag(R.id.calamansi_picture_filter_binding, binding)
        root.addOnAttachStateChangeListener(binding)
        activity.application.registerActivityLifecycleCallbacks(binding)
    }

    private class CanvasBinding(private val view: View) : View.OnAttachStateChangeListener {
        private val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) = refresh()
        }
        private var observing = false

        private fun refresh() {
            val filter = selected(view.context)
            val previous = view.foreground
            if (previous is PictureFilterDrawable && previous.filter == filter) return
            if (filter == PictureFilter.OFF) {
                if (previous is PictureFilterDrawable) view.foreground = null
            } else {
                check(previous == null || previous is PictureFilterDrawable) {
                    "The game canvas already owns its foreground"
                }
                view.foreground = PictureFilterDrawable(filter)
            }
        }

        override fun onViewAttachedToWindow(v: View) {
            if (!observing) {
                view.context.contentResolver.registerContentObserver(
                    uri(view.context),
                    false,
                    observer
                )
                observing = true
            }
            refresh()
        }

        override fun onViewDetachedFromWindow(v: View) {
            if (observing) {
                view.context.contentResolver.unregisterContentObserver(observer)
                observing = false
            }
        }
    }

    private class NativeWindowBinding(
        private val activity: NativeActivity,
        private val root: View
    ) : Application.ActivityLifecycleCallbacks,
        View.OnAttachStateChangeListener {
        private var resumed = false
        private var observing = false
        private val location = IntArray(2)
        private val mask = View(activity).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            isFocusable = false
            isClickable = false
        }
        private val popup = PopupWindow(mask, 0, 0, false).apply {
            isTouchable = false
            isFocusable = false
            isClippingEnabled = true
            setIsLaidOutInScreen(true)
            animationStyle = 0
        }
        private val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) = refresh()
        }
        private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            refresh()
        }

        private fun refresh() {
            if (!resumed || !root.isAttachedToWindow || activity.isFinishing ||
                activity.isDestroyed
            ) {
                popup.dismiss()
                return
            }
            val filter = selected(activity)
            if (filter == PictureFilter.OFF || root.width == 0 || root.height == 0) {
                popup.dismiss()
                return
            }
            if ((mask.background as? PictureFilterDrawable)?.filter != filter) {
                mask.background = PictureFilterDrawable(filter)
            }
            root.getLocationOnScreen(location)
            if (popup.isShowing) {
                popup.update(location[0], location[1], root.width, root.height)
            } else {
                popup.width = root.width
                popup.height = root.height
                popup.showAtLocation(root, Gravity.TOP or Gravity.LEFT, location[0], location[1])
            }
        }

        private fun observe() {
            if (!observing && resumed && root.isAttachedToWindow) {
                activity.contentResolver.registerContentObserver(uri(activity), false, observer)
                root.addOnLayoutChangeListener(layoutListener)
                observing = true
            }
            refresh()
        }

        private fun hide() {
            popup.dismiss()
            if (observing) {
                activity.contentResolver.unregisterContentObserver(observer)
                root.removeOnLayoutChangeListener(layoutListener)
                observing = false
            }
        }

        override fun onViewAttachedToWindow(v: View) = observe()
        override fun onViewDetachedFromWindow(v: View) = hide()
        override fun onActivityResumed(owner: Activity) {
            if (owner === activity) {
                resumed = true
                observe()
            }
        }
        override fun onActivityPaused(owner: Activity) {
            if (owner === activity) {
                resumed = false
                hide()
            }
        }
        override fun onActivityDestroyed(owner: Activity) {
            if (owner === activity) {
                hide()
                root.removeOnAttachStateChangeListener(this)
                activity.application.unregisterActivityLifecycleCallbacks(this)
            }
        }
        override fun onActivityCreated(owner: Activity, state: Bundle?) = Unit
        override fun onActivityStarted(owner: Activity) = Unit
        override fun onActivityStopped(owner: Activity) = Unit
        override fun onActivitySaveInstanceState(owner: Activity, state: Bundle) = Unit
    }
}
