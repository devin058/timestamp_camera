package com.devin.ts_camera.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.devin.ts_camera.model.LocationData
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Provides the most recent device location via Google Fused Location Provider.
 *
 * Usage:
 *   val provider = LocationProvider(context)
 *   provider.location.collect { location -> ... }
 *   provider.start()
 *   // ... later ...
 *   provider.stop()
 */
class LocationProvider(private val context: Context) {

    private val fusedClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private var locationCallback: LocationCallback? = null

    /** Hot flow that emits the latest [LocationData] each time the device moves. */
    @SuppressLint("MissingPermission")
    val location: Flow<LocationData> = callbackFlow {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000L)
            .setMinUpdateIntervalMillis(2_000L)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { loc ->
                    trySend(loc.toLocationData())
                }
            }
        }
        locationCallback = callback

        fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
            .addOnFailureListener { close(it) }

        // Also get the last known location immediately
        fusedClient.lastLocation.addOnSuccessListener { loc: Location? ->
            if (loc != null) trySend(loc.toLocationData())
        }

        awaitClose {
            fusedClient.removeLocationUpdates(callback)
            locationCallback = null
        }
    }

    /** Convenience: fire-and-forget start. The Flow can be collected independently. */
    fun start() { /* Flow is cold – no-op; collection drives registration */ }

    fun stop() {
        locationCallback?.let { fusedClient.removeLocationUpdates(it) }
        locationCallback = null
    }
}

private fun Location.toLocationData() = LocationData(
    latitude = latitude,
    longitude = longitude,
    timestamp = time
)
