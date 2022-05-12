package com.github.brugapp.brug

import com.github.brugapp.brug.model.MyUser
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.core.IsEqual
import org.hamcrest.core.IsNot
import org.hamcrest.core.IsNull
import org.junit.Test

class MyUserTest {
    @Test
    fun initUserWithoutIconCorrectlyInitializesUser() {
        val userID = "DUMMYID"
        val firstName = "Rayan"
        val lastName = "Kikou"
        val userIconPath: String? = null

        val user = MyUser(userID, firstName, lastName, userIconPath)
        assertThat(user.uid, IsEqual(userID))
        assertThat(user.firstName, IsEqual(firstName))
        assertThat(user.lastName, IsEqual(lastName))
        assertThat(user.getUserIconPath(), IsNull.nullValue())
    }

    @Test
    fun comparingTwoIdenticalUsersReturnsEquality() {
        val user1 = MyUser("DUMMYID", "Rayan", "Kikou", null)
        val user2 = MyUser("DUMMYID", "Rayan", "Kikou", null)
        assertThat(user1, IsEqual(user2))
    }

    @Test
    fun comparingTwoAlmostIdenticalUsersReturnsFalse() {
        val user1 = MyUser("DUMMYID", "Rayan", "Kikou", null)
        val user2 = MyUser("DUMMYID2", "Rayan", "Kikou", null)
        assertThat(user1, IsNot(IsEqual(user2)))
    }
}