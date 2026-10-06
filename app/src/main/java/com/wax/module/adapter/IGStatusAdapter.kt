package com.wax.module.adapter

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import com.wax.module.R
import com.wax.module.views.dialog.TabDialogContent
import com.wax.module.xposed.core.ModuleRuntime
import com.wax.module.xposed.core.ModuleRuntime.getCurrentActivity
import com.wax.module.xposed.core.ModuleRuntime.getMyPhoto
import com.wax.module.xposed.core.components.FMessageWpp.UserJid
import com.wax.module.xposed.core.components.WaContactWpp
import com.wax.module.xposed.core.devkit.Unobfuscator.findFirstClassUsingName
import com.wax.module.xposed.core.devkit.Unobfuscator.getClassByName
import com.wax.module.xposed.core.devkit.UnobfuscatorCache.Companion.getInstance
import com.wax.module.xposed.features.customization.IGStatus
import com.wax.module.xposed.utils.DesignUtils.coloredDrawable
import com.wax.module.xposed.utils.DesignUtils.generatePrimaryColorDrawable
import com.wax.module.xposed.utils.DesignUtils.getDrawable
import com.wax.module.xposed.utils.DesignUtils.getDrawableByName
import com.wax.module.xposed.utils.DesignUtils.getIconByName
import com.wax.module.xposed.utils.DesignUtils.getUnSeenColor
import com.wax.module.xposed.utils.DesignUtils.isNightMode
import com.wax.module.xposed.utils.ReflectionUtils.findMethodUsingFilter
import com.wax.module.xposed.utils.ReflectionUtils.getFieldByExtendType
import com.wax.module.xposed.utils.ReflectionUtils.getObjectField
import com.wax.module.xposed.utils.Utils.application
import com.wax.module.xposed.utils.Utils.dipToPixels
import com.wax.module.xposed.utils.Utils.showToast
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Method

@Suppress("TYPE_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
class IGStatusAdapter(
    context: Context,
    private val statusInfoClazz: Class<*>,
) : ArrayAdapter<Any?>(context, 0) {
    private val clazzImageStatus: Class<*> =
        findFirstClassUsingName(
            context.classLoader,
            StringMatchType.EndsWith,
            ".ContactStatusThumbnail",
        )

    private val setCountStatus: Method? =
        findMethodUsingFilter(clazzImageStatus) { method: Method? ->
            method != null &&
                method.parameterCount == 3 &&
                intArrayParameterTypes.contentEquals(method.parameterTypes)
        }

    override fun getCount(): Int = IGStatus.itens.size

    override fun getView(
        position: Int,
        convertView: View?,
        parent: ViewGroup,
    ): View {
        if (position !in IGStatus.itens.indices) return convertView ?: View(context)

        val holder = obtainViewHolder(convertView)
        bindStatus(holder, IGStatus.itens[position])
        holder.itemView.setOnClickListener { onStatusClick(holder) }
        return holder.itemView
    }

    private fun obtainViewHolder(convertView: View?): IGStatusViewHolder {
        val recycled = convertView?.tag as? IGStatusViewHolder
        if (recycled != null) return recycled

        return createViewHolder().also { holder ->
            holder.itemView.tag = holder
        }
    }

    private fun bindStatus(
        holder: IGStatusViewHolder,
        item: Any?,
    ) {
        when {
            item == null -> {
                holder.setInfo(MY_STATUS_SENTINEL)
                holder.addButton.visibility = View.VISIBLE
            }

            statusInfoClazz.isInstance(item) -> {
                if (item is View) item.isClickable = false
                holder.setInfo(item)
                holder.addButton.visibility = View.GONE
            }

            else -> {
                holder.reset()
                holder.addButton.visibility = View.GONE
            }
        }
    }

    private fun onStatusClick(holder: IGStatusViewHolder) {
        val activity = getCurrentActivity() ?: return
        if (holder.myStatus) {
            showMyStatusDialog(activity)
        } else {
            openStatusPlayback(activity, holder.userJid)
        }
    }

    private fun showMyStatusDialog(activity: Activity) {
        val dialog = ModuleRuntime.createBottomDialog(activity)
        val content = TabDialogContent(activity)
        content.setTitle(activity.getString(R.string.select_status_type))

        addStatusListTab(activity, content) { dialog.dismissDialog() }
        addCameraTab(activity, content) { dialog.dismissDialog() }
        addTextStatusTab(activity, content) { dialog.dismissDialog() }

        dialog.setContentView(content)
        dialog.showDialog()
    }

    private fun addStatusListTab(
        activity: Activity,
        content: TabDialogContent,
        dismiss: () -> Unit,
    ) {
        content.addTab(
            getInstance().getString("mystatus"),
            getIconByName("ic_status", true),
        ) {
            runActivityAction(dismiss) {
                val clazz = getClassByName("MyStatusesActivity", context.classLoader)
                activity.startActivity(Intent(activity, clazz))
            }
        }
    }

    private fun addCameraTab(
        activity: Activity,
        content: TabDialogContent,
        dismiss: () -> Unit,
    ) {
        val icon = getDrawable(R.drawable.camera)
        coloredDrawable(icon, if (isNightMode()) Color.WHITE else Color.BLACK)
        content.addTab(activity.getString(R.string.open_camera), icon) {
            runActivityAction(dismiss) {
                val clazz = getClassByName("CameraActivity", context.classLoader)
                val intent =
                    Intent().apply {
                        setClassName(activity.packageName, clazz.name)
                        putExtra("jid", "status@broadcast")
                        putExtra("camera_origin", 4)
                        putExtra("is_coming_from_chat", false)
                        putExtra("media_sharing_user_journey_origin", 32)
                        putExtra("media_sharing_user_journey_start_target", 9)
                        putExtra("media_sharing_user_journey_chat_type", 4)
                    }
                activity.startActivity(intent)
            }
        }
    }

    private fun addTextStatusTab(
        activity: Activity,
        content: TabDialogContent,
        dismiss: () -> Unit,
    ) {
        val icon = getDrawable(R.drawable.edit2)
        coloredDrawable(icon, if (isNightMode()) Color.WHITE else Color.BLACK)
        content.addTab(activity.getString(R.string.edit_text), icon) {
            runActivityAction(dismiss) {
                activity.startActivity(textStatusIntent(activity))
            }
        }
    }

    private fun textStatusIntent(activity: Activity): Intent {
        val intent = Intent()
        val clazz =
            runCatching {
                getClassByName("TextStatusComposerActivity", activity.classLoader)
            }.getOrElse {
                intent.putExtra("status_composer_mode", 2)
                getClassByName("ConsolidatedStatusComposerActivity", context.classLoader)
            }
        intent.setClassName(activity.packageName, clazz.name)
        return intent
    }

    private inline fun runActivityAction(
        dismiss: () -> Unit,
        action: () -> Unit,
    ) {
        runCatching(action)
            .onFailure { throwable ->
                XposedBridge.log(throwable)
                showToast(throwable.message, 1)
            }
        dismiss()
    }

    private fun openStatusPlayback(
        activity: Activity,
        userJid: UserJid?,
    ) {
        val jid = userJid?.phoneRawString ?: return
        runCatching {
            val clazz = getClassByName("StatusPlaybackActivity", context.classLoader)
            activity.startActivity(
                Intent(activity, clazz).putExtra("jid", jid),
            )
        }.onFailure { throwable ->
            XposedBridge.log(throwable)
            showToast(throwable.message, 1)
        }
    }

    internal inner class IGStatusViewHolder(
        val itemView: RelativeLayout,
        val contactPhoto: ImageView,
        val addButton: RelativeLayout,
        val contactName: TextView,
    ) {
        var myStatus: Boolean = false
            private set
        var userJid: UserJid? = null
            private set

        fun reset() {
            myStatus = false
            userJid = null
            contactName.text = ""
            contactPhoto.setImageDrawable(getDrawableByName("avatar_contact"))
            setCountStatus(0, 0)
        }

        fun setInfo(item: Any?) {
            reset()
            if (item == MY_STATUS_SENTINEL) {
                bindMyStatus()
                return
            }
            bindContactStatus(item ?: return)
        }

        private fun bindMyStatus() {
            myStatus = true
            contactName.text = getInstance().getString("mystatus")
            val profile = getMyPhoto() ?: application.getDrawable(R.drawable.user_foreground)
            contactPhoto.setImageDrawable(profile)
        }

        private fun bindContactStatus(item: Any) {
            runCatching {
                val statusInfo =
                    XposedHelpers.getObjectField(item, "A01")
                        .takeUnless { it is Number }
                        ?: XposedHelpers.getObjectField(item, "A02")
                val targetClassLoader = statusInfoClazz.classLoader ?: return
                val classJid =
                    findFirstClassUsingName(
                        targetClassLoader,
                        StringMatchType.EndsWith,
                        "jid.Jid",
                    )
                val field = getFieldByExtendType(statusInfo.javaClass, classJid)
                userJid = UserJid(getObjectField(field, statusInfo))
                val contact = userJid?.let(WaContactWpp::getWaContactFromJid) ?: return
                contactName.text = contact.displayName
                contactPhoto.setImageDrawable(contact.profileDrawable())
                setCountStatus(
                    XposedHelpers.getIntField(statusInfo, "A01"),
                    XposedHelpers.getIntField(statusInfo, "A00"),
                )
            }.onFailure(XposedBridge::log)
        }

        private fun WaContactWpp.profileDrawable(): Drawable? =
            BitmapDrawable.createFromStream(getProfilePhoto(false), "profile")
                ?: application.getDrawable(R.drawable.user_foreground)

        private fun setCountStatus(
            countUnseen: Int,
            total: Int,
        ) {
            runCatching {
                setCountStatus?.invoke(contactPhoto, total, countUnseen, total)
            }.onFailure(XposedBridge::log)
        }
    }

    private fun createViewHolder(): IGStatusViewHolder {
        val root =
            RelativeLayout(context).apply {
                layoutParams = RelativeLayout.LayoutParams(dipToPixels(86), ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        val frame =
            FrameLayout(context).apply {
                layoutParams =
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
            }
        val column =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
            }
        val photoContainer =
            RelativeLayout(context).apply {
                layoutParams = RelativeLayout.LayoutParams(dipToPixels(64), dipToPixels(64))
            }
        val photo = createContactPhoto()
        val addButton = createAddButton()
        val name = createContactName()

        photoContainer.addView(photo)
        photoContainer.addView(addButton)
        column.addView(photoContainer)
        column.addView(name)
        frame.addView(column)
        root.addView(frame)

        return IGStatusViewHolder(root, photo, addButton, name)
    }

    private fun createContactPhoto(): ImageView =
        (XposedHelpers.newInstance(clazzImageStatus, context) as ImageView).apply {
            layoutParams =
                RelativeLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            val padding = dipToPixels(2.5f)
            setPadding(padding, padding, padding, padding)
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageDrawable(getDrawableByName("avatar_contact"))
            isClickable = true
            XposedHelpers.callMethod(this, "setBorderSize", padding.toFloat())
            XposedHelpers.callMethod(this, "setCornerRadius", dipToPixels(80f).toFloat())
            XposedHelpers.setObjectField(this, "A02", Color.GRAY)
            XposedHelpers.setObjectField(this, "A03", getUnSeenColor())
        }

    private fun createAddButton(): RelativeLayout =
        RelativeLayout(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams =
                RelativeLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
                    addRule(RelativeLayout.ALIGN_PARENT_END)
                }
            visibility = View.GONE
            addView(
                ImageView(context).apply {
                    layoutParams = RelativeLayout.LayoutParams(dipToPixels(24), dipToPixels(24))
                    val icon = getDrawableByName("my_status_add_button_new")
                    setImageDrawable(generatePrimaryColorDrawable(icon) ?: icon)
                    setBackgroundColor(Color.TRANSPARENT)
                },
            )
        }

    private fun createContactName(): TextView =
        TextView(context).apply {
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            layoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(Typeface.DEFAULT_BOLD)
            maxLines = 1
        }

    companion object {
        private const val MY_STATUS_SENTINEL = "my_status"

        private val intArrayParameterTypes =
            arrayOf<Class<*>>(
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
            )
    }
}
