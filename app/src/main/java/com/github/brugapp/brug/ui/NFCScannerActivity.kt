package com.github.brugapp.brug.ui

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.nfc.FormatException
import android.nfc.NfcAdapter
import android.nfc.NfcAdapter.ACTION_TAG_DISCOVERED
import android.nfc.Tag
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.liveData
import com.github.brugapp.brug.R
import com.github.brugapp.brug.SUCCESS_TEXT
import com.github.brugapp.brug.view_model.NFCScanViewModel
import com.github.brugapp.brug.view_model.QrCodeScanViewModel
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import java.io.IOException
import javax.inject.Inject

@AndroidEntryPoint
open class NFCScannerActivity: AppCompatActivity() {
    private val Error_detected = "No NFC was found!"
    private val Write_success = "Text written successfully"
    private val Write_error = "Error occurred during writing, try again"
    private val viewModel: NFCScanViewModel by viewModels()
    private val qrviewModel: QrCodeScanViewModel by viewModels()
    var writeMode: Boolean = false
    var tag: Tag? = null
    var adapter: NfcAdapter? = null
    lateinit var context: Context
    private lateinit var nfcIntent: PendingIntent
    private lateinit var writingTagFilters: Array<IntentFilter>
    lateinit var scanMessage: TextView
    lateinit var nfcContents: TextView
    private lateinit var activateButton: Button
    var writebool: String? = null
    var itemid: String? = null


    @Inject
    lateinit var firestore: FirebaseFirestore

    @Inject
    lateinit var firebaseStorage: FirebaseStorage

    @Inject
    lateinit var firebaseAuth: FirebaseAuth

    /**
     * To test the report item functionality:
     * 1) sign in to unlost.app account
     * 2) add an nfc item
     * 3) log out of account and scan nfc anonymously
     * 3) log back in to initial account and go to new conversation linked to lost object (takes 10-15s to load)
     * @param savedInstanceState
     */

    @SuppressLint("SetTextI18n")
    public override fun onCreate(savedInstanceState: Bundle?){ super.onCreate(savedInstanceState)
        context = this
        setContentView(R.layout.activity_nfc_scanner)
        viewModel.checkNFCPermission(this)
        adapter = viewModel.setupAdapter(this)
        findViews() //maybe return early if false
        if (adapter==null) Toast.makeText(this,"This device doesn't support NFC!",Toast.LENGTH_SHORT).show() //if (adapter==null) finish()
        nfcIntent = viewModel.setupWritingTagFilters(this).first
        writingTagFilters = viewModel.setupWritingTagFilters(this).second
        writebool = intent.getStringExtra("write")
        itemid = intent.getStringExtra("itemid")

        if(writebool==null || itemid==null){
            activateButton.visibility = View.GONE
            activateButton.isClickable = false
        }
        activateButton.setOnClickListener {
            try {
                if (tag == null) Toast.makeText(this, Error_detected, Toast.LENGTH_LONG).show() //no tag
                else  { //no firestore link
                    nfcLinks(nfcContents.text.toString())
                    nfcContents.text = firebaseAuth.currentUser?.uid+':'+itemid
                    viewModel.write(nfcContents.text.toString(), tag!!)
                    Toast.makeText(this, "new item created:"+nfcContents.text.toString(), Toast.LENGTH_LONG).show()
                    writebool = null
                    itemid = null
                    activateButton.visibility = View.GONE
                    val myIntent = Intent(this, ItemsMenuActivity::class.java)
                    startActivity(myIntent)
                }
            } catch (e: Exception) {
                when (e) {
                    is IOException, is FormatException -> {
                        Toast.makeText(this, Write_error, Toast.LENGTH_LONG).show()
                        e.printStackTrace()
                    }
                    else -> throw e
                }
            }
        }
    }


    /**
     * checks text input, if valid we send user to sign-in or chat menu correspondingly
     * @param editable
     * @return
     */

    fun nfcLinks(editable: String) {
        val newContext = this
        liveData(Dispatchers.IO){
            emit(qrviewModel.parseTextAndCreateConv(
                editable,
                newContext,
                firebaseAuth,
                firestore,
                firebaseStorage
            ))
        }.observe(newContext) { resultTxt ->
            Toast.makeText(context, resultTxt, Toast.LENGTH_LONG).show()
            if (resultTxt == SUCCESS_TEXT) {
                val myIntent = if (firebaseAuth.currentUser == null) {
                    Intent(this, SignInActivity::class.java)
                } else {
                    Intent(this, ChatMenuActivity::class.java)
                }
                startActivity(myIntent)
            }
        }
    }

    /**
     * abbreviates the onCreate method by finding buttons & textviews
     * @return true iff all textviews & buttons are found
     */
    fun findViews(): Boolean{
        scanMessage = findViewById<View>(R.id.scanMessage) as TextView
        nfcContents = findViewById<View>(R.id.nfcContents) as TextView
        activateButton = findViewById<View>(R.id.buttonReportItem) as Button
        return ::scanMessage.isInitialized && ::nfcContents.isInitialized && ::activateButton.isInitialized
    }

    /**
     * disables the nfc write mode while activity is paused
     */
    public override fun onPause() {
        super.onPause()
        writeModeOff()
    }

    /**
     * enables the nfc write mode once activity is resumed
     */
    public override fun onResume(){
        super.onResume()
        writeModeOn()
    }

    /**
     * allows us to stop writing to NFC tag when app is paused
     */
    fun writeModeOff(){
        writeMode = true
        if(adapter!=null) {
            adapter!!.disableForegroundDispatch(this)
        }
    }

    /**
     * allows us to write to NFC tag as long as app is started/resumed
     *
     */
    fun writeModeOn(){
        if(adapter!=null) {
            adapter!!.enableForegroundDispatch(this,nfcIntent,writingTagFilters,null)
        }
    }

    /**
     * allows us to assign a tag for a corresponding intent
     *
     * @param intent
     */
    public override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        setIntent(intent)
        viewModel.readFromIntent(nfcContents,intent)
        if ((ACTION_TAG_DISCOVERED) == intent.action){
            tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)!!
        }
        nfcLinks(nfcContents.text.toString()) //maybe remove this
    }
}
