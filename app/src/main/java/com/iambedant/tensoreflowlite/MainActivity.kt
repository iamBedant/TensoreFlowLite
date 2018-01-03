package com.iambedant.tensoreflowlite

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.os.Build
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.support.v4.content.ContextCompat
import android.util.Size
import android.util.SparseIntArray
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import kotlinx.android.synthetic.main.activity_main.*
import java.util.*

class MainActivity : AppCompatActivity() {


    private var cameraDevice: CameraDevice? = null
    private var cameraId: String? = null
    private val REQUEST_CAMERA_CODE: Int = 9
    private var previewSize: Size? = null
    private var captureRequestBuilder: CaptureRequest.Builder? = null

    private var backgroundHandler: Handler? = null
    private var backgroundHandlerThread: HandlerThread? = null

    private val oriantations = SparseIntArray().apply {
        append(Surface.ROTATION_0, 0)
        append(Surface.ROTATION_90, 90)
        append(Surface.ROTATION_180, 180)
        append(Surface.ROTATION_270, 270)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }

    private val surfactTextureListener = object : TextureView.SurfaceTextureListener {

        override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, i: Int, i1: Int) {
            setUpCamera(i, i1)
            connectCamera()
        }

        override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, i: Int, i1: Int) {

        }

        override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
            return false
        }

        override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {

        }
    }


    private val cameraDeviceStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(p0: CameraDevice?) {
            cameraDevice = p0
            startPreview()
        }

        override fun onDisconnected(p0: CameraDevice?) {
            p0?.close()
            cameraDevice = null
        }

        override fun onError(p0: CameraDevice?, p1: Int) {
            p0?.close()
            cameraDevice = null
        }
    }


    override fun onResume() {
        super.onResume()
        if (textureView.isAvailable) {
            setUpCamera(textureView.width, textureView.height)
            connectCamera()
        } else {
            textureView.surfaceTextureListener = surfactTextureListener
        }
        startBackgroundThread()
    }

    override fun onPause() {
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }


    private fun setUpCamera(width: Int, height: Int) {
        var cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        for (id in cameraManager.cameraIdList) {
            val cameraCharacteristics = cameraManager.getCameraCharacteristics(id)
            if (cameraCharacteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) {
                continue
            }

            val map = cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val deviceOrientation = windowManager.defaultDisplay.rotation
            val totalRotation = sensoreToDeviceRotation(cameraCharacteristics, deviceOrientation)
            val swapRotation = totalRotation == 90 || totalRotation == 270

            var rotatedWidth = width
            var rotatedHeight = height
            if (swapRotation) {
                rotatedWidth = height
                rotatedHeight = width
            }
            previewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture::class.java), rotatedWidth, rotatedHeight)

            cameraId = id
            return
        }
    }


    private fun connectCamera() {
        val cameramanager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                cameramanager.openCamera(cameraId, cameraDeviceStateCallback, backgroundHandler)
            } else {
                if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                    Toast.makeText(this, "This App requires camera permission", Toast.LENGTH_SHORT).show()
                }
                requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_CODE)
            }
        } else {
            cameramanager.openCamera(cameraId, cameraDeviceStateCallback, backgroundHandler)
        }

    }


    private fun startPreview() {
        var surfaceTexture = textureView.surfaceTexture
        surfaceTexture.setDefaultBufferSize(previewSize!!.width, previewSize!!.height)

        var previewSurface = Surface(surfaceTexture)
        captureRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        captureRequestBuilder!!.addTarget(previewSurface)

        cameraDevice!!.createCaptureSession(listOf(previewSurface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigureFailed(session: CameraCaptureSession?) {
                Toast.makeText(applicationContext, "Something went wrong with camera configurationn", Toast.LENGTH_SHORT).show()
            }

            override fun onConfigured(session: CameraCaptureSession?) {
                session?.setRepeatingRequest(captureRequestBuilder!!.build(), null, backgroundHandler)

            }

        }, null)

    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA_CODE) {
            if (grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Application can't run without camera permission", Toast.LENGTH_SHORT).show()

            }
        }
    }

    private fun closeCamera() {
        cameraDevice?.close()
        cameraDevice = null
    }


    private fun startBackgroundThread() {
        backgroundHandlerThread = HandlerThread("Camera2Thread")
        backgroundHandlerThread?.start()
        backgroundHandler = Handler(backgroundHandlerThread?.looper)
    }

    private fun stopBackgroundThread() {
        backgroundHandlerThread?.quitSafely()
        backgroundHandlerThread?.join()
        backgroundHandlerThread = null
        backgroundHandler = null
    }

    private fun sensoreToDeviceRotation(cameraCharacteristics: CameraCharacteristics, deviceOrientation: Int): Int {
        val sensorOrientation = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)
        return (sensorOrientation + oriantations.get(deviceOrientation) + 360) % 360
    }


    companion object {
        class CompareSizeByArea : Comparator<Size> {
            override fun compare(lsh: Size, rhs: Size): Int {
                return java.lang.Long.signum(((lsh.width * lsh.height) / (rhs.width * rhs.width)).toLong())
            }

        }

        fun chooseOptimalSize(choices: Array<Size>, width: Int, height: Int): Size {
            val bigEnough = choices.filter {
                it.height == it.width * height / width &&
                        it.width >= width &&
                        it.height >= height
            }
            if (bigEnough.isNotEmpty()) {
                return Collections.min(bigEnough, CompareSizeByArea())
            } else {
                return choices[0]
            }
        }
    }
}





















