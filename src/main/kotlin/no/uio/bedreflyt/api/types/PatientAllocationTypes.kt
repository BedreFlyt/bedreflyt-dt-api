package no.uio.bedreflyt.api.types

import jakarta.annotation.Nullable
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern

data class PatientAllocationRequest (
    @NotBlank(message = "Patient ID is required")
    @NotNull(message = "Patient ID is required")
    // The pattern is for a UUID
    @Pattern(regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$", message = "Patient ID is invalid")
    val patientId: String,
    @NotBlank(message = "Acute status is required")
    @NotNull(message = "Acute status is required")
    @Pattern(regexp = "^(true|false)$", message = "Acute status is invalid")
    val acute: Boolean,
    @NotBlank(message = "Diagnosis code is required")
    @NotNull(message = "Diagnosis code is required")
    // The Pattern for ICD-10 codes is letter two digit, possible dot and eventually more digits
    @Pattern(regexp = "^[A-Z][0-9]{2}(\\.[0-9]+)?$", message = "Diagnosis code is invalid")
    val diagnosisCode: String,
    @NotBlank(message = "Diagnosis name is required")
    @NotNull(message = "Diagnosis name is required")
    @Pattern(regexp = "^[a-zA-Z]+(?:[\\s-][a-zA-Z]+)*$", message = "Diagnosis name is invalid")
    val diagnosisName: String,
    @Nullable
    @Pattern(regexp = "^[0-9]+$", message = "Acute category is invalid")
    val acuteCategory: Int?,
    @Nullable
    @Pattern(regexp = "^[0-9]+$", message = "Care category is invalid")
    val careCategory: Int?,
    @Nullable
    @Pattern(regexp = "^[0-9]+$", message = "Monitoring category is invalid")
    val monitoringCategory: Int?,
    @Nullable
    @Pattern(regexp = "^[0-9]+$", message = "Care ID is invalid")
    val careId: Int?,
    @NotBlank(message = "Contagious status is required")
    @NotNull(message = "Contagious status is required")
    @Pattern(regexp = "^(true|false)$", message = "Contagious status is invalid")
    val contagious: Boolean,
    @Nullable
    @Pattern(regexp = "^[0-9]+$", message = "Room number is invalid")
    val roomNumber: Int?
)

data class UpdatePatientAllocationRequest (
    @Nullable
    @Pattern(regexp = "^(true|false)$", message = "Acute status is invalid")
    val newAcute: Boolean?,
    @Nullable
    @Pattern(regexp = "^[A-Z][0-9]{2}(\\.[0-9]+)?$", message = "Diagnosis code is invalid")
    val newDiagnosisCode: String?,
    @Nullable
    @Pattern(regexp = "^[a-zA-Z]+(?:[\\s-][a-zA-Z]+)*$", message = "Diagnosis name is invalid")
    val newDiagnosisName: String?,
    @Nullable
    @Pattern(regexp = "^[0-9]+$", message = "Acute category is invalid")
    val newAcuteCategory: Int?,
    @Nullable
    @Pattern(regexp = "^[0-9]+$", message = "Care category is invalid")
    val newCareCategory: Int?,
    @Nullable
    @Pattern(regexp = "^[0-9]+$", message = "Monitoring category is invalid")
    val newMonitoringCategory: Int?,
    @Nullable
    @Pattern(regexp = "^[0-9]+$", message = "Care ID is invalid")
    val newCareId: Int?,
    @Nullable
    @Pattern(regexp = "^(true|false)$", message = "Contagious status is invalid")
    val newContagious: Boolean?,
    @Nullable
    @Pattern(regexp = "^[0-9]+$", message = "Room number is invalid")
    val newRoomNumber: Int?
)

data class PrivacyPatientAllocationRequest (
    @param:NotBlank(message = "Privacy level is required")
    @param:NotNull(message = "Privacy level is required")
    val userId: String,
    @param:NotBlank(message = "Patient ID is required")
    @param:NotNull(message = "Patient ID is required")
    @param:Pattern(regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$", message = "Patient ID is invalid")
    val patientId: String,
    @param:NotBlank(message = "Action type is required")
    @param:NotNull(message = "Action type is required")
    val actionType: String,
    @param:NotBlank(message = "Purpose name is required")
    @param:NotNull(message = "Purpose name is required")
    val purposeName: String,
    @param:NotBlank(message = "Attributes are required")
    @param:NotNull(message = "Attributes are required")
    val attributes: List<String>
)
