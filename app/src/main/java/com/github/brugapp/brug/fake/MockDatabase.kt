package com.github.brugapp.brug.fake

import com.github.brugapp.brug.model.User

class MockDatabase {

    companion object {
        val currentUser = User("Rayan", "Kikou", "rayan.kikou@gmail.com", "0")
    }

}