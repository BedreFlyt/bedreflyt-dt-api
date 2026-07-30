package no.uio.bedreflyt.api.utils

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import no.uio.bedreflyt.api.config.EnvironmentConfig
import no.uio.bedreflyt.api.model.live.Patient
import no.uio.bedreflyt.api.model.live.PatientAllocation
import no.uio.bedreflyt.api.model.triplestore.TreatmentRoom
import no.uio.bedreflyt.api.model.triplestore.Ward
import no.uio.bedreflyt.api.service.live.PatientAllocationService
import no.uio.bedreflyt.api.service.live.PatientService
import no.uio.bedreflyt.api.service.triplestore.RoomService
import no.uio.bedreflyt.api.types.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import kotlin.text.toInt

@Service
class Simulator (
    private val environmentConfig: EnvironmentConfig,
    private val patientService: PatientService,
    private val patientAllocationService: PatientAllocationService,
    private val roomService: RoomService
) {

    private val log: Logger = LoggerFactory.getLogger(Simulator::class.java.name)
    private var roomMap: MutableMap<Int, Int> = mutableMapOf()
    private var indexRoomMap : MutableMap<Int, Int> = mutableMapOf()

    fun setRoomMap(roomMap: Map<Int, Int>) {
        this.roomMap = roomMap.toMutableMap()
    }

    fun setIndexRoomMap(indexRoomMap: Map<Int, Int>) {
        this.indexRoomMap = indexRoomMap.toMutableMap()
    }

    private fun processDailyNeeds(needs: String) : SimulationNeeds {
        val information = needs.split("------").filter { it.isNotEmpty() } // EB - split data over ------
        val groupedInformation: List<List<String>> =
            information.map { it -> it.split("\n").filter { it.isNotEmpty() } }.filter { it.isNotEmpty() }

        return groupedInformation.map { group ->
            group.map { line ->
                val patientData = line.split(",")
                val patientId = patientData[0]
                val patientDistance = patientData[1]
                Pair(patientService.findByPatientId(patientId)!!, patientDistance.toInt())
            }.toMutableList()
        }
    }

    /**
     * Execute the JAR
     *
     * Execute the JAR file to get the ABS model output
     *
     * @return String - Output of the JAR file -- we will use to get the whole trace of days
     */
    fun computeDailyNeeds(tempDir: Path): SimulationNeeds? {
        val jarFileName = "bedreflyt.jar"
        val jarFilePath = tempDir.resolve(jarFileName)
        Files.copy(Paths.get(jarFileName), jarFilePath, StandardCopyOption.REPLACE_EXISTING)

        val command = listOf("java", "-jar", jarFilePath.toString())

        log.info("Executing JAR with command: $command")

        // Start the process
        val process = ProcessBuilder(command)
            .directory(tempDir.toFile())
            .redirectErrorStream(false) // Do not redirect error stream so we can capture separately
            .start()

        // Capture output
        val output = StringBuilder()
        BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.appendLine(line)
            }
        }

        log.info("Standard Output size: ${output.length}")

        // Capture error output
        val errorOutput = StringBuilder()
        BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                errorOutput.appendLine(line)
            }
        }

        log.info("Error Output: $errorOutput")

        // Wait for process to complete
        val exitCode = process.waitFor()

        log.info("Exit code: $exitCode")

        // Return combined output or error output based on the exit code
        return if (exitCode == 0) {
            processDailyNeeds(output.toString())
        } else {
            null
        }
    }

    private fun processSolverOutput(patientMap: Map<Int, Patient>, allocations: List<Map<String, Any>>, rooms: List<TreatmentRoom>) : List<Allocation> {
        return allocations.flatMap { roomData ->
            roomData.map { (roomNumber, roomInfo) ->
                val roomInfoMap = roomInfo as Map<String, Any>
                val patientNumbersMap = (roomInfoMap["patients"] as List<String>).map { it.toInt() }
                val patients = patientNumbersMap.map { number ->
                    val singlePatientMap = patientMap[number]!!
                    patientService.findByPatientId(singlePatientMap.patientId)!!
                }
                val gender = if (roomInfoMap["gender"] as String == "True") "Male" else "Female"

                val treatmentRoom = rooms.find { it.roomNumber == roomMap[roomNumber.toInt()] }
                    ?: throw IllegalArgumentException("Room with number $roomNumber not found in the provided rooms list")

                mapOf(
                    WardRoom(treatmentRoom.roomNumber, treatmentRoom.treatmentWard.wardName, treatmentRoom.treatmentWard.wardHospital.hospitalCode) to RoomInfo(patients, gender)
                )
            }
        }
    }

    private fun invokeSolver(
        solverRequest: SolverRequest,
        patientMap: Map<Int, Patient>, // Map of patient based on the order that is passed to the solver
        rooms: List<TreatmentRoom>
    ) : Pair<SolverResponse, MutableMap<String, Long>> {
        val solverEndpoint = environmentConfig.getOrDefault("SOLVER_ENDPOINT", "localhost")
        val solverUrl = "http://$solverEndpoint:8000/api/solve"
        val connection = URI(solverUrl).toURL().openConnection() as HttpURLConnection

        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.doOutput = true

        val jsonBody = jacksonObjectMapper().writeValueAsString(solverRequest)
        val time = System.currentTimeMillis()

        log.info("Invoking solver with request")
        OutputStreamWriter(connection.outputStream).use {
            it.write(jsonBody)
        }

        if (connection.responseCode != 200) {
            log.warn("Solver returned status code ${connection.responseCode}")
            val errorTime = System.currentTimeMillis() - time
            return Pair(SolverResponse(listOf(), -1), mutableMapOf(
                "solverTime" to errorTime,
                "postProcessTime" to 0
            ))
        }

        val response = connection.inputStream.bufferedReader().use { it.readText() }

        if (response.contains("Model is unsat")) {
            val unsatTime = System.currentTimeMillis() - time
            log.info("Solver returned UNSAT")
            Pair(SolverResponse(listOf(), -1), mutableMapOf(
                "solverTime" to unsatTime.toInt(),
                "postProcessTime" to 0
            ))
        }

        val elapsedTime = System.currentTimeMillis() - time

        val mapper = jacksonObjectMapper()
        val responseMap: Map<String, Any> = mapper.readValue(response)

        // Extract changes and allocations from the response map
        val changes = responseMap["changes"] as Int
        val allocations = responseMap["allocations"] as List<Map<String, Any>>

        // Transform the allocations into the desired structure
        val transformedData: List<Allocation> = processSolverOutput(patientMap, allocations, rooms)
        val postProcessTime = System.currentTimeMillis() - time - elapsedTime

        // Return the transformed data along with the changes
        return Pair(SolverResponse(transformedData, changes), mutableMapOf(
            "solverTime" to elapsedTime,
            "postProcessTime" to postProcessTime
        ))
    }

    private fun solve(
        dailyNeeds: DailyNeeds,
        patientsSimulated: Map<String, Patient>, // All patients that are simulated
        allocations: Map<Patient, PatientAllocation>,
        rooms: List<TreatmentRoom>,
        ward: Ward,
        smtMode: String,
        targetWardName: String = ward.wardName
    ): Pair<SolverResponse, MutableMap<String, Long>> {
        val currentTime = System.currentTimeMillis()
        val numberOfRooms = rooms.size
        val capacities = rooms.map { it.capacity ?: 0 }
        val roomCategories: List<Long> = rooms.map { it.monitoringCategory.category.toLong() ?: 0 }

        // Compute base penalties for rooms in the target ward
        val basePenalties: List<Int> = rooms.map {
            if (it.treatmentWard.wardName == targetWardName) {
                if (it.monitoringCategory.description == "Korridor" || it.monitoringCategory.description == "Midlertidig") {
                    it.penalty.toInt()
                } else {
                    0
                }
            } else {
                0
            }
        }
        val maxBasePenalty = basePenalties.maxOrNull() ?: 0
        val targetFloor = ward.wardFloor.floorNumber

        val penalties: List<Int> = rooms.mapIndexed { idx, room ->
            if (room.treatmentWard.wardName == targetWardName) {
                basePenalties[idx]
            } else {
                val roomFloor = room.treatmentWard.wardFloor.floorNumber
                val floorDistance = Math.abs(roomFloor - targetFloor)
                maxOf(1, maxBasePenalty) * 10 * floorDistance
            }
        }
        val allowContagious : List<Boolean> = rooms.map { it.monitoringCategory.description != "Korridor" }
        var patientNumbers = 0
        val genders = mutableListOf<Boolean>()
        val contagious = mutableListOf<Boolean>()
        val patientDistances = mutableListOf<Int>()
        val previous = mutableListOf<Int>()
        val patientMap = mutableMapOf<Int, Patient>()

        dailyNeeds.forEach { dailyNeed ->
            val patientId = dailyNeed.first.patientId
            val patientDistance = dailyNeed.second

            patientsSimulated[patientId]?.let { patientInfo ->
                if (patientDistance.toInt() > 0 && allocations.containsKey(patientInfo)) {
                    val gender = patientInfo.gender == "Male"
                    genders.add(gender)
                    val singlePatient = patientService.findByPatientId(patientInfo.patientId)!!
                    contagious.add(allocations[singlePatient]!!.contagious)
                    patientDistances.add(patientDistance.toInt())
                    val previousRoom = allocations[singlePatient]!!.roomNumber
                    if (previousRoom == -1) {
                        previous.add(-1)
                    } else {
                        val roomNumber = indexRoomMap[previousRoom] ?: previousRoom
                        previous.add(roomNumber)
                    }
                    patientMap[patientNumbers] = patientInfo
                    patientNumbers += 1
                }
            }
        }

        // call the localhost:8000/api/solve passing all the lists as requirements
        val solverRequest = SolverRequest(
            numberOfRooms,
            capacities,
            roomCategories,
            patientNumbers,
            genders,
            contagious,
            patientDistances,
            previous,
            smtMode,
            penalties,
            allowContagious
        )

        val times = HashMap<String, Long>()
        val elapsedTime = System.currentTimeMillis() - currentTime
        times["prepareDataForSolve"] = elapsedTime

        log.info("Invoking solver with  ${solverRequest.no_rooms} rooms, ${solverRequest.no_patients} patients in mode ${solverRequest.mode}")

        if (patientNumbers > 0) {
            val res = invokeSolver(solverRequest, patientMap, rooms)
            times["solverTime"] = res.second["solverTime"] ?: 0
            times["postProcessTime"] = res.second["postProcessTime"] ?: 0
            return Pair(res.first, times)
        } else {
            times["solverTime"] = 0
            times["postProcessTime"] = 0
            log.info("No patients to simulate for ward ${ward.wardName}")
            return Pair(SolverResponse(listOf(), -1), times)
        }
    }

    private fun processPatientMap(patients: Map<String, Patient>, allocations: Map<Patient, PatientAllocation>, solveData: List<Allocation>) {
        solveData.forEach { roomData ->
            roomData.forEach { (room, roomInfo) ->
                if (roomInfo == null) {
                    log.warn("No room info for ${room.roomNumber} in $roomData")
                    throw Exception("No room info")
                }
                val allPatients = roomInfo.patients
                // We use strings to index the map. Convert it to int
                val patientRoom = room.roomNumber
                // Insert patient data
                allPatients.forEach { patient ->
                    patients[patient.patientId]?.let {
                        allocations[patient]!!.roomNumber = indexRoomMap[patientRoom]!!
                    }
                }
            }
        }
    }

    fun simulate(
        needs: SimulationNeeds,
        patients: Map<String, Patient>,
        allocations: Map<Patient, PatientAllocation>,
        rooms: List<TreatmentRoom>,
        ward: Ward,
        tempDir: Path,
        smtMode: String,
        targetWardName: String = ward.wardName
    ): Pair<SimulationResponse, SolverTimeLogging> {
        val scenarios = mutableListOf<List<Map<WardRoom, RoomInfo>>>()
        try {
            var totalChanges = 0
            var totalPrepareDataTime = 0L
            var totalSolverTime = 0L
            var totalPostProcessTime = 0L

            needs.forEach { group ->
                // Solve each day
                val response = solve(group, patients, allocations, rooms, ward, smtMode, targetWardName)
                log.info("Solved day with ${response.first.changes} changes")
                if (response.first.changes != -1) {totalChanges += response.first.changes}
                val solveData = response.first.allocations
                // We ignore the day that had an unsat model
                if (solveData.isNotEmpty()) {
                    processPatientMap(patients, allocations, solveData)
                }
                scenarios.add(solveData as List<Map<WardRoom, RoomInfo>>)
                log.info(solveData.toString())

                // Accumulate timing data
                totalPrepareDataTime += response.second["prepareDataForSolve"] ?: 0L
                totalSolverTime += response.second["solverTime"] ?: 0L
                totalPostProcessTime += response.second["postProcessTime"] ?: 0L
            }

            val SolverTimeLogging = SolverTimeLogging(
                prepareDataForSolve = totalPrepareDataTime,
                solverTime = totalSolverTime,
                postProcessTime = totalPostProcessTime
            )

            return Pair(SimulationResponse(scenarios, totalChanges), SolverTimeLogging)
        } catch (e: Exception) {
            log.error("Error executing solver", e)
            val errorLogging = SolverTimeLogging(
                prepareDataForSolve = 0,
                solverTime = 0,
                postProcessTime = 0
            )
            return Pair(SimulationResponse(listOf(), -1), errorLogging)
        }
    }

    private fun invokeGlobal(
        patientsSimulated: SimulationNeeds,
        rooms: List<TreatmentRoom>,
        smtMode: String
    ): String? {
        val capacities = rooms.map { it.capacity ?: 0 }
        val roomCategories: List<Long> = rooms.map { it.monitoringCategory.category.toLong() ?: 0 }
        val genders = mutableMapOf<String, Boolean>()
        val infectious = mutableMapOf<String, Boolean>()
        val penalties = mutableListOf<MutableMap<String, Int>>()
        val contegiousUse = mutableListOf<MutableMap<String, Int>>()
        val patientCategories: List<Map<String, Int>> = patientsSimulated.map { day ->
            day.map { patientDistance ->
                val patientId = patientDistance.first.patientId
                val distance = patientDistance.second
                mapOf(patientId to distance)
            }.reduce { acc, map -> acc + map }
        }

        patientsSimulated.forEach { day ->
            day.forEach { patientDistance ->
                genders[patientDistance.first.patientId] = patientDistance.first.gender == "Male"
                infectious[patientDistance.first.patientId] =
                    patientAllocationService.findByPatientId(patientDistance.first)!!.contagious
            }
        }

        val req: GlobalSolverRequest = GlobalSolverRequest(
            capacities,
            roomCategories,
            genders,
            infectious,
            patientCategories,
            smtMode
        )

        log.info("Invoking global solver with  ${rooms.size} rooms, ${genders.size} patients in mode $smtMode")

        val solverEndpoint = environmentConfig.getOrDefault("SOLVER_ENDPOINT", "localhost")
        val solverUrl = "http://$solverEndpoint:8000/api/solve-global"
        log.info("Invoking global solver")
        val connection = URI(solverUrl).toURL().openConnection() as HttpURLConnection

        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.doOutput = true

        val jsonBody = jacksonObjectMapper().writeValueAsString(req)

        log.info("Invoking solver with request")
        OutputStreamWriter(connection.outputStream).use {
            it.write(jsonBody)
        }

        if (connection.responseCode != 200) {
            log.warn("Solver returned status code ${connection.responseCode}")
            return "error"
        }

        val response = connection.inputStream.bufferedReader().use { it.readText() }

        if (response.contains("Model is unsat")) {
            return "error"
            //return listOf(listOf(mapOf("error" to null)))
        }

        return response
    }

    fun globalSolution(
        needs: SimulationNeeds,
        patients: Map<String, Patient>,
        rooms: List<TreatmentRoom>,
        tempDir: Path,
        smtMode: String
    ): String {
        //List<SimulationResponse> {
        try {
            val solverResponse = invokeGlobal(needs, rooms, smtMode)
            return solverResponse?: "error"
        } catch (e: Exception) {
            "Error executing JAR: ${e.message}"
            log.error("Error executing JAR", e)
            return "error"
            //return listOf(listOf(listOf(mapOf("error" to null))))
        }
    }
}
