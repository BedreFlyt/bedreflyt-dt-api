package no.uio.bedreflyt.api.types

import no.uio.bedreflyt.api.model.live.Patient
import no.uio.bedreflyt.api.model.live.PatientAllocation
import no.uio.bedreflyt.api.model.live.PatientTrajectory
import no.uio.bedreflyt.api.model.triplestore.TreatmentRoom
import java.nio.file.Path

data class AllocationContext(
    val wardName: String,
    val hospitalCode: String,
    val isSimulated: Boolean,
    val timeStep: Long = 0,
    val adaptiveCapacity: Boolean = false,
    val smtMode: String,
    val executionMode: String = "managed",
    // When true the twin may use every ward of the hospital; when false only
    // the requested ward's rooms are considered.
    val multiWard: Boolean = false
)

data class AllocationSetupResult(
    val currentPatients: List<PatientAllocation>?,
    val patientTrajectories: List<PatientTrajectory>,
    val incomingPatients: MutableList<Pair<Patient, String>>,
    val lmResult: TimeLogging,
    val endLifecycleManager: Long
)

data class DatabaseSetupResult(
    val rooms: List<TreatmentRoom>,
    val tempDir: Path,
    val bedreflytDB: String,
    val patients: MutableMap<String, Patient>,
    val trajectories: List<PatientTrajectory>
)

data class SimulationResult(
    val simulationNeeds: MutableList<DailyNeeds>,
    val patientAllocations: MutableMap<Patient, PatientAllocation>,
    val patientsNeeds: MutableList<DailyNeeds>,
    val ward: no.uio.bedreflyt.api.model.triplestore.Ward,
    val componentsRetrievalTime: Long,
    val absTime: Long
)