package com.github.brugapp.brug.di.sign_in

import android.content.Intent

abstract class SignInClient {

    abstract val signInIntent: Intent

    abstract fun signOut()
}