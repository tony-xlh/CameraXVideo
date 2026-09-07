package com.dynamsoft.cameraxvideo

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.util.Size
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.LifecycleOwner
import com.dynamsoft.core.basic_structures.EnumImagePixelFormat
import com.dynamsoft.core.basic_structures.ImageData
import com.dynamsoft.cvr.CaptureVisionRouter
import com.dynamsoft.cvr.EnumPresetTemplate
import com.dynamsoft.dbr.EnumBarcodeFormat
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import java.math.RoundingMode
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.Executors
import kotlin.concurrent.timerTask


class LiveScanActivity : AppCompatActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var cvr: CaptureVisionRouter
    private lateinit var resultTextView:TextView
    private lateinit var previewView:PreviewView
    private lateinit var previewImageView:ImageView
    private val zxingReader = MultiFormatReader().apply {
        val map = mapOf(
            DecodeHintType.POSSIBLE_FORMATS to arrayListOf(BarcodeFormat.QR_CODE, BarcodeFormat.EAN_13)
        )
        setHints(map)
    }
    private var framesProcessed = 0;
    private var framesProcessedWithBarcodeFound = 0;
    private var targetDuration:Long = 10000;
    private var elapsedTime:Long = 0;
    private var lastBarcodeResult = "";
    private var lastFrameProcessingTime:Long = -1;
    private var firstBarcodeResult = "";
    private var firstBarcodeFoundTime:Long = -1;

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_live_scan)
        targetDuration = (intent.getIntExtra("duration",10)*1000).toLong()
        resultTextView = findViewById(R.id.liveResultTextView)
        previewView = findViewById<PreviewView>(R.id.livePreviewView)
        val orientation = baseContext.resources.configuration.orientation
        previewView.updateLayoutParams<ConstraintLayout.LayoutParams> {
            if (orientation == Configuration.ORIENTATION_PORTRAIT) {
                dimensionRatio = "V,9:16"
            } else {
                dimensionRatio = "H,16:9"
            }
        }
        previewImageView= findViewById(R.id.previewImageView)
        previewImageView.visibility = View.INVISIBLE
        val decorView: View = window.decorView
        decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        initDBR()
        bindCameraUseCases()
    }

    private fun initDBR(){
        cvr = CaptureVisionRouter(this)
        try {
            val settings = cvr.getSimplifiedSettings(EnumPresetTemplate.PT_READ_BARCODES_SPEED_FIRST)
            val barcodeSettings = settings.barcodeSettings
            if (barcodeSettings != null) {
                barcodeSettings.barcodeFormatIds = EnumBarcodeFormat.BF_EAN_13 or EnumBarcodeFormat.BF_QR_CODE
                barcodeSettings.expectedBarcodesCount = 1
            }
            cvr.updateSettings(EnumPresetTemplate.PT_READ_BARCODES_SPEED_FIRST, settings)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateResult(){
        val sb:StringBuilder = StringBuilder()
        sb.append("time elapsed (ms):").append(elapsedTime).append("\n")
        var fps = (framesProcessed/(elapsedTime/1000.0)).toBigDecimal().setScale(2,RoundingMode.FLOOR).toDouble()
        sb.append("fps:").append(fps).append("\n")
        sb.append("frames processed:").append(framesProcessed).append("\n")
        sb.append("frames processed with barcodes found:").append(framesProcessedWithBarcodeFound).append("\n")
        sb.append("last frame processing time (ms):").append(lastFrameProcessingTime).append("\n")
        sb.append("last barcode result:").append(lastBarcodeResult).append("\n")
        sb.append("first barcode found time (ms):").append(firstBarcodeFoundTime).append("\n")
        sb.append("first barcode result:").append(firstBarcodeResult).append("\n")
        resultTextView.text = sb.toString()
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCameraUseCases() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener ({

            // Camera provider is now guaranteed to be available
            val cameraProvider = cameraProviderFuture.get()
            val orientation = baseContext.resources.configuration.orientation

            val targetResolution = intent.getStringExtra("resolution")
            var targetSDK = "DBR"
            if (intent.hasExtra("SDK")) {
                targetSDK = intent.getStringExtra("SDK")!!
            }

            var targetWidth:Int = 1280
            var targetHeight:Int = 720
            if (targetResolution == "720P") {
                targetWidth = 1280
                targetHeight = 720
            }else if (targetResolution == "1080P") {
                targetWidth = 1920
                targetHeight = 1080
            }else if (targetResolution == "4K") {
                targetWidth = 3840
                targetHeight = 2160
            }

            var resolution:Size
            if (orientation == Configuration.ORIENTATION_PORTRAIT) {
                resolution = Size(targetHeight,targetWidth)
            } else {
                resolution = Size(targetWidth,targetHeight)
            }

            // Set up the view finder use case to display camera preview
            val preview = Preview.Builder()
                .setTargetResolution(resolution)
                .build()

            // Set up the image analysis use case which will process frames in real time
            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(resolution)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()


            imageAnalysis.setAnalyzer(executor, ImageAnalysis.Analyzer { image ->
                if (elapsedTime<=targetDuration) {
                    decodeImage(image,targetSDK)
                    image.close()
                }else{
                    val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
                    YuvToRgbConverter(this).yuvToRgb(image.image!!,bitmap)
                    val rotated = rotateBitmap(bitmap,image.imageInfo.rotationDegrees)
                    runOnUiThread{
                        previewImageView.setImageBitmap(rotated)
                        previewImageView.visibility = View.VISIBLE
                        previewView.visibility = View.INVISIBLE
                    }
                    return@Analyzer
                }
            })

            // Create a new camera selector each time, enforcing lens facing
            val cameraSelector = CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK).build()

            // Apply declared configs to CameraX using the same lifecycle owner
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                this as LifecycleOwner, cameraSelector, preview, imageAnalysis)

            // Use the camera object to link our preview use case with the view
            preview.setSurfaceProvider(previewView.surfaceProvider)
            startTimer()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun rotateBitmap(source: Bitmap, degrees: Int): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(degrees.toFloat())
        return Bitmap.createBitmap(
            source, 0, 0, source.width, source.height, matrix, true
        )
    }

    private fun startTimer(){
        val timer = Timer()
        val period:Long = 5
        timer.scheduleAtFixedRate(timerTask {
            elapsedTime = elapsedTime + period
            if (elapsedTime<=targetDuration) {
                runOnUiThread {
                    updateResult()
                }
            }else{
                timer.cancel()
            }
        },0,period)
    }

    private fun decodeImage(image:ImageProxy,SDK:String){
        val buffer: ByteBuffer = image.planes[0].buffer
        val nRowStride = image.planes[0].rowStride
        val nPixelStride = image.planes[0].pixelStride
        val length: Int = buffer.remaining()
        val bytes = ByteArray(length)
        buffer.get(bytes)
        var startTime = System.currentTimeMillis()
        if (SDK == "DBR") {
            val imageData = ImageData()
            imageData.bytes = bytes
            imageData.width = image.width
            imageData.height = image.height
            imageData.stride = nRowStride * nPixelStride
            imageData.format = EnumImagePixelFormat.IPF_NV21
            val capturedResult = cvr.capture(imageData, EnumPresetTemplate.PT_READ_BARCODES_SPEED_FIRST)
            val barcodesResult = capturedResult?.decodedBarcodesResult
            if (barcodesResult != null && barcodesResult.items != null && barcodesResult.items.isNotEmpty()) {
                lastBarcodeResult = barcodesResult.items[0].text
                framesProcessedWithBarcodeFound++
                if (firstBarcodeFoundTime == (-1).toLong() ) {
                    firstBarcodeFoundTime = elapsedTime
                    firstBarcodeResult = lastBarcodeResult
                }
            }
        }else{
            val source = PlanarYUVLuminanceSource(
                bytes,
                image.width,
                image.height,
                0,
                0,
                image.width,
                image.height,
                false
            )

            val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
            try {
                val rawResult = zxingReader.decodeWithState(binaryBitmap)
                lastBarcodeResult = rawResult.text
                framesProcessedWithBarcodeFound++
                if (firstBarcodeFoundTime == (-1).toLong() ) {
                    firstBarcodeFoundTime = elapsedTime
                    firstBarcodeResult = lastBarcodeResult
                }
            } catch (e: NotFoundException) {
                e.printStackTrace()
            }
        }
        lastFrameProcessingTime = System.currentTimeMillis() - startTime
        framesProcessed++

    }
}