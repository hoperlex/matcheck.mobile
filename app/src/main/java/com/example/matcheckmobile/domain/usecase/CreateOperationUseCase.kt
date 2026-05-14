package com.example.matcheckmobile.domain.usecase

import com.example.matcheckmobile.data.local.entity.MaterialOperationEntity
import com.example.matcheckmobile.data.repository.OperationRepository
import com.example.matcheckmobile.domain.model.OperationType

class CreateOperationUseCase(
    private val repository: OperationRepository,
) {
    suspend operator fun invoke(
        type: OperationType,
        siteId: String,
        materialNameRaw: String,
        quantity: Double,
        unit: String,
        userId: String,
        deviceId: String,
        vehicleNumber: String?,
        driverName: String?,
        comment: String?,
        materialId: String? = null,
    ): MaterialOperationEntity = repository.createOperation(
        type = type,
        siteId = siteId,
        materialId = materialId,
        materialNameRaw = materialNameRaw,
        quantity = quantity,
        unit = unit,
        userId = userId,
        deviceId = deviceId,
        vehicleNumber = vehicleNumber,
        driverName = driverName,
        comment = comment,
    )
}
