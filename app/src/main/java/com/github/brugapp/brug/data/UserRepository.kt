package com.github.brugapp.brug.data

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.util.Log
import androidx.core.graphics.drawable.toBitmap
import com.github.brugapp.brug.di.sign_in.SignInAccount
import com.github.brugapp.brug.model.MyUser
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.tasks.await
import java.io.ByteArrayOutputStream
import java.io.File

private const val USERS_ASSETS = "users_assets/"
private const val USERS_DB = "Users"

/**
 * Repository class handling bindings between the User objects in Firebase & in local.
 */
object UserRepository {

    /**
     * Adds a new user after a new account has been created, if the authenticated one is not already present in the database.
     *
     * @param authUID the user ID of the authenticated user
     * @param account the account with informations to populate the new user entry
     *
     * @return FirebaseResponse object, denoting if the new entry has correctly been added to the database
     */
    suspend fun addUserFromAccount(
        authUID: String, account: SignInAccount, firestore: FirebaseFirestore
    )
            : FirebaseResponse {
        val response = FirebaseResponse()

        try {
            val userDoc = firestore.collection(USERS_DB).document(authUID)
            if (!userDoc.get().await().exists()) {
                firestore.collection(USERS_DB).document(authUID).set(
                    mapOf(
                        "first_name" to account.firstName,
                        "last_name" to account.lastName
                    )
                ).await()
            }

            response.onSuccess = true

        } catch (e: Exception) {
            response.onError = e
        }
        return response
    }

    /**
     * Updates the (firstName, lastName) parameters of a given user, just after its fields were updated in local.
     *
     * @param user the user with updated parameters
     *
     * @return FirebaseResponse object, denoting if the entry to update has correctly been updated in Firebase
     */
    suspend fun updateUserFields(user: MyUser, firestore: FirebaseFirestore): FirebaseResponse {
        val response = FirebaseResponse()
        try {
            val userRef = firestore.collection(USERS_DB).document(user.uid)
            if (!userRef.get().await().exists()) {
                response.onError = Exception("User doesn't exist")
                return response
            }
            userRef.update(
                mapOf(
                    "first_name" to user.firstName,
                    "last_name" to user.lastName
                )
            ).await()
            response.onSuccess = true
        } catch (e: Exception) {
            response.onError = e
        }

        return response
    }

    /**
     * Uploads & updates the userIcon parameter of a given user to Firebase, just after it was updated in local.
     *
     * @param uid the user ID for which the image will be modified
     * @param newIcon the image to upload
     *
     * @return FirebaseResponse object, denoting if the upload + update to Firebase was successful
     */
    suspend fun updateUserIcon(
        uid: String, newIcon: Drawable,
        auth: FirebaseAuth, firebaseStorage: FirebaseStorage, firestore: FirebaseFirestore
    ): FirebaseResponse {
        val response = FirebaseResponse()
        try {
            // Uploading to Firebase Storage requires a signed-in user !
            val currentUser = auth.currentUser
            if (currentUser == null) { //|| currentUser.uid != uid NOT FOR THE MOMENT, SINCE USER ISN'T IN AUTH DATABASE
                response.onError = Exception("User is not signed in")
                return response
            }

            val iconPath = "$USERS_ASSETS$uid/$uid.jpg"

            val bitmap = newIcon
                .toBitmap(newIcon.intrinsicWidth, newIcon.intrinsicHeight, Bitmap.Config.ARGB_8888)
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, baos)
            val data = baos.toByteArray()

            // FIRST UPLOAD THE FILE TO THE LOCATION
            firebaseStorage.reference.child(iconPath).putBytes(data).await()

            // THEN UPDATE THE FIELD IN THE USER DOCUMENT
            firestore.collection(USERS_DB).document(uid).update("user_icon", iconPath).await()

            baos.close()
            response.onSuccess = true

        } catch (e: Exception) {
            response.onError = e
        }

        return response
    }

    /* ONLY FOR TEST PURPOSES */
    suspend fun resetUserIcon(uid: String, firestore: FirebaseFirestore): FirebaseResponse {
        val response = FirebaseResponse()
        try {
            val userRef = firestore.collection(USERS_DB).document(uid)
            if (!userRef.get().await().exists()) {
                response.onError = Exception("User doesn't exist")
                return response
            }

            firestore.collection(USERS_DB).document(uid).update(
                mapOf(
                    "user_icon" to ""
                )
            ).await()
            response.onSuccess = true
        } catch (e: Exception) {
            response.onError = e
        }

        return response
    }

    /**
     * Deletes a user from the database, given a user ID.
     *
     * @param uid the user ID
     *
     * @return FirebaseResponse object, denoting if the deletion was successful
     */
    suspend fun deleteUserFromID(uid: String, firestore: FirebaseFirestore): FirebaseResponse {
        val response = FirebaseResponse()
        try {
            val userRef = firestore.collection(USERS_DB).document(uid)
            if (!userRef.get().await().exists()) {
                response.onError = Exception("User doesn't exist")
                return response
            }

            firestore.collection(USERS_DB).document(uid).delete().await()
            response.onSuccess = true
        } catch (e: Exception) {
            response.onError = e
        }

        return response
    }

    /**
     * Fetches a user without its Items nor its Conversation objects, given a user ID.
     *
     * @param uid the user ID
     *
     * @return MyUser object instantiated with the parameters held in Firebase, or a null value in case of error
     */
    suspend fun getMinimalUserFromUID(
        uid: String,
        firestore: FirebaseFirestore,
        firebaseAuth: FirebaseAuth,
        firebaseStorage: FirebaseStorage
    )
            : MyUser? {
        try {
            val userDoc = firestore.collection(USERS_DB).document(uid).get().await()
            if (!userDoc.contains("first_name")
                || !userDoc.contains("last_name")
            ) {
                Log.e("FIREBASE ERROR", "Invalid User Format")
                return null
            }

            return if (userDoc.contains("user_icon")) {
                val userIconPath: String? = downloadUserIconInTemp(
                    uid, userDoc["user_icon"] as String,
                    firebaseAuth, firebaseStorage
                )
                MyUser(
                    uid,
                    userDoc["first_name"] as String,
                    userDoc["last_name"] as String,
                    userIconPath
                )
            } else MyUser(
                uid,
                userDoc["first_name"] as String,
                userDoc["last_name"] as String,
                null
            )

        } catch (e: Exception) {
            Log.e("FIREBASE ERROR", e.message.toString())
            return null
        }
    }

    private suspend fun downloadUserIconInTemp(
        uid: String, path: String,
        firebaseAuth: FirebaseAuth,
        firebaseStorage: FirebaseStorage
    )
            : String? {
        try {
            val file = File.createTempFile(uid, ".jpg")
            // Downloading from Firebase Storage requires a signed-in user !
            val currentUser = firebaseAuth.currentUser
            if (currentUser == null) { // || currentUser.uid != uid NOT FOR THE MOMENT, SINCE USER ISN'T IN AUTH DATABASE
                Log.e("FIREBASE ERROR", "User is not signed in")
                return null
            }

            firebaseStorage
                .getReference(path)
                .getFile(file)
                .await()
            return file.path

        } catch (e: Exception) {
            Log.e("FIREBASE ERROR", e.message.toString())
            return null
        }
    }
}