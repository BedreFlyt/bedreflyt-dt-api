package no.uio.bedreflyt.api.controller

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import jakarta.validation.Valid
import no.uio.bedreflyt.api.config.EnvironmentConfig
import no.uio.bedreflyt.api.model.live.Patient
import no.uio.bedreflyt.api.model.live.PatientTrajectory
import no.uio.bedreflyt.api.model.triplestore.TreatmentRoom
import io.swagger.v3.oas.annotations.parameters.RequestBody as SwaggerRequestBody
import no.uio.bedreflyt.api.service.live.PatientAllocationService
import no.uio.bedreflyt.api.service.live.PatientService
import no.uio.bedreflyt.api.service.live.PatientTrajectoryService
import no.uio.bedreflyt.api.service.simulation.DatabaseService
import no.uio.bedreflyt.api.service.triplestore.RoomService
import no.uio.bedreflyt.api.service.triplestore.TreatmentService
import no.uio.bedreflyt.api.service.triplestore.TreatmentStepService
import no.uio.bedreflyt.api.service.triplestore.WardService
import no.uio.bedreflyt.api.types.AllocationContext
import no.uio.bedreflyt.api.utils.Simulator
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import no.uio.bedreflyt.api.types.AllocationRequest
import no.uio.bedreflyt.api.types.AllocationResponse
import no.uio.bedreflyt.api.types.AllocationResponseDTO
import no.uio.bedreflyt.api.types.AllocationSetupResult
import no.uio.bedreflyt.api.types.AllocationSimulationRequest
import no.uio.bedreflyt.api.types.CompleteTimeLogging
import no.uio.bedreflyt.api.types.DailyNeeds
import no.uio.bedreflyt.api.types.SimulationRequest
import no.uio.bedreflyt.api.types.SolverTimeLogging
import no.uio.bedreflyt.api.types.DatabaseSetupResult
import no.uio.bedreflyt.api.types.SimulationResult
import no.uio.bedreflyt.api.types.Supply
import no.uio.bedreflyt.api.types.SupplyChecker
import no.uio.bedreflyt.api.types.SuppliesChecker
import no.uio.bedreflyt.api.types.TimeLogging
import no.uio.bedreflyt.api.types.TriggerAllocationRequest
import no.uio.bedreflyt.api.utils.AllocationHelper
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.util.*
import java.util.concurrent.locks.ReentrantLock

@RestController
@RequestMapping("/api/v1/allocation")
class AllocationController (
    private val databaseService: DatabaseService,
    private val simulator: Simulator,
    private val patientAllocationService: PatientAllocationService,
    private val patientService: PatientService,
    private val patientTrajectoryService: PatientTrajectoryService,
    private val wardService: WardService,
    private val roomService: RoomService,
    private val treatmentStepService: TreatmentStepService,
    private val treatmentService: TreatmentService,
    private val environmentConfig: EnvironmentConfig,
    private val allocationHelper: AllocationHelper
) {

    private val log: Logger = LoggerFactory.getLogger(AllocationController::class.java.name)

    private val roomMap: MutableMap<Int, Int> = mutableMapOf()
    private val indexRoomMap : MutableMap<Int, Int> = mutableMapOf()
    private val roomMapSim: MutableMap<Int, Int> = mutableMapOf()
    private val indexRoomMapSim : MutableMap<Int, Int> = mutableMapOf()

    private val allocationLock: ReentrantLock = ReentrantLock()
    private val simulationLock: ReentrantLock = ReentrantLock()

    @Operation(summary = "Allocate rooms for patients")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Rooms allocated"),
            ApiResponse(responseCode = "400", description = "Invalid allocation"),
            ApiResponse(responseCode = "401", description = "Unauthorized"),
            ApiResponse(
                responseCode = "403",
                description = "Accessing the resource you were trying to reach is forbidden"
            ),
            ApiResponse(responseCode = "500", description = "Internal server error")
        ]
    )
    @PostMapping("/allocate")
    fun allocateRooms(@SwaggerRequestBody(description = "Request to allocate rooms for patients") @Valid @RequestBody allocationRequest: AllocationRequest): ResponseEntity<AllocationResponseDTO> {
        log.info("Allocating rooms for ${allocationRequest.scenario.size} patients")
        allocationLock.lock()
        try {
            log.info("Allocating rooms for ${allocationRequest.scenario.size} patients")
            val startTime = System.currentTimeMillis()

            val context = AllocationContext(
                wardName = allocationRequest.wardName,
                hospitalCode = allocationRequest.hospitalCode,
                isSimulated = false,
                timeStep = 0,
                adaptiveCapacity = allocationRequest.adaptive,
                smtMode = allocationRequest.smtMode
            )

            // Initialize allocation
            val setupResult = initializeAllocation(context, allocationRequest.scenario, startTime)
                ?: return ResponseEntity.badRequest().build()

            // Setup database
            val databaseResult = setupDatabase(context, allocationRequest.scenario, allocationRequest.mode)
                ?: return ResponseEntity.badRequest().build()

            // Create patient allocations
            val allocations = allocationHelper.createPatientAllocations(context, setupResult.incomingPatients)

            // Prepare simulation
            val simulationResult = allocationHelper.prepareSimulation(
                context, setupResult, databaseResult, allocations, allocationRequest.scenario, startTime
            ) ?: return ResponseEntity.badRequest().build()

            // Execute simulation
            allocationHelper.cleanAllocations()
            allocationHelper.removeUnusedAllocations(simulationResult.simulationNeeds[0])
            
            // Add existing allocated patients (roomNumber != -1) and their trajectories
            val existingAllocatedPatients = patientAllocationService.findAll()
                ?.filter { it.wardName == context.wardName && it.hospitalCode == context.hospitalCode }
                ?.filter { it.simulated == context.isSimulated }
                ?.filter { it.roomNumber != -1 }
            
            existingAllocatedPatients?.forEach { allocation ->
                // Add to patient allocations if not already present
                if (!simulationResult.patientAllocations.containsKey(allocation.patientId)) {
                    simulationResult.patientAllocations[allocation.patientId] = allocation
                }
                
                // Add trajectories for these patients
                val trajectories = patientTrajectoryService.findByPatientId(allocation.patientId, context.isSimulated)
                trajectories?.forEach { trajectory ->
                    val batchDay = trajectory.getBatchDay()
                    while (simulationResult.patientsNeeds.size <= batchDay) {
                        simulationResult.patientsNeeds.add(mutableListOf())
                    }
                    if (!simulationResult.patientsNeeds[batchDay].any { it.first == trajectory.patientId }) {
                        simulationResult.patientsNeeds[batchDay].add(Pair(trajectory.patientId, trajectory.need))
                    }
                }
            }
            
            simulator.setRoomMap(roomMap)
            simulator.setIndexRoomMap(indexRoomMap)

            val supplyType = environmentConfig.getOrDefault("SUPPLY_TYPE", "supplies")
            sendSupplyRequest(simulationResult.patientsNeeds, allocationRequest.scenario, allocationRequest.mode, supplyType, allocationRequest.sbl)

            val filteredPatients = simulationResult.patientsNeeds[0].distinctBy { it.first } as DailyNeeds

            log.info("Filtered patients for day 0: ${filteredPatients.size}")
            log.info("Subset of filtered patients: ${filteredPatients.take(5).map { it.first.patientId }}")
            val res = simulator.simulate(
                mutableListOf(filteredPatients),
                databaseResult.patients,
                simulationResult.patientAllocations,
                databaseResult.rooms,
                simulationResult.ward,
                databaseResult.tempDir,
                allocationRequest.smtMode,
                context.wardName
            )
            val allocationResponse = res.first
            val allocationTimes = res.second

            // Process and return response
            return processAllocationResponse(
                allocationResponse, allocationTimes, context, setupResult,
                simulationResult, setupResult.incomingPatients, startTime, databaseResult.rooms
            )
        } finally {
            allocationLock.unlock()
        }
    }

    @Operation(summary = "Allocate rooms for patients")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Rooms allocated"),
            ApiResponse(responseCode = "400", description = "Invalid allocation"),
            ApiResponse(responseCode = "401", description = "Unauthorized"),
            ApiResponse(
                responseCode = "403",
                description = "Accessing the resource you were trying to reach is forbidden"
            ),
            ApiResponse(responseCode = "500", description = "Internal server error")
        ]
    )
    @PostMapping("/simulate")
    fun simulateRooms(@SwaggerRequestBody(description = "Request to allocate rooms for patients") @Valid @RequestBody allocationRequest: AllocationSimulationRequest): ResponseEntity<AllocationResponseDTO> {
        simulationLock.lock()
        try {
            log.info("Allocating rooms for ${allocationRequest.scenario.size} patients")
            log.info("Need to remove ${LocalDateTime.now().plusDays(allocationRequest.timeStep)}")
            val startTime = System.currentTimeMillis()

            val context = AllocationContext(
                wardName = allocationRequest.wardName,
                hospitalCode = allocationRequest.hospitalCode,
                isSimulated = true,
                timeStep = allocationRequest.timeStep,
                adaptiveCapacity = allocationRequest.adaptiveCapacity,
                smtMode = allocationRequest.smtMode
            )

            // Initialize allocation
            val setupResult = initializeAllocation(context, allocationRequest.scenario, startTime)
                ?: return ResponseEntity.badRequest().build()

            // Setup database
            val databaseResult = setupDatabase(context, allocationRequest.scenario, allocationRequest.mode)
                ?: return ResponseEntity.badRequest().build()

            // Create patient allocations
            val allocations = allocationHelper.createPatientAllocations(context, setupResult.incomingPatients)

            // Prepare simulation
            val simulationResult = allocationHelper.prepareSimulation(
                context, setupResult, databaseResult, allocations, allocationRequest.scenario, startTime
            ) ?: return ResponseEntity.badRequest().build()

            val now = System.currentTimeMillis()
            val update = System.currentTimeMillis()
            log.info("Updated patient needs in ${update - now}ms")

            val afterNeeds = System.currentTimeMillis()
            log.info("Prepared patient needs in ${afterNeeds - update}ms")

            // Execute simulation
            allocationHelper.cleanAllocations()
            allocationHelper.removeUnusedAllocations(simulationResult.simulationNeeds[0])
            
            // Add existing allocated patients (roomNumber != -1) and their trajectories
            val existingAllocatedPatients = patientAllocationService.findAll()
                ?.filter { it.wardName == context.wardName && it.hospitalCode == context.hospitalCode }
                ?.filter { it.simulated == context.isSimulated }
                ?.filter { it.roomNumber != -1 }
            
            existingAllocatedPatients?.forEach { allocation ->
                // Add to patient allocations if not already present
                if (!simulationResult.patientAllocations.containsKey(allocation.patientId)) {
                    simulationResult.patientAllocations[allocation.patientId] = allocation
                }
                
                // Add trajectories for these patients
                val trajectories = patientTrajectoryService.findByPatientId(allocation.patientId, context.isSimulated)
                trajectories?.forEach { trajectory ->
                    val batchDay = trajectory.getBatchDay()
                    while (simulationResult.patientsNeeds.size <= batchDay) {
                        simulationResult.patientsNeeds.add(mutableListOf())
                    }
                    if (!simulationResult.patientsNeeds[batchDay].any { it.first == trajectory.patientId }) {
                        simulationResult.patientsNeeds[batchDay].add(Pair(trajectory.patientId, trajectory.need))
                    }
                }
            }
            
            simulator.setRoomMap(roomMapSim)
            simulator.setIndexRoomMap(indexRoomMapSim)

            val supplyType = environmentConfig.getOrDefault("SUPPLY_TYPE", "supplies")
            sendSupplyRequest(simulationResult.patientsNeeds, allocationRequest.scenario, allocationRequest.mode, supplyType, allocationRequest.sbl)

            val cleaned = System.currentTimeMillis()
            log.info("Cleaned allocations in ${cleaned - afterNeeds}ms")

            val filteredPatients = simulationResult.patientsNeeds[0].distinctBy { it.first } as DailyNeeds
            val res = simulator.simulate(
                mutableListOf(filteredPatients),
                databaseResult.patients,
                simulationResult.patientAllocations,
                databaseResult.rooms,
                simulationResult.ward,
                databaseResult.tempDir,
                allocationRequest.smtMode,
                context.wardName
            )
            val allocationResponse = res.first
            val allocationTimes = res.second
            log.info("Allocation response size: ${allocationResponse.allocations.size}")

            // Process and return response
            return processAllocationResponse(
                allocationResponse, allocationTimes, context, setupResult,
                simulationResult, setupResult.incomingPatients, startTime, databaseResult.rooms
            )
        } finally {
            simulationLock.unlock()
        }
    }

    private fun checkRoomOpening (wardName: String, hospitalCode: String, incomingPatients: Int) : TimeLogging? {
        val host = environmentConfig.getOrDefault("LM_HOST", "localhost")
        val port = environmentConfig.getOrDefault("LM_PORT", "8091")
        val endpoint = "http://$host:$port/api/v1/states/check/$wardName/$hospitalCode"

        val requestBody = TriggerAllocationRequest(incomingPatients)

        val connection = URI(endpoint).toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.doOutput = true
        connection.outputStream.use { outputStream ->
            val objectMapper = jacksonObjectMapper()
            val jsonString = objectMapper.writeValueAsString(requestBody)
            outputStream.write(jsonString.toByteArray(Charsets.UTF_8))
        }

        return if (connection.responseCode == HttpURLConnection.HTTP_OK) {
            // If the status code was 200, I will have to parse the corresponding TimeLoggin passed as a json
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val objectMapper = jacksonObjectMapper()
            objectMapper.readValue(response, TimeLogging::class.java).also {
                log.info("Room opening check successful: $it")
            }
        } else {
            log.warn("API returned status code ${connection.responseCode}")
            null
        }
    }

    private fun createMaps(rooms: List<TreatmentRoom>, simulated: Boolean = false) {
        if (simulated) {
            rooms.forEachIndexed { index, room -> roomMapSim[index] = room.roomNumber }
            roomMapSim.forEach { (key, value) -> indexRoomMapSim[value] = key }
        }
        rooms.forEachIndexed { index, room -> roomMap[index] = room.roomNumber }
        roomMap.forEach { (key, value) -> indexRoomMap[value] = key }
    }

    private fun createTemporaryDatabase(): Pair<Path, String> {
        val uniqueID = UUID.randomUUID().toString()
        val tempDir: Path = Files.createTempDirectory("allocation_$uniqueID")
        val bedreflytDB = tempDir.resolve("bedreflyt.db").toString()
        databaseService.createTables(bedreflytDB)
        return Pair(tempDir, bedreflytDB)
    }

    private fun preparePatients(allocationRequest: SimulationRequest): MutableList<Pair<Patient, String>> {
        val incomingPatients: MutableList<Pair<Patient, String>> = mutableListOf()
        allocationRequest.scenario.forEach { scenarioRequest ->
            val patient = patientService.findByPatientId(scenarioRequest.patientId) ?: return mutableListOf()
            incomingPatients.add(Pair(patient, scenarioRequest.diagnosis))
        }
        return incomingPatients
    }

    private fun savePatientAllocations(incomingPatients: MutableList<Pair<Patient, String>>) {
        incomingPatients.forEach { patient ->
            val patientAllocation = patientAllocationService.findByPatientId(patient.first)
            patientAllocation?.let {
                if (it.diagnosisCode == "" && it.diagnosisName == "") {
                    it.diagnosisCode = patient.second
                    it.diagnosisName = patient.second
                    patientAllocationService.updatePatientAllocation(it)
                }
            }
        }
    }

    private fun populateDatabase(bedreflytDB: String, allocationRequest: SimulationRequest): Pair<MutableMap<String, Patient>, List<PatientTrajectory>> {
        val ward = wardService.getWardByNameAndHospital(allocationRequest.wardName, allocationRequest.hospitalCode) ?: throw IllegalArgumentException("Invalid ward or hospital")
        databaseService.createAndPopulateRooms(bedreflytDB, ward)
        val patients = databaseService.createAndPopulatePatientTables(bedreflytDB, allocationRequest.scenario, allocationRequest.mode).toMutableMap()
        val trajectories = patientTrajectoryService.findAll() ?: listOf()
        trajectories.forEach { trajectory ->
            val patient = trajectory.patientId
            patients[patient.patientId] = trajectory.patientId
        }
        return Pair(patients, trajectories)
    }

    // Extracted common methods
    private fun initializeAllocation(
        context: AllocationContext,
        scenario: List<no.uio.bedreflyt.api.types.ScenarioRequest>,
        startTime: Long
    ): AllocationSetupResult? {
        // Handle expired trajectories
        if (context.isSimulated) {
            patientTrajectoryService.deleteExpiredTrajectoryWithOffset(context.timeStep)
            patientAllocationService.deletePatientAllocationWithOffset(context.timeStep)
        } else {
            patientTrajectoryService.deleteExpiredTrajectory()
        }

        // Get current patients
        val currentPatients = patientAllocationService.findAll()
            ?.filter { it.wardName == context.wardName && it.hospitalCode == context.hospitalCode }
            ?.filter { it.simulated == context.isSimulated }

        val patientTrajectories = currentPatients?.mapNotNull { patient ->
            patientTrajectoryService.findByPatientId(patient.patientId)
        }?.flatten() ?: listOf()

        // Prepare incoming patients
        val request = if (context.isSimulated) {
            SimulationRequest(
                scenario = scenario,
                mode = "", // Will be set by caller
                smtMode = context.smtMode,
                wardName = context.wardName,
                hospitalCode = context.hospitalCode
            )
        } else {
            AllocationRequest(
                scenario = scenario,
                mode = "", // Will be set by caller
                smtMode = context.smtMode,
                wardName = context.wardName,
                hospitalCode = context.hospitalCode,
                adaptive = context.adaptiveCapacity
            )
        }

        val incomingPatients = preparePatients(request)
        if (incomingPatients.isEmpty()) return null

        // Handle adaptive capacity
        val lmResult = if (context.adaptiveCapacity) {
            checkRoomOpening(context.wardName, context.hospitalCode, incomingPatients.size)
                ?: return null
        } else {
            TimeLogging(0, 0, 0)
        }

        val endLifecycleManager = System.currentTimeMillis() - startTime

        return AllocationSetupResult(
            currentPatients = currentPatients,
            patientTrajectories = patientTrajectories,
            incomingPatients = incomingPatients,
            lmResult = lmResult,
            endLifecycleManager = endLifecycleManager
        )
    }

    private fun setupDatabase(
        context: AllocationContext,
        scenario: List<no.uio.bedreflyt.api.types.ScenarioRequest>,
        mode: String
    ): DatabaseSetupResult? {
        val rooms = roomService.getAllRooms()
            ?.filter { it.hospital.hospitalCode == context.hospitalCode }
            ?: return null

        createMaps(rooms, context.isSimulated)

        val (tempDir, bedreflytDB) = createTemporaryDatabase()

        val request = if (context.isSimulated) {
            SimulationRequest(
                scenario = scenario,
                mode = mode,
                smtMode = context.smtMode,
                wardName = context.wardName,
                hospitalCode = context.hospitalCode
            )
        } else {
            AllocationRequest(
                scenario = scenario,
                mode = mode,
                smtMode = context.smtMode,
                wardName = context.wardName,
                hospitalCode = context.hospitalCode,
                adaptive = context.adaptiveCapacity
            )
        }

        val (patients, trajectories) = populateDatabase(bedreflytDB, request)

        return DatabaseSetupResult(
            rooms = rooms,
            tempDir = tempDir,
            bedreflytDB = bedreflytDB,
            patients = patients,
            trajectories = trajectories
        )
    }

    private fun buildSupplyChecker(
        patientsNeeds: MutableList<DailyNeeds>,
        scenario: List<no.uio.bedreflyt.api.types.ScenarioRequest>,
        mode: String,
        sbl: Boolean
    ): SupplyChecker {
        // Map patientId -> resolved treatment name from the incoming scenario.
        // When treatmentName is absent, resolve it from the diagnosis via the triplestore
        // so that getTreatmentStepsByTreatmentName can find the correct steps.
        val scenarioTreatmentMap = scenario.associate { req ->
            val resolved = req.treatmentName
                ?: try {
                    treatmentService.getTreatmentByDiagnosisAndMode(req.diagnosis, mode).treatmentName
                } catch (e: Exception) {
                    log.warn("Could not resolve treatment name for diagnosis '${req.diagnosis}' (patient ${req.patientId}): ${e.message}")
                    req.diagnosis // fall back to diagnosis so the patient is processed and the step-lookup warn is shown
                }
            req.patientId to resolved
        }

        val allPatients = patientsNeeds.flatMap { it }.map { it.first }.distinctBy { it.patientId }
        val suppliesMap = mutableMapOf<String, List<List<Supply>>>()

        allPatients.forEach { patient ->
            val patientId = patient.patientId

            // Prefer scenario-provided treatment name; fall back to allocation's diagnosisCode
            val treatmentName = scenarioTreatmentMap[patientId]
                ?: patientAllocationService.findByPatientId(patient)?.diagnosisCode
                ?: run {
                    log.warn("No treatment found for patient $patientId — skipping supply entry")
                    return@forEach
                }

            val steps = treatmentStepService.getTreatmentStepsByTreatmentName(treatmentName)
                ?.sortedBy { it.stepNumber }
                ?: run {
                    log.warn("No treatment steps found for treatment '$treatmentName' (patient $patientId)")
                    return@forEach
                }

            val timestepSupplies: List<List<Supply>> = patientsNeeds.mapIndexed { timeStep, dailyNeeds ->
                if (dailyNeeds.any { it.first.patientId == patientId }) {
                    val step = steps.getOrNull(timeStep)
                    if (step != null) {
                        listOf(Supply(itemName = step.task.taskName, quantity = 1))
                    } else {
                        emptyList()
                    }
                } else {
                    emptyList()
                }
            }

            suppliesMap[patientId] = timestepSupplies
        }

        return SupplyChecker(supplies = suppliesMap, sbl = sbl)
    }

    private fun buildSuppliesChecker(
        patientsNeeds: MutableList<DailyNeeds>,
        scenario: List<no.uio.bedreflyt.api.types.ScenarioRequest>,
        mode: String,
        sbl: Boolean
    ): SuppliesChecker {
        val scenarioTreatmentMap = scenario.associate { req ->
            val resolved = req.treatmentName
                ?: try {
                    treatmentService.getTreatmentByDiagnosisAndMode(req.diagnosis, mode).treatmentName
                } catch (e: Exception) {
                    log.warn("Could not resolve treatment name for diagnosis '${req.diagnosis}' (patient ${req.patientId}): ${e.message}")
                    req.diagnosis
                }
            req.patientId to resolved
        }

        val allPatients = patientsNeeds.flatMap { it }.map { it.first }.distinctBy { it.patientId }
        val numberOfPatients = allPatients.size

        val suppliesAggregated = mutableMapOf<String, Int>()

        allPatients.forEach { patient ->
            val patientId = patient.patientId

            val treatmentName = scenarioTreatmentMap[patientId]
                ?: patientAllocationService.findByPatientId(patient)?.diagnosisCode
                ?: run {
                    log.warn("No treatment found for patient $patientId — skipping supply entry")
                    return@forEach
                }

            val steps = treatmentStepService.getTreatmentStepsByTreatmentName(treatmentName)
                ?.sortedBy { it.stepNumber }
                ?: run {
                    log.warn("No treatment steps found for treatment '$treatmentName' (patient $patientId)")
                    return@forEach
                }

            steps.forEach { step ->
                step.task.supplies.forEach { supply ->
                    suppliesAggregated[supply.supplyName] = (suppliesAggregated[supply.supplyName] ?: 0) + 1
                }
            }
        }

        val supplies = suppliesAggregated.map { (name, qty) -> Supply(itemName = name, quantity = qty) }
        return SuppliesChecker(numberOfPatients = numberOfPatients, supplies = supplies, sbl = sbl)
    }

    fun sendSupplyRequest(
        patientsNeeds: MutableList<DailyNeeds>,
        scenario: List<no.uio.bedreflyt.api.types.ScenarioRequest>,
        mode: String,
        type: String,
        sbl: Boolean
    ) {
        try {
            val sblHost = environmentConfig.getOrDefault("SBL_HOST", "localhost")
            val sblPort = environmentConfig.getOrDefault("SBL_PORT", "8092")
            val endpoint = if (type == "supplies") {
                "http://$sblHost:$sblPort/api/supply-management/supplies"
            } else {
                "http://$sblHost:$sblPort/api/supply-management/supply-check"
            }

            val requestBody: Any = if (type == "supplies") {
                buildSuppliesChecker(patientsNeeds, scenario, mode, sbl)
            } else {
                buildSupplyChecker(patientsNeeds, scenario, mode, sbl)
            }

            val connection = URI(endpoint).toURL().openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true

            val objectMapper = jacksonObjectMapper()
            val jsonString = objectMapper.writeValueAsString(requestBody)
            log.info("Sending supply request to $endpoint: $jsonString")

            connection.outputStream.use { outputStream ->
                outputStream.write(jsonString.toByteArray(Charsets.UTF_8))
            }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                log.info("Supply request sent successfully to $endpoint")
            } else {
                log.warn("Supply request to $endpoint returned status ${connection.responseCode}")
            }
        } catch (e: Exception) {
            log.error("Failed to send supply request to SBL: ${e.message}", e)
        }
    }

    private fun processAllocationResponse(
        allocationResponse: AllocationResponse,
        allocationTimes: SolverTimeLogging,
        context: AllocationContext,
        setupResult: AllocationSetupResult,
        simulationResult: SimulationResult,
        incomingPatients: MutableList<Pair<Patient, String>>,
        startTime: Long,
        rooms: List<TreatmentRoom>
    ): ResponseEntity<AllocationResponseDTO> {
        val roomLookup: Map<Int, TreatmentRoom> = rooms.associateBy { it.roomNumber }
        return if (allocationResponse.allocations.isNotEmpty()) {
            // Save allocation results to database
            allocationResponse.allocations.forEach { allocationList ->
                allocationList.forEach { allocation ->
                    allocation.forEach { (room, roomInfo) ->
                        val allocatedPatients = roomInfo?.patients
                        if (!allocatedPatients.isNullOrEmpty()) {
                            allocatedPatients.forEach { singlePatient ->
                                val patientAllocation = patientAllocationService.findAll()
                                    ?.filter { it.patientId.patientId == singlePatient.patientId }
                                    ?.firstOrNull { it.simulated == context.isSimulated }
                                if (patientAllocation != null) {
                                    if (patientAllocation.roomNumber == -1) {
                                        patientAllocation.roomNumber = room.roomNumber
                                    } else {
                                        val roomMapToUse = if (context.isSimulated) roomMapSim else roomMap
                                        patientAllocation.roomNumber =
                                            roomMapToUse[patientAllocation.roomNumber] ?: patientAllocation.roomNumber
                                    }
                                    roomLookup[patientAllocation.roomNumber]?.let { treatmentRoom ->
                                        patientAllocation.wardName = treatmentRoom.treatmentWard.wardName
                                        patientAllocation.hospitalCode = treatmentRoom.hospital.hospitalCode
                                    } ?: log.warn("No TreatmentRoom found for room number ${patientAllocation.roomNumber}")
                                    patientAllocationService.updatePatientAllocation(patientAllocation)
                                } else {
                                    log.warn("Could not find allocation for patient ${singlePatient.patientId}")
                                }
                            }
                        }
                    }
                }
            }

            val endTime = System.currentTimeMillis() - startTime - simulationResult.absTime
            
            // Clean up patients with roomNumber == -1 and their trajectories
            val allocationsToDelete = patientAllocationService.findAll()
                ?.filter { it.wardName == context.wardName && it.hospitalCode == context.hospitalCode }
                ?.filter { it.simulated == context.isSimulated }
                ?.filter { it.roomNumber == -1 }
            
            allocationsToDelete?.forEach { allocation ->
                // Delete trajectories first
                val trajectories = patientTrajectoryService.findByPatientId(allocation.patientId, context.isSimulated)
                trajectories?.forEach { trajectory ->
                    patientTrajectoryService.deletePatientTrajectory(trajectory)
                }
                // Delete allocation
                patientAllocationService.deletePatientAllocation(allocation)
            }
            
            allocationResponse.executions = CompleteTimeLogging(
                lifecycleManagerTime = setupResult.lmResult,
                componentsRetrievalTime = simulationResult.componentsRetrievalTime,
                absTime = simulationResult.absTime,
                solverTime = allocationTimes
            )
            ResponseEntity.ok(allocationResponse.toDTO())
        } else {
            allocationHelper.handleEmptyAllocations(incomingPatients)
            ResponseEntity.badRequest().build()
        }
    }
}

