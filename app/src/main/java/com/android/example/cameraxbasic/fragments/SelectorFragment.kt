package com.android.example.cameraxbasic.fragments

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.Navigation
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.android.example.cameraxbasic.R
import com.android.example.cameraxbasic.databinding.FragmentSelectorBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.concurrent.futures.await

class SelectorFragment : Fragment() {

    private var _binding: FragmentSelectorBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSelectorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initSelectorList()
    }

    private fun initSelectorList() {
        lifecycleScope.launch(Dispatchers.Default) {
            val cameraList = getAvailableCameras(requireContext())
            withContext(Dispatchers.Main) {
                binding.recyclerView.apply {
                    layoutManager = LinearLayoutManager(requireContext())
                    adapter = SelectorAdapter(cameraList) { cameraItem ->
                        when (cameraItem.cameraType) {
                            "Header" -> { /* Do nothing for header */ }
                            else -> {
                                // Navigate to CameraFragment with the selected CameraType Int
                                cameraItem.cameraSelectorInt?.let { cameraTypeInt ->
                                    val action = SelectorFragmentDirections.actionSelectorToCamera(cameraTypeInt)
                                    Navigation.findNavController(requireView()).navigate(action)
                                } ?: run {
                                    Log.e(TAG, "Attempted to navigate with null CameraSelectorInt for item: ${cameraItem.cameraType}")
                                    Toast.makeText(context, "Error: Camera not available.", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun getAvailableCameras(context: Context): List<CameraItem> {
        val cameraProvider = ProcessCameraProvider.getInstance(context).await()
        val cameraItems = mutableListOf<CameraItem>()

        // Add a header item
        cameraItems.add(CameraItem("Header", null, R.drawable.ic_photo_camera))

        // Check for back camera
        if (cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
            cameraItems.add(
                CameraItem(
                    "Back Camera",
                    CAMERA_TYPE_BACK,
                    R.drawable.ic_back_camera
                )
            )
        }

        // Check for front camera
        if (cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
            cameraItems.add(
                CameraItem(
                    "Front Camera",
                    CAMERA_TYPE_FRONT,
                    R.drawable.ic_front_camera
                )
            )
        }

        return cameraItems
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /**
     * Data class to represent a camera item in the selector list.
     * `cameraSelectorInt` can be null for header items.
     */
    data class CameraItem(
        val cameraType: String,
        val cameraSelectorInt: Int?, // <-- ต้องเป็น Int?
        @DrawableRes val iconResource: Int
    )

    private class SelectorAdapter(
        private val cameraList: List<CameraItem>,
        private val onClickListener: (CameraItem) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemViewType(position: Int): Int {
            return if (cameraList[position].cameraType == "Header") TYPE_HEADER else TYPE_ITEM
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == TYPE_HEADER) {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.list_item_selector_header, parent, false)
                HeaderViewHolder(view)
            } else {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.list_item_selector, parent, false)
                ItemViewHolder(view)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val cameraItem = cameraList[position]
            if (holder is ItemViewHolder) {
                holder.bind(cameraItem, onClickListener)
            } else if (holder is HeaderViewHolder) {
                holder.bind(cameraItem)
            }
        }

        override fun getItemCount(): Int = cameraList.size

        companion object {
            private const val TYPE_HEADER = 0
            private const val TYPE_ITEM = 1
        }

        class ItemViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val textView: TextView = view.findViewById(R.id.item_name)
            private val imageView: ImageView = view.findViewById(R.id.item_icon)

            fun bind(cameraItem: CameraItem, onClickListener: (CameraItem) -> Unit) {
                textView.text = cameraItem.cameraType
                imageView.setImageDrawable(ContextCompat.getDrawable(imageView.context, cameraItem.iconResource))
                itemView.setOnClickListener { onClickListener(cameraItem) }
            }
        }

        class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val textView: TextView = view.findViewById(R.id.header_text)
            fun bind(cameraItem: CameraItem) {
                textView.text = "Select Camera" // Or use cameraItem.cameraType if it's dynamic for header
            }
        }
    }

    companion object {
        private const val TAG = "SelectorFragment"
        // เพิ่มค่าคงที่สำหรับประเภทกล้อง
        const val CAMERA_TYPE_BACK = 0
        const val CAMERA_TYPE_FRONT = 1
    }
}