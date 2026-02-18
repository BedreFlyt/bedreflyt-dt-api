package no.uio.bedreflyt.api.utils

import no.uio.bedreflyt.api.model.live.Patient
import no.uio.bedreflyt.api.model.live.PatientAllocation
import no.uio.bedreflyt.api.model.live.PatientTrajectory
import no.uio.bedreflyt.api.service.live.PatientAllocationService
import no.uio.bedreflyt.api.service.live.PatientTrajectoryService
import no.uio.bedreflyt.api.service.simulation.DatabaseService
import no.uio.bedreflyt.api.service.triplestore.WardService
import no.uio.bedreflyt.api.types.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDateTime

@Service
class AllocationHelper (
    private val patientTrajectoryService: PatientTrajectoryService,
    private val patientAllocationService: PatientAllocationService,
    private val databaseService: DatabaseService,
    private val simulator: Simulator,
    private val wardService: WardService,
) {

    val logger: Logger = LoggerFactory.getLogger(AllocationHelper::class.java)

    fun updatePatientNeeds(
        simulationNeeds: MutableList<DailyNeeds>,
        trajectories: List<PatientTrajectory>,
        patientNeeds: MutableMap<Patient, Long>,
        simulation: Boolean
    ) {
        // Pre-calculate maximum batch day to avoid list growth in loop
        val maxBatchDay = trajectories.maxOfOrNull { it.getBatchDay() } ?: -1

        // Ensure simulationNeeds has sufficient capacity upfront
        while (simulationNeeds.size <= maxBatchDay) {
            simulationNeeds.add(mutableListOf())
        }

        // Build existing entries map for faster lookup
        val existingEntries = simulationNeeds.flatMapIndexed { index, dailyNeeds ->
            dailyNeeds.map { (patientId, _) -> "${index}_${patientId.patientId}" }
        }.toSet()

        // Process trajectories and collect new trajectories for batch insert
        val newTrajectories = mutableListOf<PatientTrajectory>()

        trajectories.forEach { trajectory ->
            val batchDay = trajectory.getBatchDay()
            val key = "${batchDay}_${trajectory.patientId.patientId}"

            if (key !in existingEntries) {
                simulationNeeds[batchDay].add(Pair(trajectory.patientId, trajectory.need))
            }
        }

        simulationNeeds.forEachIndexed { index, dailyNeeds ->
            dailyNeeds.forEach { (patient, need) ->
                patientNeeds[patient] = patientNeeds.getOrDefault(patient, 0L) + need.toLong()
                val trajectory = PatientTrajectory(
                    patientId = patient,
                    date = LocalDateTime.now().withMinute(0).withSecond(0).withNano(0),
                    need = need,
                    simulated = simulation
                )

                trajectory.date = if (simulation) {
                    trajectory.setDate(index * 24).withMinute(0).withSecond(0).withNano(0)
                } else {
                    trajectory.setDate(index).withMinute(0).withSecond(0).withNano(0)
                }
                newTrajectories.add(trajectory)
            }
        }

        // Batch save all trajectories at once
        if (newTrajectories.isNotEmpty()) {
            patientTrajectoryService.saveAllPatientTrajectories(newTrajectories)
        }
    }

    fun updateAllocations(patientNeeds: Map<Patient, Long>, offset: Long = 0, simulation: Boolean) {
        if (patientNeeds.isEmpty()) return

        // Batch fetch all relevant allocations
        val patientIds = patientNeeds.keys.toList()
        val allocations = patientAllocationService.findByPatientIds(patientIds, simulation) ?: return

        // Update allocations in memory
        val updatedAllocations = allocations.mapNotNull { allocation ->
            val need = patientNeeds[allocation.patientId]
            if (need != null) {
                allocation.apply {
                    dueDate = LocalDateTime.now().plusDays((need / 24).toInt() + offset).withMinute(0).withSecond(0).withNano(0)
                }
            } else null
        }

        // Batch update all allocations
        if (updatedAllocations.isNotEmpty()) {
            patientAllocationService.updateAllPatientAllocations(updatedAllocations)
        }
    }

    fun handleEmptyAllocations(incomingPatients: MutableList<Pair<Patient, String>>) {
        incomingPatients.forEach { patient ->
            val patientTrajectory = patientTrajectoryService.findByPatientId(patient.first)
            patientTrajectory?.forEach { trajectory ->
                patientTrajectoryService.deletePatientTrajectory(trajectory)
            }
            val patientAllocation = patientAllocationService.findByPatientId(patient.first)
            if (patientAllocation != null) {
                patientAllocationService.deletePatientAllocation(patientAllocation)
            }
        }
    }

    fun removeUnusedAllocations(needs: DailyNeeds) {
        val allocations = patientAllocationService.findAll() ?: return
        allocations.forEach { allocation ->
            if (needs.none { it.first == allocation.patientId} || needs.any { it.first == allocation.patientId && it.second == 0 }) {
                allocation.roomNumber = -1
                patientAllocationService.updatePatientAllocation(allocation)
            }
        }
    }

    fun cleanAllocations() {
        val allocation = patientAllocationService.findAll()
        allocation?.forEach { patientAllocation ->
            if (patientAllocation.diagnosisCode == "" && patientAllocation.diagnosisName == "") {
                cleanTrajectories(patientAllocation.patientId, patientAllocation.simulated)
                patientAllocationService.deletePatientAllocation(patientAllocation)
            }
        }
    }

    fun cleanTrajectories(patientId: Patient, simulated: Boolean) {
//        val trajectories = patientTrajectoryService.findByPatientId(patientId)
        patientTrajectoryService.deleteTrajectoryByPatient(patientId)
//        trajectories?.forEach { trajectory ->
//            patientTrajectoryService.deletePatientTrajectory(trajectory)
//        }
    }

    fun createPatientAllocations(
        context: AllocationContext,
        incomingPatients: MutableList<Pair<Patient, String>>
    ): MutableMap<Patient, PatientAllocation> {
        val allocations: MutableMap<Patient, PatientAllocation> = mutableMapOf()
        incomingPatients.forEach { (patient, diagnosis) ->
            val patientAllocation = patientAllocationService.findByPatientId(patient)
            if (patientAllocation == null) {
                val newPatientAllocation = PatientAllocation(
                    patientId = patient,
                    acute = false,
                    diagnosisCode = diagnosis,
                    diagnosisName = diagnosis,
                    acuteCategory = 0,
                    careCategory = 0,
                    monitoringCategory = 0,
                    careId = 0,
                    contagious = false,
                    wardName = context.wardName,
                    hospitalCode = context.hospitalCode,
                    roomNumber = -1,
                    dueDate = LocalDateTime.now().plusDays(1),
                    simulated = context.isSimulated
                )
                patientAllocationService.savePatientAllocation(newPatientAllocation)
                allocations[patient] = newPatientAllocation
            } else {
                allocations[patient] = patientAllocation
            }
        }
        return allocations
    }

    fun prepareSimulation(
        context: AllocationContext,
        setupResult: AllocationSetupResult,
        databaseResult: DatabaseSetupResult,
        allocations: MutableMap<Patient, PatientAllocation>,
        scenario: List<ScenarioRequest>,
        startTime: Long
    ): SimulationResult? {
        // Create and populate treatment tables
        if (context.isSimulated) {
            databaseService.createAndPopulateTreatmentTables(databaseResult.bedreflytDB, true)
        } else {
            databaseService.createAndPopulateTreatmentTables(databaseResult.bedreflytDB)
        }
        databaseService.createTreatmentView(databaseResult.bedreflytDB)

        val componentsRetrievalTime = System.currentTimeMillis() - startTime - setupResult.endLifecycleManager

        logger.info("Tables populated, invoking ABS with ${scenario.size} requests")

        val simulationNeeds: MutableList<DailyNeeds> = simulator.computeDailyNeeds(databaseResult.tempDir)?.toMutableList()
            ?: throw Exception("Could not compute daily needs")

        // For patients whose day-0 entry has need=0 (arrival / admission step), remove that
        // entry and promote their day-1 need into day 0 so they are sent to the solver on the
        // very first allocation call.  Multiple leading zeros are also handled: we keep stepping
        // forward until we find the first non-zero need.
        if (simulationNeeds.isNotEmpty()) {
            val day0 = simulationNeeds[0]
            val arrivals = day0.filter { it.second == 0 }
            if (arrivals.isNotEmpty()) {
                day0.removeAll(arrivals)
                arrivals.forEach { (patient, _) ->
                    val firstRealNeed = simulationNeeds.drop(1).firstOrNull { dayNeeds ->
                        dayNeeds.any { it.first == patient && it.second > 0 }
                    }?.firstOrNull { it.first == patient && it.second > 0 }
                    if (firstRealNeed != null) {
                        day0.add(Pair(patient, firstRealNeed.second))
                        logger.info("Patient ${patient.patientId} promoted from arrival (need=0) to day-0 with need=${firstRealNeed.second}")
                    } else {
                        logger.info("Patient ${patient.patientId} has arrival entry (need=0) and no subsequent need — excluded from solver")
                    }
                }
            }
        }

        val absTime = System.currentTimeMillis() - startTime - componentsRetrievalTime - setupResult.endLifecycleManager

        val patientNeeds = mutableMapOf<Patient, Long>()
        updatePatientNeeds(simulationNeeds, databaseResult.trajectories, patientNeeds, context.isSimulated)
        updateAllocations(patientNeeds, context.timeStep, context.isSimulated)

        val ward = wardService.getWardByNameAndHospital(context.wardName, context.hospitalCode)
            ?: return null

        // Create complete patient allocations map
        val patientAllocations = mutableMapOf<Patient, PatientAllocation>()
        setupResult.currentPatients?.forEach { patient ->
            val patientAllocation = patientAllocationService.findByPatientId(patient.patientId)
            if (patientAllocation != null) {
                patientAllocations[patient.patientId] = patientAllocation
            }
        }
        allocations.forEach { (patient, allocation) ->
            patientAllocations[patient] = allocation
        }

        // Prepare patient needs
        val patientsNeeds = mutableListOf<DailyNeeds>()
        simulationNeeds.forEach { dailyNeeds ->
            patientsNeeds.add(dailyNeeds)
        }
        setupResult.patientTrajectories.forEach { trajectory ->
            val batchDay = trajectory.getBatchDay()
            while (patientsNeeds.size <= batchDay) {
                patientsNeeds.add(mutableListOf())
            }
            if (!patientsNeeds[batchDay].any { it.first == trajectory.patientId }) {
                patientsNeeds[batchDay].add(Pair(trajectory.patientId, trajectory.need))
            }
        }

        return SimulationResult(
            simulationNeeds = simulationNeeds,
            patientAllocations = patientAllocations,
            patientsNeeds = patientsNeeds,
            ward = ward,
            componentsRetrievalTime = componentsRetrievalTime,
            absTime = absTime
        )
    }
}