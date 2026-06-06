package com.example.focusforlife.core

import com.example.focusforlife.logging.FocusLogger
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Syncs usage state with Firebase Realtime Database.
 *
 * Each device writes its own node under /devices/{deviceId} and listens
 * for changes from other devices. Combined usage is exposed via
 * [remoteDailySeconds] and [remoteHourlyUsedSeconds].
 */
object FocusSync {

    private const val DEVICE_ID = "android"

    private val db = FirebaseDatabase.getInstance(
        "https://YOUR-PROJECT-default-rtdb.YOUR-REGION.firebasedatabase.app"
    )
    private val devicesRef = db.getReference("devices")

    @Volatile var remoteDailySeconds: Long = 0L
        private set
    @Volatile var remoteHourlyUsedSeconds: Long = 0L
        private set

    private var listening = false

    /** Start listening for remote device changes. Call once at app startup. */
    fun startListening() {
        if (listening) return
        listening = true

        devicesRef.addValueEventListener(object : ValueEventListener {
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
        })
    }

    /** Push local usage state to Firebase. Call after each usage update. */
    fun pushLocalState(dailySeconds: Long, hourlyUsedSeconds: Long, hourlyStamp: Long) {
        val state = mapOf(
            "date" to LocalDate.now().toString(),
            "daily_seconds" to dailySeconds,
            "hourly_used_seconds" to hourlyUsedSeconds,
            "hourly_stamp" to hourlyStamp,
        )
        devicesRef.child(DEVICE_ID).setValue(state)
    }
}
