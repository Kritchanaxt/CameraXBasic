package com.android.example.cameraxbasic.fragments

import android.content.Intent
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import androidx.navigation.Navigation
import androidx.navigation.fragment.navArgs
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.android.example.cameraxbasic.R
import com.android.example.cameraxbasic.databinding.FragmentGalleryBinding
import com.android.example.cameraxbasic.utils.MediaStoreFile
import com.android.example.cameraxbasic.utils.MediaStoreUtils
import com.android.example.cameraxbasic.utils.padWithDisplayCutout
import com.android.example.cameraxbasic.utils.showImmersive
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import java.io.File // Import File

class GalleryFragment : Fragment() {

    private var _fragmentGalleryBinding: FragmentGalleryBinding? = null
    private val fragmentGalleryBinding get() = _fragmentGalleryBinding!!

    private val args: GalleryFragmentArgs by navArgs()

    private var mediaList: MutableList<MediaStoreFile> = mutableListOf()
    private var hasMediaItems = CompletableDeferred<Boolean>()

    inner class MediaPagerAdapter(
        fm: FragmentManager,
        private var mediaList: MutableList<MediaStoreFile>
    ) : FragmentStateAdapter(fm, lifecycle) {
        override fun getItemCount(): Int = mediaList.size
        override fun createFragment(position: Int): Fragment =
            PhotoFragment.create(mediaList[position])

        override fun getItemId(position: Int): Long = mediaList[position].id
        override fun containsItem(itemId: Long): Boolean =
            mediaList.any { it.id == itemId }

        fun setMediaListAndNotify(mediaList: MutableList<MediaStoreFile>) {
            this.mediaList = mediaList
            notifyDataSetChanged()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Data loading should ideally be done in onViewCreated or observing LiveData
        // to ensure views are ready. For now, just initialize the adapter.
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _fragmentGalleryBinding = FragmentGalleryBinding.inflate(inflater, container, false)
        return fragmentGalleryBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Get the output directory from arguments
        val rootDirectoryPath = args.rootDirectory // Corrected argument name
        val outputDirectory = if (rootDirectoryPath.isNotEmpty()) File(rootDirectoryPath) else null

        lifecycleScope.launch {
            // Only query images if outputDirectory is available
            outputDirectory?.let {
                mediaList = MediaStoreUtils.getImages(requireContext(), it) // <-- แก้ไขตรงนี้
            } ?: run {
                // If no specific directory is passed, query all app-specific images (if any)
                mediaList = MediaStoreUtils.getImages(requireContext(), CameraFragment.getOutputDirectory(requireContext())) // <-- แก้ไขตรงนี้
            }

            fragmentGalleryBinding.photoViewPager.apply {
                offscreenPageLimit = 2
                adapter = MediaPagerAdapter(childFragmentManager, mediaList)
            }

            val hasItems = mediaList.isNotEmpty()
            fragmentGalleryBinding.deleteButton.isEnabled = hasItems
            fragmentGalleryBinding.shareButton.isEnabled = hasItems
            hasMediaItems.complete(hasItems)
        }


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            fragmentGalleryBinding.cutoutSafeArea.padWithDisplayCutout()
        }

        fragmentGalleryBinding.backButton.setOnClickListener {
            Navigation.findNavController(requireActivity(), R.id.fragment_container).navigateUp()
        }

        fragmentGalleryBinding.shareButton.setOnClickListener {
            mediaList.getOrNull(fragmentGalleryBinding.photoViewPager.currentItem)?.let { mediaStoreFile ->
                val mediaFile = mediaStoreFile.file
                val intent = Intent().apply {
                    val mediaType = MimeTypeMap.getSingleton()
                        .getMimeTypeFromExtension(mediaFile.extension)
                    type = mediaType
                    action = Intent.ACTION_SEND
                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    putExtra(Intent.EXTRA_STREAM, mediaStoreFile.uri)
                }
                startActivity(Intent.createChooser(intent, getString(R.string.share_hint)))
            }
        }

        fragmentGalleryBinding.deleteButton.setOnClickListener {
            mediaList.getOrNull(fragmentGalleryBinding.photoViewPager.currentItem)?.let { mediaStoreFile ->
                val mediaFile = mediaStoreFile.file
                AlertDialog.Builder(view.context, android.R.style.Theme_Material_Dialog)
                    .setTitle(getString(R.string.delete_title))
                    .setMessage(getString(R.string.delete_dialog))
                    .setIcon(android.R.drawable.ic_dialog_alert)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        mediaFile.delete()
                        MediaScannerConnection.scanFile(
                            view.context, arrayOf(mediaFile.absolutePath), null, null
                        )
                        mediaList.removeAt(fragmentGalleryBinding.photoViewPager.currentItem)
                        fragmentGalleryBinding.photoViewPager.adapter?.notifyDataSetChanged()

                        if (mediaList.isEmpty()) {
                            Navigation.findNavController(requireActivity(), R.id.fragment_container).navigateUp()
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .create()
                    .showImmersive()
            }
        }
    }

    override fun onDestroyView() {
        _fragmentGalleryBinding = null
        super.onDestroyView()
    }
}