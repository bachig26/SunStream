package com.lagradost.cloudstream3.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isGone
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.utils.UIHelper.setImage
import kotlinx.android.synthetic.main.home_scroll_view.view.*


class HomeScrollAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private var items: MutableList<LoadResponse> = mutableListOf()
    var hasMoreItems: Boolean = false

    fun getItem(position: Int) : LoadResponse? {
        return items.getOrNull(position)
    }

    fun setItems(newItems: List<LoadResponse>, hasNext: Boolean): Boolean {
        val isSame = newItems.firstOrNull()?.url == items.firstOrNull()?.url
        hasMoreItems = hasNext

        val diffResult = DiffUtil.calculateDiff(
            HomeScrollDiffCallback(this.items, newItems)
        )

        items.clear()
        items.addAll(newItems)


        diffResult.dispatchUpdatesTo(this)

        return isSame
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return CardViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.home_scroll_view, parent, false),
        )
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is CardViewHolder -> {
                holder.bind(items[position])
            }
        }
    }

    class CardViewHolder
    constructor(
        itemView: View,
    ) :
        RecyclerView.ViewHolder(itemView) {

        fun bind(card: LoadResponse) {
            card.apply {
                itemView.home_scroll_preview_tags?.text = tags?.joinToString(" • ") ?: ""
                itemView.home_scroll_preview_tags?.isGone = tags.isNullOrEmpty()
                itemView.home_scroll_preview?.setImage(posterUrl, posterHeaders)
                itemView.home_scroll_preview_title?.text = name
            }
        }
    }

    class HomeScrollDiffCallback(
        private val oldList: List<LoadResponse>,
        private val newList: List<LoadResponse>
    ) :
        DiffUtil.Callback() {
        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
            oldList[oldItemPosition].url == newList[newItemPosition].url

        override fun getOldListSize() = oldList.size

        override fun getNewListSize() = newList.size

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
            oldList[oldItemPosition] == newList[newItemPosition]
    }

    override fun getItemCount(): Int {
        return items.size
    }
}