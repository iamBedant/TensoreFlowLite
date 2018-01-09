package com.iambedant.tensoreflowlite

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.*
import android.hardware.camera2.*
import android.media.ImageReader
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.support.v4.content.ContextCompat
import android.util.Size
import android.view.Surface
import android.view.TextureView
import com.iambedant.tf_lite_module.Predictions
import com.iambedant.tf_lite_module.TfLiteClassifier
import kotlinx.android.synthetic.main.activity_main2.*
import timber.log.Timber
import java.io.IOException
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

class Main2Activity : AppCompatActivity() {

    private var DIM_IMG_SIZE= 224
    private var imageClassifier: TfLiteClassifier? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var runClassifier = false
    private val lock = Any()
    private var cameraDevice: CameraDevice? = null
    private var checkPermission = false
    private val PERMISSION_REQUEST_CODE = 100
    private var imageReader: ImageReader? = null
    private var previewRequest: CaptureRequest? = null

    private val MAX_PREVIEW_WIDTH = 1920
    private val MAX_PREVIEW_HEIGHT = 1080
    private var previewSize: Size? = null
    private val cameraOpenCloseLock = Semaphore(1)
    private var cameraId: String? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var captureSession: CameraCaptureSession? = null

    internal var captureCallback: CameraCaptureSession.CaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureProgressed(session: CameraCaptureSession, request: CaptureRequest, partialResult: CaptureResult) {
            super.onCaptureProgressed(session, request, partialResult)
        }

        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            super.onCaptureCompleted(session, request, result)
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main2)
        try {
            imageClassifier = TfLiteClassifier.TfLiteClassifierBuilder(this)
                    .setLabelPath("labels.txt")
                    .setModelPath("mobilenet_quant_v1_224.tflite")
                    .setImageDimention(DIM_IMG_SIZE)
                    .build()
        } catch (e: IOException) {
            Timber.e("Failed to initialize image classifier")
        }

    }

    public override fun onDestroy() {
        imageClassifier?.close()
        super.onDestroy()
    }

    public override fun onPause() {
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    private fun closeCamera() {
        try {
            cameraOpenCloseLock.acquire()
            if (null != captureSession) {
                captureSession?.close()
                captureSession = null
            }
            if (null != cameraDevice) {
                cameraDevice?.close()
                cameraDevice = null
            }
            if (null != imageReader) {
                imageReader?.close()
                imageReader = null
            }
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera closing.", e)
        } finally {
            cameraOpenCloseLock.release()
        }
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("Thread_name")
        backgroundThread?.start()
        backgroundHandler = Handler(backgroundThread?.looper)
        synchronized(lock) {
            runClassifier = true
        }
        backgroundHandler?.post(periodicClassify)
    }

    private val periodicClassify = object : Runnable {
        override fun run() {
            synchronized(lock) {
                if (runClassifier) {
                    classifyFrame()
                }
            }
            backgroundHandler?.post(this)
        }
    }

    private fun classifyFrame() {
        if (imageClassifier == null || this == null || cameraDevice == null) {
            "Uninitialized Classifier or invalid context.".toast(this)
            return
        }
        val bitmap = textureView.getBitmap(DIM_IMG_SIZE, DIM_IMG_SIZE)

        val textToShow: List<Predictions>? = (imageClassifier?.classifyFrameToList(bitmap))?.reversed()


        this.runOnUiThread({
            tvPredOne.text = textToShow?.get(0)?.thing
            tvPredOnePer.text = textToShow?.get(0)?.probability
            tvPredTwo.text = textToShow?.get(1)?.thing
            tvPredTwoPer.text = textToShow?.get(1)?.probability
            tvPredThree.text = textToShow?.get(2)?.thing
            tvPredThreePer.text = textToShow?.get(2)?.probability
        })



        bitmap.recycle()
    }


    public override fun onResume() {
        super.onResume()
        if (cameraDevice != null) {
            startBackgroundThread()
        }
        if (textureView.isAvailable) {
            openCamera(textureView.width, textureView.height)
        } else {
            textureView.surfaceTextureListener = surfaceTextureListener
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(width: Int, height: Int) {

        if (!checkPermission && !allPermissionsGranted()) {
            requestPermissions(getRequiredPermissions(), PERMISSION_REQUEST_CODE)
            return
        } else {
            checkPermission = true
        }

        setUpCameraOutputs(width, height)
        val activity = this
        val manager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Time out waiting to lock camera opening.")
            }
            manager.openCamera(cameraId, stateCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera opening.", e)
        }

    }

    internal var stateCallback: CameraDevice.StateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(currentDevice: CameraDevice) {
            cameraOpenCloseLock.release()
            cameraDevice = currentDevice
            createCameraPreviewSession()
        }

        override fun onDisconnected(currentDevice: CameraDevice) {
            cameraOpenCloseLock.release()
            currentDevice.close()
            cameraDevice = null
        }

        override fun onError(currentDevice: CameraDevice, i: Int) {
            cameraOpenCloseLock.release()
            currentDevice.close()
            cameraDevice = null
            val activity = this
            if (activity != null) {
                finish()
            }
        }
    }
    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
            openCamera(height, width)
        }

        override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        }

        override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
            return true
        }

        override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {

        }
    }

    private fun allPermissionsGranted(): Boolean {
        for (permission in getRequiredPermissions()) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false
            }
        }
        return true
    }

    private fun getRequiredPermissions(): Array<String> {
        val activity = this
        return try {
            val info = activity
                    .packageManager
                    .getPackageInfo(activity.packageName, PackageManager.GET_PERMISSIONS)
            val ps = info.requestedPermissions
            if (ps != null && ps.isNotEmpty()) {
                ps
            } else {
                emptyArray()
            }
        } catch (e: Exception) {
            emptyArray()
        }

    }

    private fun setUpCameraOutputs(width: Int, height: Int) {
        val activity = this
        val manager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            for (cameraId in manager.cameraIdList) {
                val characteristics = manager.getCameraCharacteristics(cameraId)

                // We don't use a front facing camera in this sample.
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    continue
                }

                val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue

                // // For still image captures, we use the largest available size.
                val largest = Collections.max(
                        Arrays.asList(*map.getOutputSizes(ImageFormat.JPEG)), CompareSizeByArea())
                imageReader = ImageReader.newInstance(
                        largest.width, largest.height, ImageFormat.JPEG, /*maxImages*/ 2)

                // Find out if we need to swap dimension to get the preview size relative to sensor
                // coordinate.
                val displayRotation = activity.getWindowManager().getDefaultDisplay().getRotation()

                /* Orientation of the camera sensor */
                val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)!!
                var swappedDimensions = false
                when (displayRotation) {
                    Surface.ROTATION_0, Surface.ROTATION_180 -> if (sensorOrientation == 90 || sensorOrientation == 270) {
                        swappedDimensions = true
                    }
                    Surface.ROTATION_90, Surface.ROTATION_270 -> if (sensorOrientation == 0 || sensorOrientation == 180) {
                        swappedDimensions = true
                    }
                    else -> Timber.e("Display rotation is invalid: " + displayRotation)
                }

                val displaySize = Point()
                activity.getWindowManager().getDefaultDisplay().getSize(displaySize)
                var rotatedPreviewWidth = width
                var rotatedPreviewHeight = height
                var maxPreviewWidth = displaySize.x
                var maxPreviewHeight = displaySize.y

                if (swappedDimensions) {
                    rotatedPreviewWidth = height
                    rotatedPreviewHeight = width
                    maxPreviewWidth = displaySize.y
                    maxPreviewHeight = displaySize.x
                }

                if (maxPreviewWidth > MAX_PREVIEW_WIDTH) {
                    maxPreviewWidth = MAX_PREVIEW_WIDTH
                }

                if (maxPreviewHeight > MAX_PREVIEW_HEIGHT) {
                    maxPreviewHeight = MAX_PREVIEW_HEIGHT
                }

                previewSize = chooseOptimalSize(
                        map.getOutputSizes(SurfaceTexture::class.java),
                        rotatedPreviewWidth,
                        rotatedPreviewHeight,
                        maxPreviewWidth,
                        maxPreviewHeight,
                        largest)

                // We fit the aspect ratio of TextureView to the size of preview we picked.
                val orientation = resources.configuration.orientation
                if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    textureView.setAspectRatio(previewSize!!.width, previewSize!!.height)
                } else {
                    textureView.setAspectRatio(previewSize!!.height, previewSize!!.width)
                }

                this.cameraId = cameraId
                return
            }
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        } catch (e: NullPointerException) {
            Timber.e(e.localizedMessage)
        }

    }


    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
            synchronized(lock) {
                runClassifier = false
            }
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }

    }


    private fun createCameraPreviewSession() {
        try {
            val texture = textureView.surfaceTexture!!

            // We configure the size of default buffer to be the size of camera preview we want.
            texture.setDefaultBufferSize(previewSize!!.getWidth(), previewSize!!.getHeight())

            // This is the output Surface we need to start preview.
            val surface = Surface(texture)

            // We set up a CaptureRequest.Builder with the output Surface.
            previewRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewRequestBuilder?.addTarget(surface)

            // Here, we create a CameraCaptureSession for camera preview.
            cameraDevice?.createCaptureSession(
                    Arrays.asList(surface),
                    object : CameraCaptureSession.StateCallback() {

                        override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                            // The camera is already closed
                            if (null == cameraDevice) {
                                return
                            }

                            // When the session is ready, we start displaying the preview.
                            captureSession = cameraCaptureSession
                            try {
                                // Auto focus should be continuous for camera preview.
                                previewRequestBuilder?.set(
                                        CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)

                                // Finally, we start displaying the camera preview.
                                previewRequest = previewRequestBuilder?.build()
                                captureSession?.setRepeatingRequest(
                                        previewRequest, captureCallback, backgroundHandler)
                            } catch (e: CameraAccessException) {
                                e.printStackTrace()
                            }

                        }

                        override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {
                            "Failed".toast(applicationContext)
                        }
                    }, null)

            startBackgroundThread()
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }

    }

    companion object {
        class CompareSizeByArea : Comparator<Size> {
            override fun compare(lsh: Size, rhs: Size): Int {
                return java.lang.Long.signum(((lsh.width * lsh.height) / (rhs.width * rhs.width)).toLong())
            }

        }

        private fun chooseOptimalSize(
                choices: Array<Size>,
                textureViewWidth: Int,
                textureViewHeight: Int,
                maxWidth: Int,
                maxHeight: Int,
                aspectRatio: Size): Size {

            // Collect the supported resolutions that are at least as big as the preview Surface
            val bigEnough = ArrayList<Size>()
            // Collect the supported resolutions that are smaller than the preview Surface
            val notBigEnough = ArrayList<Size>()
            val w = aspectRatio.width
            val h = aspectRatio.height
            for (option in choices) {
                if (option.width <= maxWidth
                        && option.height <= maxHeight
                        && option.height == option.width * h / w) {
                    if (option.width >= textureViewWidth && option.height >= textureViewHeight) {
                        bigEnough.add(option)
                    } else {
                        notBigEnough.add(option)
                    }
                }
            }

            // Pick the smallest of those big enough. If there is no one big enough, pick the
            // largest of those not big enough.
            if (bigEnough.size > 0) {
                return Collections.min(bigEnough, CompareSizeByArea())
            } else if (notBigEnough.size > 0) {
                return Collections.max(notBigEnough, CompareSizeByArea())
            } else {
                Timber.e("Couldn't find any suitable preview size")
                return choices[0]
            }
        }
    }
}

