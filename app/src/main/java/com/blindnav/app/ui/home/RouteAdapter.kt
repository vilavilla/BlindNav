package com.blindnav.app.ui.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.blindnav.app.data.db.entity.Route
import com.blindnav.app.databinding.ItemRouteBinding

/**
 * RouteAdapter - Adaptador para lista de rutas guardadas
 */
class RouteAdapter(
    private val onRouteClick: (Route) -> Unit,
    private val onDeleteClick: (Route) -> Unit
) : ListAdapter<Route, RouteAdapter.RouteViewHolder>(RouteDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RouteViewHolder {
        val binding = ItemRouteBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return RouteViewHolder(binding)
    }

    override fun onBindViewHolder(holder: RouteViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class RouteViewHolder(
        private val binding: ItemRouteBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(route: Route) {
            binding.tvRouteName.text = route.name
            
            // Description as subtitle
            binding.tvRouteDistance.text = if (route.description.isNotEmpty()) {
                route.description
            } else {
                "Ruta guardada"
            }
            
            // Placeholder para eventos
            binding.tvRouteEvents.text = "Tap para ver"
            
            // Click en toda la tarjeta
            binding.root.setOnClickListener {
                onRouteClick(route)
            }
            
            // Botón cargar
            binding.btnLoadRoute.setOnClickListener {
                onRouteClick(route)
            }
            
            // Botón eliminar
            binding.btnDeleteRoute.setOnClickListener {
                onDeleteClick(route)
            }
        }
    }

    class RouteDiffCallback : DiffUtil.ItemCallback<Route>() {
        override fun areItemsTheSame(oldItem: Route, newItem: Route): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Route, newItem: Route): Boolean {
            return oldItem == newItem
        }
    }
}
