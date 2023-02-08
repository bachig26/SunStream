package com.lagradost.cloudstream3.ui.home

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TmdbNetwork
import com.lagradost.cloudstream3.ui.search.NetworkClickCallback
import com.lagradost.cloudstream3.ui.search.SearchClickCallback
import com.lagradost.cloudstream3.ui.search.SearchResultBuilder
import com.lagradost.cloudstream3.utils.UIHelper.IsBottomLayout
import com.lagradost.cloudstream3.utils.UIHelper.setImage
import com.lagradost.cloudstream3.utils.UIHelper.toPx
import kotlinx.android.synthetic.main.home_network_grid.view.*
import kotlinx.android.synthetic.main.home_result_grid.view.background_card
import kotlinx.android.synthetic.main.home_result_grid_expanded.view.*

class HomeNetworkChildItemAdapter(
    val cardList: MutableList<TmdbNetwork>,
    private val overrideLayout: Int? = null,
    private val nextFocusUp: Int? = null,
    private val nextFocusDown: Int? = null,
    private val clickCallback: (NetworkClickCallback) -> Unit,
) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    var isHorizontal: Boolean = false
    var hasNext: Boolean = false

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val layout = overrideLayout ?: R.layout.home_network_grid

        return CardViewHolder(
            LayoutInflater.from(parent.context).inflate(layout, parent, false),
            clickCallback,
            itemCount,
            nextFocusUp,
            nextFocusDown,
            isHorizontal
        )
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is CardViewHolder -> {
                holder.itemCount = itemCount // i know ugly af
                holder.bind(cardList[position], position)
            }
        }
    }

    override fun getItemCount(): Int {
        return cardList.size
    }

    override fun getItemId(position: Int): Long {
        return (cardList[position].id ?: position).toLong()
    }

    fun updateList(newList: List<TmdbNetwork>) {
        val diffResult = DiffUtil.calculateDiff(
            HomeNetworkChildDiffCallback(this.cardList, newList)
        )

        cardList.clear()
        cardList.addAll(newList)

        diffResult.dispatchUpdatesTo(this)
    }

    class CardViewHolder
    constructor(
        itemView: View,
        private val clickCallback: (NetworkClickCallback) -> Unit,
        var itemCount: Int,
        private val nextFocusUp: Int? = null,
        private val nextFocusDown: Int? = null,
        private val isHorizontal: Boolean = false
    ) :
        RecyclerView.ViewHolder(itemView) {

        fun bind(card: TmdbNetwork, position: Int) {

            // TV focus fixing
            val nextFocusBehavior = when (position) {
                0 -> true
                itemCount - 1 -> false
                else -> null
            }

            (itemView.networkImageView)?.apply {

                layoutParams =
                    layoutParams.apply {
                        width = 180.toPx
                        height = 114.toPx
                    }
            }
            (itemView.network_filter_background_card)?.apply {

                layoutParams =
                    layoutParams.apply {
                        width = 180.toPx
                        height = 114.toPx
                    }
            }

            if (card.enableNetworkTint == 1) {
                itemView.networkImageView.setColorFilter(Color.argb(240, 255, 255, 255)); // 180: kinda transparent
            } else {
                itemView.networkImageView.colorFilter = null
            }

            /*SearchResultBuilder.bind(
                clickCallback,
                card,
                position,
                itemView,
                nextFocusBehavior,
                nextFocusUp,
                nextFocusDown
            )*/
            itemView.networkImageText.text = card.name
            val imagePath =  "https://image.tmdb.org/t/p/w500" + card.networkImagePath
            itemView.networkImageView?.setImage(imagePath)
            itemView.tag = position

            if (position == 0) { // to fix tv
                itemView.network_filter_background_card?.nextFocusLeftId = R.id.nav_rail_view
            }


            fun click(view: View?) {
                clickCallback.invoke(
                    NetworkClickCallback(
                        view ?: return,
                        position,
                        card
                    )
                )
            }

            val bg: CardView = itemView.network_filter_background_card
            bg.setOnClickListener {
                click(it)
            }

            //val ani = ScaleAnimation(0.9f, 1.0f, 0.9f, 1f)
            //ani.fillAfter = true
            //ani.duration = 200
            //itemView.startAnimation(ani)
        }
    }
}

class HomeNetworkChildDiffCallback(
    private val oldList: List<TmdbNetwork>,
    private val newList: List<TmdbNetwork>
) :
    DiffUtil.Callback() {
    override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
        oldList[oldItemPosition].name == newList[newItemPosition].name

    override fun getOldListSize() = oldList.size

    override fun getNewListSize() = newList.size

    override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
        oldList[oldItemPosition] == newList[newItemPosition] && oldItemPosition < oldList.size - 1 // always update the last item
}