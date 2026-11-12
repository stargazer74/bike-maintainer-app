package de.bikemaintainer.app.ui.profiles

import de.bikemaintainer.app.domain.AppError
import de.bikemaintainer.app.domain.Outcome
import de.bikemaintainer.app.domain.Profile
import de.bikemaintainer.app.ui.FakeAuthRepository
import de.bikemaintainer.app.ui.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ProfilesViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private val auth = FakeAuthRepository()
    private val max = Profile(1, "Max")
    private val anna = Profile(2, "Anna")

    @Test fun loadsProfilesOnStart() {
        auth.profilesResult = Outcome.Success(listOf(max, anna))
        val vm = ProfilesViewModel(auth, "https://h")
        assertEquals(listOf(max, anna), vm.state.value.profiles)
        assertFalse(vm.state.value.loading)
        assertFalse(vm.state.value.loadFailed)
    }

    @Test fun loadFailureCanBeRetried() {
        auth.profilesResult = Outcome.Failure(AppError.Network)
        val vm = ProfilesViewModel(auth, "https://h")
        assertTrue(vm.state.value.loadFailed)
        assertFalse(vm.state.value.loading)
        auth.profilesResult = Outcome.Success(listOf(max))
        vm.reload()
        assertEquals(listOf(max), vm.state.value.profiles)
        assertFalse(vm.state.value.loadFailed)
        assertEquals(2, auth.profilesCalls)
    }

    @Test fun selectingProfileIsDone() {
        auth.profilesResult = Outcome.Success(listOf(max, anna))
        val vm = ProfilesViewModel(auth, "https://h")
        vm.select(2)
        assertEquals(listOf(2L), auth.selectProfileCalls)
        assertTrue(vm.state.value.done)
        assertNull(vm.state.value.selecting)
        assertNull(vm.state.value.error)
    }

    @Test fun secondSelectIsIgnoredWhileSelecting() {
        auth.profilesResult = Outcome.Success(listOf(max, anna))
        val vm = ProfilesViewModel(auth, "https://h")
        val gate = CompletableDeferred<Unit>()
        auth.selectProfileGate = gate
        vm.select(1)
        vm.select(2)
        assertEquals(1L, vm.state.value.selecting)
        assertEquals(listOf(1L), auth.selectProfileCalls)
        gate.complete(Unit)
        assertTrue(vm.state.value.done)
    }

    @Test fun deletedProfileShowsErrorAndReloads() {
        auth.profilesResult = Outcome.Success(listOf(max, anna))
        val vm = ProfilesViewModel(auth, "https://h")
        auth.selectProfileResult = Outcome.Failure(AppError.NotFound)
        auth.profilesResult = Outcome.Success(listOf(max))
        vm.select(2)
        assertEquals(ProfilesError.SELECT_FAILED, vm.state.value.error)
        assertFalse(vm.state.value.done)
        assertNull(vm.state.value.selecting)
        assertEquals(listOf(max), vm.state.value.profiles)
        assertEquals(2, auth.profilesCalls)
    }

    @Test fun otherFailureShowsErrorWithoutReload() {
        auth.profilesResult = Outcome.Success(listOf(max))
        val vm = ProfilesViewModel(auth, "https://h")
        auth.selectProfileResult = Outcome.Failure(AppError.Network)
        vm.select(1)
        assertEquals(ProfilesError.SELECT_FAILED, vm.state.value.error)
        assertEquals(1, auth.profilesCalls)
    }

    @Test fun storageFailureHasItsOwnError() {
        auth.profilesResult = Outcome.Success(listOf(max))
        val vm = ProfilesViewModel(auth, "https://h")
        auth.selectProfileResult = Outcome.Failure(AppError.Storage)
        vm.select(1)
        assertEquals(ProfilesError.STORAGE, vm.state.value.error)
        assertFalse(vm.state.value.done)
    }

    @Test fun createsAPersonAndSelectsItRightAway() {
        auth.profilesResult = Outcome.Success(listOf(max))
        auth.createProfileResult = Outcome.Success(anna)
        val vm = ProfilesViewModel(auth, "https://h")
        vm.openCreate()
        vm.onNewNameChange("  Anna ")
        vm.create()
        assertEquals(listOf("Anna"), auth.createProfileCalls)
        assertEquals(listOf(2L), auth.selectProfileCalls)
        assertEquals(listOf(max, anna), vm.state.value.profiles)
        assertTrue(vm.state.value.done)
        assertFalse(vm.state.value.creatingOpen)
    }

    @Test fun emptyNameIsNotSent() {
        val vm = ProfilesViewModel(auth, "https://h")
        vm.onNewNameChange("   ")
        vm.create()
        assertEquals(CreateProfileError.NAME_REQUIRED, vm.state.value.createError)
        assertTrue(auth.createProfileCalls.isEmpty())
    }

    @Test fun takenNameIsReported() {
        auth.createProfileResult = Outcome.Failure(AppError.Http(409, "CONFLICT"))
        val vm = ProfilesViewModel(auth, "https://h")
        vm.onNewNameChange("Max")
        vm.create()
        assertEquals(CreateProfileError.NAME_TAKEN, vm.state.value.createError)
        assertTrue(auth.selectProfileCalls.isEmpty())
        auth.createProfileResult = Outcome.Failure(AppError.Network)
        vm.create()
        assertEquals(CreateProfileError.FAILED, vm.state.value.createError)
    }

    @Test fun longNamesAreCutToTheLimit() {
        val vm = ProfilesViewModel(auth, "https://h")
        vm.onNewNameChange("x".repeat(80))
        assertEquals(50, vm.state.value.newName.length)
    }
}
