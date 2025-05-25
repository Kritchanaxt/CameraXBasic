package com.android.example.cameraxbasic.fragments

import android.annotation.SuppressLint
import android.content.*
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.concurrent.futures.await
import androidx.core.content.ContextCompat
import androidx.core.net.toFile
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.navigation.Navigation
import androidx.navigation.fragment.navArgs // เพิ่มการนำเข้านี้
import com.android.example.cameraxbasic.KEY_EVENT_ACTION
import com.android.example.cameraxbasic.KEY_EVENT_EXTRA
import com.android.example.cameraxbasic.R
import com.android.example.cameraxbasic.databinding.CameraUiContainerBinding
import com.android.example.cameraxbasic.databinding.FragmentCameraBinding
import com.android.example.cameraxbasic.utils.ANIMATION_FAST_MILLIS
import com.android.example.cameraxbasic.utils.simulateClick
import com.android.example.cameraxbasic.utils.MediaStoreUtils
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import android.media.MediaScannerConnection // Import MediaScannerConnection

class CameraFragment : Fragment() {

    // ใช้ navArgs() เพื่อรับ arguments ที่ส่งมาจาก Navigation Component
    private val args: CameraFragmentArgs by navArgs()

    private var _fragmentCameraBinding: FragmentCameraBinding? = null
    private val fragmentCameraBinding get() = _fragmentCameraBinding!!

    private var _cameraUiContainerBinding: CameraUiContainerBinding? = null
    private val cameraUiContainerBinding get() = _cameraUiContainerBinding!!

    private lateinit var outputDirectory: File
    private lateinit var broadcastManager: LocalBroadcastManager

    private var displayId: Int = -1
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null

    /** Blocking camera operations are performed using this executor */
    private lateinit var cameraExecutor: ExecutorService

    /** Volume down button receiver */
    private val volumeDownReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(KEY_EVENT_EXTRA, KeyEvent.KEYCODE_UNKNOWN)) {
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    // Use safe call operator for cameraCaptureButton
                    cameraUiContainerBinding.cameraCaptureButton?.simulateClick()
                }
            }
        }
    }

    private val displayManager by lazy {
        requireContext().getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    }

    /**
     * We need a display listener for orientation changes that do not trigger a configuration
     * change, for example if we choose to display the preview in a 'portrait' aspect ratio but
     * the device is held in a 'landscape' orientation. In this case, the activity "stays" rotated
     * but the system bars will still place themselves correctly in a "landscape" orientation.
     */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) = view?.let { view ->
            if (displayId == this@CameraFragment.displayId) {
                Log.d(TAG, "Rotation changed: ${view.display.rotation}")
                imageCapture?.targetRotation = view.display.rotation
                imageAnalyzer?.targetRotation = view.display.rotation
            }
        } ?: Unit
    }

    override fun onResume() {
        super.onResume()
        // Make sure that all permissions are still present, since the
        // user could have removed them while the app was in the background.
        if (!PermissionsFragment.hasPermissions(requireContext())) {
            Navigation.findNavController(requireActivity(), R.id.fragment_container).navigate(
                PermissionsFragmentDirections.actionPermissionsToSelector()) // Changed to SelectorFragment
        }
    }

    override fun onDestroyView() {
        _fragmentCameraBinding = null
        _cameraUiContainerBinding = null // Ensure this is nulled out too
        super.onDestroyView()

        // Shut down our background executor
        cameraExecutor.shutdown()

        // Unregister the broadcast receivers and listeners
        broadcastManager.unregisterReceiver(volumeDownReceiver)
        displayManager.unregisterDisplayListener(displayListener)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _fragmentCameraBinding = FragmentCameraBinding.inflate(inflater, container, false)
        return fragmentCameraBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize our background executor
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Every time the orientation of device changes, update the preview layout
        displayManager.registerDisplayListener(displayListener, null)

        // Local broadcasts to receive D-pad key events
        broadcastManager = LocalBroadcastManager.getInstance(view.context)
        val filter = IntentFilter().apply { addAction(KEY_EVENT_ACTION) }
        broadcastManager.registerReceiver(volumeDownReceiver, filter)

        // Determine the output directory
        outputDirectory = getOutputDirectory(requireContext())

        // Wait for the views to be properly laid out
        fragmentCameraBinding.viewFinder.post {
            // Keep track of the display in which this view is currently attached
            displayId = fragmentCameraBinding.viewFinder.display.displayId

            // Build UI controls
            updateCameraUi()

            // Set up the camera and its use cases
            lifecycleScope.launch {
                startCamera()
            }
        }
    }

    /**
     * Inflate camera controls and bind to the UI.
     */
    private fun updateCameraUi() {

        // Remove previous UI if any
        _cameraUiContainerBinding?.root?.let {
            fragmentCameraBinding.cameraContainer.removeView(it)
        }

        _cameraUiContainerBinding = CameraUiContainerBinding.inflate(
            LayoutInflater.from(requireContext()),
            fragmentCameraBinding.cameraContainer,
            true
        )

        // Listener for button used to capture photo
        cameraUiContainerBinding.cameraCaptureButton?.setOnClickListener { // Use safe call
            // Get a stable reference of the modifiable image capture use case
            imageCapture?.let { imageCapture ->

                // Create output file to hold the image
                val photoFile = createFile(outputDirectory, FILENAME, PHOTO_EXTENSION)

                // Setup image capture metadata
                val metadata = ImageCapture.Metadata().apply {
                    // Mirror image when using the front camera
                    // ใช้ args.cameraTypeInt แทน args.cameraSelector.lensFacing
                    isReversedHorizontal = args.cameraTypeInt == SelectorFragment.CAMERA_TYPE_FRONT
                }

                // Create output options object which contains file + metadata
                val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile)
                    .setMetadata(metadata)
                    .build()

                // Setup image capture listener which is triggered after photo has been taken
                imageCapture.takePicture(
                    outputOptions, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
                        override fun onError(exc: ImageCaptureException) {
                            Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                        }

                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            val savedUri = output.savedUri ?: Uri.fromFile(photoFile)
                            Log.d(TAG, "Photo capture succeeded: $savedUri")

                            // We can only change the foreground Drawable using API level 23+
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                // Update the gallery thumbnail with latest picture taken
                                // Removed the problematic 'set};' line
                            }

                            // Implicit intent to launch the gallery app
                            val galleryIntent = Intent(Intent.ACTION_VIEW).apply {
                                val mimeType = MimeTypeMap.getSingleton()
                                    .getMimeTypeFromExtension(savedUri.toFile().extension)
                                setDataAndType(savedUri, mimeType)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            startActivity(galleryIntent)

                            // If the folder selected is an external media directory, this is
                            // unnecessary but other folders might still need it.
                            val mimeType = MimeTypeMap.getSingleton()
                                .getMimeTypeFromExtension(savedUri.toFile().extension)
                            MediaScannerConnection.scanFile(
                                context,
                                arrayOf(savedUri.toFile().absolutePath),
                                arrayOf(mimeType)
                            ) { path: String, uri: Uri? -> // Explicitly define types for lambda parameters
                                Log.d(TAG, "Image capture scanned into media store: $uri")
                            }

                            // Navigate to the PhotoFragment to display the captured image
                            lifecycleScope.launch {
                                // Add the captured image to MediaStore
                                val mediaStoreFile = MediaStoreUtils.addImageToMediaStore(
                                    requireContext().contentResolver,
                                    photoFile.name,
                                    System.currentTimeMillis(),
                                    outputDirectory,
                                    savedUri,
                                    mimeType ?: "image/jpeg" // Default to JPEG if mimeType is null
                                )
                                mediaStoreFile?.let {
                                    val action = CameraFragmentDirections.actionCameraToPhoto(it)
                                    Navigation.findNavController(requireView()).navigate(action)
                                } ?: run {
                                    Toast.makeText(context, "Failed to save image to MediaStore", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    })

                // We can only change the foreground Drawable using API level 23+
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    // Display flash animation to indicate that photo was captured
                    fragmentCameraBinding.cameraContainer.postDelayed({
                        fragmentCameraBinding.cameraContainer.foreground = ColorDrawable(Color.WHITE)
                        fragmentCameraBinding.cameraContainer.postDelayed(
                            { fragmentCameraBinding.cameraContainer.foreground = null }, ANIMATION_FAST_MILLIS)
                    }, ANIMATION_FAST_MILLIS)
                }
            }
        }

        // Listener for button used to view the photo gallery
        cameraUiContainerBinding.photoViewButton?.setOnClickListener { // Use safe call
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val action = CameraFragmentDirections.actionCameraToGallery(outputDirectory.absolutePath)
                Navigation.findNavController(requireView()).navigate(action)
            } else {
                // Before Android Q, we don't need to pass the directory, as MediaStoreUtils will
                // query the whole MediaStore.
                val action = CameraFragmentDirections.actionCameraToGallery("")
                Navigation.findNavController(requireView()).navigate(action)
            }
        }
    }

    /**
     * Helper function used to locate the device's back camera
     */
    private fun findCamera(
        cameraProvider: ProcessCameraProvider,
        selector: CameraSelector
    ): CameraSelector? {
        return try {
            if (cameraProvider.hasCamera(selector)) {
                selector
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cannot get camera with selector: $selector", e)
            null
        }
    }

    /**
     * Declare and bind preview, capture and analysis use cases
     */
    @SuppressLint("MissingPermission") // Permissions are checked in PermissionsFragment
    private suspend fun startCamera() {
        val context = requireContext()
        cameraProvider = ProcessCameraProvider.getInstance(context).await()

        // Get the camera selector based on the argument received
        val cameraSelector = when (args.cameraTypeInt) {
            SelectorFragment.CAMERA_TYPE_BACK -> CameraSelector.DEFAULT_BACK_CAMERA
            SelectorFragment.CAMERA_TYPE_FRONT -> CameraSelector.DEFAULT_FRONT_CAMERA
            else -> {
                Log.e(TAG, "Invalid camera type received: ${args.cameraTypeInt}. Defaulting to back camera.")
                CameraSelector.DEFAULT_BACK_CAMERA // Fallback if an unexpected value is received
            }
        }

        // Select camera based on the argument
        val selectedCamera = findCamera(cameraProvider!!, cameraSelector)
            ?: findCamera(cameraProvider!!, CameraSelector.DEFAULT_BACK_CAMERA) // Fallback to back camera
            ?: findCamera(cameraProvider!!, CameraSelector.DEFAULT_FRONT_CAMERA) // Fallback to front camera
            ?: throw IllegalStateException("No camera available")

        // Build the preview use case
        preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
            .build()

        // Build the image capture use case
        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
            .build()

        // Attach the preview to the preview view
        preview?.setSurfaceProvider(fragmentCameraBinding.viewFinder.surfaceProvider)

        try {
            // Unbind all use cases before rebinding
            cameraProvider?.unbindAll()

            // Bind use cases to camera
            camera = cameraProvider?.bindToLifecycle(
                viewLifecycleOwner, selectedCamera, preview, imageCapture)

        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    companion object {
        private const val TAG = "CameraXBasic"
        private const val FILENAME = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val PHOTO_EXTENSION = ".jpg"
        private const val RATIO_4_3_VALUE = 4.0 / 3.0
        private const val RATIO_16_9_VALUE = 16.0 / 9.0

        /** Helper function used to create a timestamped file */
        private fun createFile(baseFolder: File, format: String, extension: String) =
            File(baseFolder, SimpleDateFormat(format, Locale.US).format(System.currentTimeMillis()) + extension)

        /** Use external media if it is available, our app's file directory otherwise */
        fun getOutputDirectory(context: Context): File {
            val appContext = context.applicationContext
            val mediaDir = context.externalMediaDirs.firstOrNull()?.let {
                File(it, appContext.resources.getString(R.string.app_name)).apply { mkdirs() }
            }
            return if (mediaDir != null && mediaDir.exists()) mediaDir else appContext.filesDir
        }
    }

    /**
     * Helper function to get the aspect ratio of the current display
     */
    private fun aspectRatio(width: Int, height: Int): Int {
        val previewRatio = max(width, height).toDouble() / min(width, height)
        if (abs(previewRatio - RATIO_4_3_VALUE) <= abs(previewRatio - RATIO_16_9_VALUE)) {
            return AspectRatio.RATIO_4_3
        }
        return AspectRatio.RATIO_16_9
    }

    private val windowManager by lazy {
        requireContext().getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }
}
typealias LumaListener = (luma: Double) -> Unit