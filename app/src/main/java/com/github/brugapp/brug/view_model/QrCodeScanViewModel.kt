package com.github.brugapp.brug.view_model

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.util.Log
import android.widget.EditText
import androidx.core.app.ActivityCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.budiyev.android.codescanner.*
import com.github.brugapp.brug.R
import com.github.brugapp.brug.data.ConvRepository
import com.github.brugapp.brug.data.ItemsRepository
import com.github.brugapp.brug.data.MessageRepository
import com.github.brugapp.brug.data.UserRepository
import com.github.brugapp.brug.di.sign_in.brug_account.BrugSignInAccount
import com.github.brugapp.brug.model.message_types.LocationMessage
import com.github.brugapp.brug.model.message_types.TextMessage
import com.github.brugapp.brug.model.services.DateService
import com.github.brugapp.brug.model.services.LocationService
import com.google.android.gms.location.LocationServices
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.time.LocalDateTime

class QrCodeScanViewModel : ViewModel() {

    private lateinit var codeScanner: CodeScanner

    /**
     * Checks if permissions for the camera & location are granted,
     * and asks the user for them if it is not the case.
     *
     * @param context the activity from which the permissions are asked
     *
     */
    fun checkPermissions(context: Context) {
        val permissionRequestCode = 1
        val permissions = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if(!hasPermissions(context, permissions)){
            ActivityCompat.requestPermissions(context as Activity, permissions, permissionRequestCode)
        }
    }

    private fun hasPermissions(context: Context, permissions: Array<String>): Boolean = permissions.all {
        ActivityCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Initializes the scanner and sets the callback for the scanner.
     *
     * @param context the activity from which the scanner is initialized
     *
     */
    fun codeScanner(activity: Activity) {
        val scannerView = activity.findViewById<CodeScannerView>(R.id.scanner_view)
        codeScanner = CodeScanner(activity.applicationContext, scannerView)
        codeScanner.apply {
            camera = CodeScanner.CAMERA_BACK
            formats = CodeScanner.ALL_FORMATS
            autoFocusMode = AutoFocusMode.SAFE
            scanMode = ScanMode.CONTINUOUS
            isAutoFocusEnabled = true
            isFlashEnabled = false
            // HERE LIES THE CODE HANDLING WHAT HAPPENS AFTER SCANNING THE QR CODE
            decodeCallback = DecodeCallback {
                activity.runOnUiThread {
                    activity.findViewById<EditText>(R.id.edit_message).setText(it.text)
                }
            }
            errorCallback = ErrorCallback {
                activity.runOnUiThread {
                    Log.e("Camera initialization error:", it.message.toString())
                }
            }
        }
    }

    /**
     * Parses the content of the scanned QR code and notifies the owner of the found item
     * if the QR content is valid.
     *
     * @param qrText the content of the scanned QR code
     * @param context the activity from which we want to have UI interaction for error messages
     *
     * @return the feedback message to display to the user, to put into a snackbar
     */
    suspend fun parseTextAndCreateConv(qrText: String,
                            context: Activity,
                            firebaseAuth: FirebaseAuth,
                            firestore: FirebaseFirestore,
                            firebaseStorage: FirebaseStorage): String {

        if(!hasPermissions(context, arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION))){
            Log.e("PERMISSIONS ERROR", "NOT GRANTED")
            return "ERROR: The camera and/or location permissions are not granted."
        }
        else if (qrText.isBlank() || !qrText.contains(":")) {
            return "ERROR: The item ID is badly formatted or is blank."
        } else {
            val isAnonymous = firebaseAuth.currentUser == null
            val convID = createNewConversation(isAnonymous, qrText, firebaseAuth, firestore)
            return if (convID == null) {
                if(isAnonymous) firebaseAuth.signOut()
                "ERROR: The user/item don't exist, or you already notified the user for this object."
            } else {
                val senderName = if(isAnonymous) "Anonymous User" else "Me"
                getLocationAndNotifyUser(
                    senderName,
                    convID,
                    firebaseAuth.uid!!,
                    context,
                    qrText,
                    firestore,
                    firebaseAuth,
                    firebaseStorage
                )
                if(isAnonymous) firebaseAuth.signOut()
                "Thank you ! The user will be notified."
            }
        }
    }

    private suspend fun createNewConversation(isAnonymous: Boolean,
                                              qrText: String,
                                              firebaseAuth: FirebaseAuth,
                                              firestore: FirebaseFirestore): String? {
        if (isAnonymous) {
            val auth = firebaseAuth.signInAnonymously().await().user ?: return null
            UserRepository.addUserFromAccount(
                auth.uid,
                BrugSignInAccount("Anonymous", "User", "", ""),
                false,
                firestore
            )
        }

        val userID = qrText.split(":")[0]

        val response = ConvRepository.addNewConversation(
            firebaseAuth.currentUser!!.uid,
            userID,
            qrText,
            null,
            firestore
        )

        return if (response.onSuccess) firebaseAuth.currentUser!!.uid + userID else null
    }

    @SuppressLint("MissingPermission")
    private fun getLocationAndNotifyUser(
        senderName: String,
        convID: String,
        authUID: String,
        context: Activity,
        qrText: String,
        firestore: FirebaseFirestore,
        firebaseAuth: FirebaseAuth,
        firebaseStorage: FirebaseStorage
    ) {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

        fusedLocationClient.lastLocation.addOnSuccessListener { lastKnownLocation: Location? ->
            if (lastKnownLocation != null) {
                sendMessages(senderName, lastKnownLocation, convID, authUID, firestore, firebaseAuth, firebaseStorage)
                viewModelScope.launch { setItemLastLocation(qrText, lastKnownLocation, firestore) }

            } else {
                // Launch the locationListener (updates every 1000 ms)
                val locationGpsProvider = LocationManager.GPS_PROVIDER
                locationManager.requestLocationUpdates(
                    locationGpsProvider, 50, 0.1f
                ) {
                    sendMessages(senderName, it, convID, authUID, firestore, firebaseAuth, firebaseStorage)
                    viewModelScope.launch { setItemLastLocation(qrText, it, firestore) }
                }

                // Stop the update as we only want it once (at least for now)
                locationManager.removeUpdates {
                    sendMessages(senderName, it, convID, authUID, firestore, firebaseAuth, firebaseStorage)
                    viewModelScope.launch { setItemLastLocation(qrText, it, firestore) }
                }
            }
        }
    }

    private fun sendMessages(
        senderName: String,
        location: Location,
        convID: String,
        authUID: String,
        firestore: FirebaseFirestore,
        firebaseAuth: FirebaseAuth,
        firebaseStorage: FirebaseStorage) {

        listOf(
            LocationMessage(
                senderName,
                DateService.fromLocalDateTime(LocalDateTime.now()),
                "📍 Location",
                LocationService.fromAndroidLocation(location)
            ),

            TextMessage(
                senderName,
                DateService.fromLocalDateTime(LocalDateTime.now()),
                "Hey ! I just found your item, I have sent you my location so that you know where it was."
            )
        ).map { message ->
            viewModelScope.launch {
                MessageRepository.addMessageToConv(
                    message,
                    true,
                    authUID,
                    convID,
                    firestore,
                    firebaseAuth,
                    firebaseStorage
                )
            }
        }
    }

    private suspend fun setItemLastLocation(
        qrStr: String,
        location: Location,
        firestore: FirebaseFirestore
    ): Boolean{
        val (userID, itemID) = qrStr.split(":")
        return ItemsRepository.addLastLocation(
            userID,
            itemID,
            LocationService.fromAndroidLocation(location),
            firestore
        ).onSuccess
    }

    /**
    * starts the camera
    */
    fun startPreview() {
        codeScanner.startPreview()
    }

    /**
     * releases the camera when the activity is destroyed or the app is closed/paused
    */
    fun releaseResources() {
        codeScanner.releaseResources()
    }

}