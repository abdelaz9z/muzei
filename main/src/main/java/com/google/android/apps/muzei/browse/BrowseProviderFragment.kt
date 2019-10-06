/*
 * Copyright 2018 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.apps.muzei.browse

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.graphics.drawable.DrawerArrowDrawable
import androidx.appcompat.widget.Toolbar
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.observe
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import coil.api.load
import com.google.android.apps.muzei.room.Artwork
import com.google.android.apps.muzei.room.MuzeiDatabase
import com.google.android.apps.muzei.sync.ProviderManager
import com.google.android.apps.muzei.util.toast
import com.google.firebase.analytics.FirebaseAnalytics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.nurik.roman.muzei.R

class BrowseProviderFragment: Fragment(R.layout.browse_provider_fragment) {

    companion object {
        const val REFRESH_DELAY = 300L // milliseconds
    }

    private val viewModel: BrowseProviderViewModel by viewModels()
    private val args: BrowseProviderFragmentArgs by navArgs()
    private val adapter = Adapter()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val pm = requireContext().packageManager
        val providerInfo = pm.resolveContentProvider(args.contentUri.authority!!, 0)
                ?: run {
                    findNavController().popBackStack()
                    return
                }

        val swipeRefreshLayout = view.findViewById<SwipeRefreshLayout>(R.id.browse_swipe_refresh)
        swipeRefreshLayout.setOnRefreshListener {
            refresh(swipeRefreshLayout)
        }
        view.findViewById<Toolbar>(R.id.browse_toolbar).apply {
            navigationIcon = DrawerArrowDrawable(requireContext()).apply {
                progress = 1f
            }
            setNavigationOnClickListener {
                findNavController().popBackStack()
            }
            title = providerInfo.loadLabel(pm)
            inflateMenu(R.menu.browse_provider_fragment)
            setOnMenuItemClickListener {
                refresh(swipeRefreshLayout)
                true
            }
        }
        view.findViewById<RecyclerView>(R.id.browse_list).adapter = adapter

        viewModel.setContentUri(args.contentUri)
        viewModel.artLiveData.observe(viewLifecycleOwner) {
            adapter.submitList(it)
        }
    }

    private fun refresh(swipeRefreshLayout: SwipeRefreshLayout) {
        lifecycleScope.launch {
            ProviderManager.requestLoad(requireContext(), args.contentUri)
            // Show the refresh indicator for some visible amount of time
            // rather than immediately dismissing it. We don't know how long
            // the provider will actually take to refresh, if it does at all.
            delay(REFRESH_DELAY)
            withContext(Dispatchers.Main.immediate) {
                swipeRefreshLayout.isRefreshing = false
            }
        }
    }

    class ArtViewHolder(
            private val owner: LifecycleOwner,
            itemView: View
    ): RecyclerView.ViewHolder(itemView) {
        private val imageView = itemView.findViewById<ImageView>(R.id.browse_image)

        fun bind(artwork: Artwork) {
            imageView.contentDescription = artwork.title
            imageView.load(artwork.imageUri) {
                lifecycle(owner)
            }
            itemView.setOnClickListener {
                val context = it.context
                owner.lifecycleScope.launch(Dispatchers.Main) {
                    FirebaseAnalytics.getInstance(context).logEvent(
                            FirebaseAnalytics.Event.SELECT_CONTENT, bundleOf(
                            FirebaseAnalytics.Param.ITEM_ID to artwork.id,
                            FirebaseAnalytics.Param.ITEM_NAME to artwork.title,
                            FirebaseAnalytics.Param.ITEM_CATEGORY to "artwork",
                            FirebaseAnalytics.Param.CONTENT_TYPE to "browse"))
                    MuzeiDatabase.getInstance(context).artworkDao()
                            .insert(artwork)
                    context.toast(if (artwork.title.isNullOrBlank()) {
                        context.getString(R.string.browse_set_wallpaper)
                    } else {
                        context.getString(R.string.browse_set_wallpaper_with_title,
                                artwork.title)
                    })
                }
            }
        }
    }

    inner class Adapter: ListAdapter<Artwork, ArtViewHolder>(
            object: DiffUtil.ItemCallback<Artwork>() {
                override fun areItemsTheSame(artwork1: Artwork, artwork2: Artwork) =
                        artwork1.imageUri == artwork2.imageUri

                override fun areContentsTheSame(artwork1: Artwork, artwork2: Artwork) =
                        artwork1 == artwork2
            }
    ) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                ArtViewHolder(viewLifecycleOwner,
                        layoutInflater.inflate(R.layout.browse_provider_item, parent, false))

        override fun onBindViewHolder(holder: ArtViewHolder, position: Int) {
            holder.bind(getItem(position))
        }
    }
}
