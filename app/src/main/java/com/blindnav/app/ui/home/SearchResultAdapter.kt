package com.blindnav.app.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.blindnav.app.R
import com.blindnav.app.data.osm.NominatimGeocoder

/**
 * SearchResultAdapter - Adaptador para resultados de búsqueda Nominatim
 */
class SearchResultAdapter(
    private val onResultClick: (NominatimGeocoder.SearchResult) -> Unit
) : ListAdapter<NominatimGeocoder.SearchResult, SearchResultAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_2, parent, false)
        return ViewHolder(view, onResultClick)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(
        itemView: View,
        private val onResultClick: (NominatimGeocoder.SearchResult) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val text1: TextView = itemView.findViewById(android.R.id.text1)
        private val text2: TextView = itemView.findViewById(android.R.id.text2)

        fun bind(result: NominatimGeocoder.SearchResult) {
            // Título: nombre principal del lugar
            val parts = result.displayName.split(",")
            text1.text = parts.firstOrNull() ?: result.displayName
            text1.textSize = 16f
            text1.setTextColor(0xFFFFFFFF.toInt())

            // Subtítulo: dirección completa
            text2.text = result.displayName
            text2.textSize = 12f
            text2.setTextColor(0xFFAAAAAA.toInt())

            itemView.setOnClickListener {
                onResultClick(result)
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<NominatimGeocoder.SearchResult>() {
        override fun areItemsTheSame(
            oldItem: NominatimGeocoder.SearchResult,
            newItem: NominatimGeocoder.SearchResult
        ): Boolean = oldItem.latitude == newItem.latitude && oldItem.longitude == newItem.longitude

        override fun areContentsTheSame(
            oldItem: NominatimGeocoder.SearchResult,
            newItem: NominatimGeocoder.SearchResult
        ): Boolean = oldItem == newItem
    }
}
