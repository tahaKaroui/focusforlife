package dev.focusforlife.android.core

import dev.focusforlife.android.BuildConfig
import dev.focusforlife.android.logging.FocusLogger
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import java.time.LocalDate

/**
 * Syncs usage state with Firebase Realtime Database.
 *
 * Every device signs in to one shared Firebase account (email/password) so they
 * all share a single uid, then writes its own node under
 * /users/{uid}/devices/{deviceId} and listens for changes from other devices.
 * Combined usage is exposed via [remoteDailySeconds] and [remoteHourlyUsedSeconds].
 *
 * Credentials and the database URL come from BuildConfig (injected from
 * local.properties — see docs/firebase-setup.md). When they are not configured
 * sync is silently disabled and the app runs standalone.
 */
object FocusSync {

    private const val DEVICE_ID = "android"

    private val auth = FirebaseAuth.getInstance()

    private val db: FirebaseDatabase? by lazy {
        val url = BuildConfig.FFL_FIREBASE_DB_URL
        if (url.isEmpty()) null else FirebaseDatabase.getInstance(url)
    }

    @Volatile private var devicesRef: DatabaseReference? = null

    @Volatile var remoteDailySeconds: Long = 0L
        private set
    @Volatile var remoteHourlyUsedSeconds: Long = 0L
        private set

    private var listening = false

    private val valueListener = object : ValueEventListener {
        override fun onDataChange(snapshot: DataSnapshot) {
            var dailySum = 0L
            var hourlySum = 0L
            val today = LocalDate.now().toString()
            val myStamp = FocusRules.currentHourStamp()

            for (child in snapshot.children) {
                if (child.key == DEVICE_ID) continue

                val date = child.child("date").getValue(String::class.java) ?: ""
                if (date == today) {
                    val daily = child.child("daily_seconds").getValue(Long::class.java) ?: 0L
                    dailySum += daily
                }

                val stamp = child.child("hourly_stamp").getValue(Long::class.java) ?: 0L
                if (stamp == myStamp) {
                    val hourly = child.child("hourly_used_seconds").getValue(Long::class.java) ?: 0L
                    hourlySum += hourly
                }
            }

            remoteDailySeconds = dailySum
            remoteHourlyUsedSeconds = hourlySum
        }

        override fun onCancelled(error: DatabaseError) {
            FocusLogger.w("Firebase listener cancelled: ${error.message}")
        }
    }

    /** Start listening for remote device changes. Call once at app startup. */
    fun startListening() {
        ensureAuth { attachListener() }
    }

    /** Push local usage state to Firebase. Call after each usage update. */
    fun pushLocalState(dailySeconds: Long, hourlyUsedSeconds: Long, hourlyStamp: Long) {
        val ref = devicesRef
        if (ref == null) {
            // Not authenticated yet — kick off sign-in; the next push will land.
            ensureAuth { }
            return
        }
        val state = mapOf(
            "date" to LocalDate.now().toString(),
            "daily_seconds" to dailySeconds,
            "hourly_used_seconds" to hourlyUsedSeconds,
            "hourly_stamp" to hourlyStamp,
        )
        ref.child(DEVICE_ID).setValue(state)
    }

    /**
     * Ensure we are signed in to the shared account and [devicesRef] points at
     * /users/{uid}/devices, then run [onReady]. No-op if sync is unconfigured.
     */
    private fun ensureAuth(onReady: () -> Unit) {
        val current = auth.currentUser
        if (current != null) {
            if (setupRef(current.uid)) onReady()
            return
        }
        if (BuildConfig.FFL_FIREBASE_EMAIL.isEmpty() || db == null) {
            FocusLogger.w("Firebase sync not configured; running standalone")
            return
        }
        auth.signInWithEmailAndPassword(
            BuildConfig.FFL_FIREBASE_EMAIL,
            BuildConfig.FFL_FIREBASE_PASSWORD,
        ).addOnSuccessListener { result ->
            val uid = result.user?.uid ?: return@addOnSuccessListener
            if (setupRef(uid)) onReady()
        }.addOnFailureListener { e ->
            FocusLogger.w("Firebase sign-in failed: ${e.message}")
        }
    }

    /** Resolve [devicesRef] for the given uid. Returns true if a ref is available. */
    @Synchronized
    private fun setupRef(uid: String): Boolean {
        if (devicesRef == null) {
            val database = db ?: return false
            devicesRef = database.getReference("users/$uid/devices")
        }
        return true
    }

    @Synchronized
    private fun attachListener() {
        if (listening) return
        val ref = devicesRef ?: return
        listening = true
        ref.addValueEventListener(valueListener)
    }
}
