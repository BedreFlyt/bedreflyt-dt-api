package no.uio.bedreflyt.api.types

import no.uio.bedreflyt.api.model.live.Patient

data class SimulationRequest(
    val scenario: List<ScenarioRequest>,
    val mode: String,
    val smtMode: String = "changes",
    val wardName: String,
    val hospitalCode: String,
    val adaptive: Boolean = true,
    val sbl: Boolean = true
)

data class TriggerAllocationRequest (
    val incomingPatients: Int
)

data class AllocationSimulationRequest(
    val scenario: List<ScenarioRequest>,
    val mode: String,
    val smtMode: String = "changes",
    val wardName: String,
    val hospitalCode: String,
    val timeStep: Long,
    val adaptiveCapacity: Boolean = true,
    val sbl: Boolean = true
)

data class WardRoom(
    val roomNumber: Int,
    val wardName: String,
    val hospitalCode: String
)

data class RoomInfo(
    val patients: List<Patient>,
    val gender: String
)

// DTO for JSON serialization
data class RoomAllocationDTO(
    val roomNumber: Int,
    val wardName: String,
    val hospitalCode: String,
    val patients: List<Patient>,
    val gender: String
)

data class AllocationResponseDTO(
    val allocations: List<List<List<RoomAllocationDTO>>>,
    val changes: Int,
    val executions: CompleteTimeLogging? = null
)

typealias Allocation = Map<WardRoom, RoomInfo?>
typealias DailyNeeds = MutableList<Pair<Patient, Int>>
typealias SimulationNeeds = List<DailyNeeds>
typealias AllocationRequest = SimulationRequest
typealias AllocationResponse = SimulationResponse

data class SimulationResponse(
    val allocations: List<List<Allocation>>,
    val changes: Int,
    var executions: CompleteTimeLogging? = null
) {
    fun toDTO(): AllocationResponseDTO {
        val dtoAllocations = allocations.map { dayAllocations ->
            dayAllocations.map { allocation ->
                allocation.map { (wardRoom, roomInfo) ->
                    RoomAllocationDTO(
                        roomNumber = wardRoom.roomNumber,
                        wardName = wardRoom.wardName,
                        hospitalCode = wardRoom.hospitalCode,
                        patients = roomInfo?.patients ?: emptyList(),
                        gender = roomInfo?.gender ?: ""
                    )
                }
            }
        }
        return AllocationResponseDTO(dtoAllocations, changes, executions)
    }
}

data class ScenarioRequest(
    val batch: Int,
    val patientId: String,
    val diagnosis: String,
    val treatmentName: String?
)

data class SolverRequest(
    val no_rooms: Int,
    val capacities: List<Int>,
    val room_distances: List<Long>,
    val no_patients: Int,
    val genders: List<Boolean>,
    val infectious: List<Boolean>,
    val patient_distances: List<Int>,
    val previous: List<Int>,
    // options are c[hanges] to minimize number of room changes or
    // m[ax] to minimize maximum number of patients per room
    val mode: String,
    val penalties: List<Int> = listOf(),
    val contagious_allowed : List<Boolean> = listOf(),
)

data class GlobalSolverRequest(
    val capacities: List<Int>,
    val room_distances: List<Long>,
    val genders: Map<String, Boolean>, // mapping from patientIds to gender (isMale)
    val contagious: Map<String, Boolean>, // patientId -> contagious
    val patient_distances: List<Map<String, Int>>, // patient_distances[i][j] = c means patient j is in category c on day i
    // options are c[hanges] to minimize number of room changes or
    // m[ax] to minimize maximum number of patients per room
    val mode: String
)

data class SolverResponse(
    val allocations: List<Allocation>,
    val changes: Int
)

// request for monte carlo sim
// the naming is a bit awkward since "SimulationRequest/Response" already exists
data class MultiSimulationRequest(
    val scenario: List<ScenarioRequest>,
    val repetitions: Int = 10,
    val risk: Double = 0.5,
    val smtMode: String = "changes",
    val wardName: String,
    val hospitalCode: String
)

data class MultiSimulationResponse(
    val runs: Int,
    val results: List<SimulationResponse>
)
