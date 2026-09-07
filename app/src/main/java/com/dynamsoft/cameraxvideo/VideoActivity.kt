package com.dynamsoft.cameraxvideo

import android.app.AlertDialog
import android.content.DialogInterface
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.widget.*
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import com.dynamsoft.cvr.CaptureVisionRouter
import com.dynamsoft.cvr.EnumPresetTemplate
import com.dynamsoft.dbr.EnumBarcodeFormat
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.bytedeco.javacv.AndroidFrameConverter
import org.bytedeco.javacv.FFmpegFrameGrabber
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread
import kotlin.concurrent.timerTask

class VideoActivity : AppCompatActivity() {
    private lateinit var imageView: ImageView
    private lateinit var videoView: VideoView
    private lateinit var resultTextView: TextView
    private lateinit var cvr: CaptureVisionRouter
    private val zxingReader = MultiFormatReader().apply {
        val map = mapOf(
            DecodeHintType.POSSIBLE_FORMATS to arrayListOf(BarcodeFormat.QR_CODE,BarcodeFormat.EAN_13)
        )
        setHints(map)
    }
    private lateinit var decodeButton: Button
    private lateinit var sdkSpinner: Spinner
    private lateinit var uri:Uri
    private var keyFrameSpan = 1
    private var decoding = false
    private var framesWithBarcodeFound = 0
    private var framesProcessed = 0
    private var firstDecodedFrameIndex = -1
    private var firstBarcodeFoundPosition = -1
    private var firstFoundResult = ""
    private val sdkList = arrayOf<String?>("ZXing", "DBR")
    private var currentSDKIndex = 0
    private var benchmarkMode = false
    private lateinit var framesModeResult:FramesModeResult
    private lateinit var videoModeResult:VideoModeResult
    private var benchmarkResult = HashMap<String,SDKResult>()
    @RequiresApi(Build.VERSION_CODES.P)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video)
        initDBR();
        Log.d("DBR","video start.")
        Thread.sleep(1000)

        imageView = findViewById(R.id.imageView)
        videoView = findViewById(R.id.videoView)
        videoView.setOnCompletionListener {
            if (decodeButton.text == "Stop") {
                decodeButton.text = "Decode"
                if (benchmarkMode == true) {
                    decodeButton.setText("Stop")
                    Thread.sleep(1000)
                    resetStats()
                    decodeEveryFrame()
                }
            }
        }

        resultTextView = findViewById(R.id.resultTextView)
        sdkSpinner = findViewById<Spinner>(R.id.spinner)

        val mArrayAdapter = ArrayAdapter<Any?>(this, R.layout.spinner_list, sdkList)
        mArrayAdapter.setDropDownViewResource(R.layout.spinner_list)
        sdkSpinner.adapter = mArrayAdapter
        val benchmarkButton = findViewById<Button>(R.id.benchmarkButton)
        benchmarkButton.setOnClickListener {
            benchmark()
        }
        decodeButton = findViewById<Button>(R.id.decodeButton)
        decodeButton.setOnClickListener {
            if (decodeButton.text == "Stop") {
                decodeButton.text = "Decode"
                if (videoView.isPlaying){
                    videoView.stopPlayback()
                    videoView.setVideoURI(uri)
                }
            }else{
                resetStats()
                showDialog()
            }
        }

        uri = Uri.parse(intent.getStringExtra("uri"))
        videoView.setVideoURI(uri)
        val inputStream: InputStream? = contentResolver.openInputStream(uri)
        val frameGrabber = FFmpegFrameGrabber(inputStream)
        frameGrabber.start()
        val frame = frameGrabber.grabFrame()
        imageView.setImageBitmap(rotateBitmaptoFitScreen(AndroidFrameConverter().convert(frame)))
        frameGrabber.close()

        if (intent.hasExtra("automation")) {
            benchmark()
        }
    }

    private fun resetStats(){
        framesProcessed = 0
        framesWithBarcodeFound = 0
        firstBarcodeFoundPosition = -1
        firstDecodedFrameIndex = -1
        firstFoundResult = ""
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun benchmark() {
        resetStats()
        benchmarkMode = true
        sdkSpinner.setSelection(currentSDKIndex)
        decodeButton.setText("Stop")
        decodeVideo()
    }

    private fun initDBR(){
        cvr = CaptureVisionRouter(this)
        try {
            val settings = cvr.getSimplifiedSettings(EnumPresetTemplate.PT_READ_BARCODES)
            val barcodeSettings = settings.barcodeSettings
            if (barcodeSettings != null) {
                barcodeSettings.barcodeFormatIds = EnumBarcodeFormat.BF_EAN_13 or EnumBarcodeFormat.BF_QR_CODE
                barcodeSettings.expectedBarcodesCount = 1
            }
            cvr.updateSettings(EnumPresetTemplate.PT_READ_BARCODES, settings)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun decodeBitmap(bm:Bitmap,selectedPosition:Int):ArrayList<String> {
        val results:ArrayList<String> = ArrayList<String>()
        val selectedItem = sdkList[selectedPosition]
        if (selectedItem == "DBR") {
            val capturedResult = cvr.capture(bm, EnumPresetTemplate.PT_READ_BARCODES)
            val barcodesResult = capturedResult?.decodedBarcodesResult
            if (barcodesResult != null && barcodesResult.items != null) {
                for (item in barcodesResult.items) {
                    results.add(item.text)
                    Log.d("DBR","confidence: "+item.confidence)
                }
            }
        }else{
            val multiFormatReader = zxingReader

            val width: Int = bm.getWidth()
            val height: Int = bm.getHeight()

            val pixels = IntArray(width * height)
            bm.getPixels(pixels, 0, width, 0, 0, width, height)

            val source = RGBLuminanceSource(width, height, pixels)

            if (source != null) {
                val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
                try {
                    val rawResult = multiFormatReader.decodeWithState(binaryBitmap)
                    results.add(rawResult.text)
                } catch (re: ReaderException) {
                    re.printStackTrace()
                } finally {
                    multiFormatReader.reset()
                }
            }
        }
        return results
    }

    private fun updateResults(textResults:ArrayList<String>,currentPosition:Int,elapsedTime:Long) {
        val sb:StringBuilder = StringBuilder()
        sb.append(currentPosition).append("/").append(videoView.duration).append("\n")
        sb.append("frame reading time: ").append(elapsedTime).append("ms").append("\n")
        sb.append(generalDecodingResults(textResults))
        resultTextView.setText(sb.toString())
    }

    private fun updateResults(textResults:ArrayList<String>,totalFrames:Int,currentFrameIndex:Int,elapsedTime:Long) {
        val sb:StringBuilder = StringBuilder()
        sb.append(currentFrameIndex+1).append("/").append(totalFrames).append("\n")
        sb.append("frame reading time: ").append(elapsedTime).append("ms").append("\n")
        sb.append(generalDecodingResults(textResults))
        resultTextView.setText(sb.toString())
    }

    private fun generalDecodingResults(textResults:ArrayList<String>):String {
        val sb:StringBuilder = StringBuilder()
        sb.append("frames processed: ").append(framesProcessed).append("\n")
        sb.append("frames with barcodes found: ").append(framesWithBarcodeFound).append("\n")

        if (firstDecodedFrameIndex != -1){
            sb.append("first decoded frame index: ").append(firstDecodedFrameIndex).append("\n")
        }

        if (firstBarcodeFoundPosition != -1){
            sb.append("first barcode found position: ").append(firstBarcodeFoundPosition).append("\n")
        }

        if (firstFoundResult != "") {
            sb.append("first barcode result: ").append(firstFoundResult).append("\n")
        }

        if (textResults.size>0){
            for (tr in textResults) {
                sb.append(tr)
                sb.append("\n")
            }
        }else{
            sb.append("No barcodes found").append("\n")
        }
        return sb.toString()
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun showDialog() {
        val options = arrayOf<String>("Decode every frame", "Play and decode")
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Pick a video")
            .setItems(options,
                DialogInterface.OnClickListener { dialog, which ->
                    startDecoding(which)
                })
        builder.create().show()
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun startDecoding(which:Int){
        decodeButton.setText("Stop")
        if (which == 0) {
            decodeEveryFrame()
        }else {
            decodeVideo()
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun decodeEveryFrame() {
        imageView.visibility = View.VISIBLE
        videoView.visibility = View.INVISIBLE
        val inputStream: InputStream? = contentResolver.openInputStream(uri)
        val frameGrabber = FFmpegFrameGrabber(inputStream)
        frameGrabber.start()

        val decodingResults = ArrayList<FrameDecodingResult>()

        val totalFrames = frameGrabber.lengthInVideoFrames
        //val totalFrames = 2
        val th = thread(start=true) {
            for (i in 0..totalFrames-1) {

                if (decodeButton.text != "Stop") {
                    break
                }

                if (keyFrameSpan != 1) {
                    if (i%keyFrameSpan != 0){
                        continue
                    }else{
                        frameGrabber.setVideoFrameNumber(i-1)
                    }
                }

                val frame = frameGrabber.grabFrame()
                var bm = AndroidFrameConverter().convert(frame)
                bm = rotateBitmaptoFitScreen(bm)
                val startTime = System.currentTimeMillis()
                val textResults = decodeBitmap(bm,sdkSpinner.selectedItemPosition)
                framesProcessed++
                val endTime = System.currentTimeMillis()
                var frameDecodingResult = FrameDecodingResult(textResults,endTime - startTime)
                decodingResults.add(frameDecodingResult)

                if (textResults.size>0) {
                    framesWithBarcodeFound++
                    if (firstDecodedFrameIndex == -1) {
                        firstDecodedFrameIndex = i
                        firstFoundResult = textResults[0]
                    }
                }

                runOnUiThread {
                    updateResults(textResults,totalFrames,i,endTime - startTime)
                    imageView.setImageBitmap(bm)
                }

            }
            frameGrabber.close()
            framesModeResult = getFrameModeStatistics(decodingResults)
            runOnUiThread {
                decodeButton.text = "Decode"
                if (benchmarkMode == true) {
                    val SDKResult = SDKResult(framesModeResult,videoModeResult)
                    benchmarkResult.put(sdkSpinner.selectedItem.toString(),SDKResult)
                    if (currentSDKIndex != sdkList.size - 1) {
                        currentSDKIndex = currentSDKIndex + 1
                        benchmark()
                    }else{
                        val string = Json.encodeToString(benchmarkResult)
                        Log.d("DBR",string)
                        val pattern = "yyyy-MM-dd-HH-mm-ss-SSS"
                        val simpleDateFormat = SimpleDateFormat(pattern)
                        val date: String = simpleDateFormat.format(Date())
                        val outputPath = writeStringAsFile(string,date+".json")
                        if (intent.hasExtra("automation")) {
                            val f = File(outputPath)
                            if (f.exists()) {
                                val resultData = Intent()
                                resultData.putExtra("outputPath",outputPath)
                                this.setResult(RESULT_OK, resultData);
                            }
                            this.finish()
                        }
                    }
                }

            }
        }
    }

    private fun writeStringAsFile(fileContents: String?, fileName: String):String {
        try {
            val externalFilesPath = getExternalFilesDir("")?.absolutePath
            var outputPath = externalFilesPath + "/" + fileName
            val out = FileWriter(File(outputPath))
            out.write(fileContents)
            out.close()
            return outputPath
        } catch (e: IOException) {
        }
        return ""
    }

    private fun getVideoModeStatistics(decodingResults:HashMap<Int,FrameDecodingResult>):VideoModeResult {
        val result = VideoModeResult(decodingResults,framesWithBarcodeFound,framesProcessed,firstBarcodeFoundPosition,firstFoundResult)
        return result
    }

    private fun getFrameModeStatistics(decodingResults:ArrayList<FrameDecodingResult>):FramesModeResult {
        val result = FramesModeResult(decodingResults,framesWithBarcodeFound,framesProcessed,firstDecodedFrameIndex,firstFoundResult)
        return result
    }

    private fun rotateBitmaptoFitScreen(bm:Bitmap):Bitmap {
        if (baseContext.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
            if (bm.width>bm.height) {
                return rotateBitmap(bm,90f)
            }
        }
        return bm
    }

    private fun rotateBitmap(source: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(degrees)
        return Bitmap.createBitmap(
            source, 0, 0, source.width, source.height, matrix, true
        )
    }

    private fun centerCrop(source: Bitmap): Bitmap {
        val left = 0
        val width = source.width
        val top = source.height/2 - source.width/2
        val height = source.width
        return Bitmap.createBitmap(source, left, top, width, height)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun decodeVideo(){
        imageView.visibility = View.INVISIBLE
        videoView.visibility = View.VISIBLE

        val decodingResults = HashMap<Int,FrameDecodingResult>()

        val timer = Timer()
        timer.scheduleAtFixedRate(timerTask {
            if (decodeButton.text != "Stop") {
                timer.cancel()
            }else{
                try {
                    if (videoView.isPlaying) {
                        val position = videoView.currentPosition //in ms

                        if (decoding == false) {
                            decoding = true
                            usePixelCopy(videoView){ bitmap: Bitmap? ->

                                val bm = rotateBitmaptoFitScreen(bitmap!!)
                                val startTime = System.currentTimeMillis()
                                val textResults = decodeBitmap(bm, sdkSpinner.selectedItemPosition)
                                framesProcessed++
                                decoding = false
                                val endTime = System.currentTimeMillis()
                                var frameDecodingResult = FrameDecodingResult(textResults,endTime - startTime)
                                decodingResults.put(position, frameDecodingResult)

                                if (decodeButton.text == "Stop"){
                                    if (textResults.size>0) {
                                        framesWithBarcodeFound++
                                        if (firstBarcodeFoundPosition == -1) {
                                            firstBarcodeFoundPosition = position
                                            firstFoundResult = textResults[0]
                                        }
                                    }
                                    videoModeResult = getVideoModeStatistics(decodingResults)
                                    runOnUiThread {
                                        updateResults(textResults,position,endTime - startTime)
                                    }
                                }
                            }
                        }
                    }
                }catch (exc:Exception) {
                    exc.printStackTrace()
                }
            }
        },0,2)
        videoView.start()
    }

    /**
     * Pixel copy to copy SurfaceView/VideoView into BitMap
     * source: https://stackoverflow.com/questions/27434087/how-to-capture-screenshot-or-video-frame-of-videoview-in-android
     */
    @RequiresApi(Build.VERSION_CODES.N)
    fun usePixelCopy(videoView: SurfaceView, callback: (Bitmap?) -> Unit) {
        val bitmap: Bitmap = Bitmap.createBitmap(
            videoView.width,
            videoView.height,
            Bitmap.Config.ARGB_8888
        );
        try {
            // Create a handler thread to offload the processing of the image.
            val handlerThread = HandlerThread("PixelCopier");
            handlerThread.start();
            PixelCopy.request(
                videoView, bitmap,
                PixelCopy.OnPixelCopyFinishedListener { copyResult ->
                    if (copyResult == PixelCopy.SUCCESS) {
                        callback(bitmap)
                    }else{
                        decoding = false
                    }
                    handlerThread.quitSafely();
                },
                Handler(handlerThread.looper)
            )
        } catch (e: IllegalArgumentException) {
            callback(null)
            // PixelCopy may throw IllegalArgumentException, make sure to handle it
            e.printStackTrace()
        }
    }
}