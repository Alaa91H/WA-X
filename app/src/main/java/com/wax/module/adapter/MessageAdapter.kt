package com.wax.module.adapter

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import com.wax.module.R
import com.wax.module.xposed.core.db.MessageHistoryStore
import com.wax.module.xposed.utils.DesignUtils
import com.wax.module.xposed.utils.Utils

class MessageAdapter(
    context: Context,
    private val items: List<MessageHistoryStore.MessageItem>,
) : ArrayAdapter<MessageHistoryStore.MessageItem>(
        context,
        android.R.layout.simple_list_item_2,
        android.R.id.text1,
        items,
    ) {
    override fun getCount(): Int = items.size

    override fun getItem(position: Int): MessageHistoryStore.MessageItem = items[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(
        position: Int,
        convertView: View?,
        parent: ViewGroup,
    ): View {
        val row = super.getView(position, convertView, parent)
        val message = items[position]
        val messageView = row.findViewById<TextView>(android.R.id.text1)
        messageView.textSize = 14f
        messageView.setTextColor(DesignUtils.getPrimaryTextColor())
        messageView.text = message.message

        val timestampView = row.findViewById<TextView>(android.R.id.text2)
        timestampView.textSize = 12f
        timestampView.alpha = 0.75f
        timestampView.setTypeface(null, Typeface.ITALIC)
        timestampView.setTextColor(DesignUtils.getPrimaryTextColor())
        timestampView.text =
            if (message.timestamp == 0L) {
                context.getString(R.string.message_original)
            } else {
                "✏️ ${Utils.getDateTimeFromMillis(message.timestamp)}"
            }
        return row
    }
}
