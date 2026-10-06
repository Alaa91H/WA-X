package com.wax.module.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.wax.module.R
import com.wax.module.model.Recording
import java.text.SimpleDateFormat
import java.util.Date
import java.util.HashSet
import java.util.Locale

class RecordingsAdapter(
    private val listener: OnRecordingActionListener,
) : RecyclerView.Adapter<RecordingsAdapter.ViewHolder>() {
    private var recordingItems: List<Recording> = emptyList()
    private var isSelectionMode = false
    private val selectedPositions = HashSet<Int>()
    private var selectionChangeListener: OnSelectionChangeListener? = null

    interface OnRecordingActionListener {
        fun onPlay(recording: Recording)

        fun onShare(recording: Recording)

        fun onDelete(recording: Recording)

        fun onLongPress(
            recording: Recording,
            position: Int,
        )
    }

    fun interface OnSelectionChangeListener {
        fun onSelectionChanged(count: Int)
    }

    fun setSelectionChangeListener(listener: OnSelectionChangeListener?) {
        selectionChangeListener = listener
    }

    fun setRecordings(recordings: List<Recording>) {
        val oldItems = recordingItems
        val result =
            DiffUtil.calculateDiff(
                object : DiffUtil.Callback() {
                    override fun getOldListSize(): Int = oldItems.size

                    override fun getNewListSize(): Int = recordings.size

                    override fun areItemsTheSame(
                        oldItemPosition: Int,
                        newItemPosition: Int,
                    ): Boolean = oldItems[oldItemPosition].file == recordings[newItemPosition].file

                    override fun areContentsTheSame(
                        oldItemPosition: Int,
                        newItemPosition: Int,
                    ): Boolean = oldItems[oldItemPosition] == recordings[newItemPosition]
                },
            )
        recordingItems = recordings.toList()
        selectedPositions.clear()
        isSelectionMode = false
        selectionChangeListener?.onSelectionChanged(0)
        result.dispatchUpdatesTo(this)
    }

    fun setSelectionMode(selectionMode: Boolean) {
        if (isSelectionMode != selectionMode) {
            isSelectionMode = selectionMode
            if (!selectionMode) selectedPositions.clear()
            if (recordingItems.isNotEmpty()) {
                notifyItemRangeChanged(0, recordingItems.size)
            }
        }
    }

    fun toggleSelection(position: Int) {
        if (!selectedPositions.add(position)) selectedPositions.remove(position)
        notifyItemChanged(position)
        selectionChangeListener?.onSelectionChanged(selectedPositions.size)
    }

    fun selectAll() {
        selectedPositions.clear()
        for (index in recordingItems.indices) selectedPositions.add(index)
        if (recordingItems.isNotEmpty()) {
            notifyItemRangeChanged(0, recordingItems.size)
        }
        selectionChangeListener?.onSelectionChanged(selectedPositions.size)
    }

    fun clearSelection() {
        selectedPositions.clear()
        isSelectionMode = false
        if (recordingItems.isNotEmpty()) {
            notifyItemRangeChanged(0, recordingItems.size)
        }
        selectionChangeListener?.onSelectionChanged(0)
    }

    val selectedRecordings: List<Recording>
        get() = selectedPositions.mapNotNull { position -> recordingItems.getOrNull(position) }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_recording, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
    ) {
        val recording = recordingItems[position]
        holder.contactName.text = recording.contactName
        holder.duration.text = recording.getFormattedDuration()

        val dateFormat = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
        holder.details.text =
            holder.itemView.context.getString(
                R.string.recording_details_format,
                recording.getFormattedSize(),
                dateFormat.format(Date(recording.date)),
            )

        if (isSelectionMode) {
            holder.checkbox.visibility = View.VISIBLE
            holder.actionsContainer.visibility = View.GONE
            holder.checkbox.isChecked = position in selectedPositions
            holder.card.isChecked = position in selectedPositions
        } else {
            holder.checkbox.visibility = View.GONE
            holder.actionsContainer.visibility = View.VISIBLE
            holder.card.isChecked = false
        }

        holder.itemView.setOnClickListener {
            if (isSelectionMode) toggleSelection(position) else listener.onPlay(recording)
        }
        holder.itemView.setOnLongClickListener {
            if (!isSelectionMode) listener.onLongPress(recording, position)
            true
        }
        holder.checkbox.setOnClickListener { toggleSelection(position) }
        holder.btnPlay.setOnClickListener { listener.onPlay(recording) }
        holder.btnShare.setOnClickListener { listener.onShare(recording) }
        holder.btnDelete.setOnClickListener { listener.onDelete(recording) }
    }

    override fun getItemCount(): Int = recordingItems.size

    class ViewHolder(
        itemView: View,
    ) : RecyclerView.ViewHolder(itemView) {
        val card: MaterialCardView = itemView as MaterialCardView
        val checkbox: CheckBox = itemView.findViewById(R.id.checkbox)
        val icon: ImageView = itemView.findViewById(R.id.icon)
        val contactName: TextView = itemView.findViewById(R.id.contact_name)
        val duration: TextView = itemView.findViewById(R.id.duration)
        val details: TextView = itemView.findViewById(R.id.details)
        val actionsContainer: LinearLayout = itemView.findViewById(R.id.actions_container)
        val btnPlay: ImageButton = itemView.findViewById(R.id.btn_play)
        val btnShare: ImageButton = itemView.findViewById(R.id.btn_share)
        val btnDelete: ImageButton = itemView.findViewById(R.id.btn_delete)
    }
}
