package com.dynamsoft.cameraxvideo

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.dynamsoft.license.LicenseManager


class MainActivity : AppCompatActivity() {
    private var PERMISSIONS_REQUIRED = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        load()
        LicenseManager.initLicense(
            "DLS2eyJoYW5kc2hha2VDb2RlIjoiMjAwMDAxLTE2NDk4Mjk3OTI2MzUiLCJvcmdhbml6YXRpb25JRCI6IjIwMDAwMSIsInNlc3Npb25QYXNzd29yZCI6IndTcGR6Vm05WDJrcEQ5YUoifQ==",
            this
        ) { isSuccessful, e ->
            if (!isSuccessful) {
                e?.printStackTrace()
            }
        }
    }

    private fun load(){
        setContentView(R.layout.activity_main)
        var startRecordingButton = findViewById<Button>(R.id.startRecordingButton)
        startRecordingButton.setOnClickListener {
            startRecording()
        }
        var showVideoButton = findViewById<Button>(R.id.showVideoButton)
        showVideoButton.setOnClickListener {
            showVideoSelectionDialog(false)
        }
        var batchTestButton = findViewById<Button>(R.id.batchTestButton)
        batchTestButton.setOnClickListener {
            showVideoSelectionDialog(true)
        }

        var liveScanButton = findViewById<Button>(R.id.liveScanButton)
        liveScanButton.setOnClickListener {
            startLiveScan()
        }


        // add the storage access permission request for Android 9 and below.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            val permissionList = PERMISSIONS_REQUIRED.toMutableList()
            permissionList.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            PERMISSIONS_REQUIRED = permissionList.toTypedArray()
        }

        if (!hasPermissions(this)) {
            // Request camera-related permissions
            activityResultLauncher.launch(PERMISSIONS_REQUIRED)
        }
    }

    private fun updateIntent(intent: Intent){
        val radioButton720P = findViewById<RadioButton>(R.id.radioButton720P)
        val radioButton1080P = findViewById<RadioButton>(R.id.radioButton1080P)
        val radioButton4K = findViewById<RadioButton>(R.id.radioButton4K)
        val durationEditText = findViewById<EditText>(R.id.durationEditText)
        intent.putExtra("duration",Integer.parseInt(durationEditText.text.toString()))

        var resolution = "720P"
        if (radioButton720P.isChecked) {
            resolution = "720P"
        }else if (radioButton1080P.isChecked) {
            resolution = "1080P"
        }else {
            resolution = "4K"
        }
        intent.putExtra("resolution",resolution)
    }

    private fun startRecording() {
        var intent = Intent(this,CameraActivity::class.java)
        updateIntent(intent)
        startActivity(intent)
    }

    private fun startLiveScan() {
        val SDKs =arrayOf("DBR","ZXing")
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Pick a SDK to test")
            .setItems(
                SDKs,
                DialogInterface.OnClickListener { dialog, which ->
                    var intent = Intent(this,LiveScanActivity::class.java)
                    intent.putExtra("SDK",SDKs[which])
                    updateIntent(intent)
                    startActivity(intent)
                })
        builder.create().show()
    }


    private fun hasPermissions(context: Context) = PERMISSIONS_REQUIRED.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    private val activityResultLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions())
        { permissions ->
            // Handle Permission granted/rejected
            var permissionGranted = true
            permissions.entries.forEach {
                if (it.key in PERMISSIONS_REQUIRED && it.value == false)
                    permissionGranted = false
            }
            if (!permissionGranted) {
                Toast.makeText(this, "Permission request denied", Toast.LENGTH_LONG).show()
            }
        }

    private fun showVideoSelectionDialog(multiple:Boolean) {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.setType("*/*")
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple);
        val chooseFile = Intent.createChooser(intent, "Pick files")
        if (multiple) {
            getMultipleSelectionResult.launch(intent)
        }else{
            getResult.launch(intent)
        }

    }

    private val getResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) {
            Log.d("dbg", it.data!!.data!!.toString())
            var intent = Intent(this,VideoActivity::class.java)
            intent.putExtra("uri",it.data!!.data!!.toString())
            startActivity(intent)
        }
    }

    private val getMultipleSelectionResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) {
            var fileUris = ArrayList<String>()
            if (it.data!!.data != null){ //single file
                fileUris.add(it.data!!.data!!.toString())
            }else{ //multiple selection
                val clipData = it.data!!.clipData!!
                val count: Int = clipData.itemCount
                var currentItem = 0

                while (currentItem < count) {
                    val uri: Uri = clipData.getItemAt(currentItem).getUri()
                    Log.d("DBR","uri: " +uri.toString())
                    fileUris.add(uri.toString())
                    currentItem = currentItem + 1
                }
            }

            var intent = Intent(this,BatchTestActivity::class.java)
            intent.putExtra("files", fileUris)
            startActivity(intent)
        }
    }
}