package com.example.matcheckmobile.domain.model

import com.example.matcheckmobile.R

data class VehicleType(
    val code: String,
    val name: String,
    val volumeM3: Double,
    val payloadTons: Double,
    val iconRes: Int,
) {
    val payloadKg: Double get() = payloadTons * 1000.0
}

val VEHICLE_TYPES: List<VehicleType> = listOf(
    VehicleType("light", "Газель", 12.0, 1.8, R.drawable.ic_vehicle_light),
    VehicleType("truck6m", "Грузовик 6м", 38.0, 5.0, R.drawable.ic_vehicle_truck6m),
    VehicleType("semitrail", "Полуприцеп", 65.0, 12.0, R.drawable.ic_vehicle_semitrail),
    VehicleType("eurotruck", "Фура", 92.0, 22.0, R.drawable.ic_vehicle_eurotruck),
)

fun vehicleTypeByCode(code: String?): VehicleType? =
    code?.let { c -> VEHICLE_TYPES.firstOrNull { it.code == c } }
