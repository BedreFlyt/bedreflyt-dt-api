package no.uio.bedreflyt.api.service.simulation

import no.uio.bedreflyt.api.model.live.Patient
import no.uio.bedreflyt.api.model.live.PatientAllocation
import no.uio.bedreflyt.api.model.simulation.Room
import no.uio.bedreflyt.api.model.triplestore.TreatmentRoom
import no.uio.bedreflyt.api.model.triplestore.Ward
import no.uio.bedreflyt.api.service.live.PatientAllocationService
import no.uio.bedreflyt.api.service.live.PatientService
import no.uio.bedreflyt.api.service.triplestore.*
import no.uio.bedreflyt.api.types.ScenarioRequest
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.stereotype.Service

@Service
class DatabaseService (
    private val treatmentService: TreatmentService,
    private val patientService: PatientService,
    private val patientAllocationService: PatientAllocationService,
    private val taskService: TaskService,
    private val monitoringCategoryService: MonitoringCategoryService,
    private val roomService: RoomService
) {

    private val log: Logger = LoggerFactory.getLogger(DatabaseService::class.java.name)

    private fun getJdbcTemplate(dbPath: String): JdbcTemplate {
        val dataSource = DriverManagerDataSource()
        dataSource.setDriverClassName("org.sqlite.JDBC")
        dataSource.url = "jdbc:sqlite:$dbPath"
        return JdbcTemplate(dataSource)
    }

    fun createTables(dbPath: String) {
        createPatientTable(dbPath)
        createRoomTables(dbPath)
        createTreatmentTables(dbPath)
    }

    fun createPatientTable(dbPath: String) {
        val jdbcTemplate = getJdbcTemplate(dbPath)
        val createPatientTable = """
            CREATE TABLE IF NOT EXISTS patient (
                patientId TEXT PRIMARY KEY,
                gender TEXT NOT NULL,
                genderModl INTEGER NOT NULL
            )
        """

        val createPatientStatusTable = """
            CREATE TABLE IF NOT EXISTS patientStatus (
                patientId TEXT PRIMARY KEY,
                infectious BOOLEAN NOT NULL,
                roomNr INTEGER NOT NULL
            )
        """

        val createScenarioTable = """
            CREATE TABLE IF NOT EXISTS scenario (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                batch INTEGER NOT NULL,
                patientId TEXT NOT NULL,
                treatmentName TEXT NOT NULL
            )
        """

        jdbcTemplate.execute(createPatientTable)
        jdbcTemplate.execute(createPatientStatusTable)
        jdbcTemplate.execute(createScenarioTable)
    }

    fun createAndPopulatePatientTables(
        scenarioDbUrl: String,
        scenario: List<ScenarioRequest>,
        mode: String
    ): Map<String, Patient> {
        createPatientTable(scenarioDbUrl)
        val patients = mutableMapOf<String, Patient>()

        scenario.forEach { scenarioRequest ->
            scenarioRequest.patientId.let { patientId ->
                val patient: Patient = patientService.findByPatientId(patientId) ?: throw IllegalArgumentException("Patient $patientId not found")
                patients[patientId] = patient
                val patientAllocation: PatientAllocation? = try {
                    patientAllocationService.findByPatientId(patient)
                } catch (e: EmptyResultDataAccessException) {
                    null
                }

                insertPatient(scenarioDbUrl, patient.patientId, patient.gender)
                insertPatientStatus(
                    scenarioDbUrl,
                    patient.patientId,
                    patientAllocation?.contagious ?: false,
                    patientAllocation?.roomNumber ?: -1
                )

                scenarioRequest.diagnosis?.let { diagnosis ->
                    try {
                        val treatment = treatmentService.getTreatmentByDiagnosisAndMode(diagnosis, mode).treatmentName
                        insertScenario(
                            scenarioDbUrl,
                            scenarioRequest.batch,
                            scenarioRequest.patientId,
                            treatment
                        )
                    } catch (e: IllegalArgumentException) {
                        throw e
                    }
                }
            }
        }
        return patients
    }

    fun createRoomTables(dbPath: String) {
        val jdbcTemplate = getJdbcTemplate(dbPath)
        val createRoomTable = """
            CREATE TABLE IF NOT EXISTS roomCategory (
                catId LONG PRIMARY KEY,
                roomDescr TEXT NOT NULL,
                catIdModl LONG NOT NULL
            )
        """
        val createRoomDistributionTable = """
            CREATE TABLE IF NOT EXISTS roomDistrib (
                roomNr INTEGER PRIMARY KEY,
                roomNrMod INTEGER NOT NULL,
                bedCategory INTEGER NOT NULL,
                capacity INTEGER NOT NULL,
                bathroom BOOLEAN NOT NULL
            )
        """
        jdbcTemplate.execute(createRoomTable)
        jdbcTemplate.execute(createRoomDistributionTable)
    }

    fun createAndPopulateRooms(roomDbUrl: String, ward: Ward): List<Room> {
        val rooms = roomService.getRoomsByWardHospital(ward.wardName, ward.wardHospital.hospitalCode)
            ?: throw IllegalArgumentException("No room distributions found")
        return createAndPopulateRooms(roomDbUrl, rooms)
    }

    /// Populate the temporary DB from an explicit room list. This lets a
    /// multi-ward run use every room of the hospital even when the requested
    /// ward has no rooms there, instead of failing with "No room distributions
    /// found".
    fun createAndPopulateRooms(roomDbUrl: String, rooms: List<TreatmentRoom>): List<Room> {
        val categories = monitoringCategoryService.getAllCategories()

        categories?.let {
            it.forEach { category ->
                insertRoom(roomDbUrl, category.category.toLong(), category.description)
            }
        } ?: throw IllegalArgumentException("No rooms found")

        if (rooms.isEmpty()) {
            throw IllegalArgumentException("No room distributions found")
        }
        val simulationRoom = mutableListOf<Room>()

        rooms.forEachIndexed { index, singleRoom ->
            insertRoomDistribution(
                roomDbUrl, singleRoom.roomNumber.toLong(), index.toLong(),
                singleRoom.monitoringCategory.category.toLong(), singleRoom.capacity, true
            )
            simulationRoom.add(
                Room(
                    singleRoom.roomNumber, index,
                    singleRoom.monitoringCategory.category.toLong(),
                    singleRoom.capacity, true
                )
            )
        }

        return simulationRoom
    }

    fun createTreatmentTables(dbPath: String) {
        val jdbcTemplate = getJdbcTemplate(dbPath)
        val createTaskTable = """
            CREATE TABLE IF NOT EXISTS tasks (
                name TEXT PRIMARY KEY,
                bedCategory INTEGER NOT NULL,
                durAvg INTEGER NOT NULL
            )
        """
        val createTaskDependencyName = """
            CREATE TABLE IF NOT EXISTS taskDependencies (
                treatmentName TEXT NOT NULL,
                taskName TEXT NOT NULL,
                taskDependency TEXT NOT NULL,
                PRIMARY KEY (treatmentName, taskName, taskDependency)
            )
        """
        jdbcTemplate.execute(createTaskTable)
        jdbcTemplate.execute(createTaskDependencyName)
    }

    fun createTreatmentView(dbPath: String) {
        val jdbcTemplate = getJdbcTemplate(dbPath)
        val createView = """
            CREATE VIEW IF NOT EXISTS treatments (treatmentName, taskName, orderTask) AS
              WITH RECURSIVE tasks(treatmentName, taskName, taskPrio)
              AS (SELECT treatmentName, taskDependency, 0
                    FROM taskDependencies base
                   WHERE taskDependency NOT IN
                         (SELECT taskName
                            FROM taskDependencies dep
                           WHERE base.treatmentName = dep.treatmentName)
                   UNION ALL
                  SELECT recur.treatmentName, recur.taskName, tasks.taskPrio + 1
                    FROM taskDependencies recur
                    JOIN tasks
                        ON recur.taskDependency = tasks.taskName
                        AND recur.treatmentName = tasks.treatmentName
                   ORDER BY 1, 3)
              SELECT *
                FROM tasks;
        """
        jdbcTemplate.execute(createView)
    }

    fun createAndPopulateTreatmentTables(treatmentDbUrl: String, simulation: Boolean = false) {
        createTreatmentTables(treatmentDbUrl)

        val treatments = treatmentService.getAllTreatments() ?: throw IllegalArgumentException("No treatments found")
        treatments.forEach { treatment ->
//            val taskDependencies = sortTaskDependencies(treatment.second)
            val taskDependencies = treatment.second

            taskDependencies.forEach { taskDependency ->
                val treatmentName = taskDependency.treatmentName
                val task = taskService.getTaskByTaskName(taskDependency.task.taskName)
                    ?: throw IllegalArgumentException("No task ${taskDependency.task} found")
                insertTask(
                    treatmentDbUrl,
                    task.taskName + "_" + treatmentName +  "_" + taskDependency.stepNumber,
                    taskDependency.monitoringCategory.category,
                    if (!simulation) taskDependency.averageDuration.toInt() else (taskDependency.averageDuration / 24).toInt().coerceAtLeast(1)
                )
                val prev = taskDependency.stepNumber - 1
                taskDependency.previousTask?.takeIf { it.isNotEmpty() }?.let  { insertTaskDependency(
                    treatmentDbUrl,
                    treatmentName,
                    task.taskName + "_" + treatmentName + "_" + taskDependency.stepNumber,
                    it + "_" + treatmentName + "_" + prev
                ) }
            }
        }

        log.info("Tables populated")
    }

    fun deleteDatabase(dbPath: String) {
        val jdbcTemplate = getJdbcTemplate(dbPath)
        jdbcTemplate.execute("DROP TABLE IF EXISTS patient")
        jdbcTemplate.execute("DROP TABLE IF EXISTS patientStatus")
        jdbcTemplate.execute("DROP TABLE IF EXISTS scenario")
        jdbcTemplate.execute("DROP TABLE IF EXISTS roomCategory")
        jdbcTemplate.execute("DROP TABLE IF EXISTS roomDistrib")
        jdbcTemplate.execute("DROP TABLE IF EXISTS tasks")
        jdbcTemplate.execute("DROP TABLE IF EXISTS taskDependencies")
        jdbcTemplate.execute("DROP VIEW IF EXISTS treatments")
    }

    fun clearTable (dbPath: String, table: String) {
        val jdbcTemplate = getJdbcTemplate(dbPath)
        jdbcTemplate.execute("DELETE FROM $table")
    }

    fun insertPatient(dbPath: String, patientId: String, gender: String) {
        if (getPatientById(dbPath, patientId) != null) {
            return
        }
        val jdbcTemplate = getJdbcTemplate(dbPath)
        val genderModl = gender == "Male"
        val sql = "INSERT INTO patient (patientId, gender, genderModl) VALUES (?, ?, ?)"
        jdbcTemplate.update(sql, patientId, gender, genderModl)
    }

    fun insertPatientStatus(dbPath: String, patientId: String, infectious: Boolean, roomNr: Int) {
        if (getPatientStatusById(dbPath, patientId) != null) {
            return
        }
        val jdbcTemplate = getJdbcTemplate(dbPath)
        val sql = "INSERT INTO patientStatus (patientId, infectious, roomNr) VALUES (?, ?, ?)"
        jdbcTemplate.update(sql, patientId, infectious, roomNr)
    }

    fun insertRoom(dbPath: String, id: Long?, description: String) {
        if (getRoomById(dbPath, id!!) != null) {
            return
        }
        val jdbcTemplate = getJdbcTemplate(dbPath)
        val sql = "INSERT INTO roomCategory (catId, roomDescr, catIdModl) VALUES (?, ?, ?)"
        jdbcTemplate.update(sql, id, description, id)
    }

    fun insertRoomDistribution(dbPath: String, roomNumber: Long, roomNumberModel: Long, roomCategory: Long, capacity: Int, bathroom: Boolean) {
        if (getRoomDistributionById(dbPath, roomNumber) != null) {
            return
        }
        val jdbcTemplate = getJdbcTemplate(dbPath)
        val sql = "INSERT INTO roomDistrib (roomNr, roomNrMod, bedCategory, capacity, bathroom) VALUES (?, ?, ?, ?, ?)"
        jdbcTemplate.update(sql, roomNumber, roomNumberModel, roomCategory, capacity, bathroom)
    }

    fun insertScenario(dbPath: String, batch: Int, patientId: String, treatmentName: String) {
        if (getScenarioByData(dbPath, batch, patientId, treatmentName) != null) {
            return
        }
        val jdbcTemplate = getJdbcTemplate(dbPath)
        val sql = "INSERT INTO scenario (batch, patientId, treatmentName) VALUES (?, ?, ?)"
        jdbcTemplate.update(sql, batch, patientId, treatmentName)
    }

    fun insertTask(dbPath: String, name: String, bedCategory: Int, durAvg: Int) {
        if (getTaskByName(dbPath, name) != null) {
            return
        }
        val jdbcTemplate = getJdbcTemplate(dbPath)
        val sql = "INSERT INTO tasks (name, bedCategory, durAvg) VALUES (?, ?, ?)"
        jdbcTemplate.update(sql, name, bedCategory, durAvg)
    }

    fun insertTaskDependency(dbPath: String, diagnosis: String, taskName: String, taskDependency: String) {
        if (getTaskDependencyByData(dbPath, diagnosis, taskName, taskDependency) != null) {
            return
        }
        val jdbcTemplate = getJdbcTemplate(dbPath)
        val sql = "INSERT INTO taskDependencies (treatmentName, taskName, taskDependency) VALUES (?, ?, ?)"
        jdbcTemplate.update(sql, diagnosis, taskName, taskDependency)
    }

    fun getRooms(dbPath: String): List<Map<String, Any>> {
        val jdbcTemplate = getJdbcTemplate(dbPath)
        val sql = "SELECT * FROM roomCategory"
        return try {
            jdbcTemplate.queryForList(sql)
        } catch (e: EmptyResultDataAccessException) {
            emptyList()
        }
    }

    fun getRoomById(dbPath: String, id: Long): Map<String, Any>? {
        val jdbcTemplate = getJdbcTemplate(dbPath)
        val sql = "SELECT * FROM roomCategory WHERE catId = ?"
        return try {
            jdbcTemplate.queryForMap(sql, id)
        } catch (e: EmptyResultDataAccessException) {
            null
        }
    }

    fun getRoomDistributions(dbPath: String): List<Map<String, Any>> {
        val jdbcTemplate = getJdbcTemplate(dbPath)
        val sql = "SELECT * FROM roomDistrib"
        return try {
            jdbcTemplate.queryForList(sql)
        } catch (e: EmptyResultDataAccessException) {
            emptyList()
        }
    }

    fun getRoomDistributionById(dbPath: String, id: Long): Map<String, Any>? {
        val jdbcTemplate = getJdbcTemplate(dbPath)
        val sql = "SELECT * FROM roomDistrib WHERE roomNr = ?"
        return try {
            jdbcTemplate.queryForMap(sql, id)
        } catch (e: EmptyResultDataAccessException) {
            null
        }
    }

    fun getPatients(dbPath: String): List<Map<String, Any>> {
        val jdbcTemplate = getJdbcTemplate(dbPath)
        val sql = "SELECT * FROM patient"
        return try {
            jdbcTemplate.queryForList(sql)
        } catch (e: EmptyResultDataAccessException) {
            emptyList()
        }
    }

    fun getPatientById(dbPath: String, id: String): Map<String, Any>? {
        val jdbcTemplate = getJdbcTemplate(dbPath)
        val sql = "SELECT * FROM patient WHERE patientId = ?"
        return try {
            jdbcTemplate.queryForMap(sql, id)
        } catch (e: EmptyResultDataAccessException) {
            null
        }
    }

    fun getPatientStatuses(dbPath: String): List<Map<String, Any>> {
        val jdbcTemplate = getJdbcTemplate(dbPath)
        val sql = "SELECT * FROM patientStatus"
        return try {
            jdbcTemplate.queryForList(sql)
        } catch (e: EmptyResultDataAccessException) {
            emptyList()
        }
    }

    fun getPatientStatusById(dbPath: String, id: String): Map<String, Any>? {
        val jdbcTemplate = getJdbcTemplate(dbPath)
        val sql = "SELECT * FROM patientStatus WHERE patientId = ?"
        return try {
            jdbcTemplate.queryForMap(sql, id)
        } catch (e: EmptyResultDataAccessException) {
            null
        }
    }

    fun getScenarios(dbPath: String): List<Map<String, Any>> {
        val jdbcTemplate = getJdbcTemplate(dbPath)
        val sql = "SELECT * FROM scenario"
        return try {
            jdbcTemplate.queryForList(sql)
        } catch (e: EmptyResultDataAccessException) {
            emptyList()
        }
    }

    fun getScenarioByData(dbPath: String, batch: Int, patientId: String, treatmentName: String): Map<String, Any>? {
        val jdbcTemplate = getJdbcTemplate(dbPath)
        val sql = "SELECT * FROM scenario WHERE batch = ? AND patientId = ? AND treatmentName = ?"
        return try {
            jdbcTemplate.queryForMap(sql, batch, patientId, treatmentName)
        } catch (e: EmptyResultDataAccessException) {
            null
        }
    }

    fun getTasks(dbPath: String): List<Map<String, Any>> {
        val jdbcTemplate = getJdbcTemplate(dbPath)
        val sql = "SELECT * FROM tasks"
        return try {
            jdbcTemplate.queryForList(sql)
        } catch (e: EmptyResultDataAccessException) {
            emptyList()
        }
    }

    fun getTaskByName(dbPath: String, name: String): Map<String, Any>? {
        val jdbcTemplate = getJdbcTemplate(dbPath)
        val sql = "SELECT * FROM tasks WHERE name = ?"
        return try {
            jdbcTemplate.queryForMap(sql, name)
        } catch (e: EmptyResultDataAccessException) {
            null
        }
    }

    fun getTaskDependencies(dbPath: String): List<Map<String, Any>> {
        val jdbcTemplate = getJdbcTemplate(dbPath)
        val sql = "SELECT * FROM taskDependencies"
        return try {
            jdbcTemplate.queryForList(sql)
        } catch (e: EmptyResultDataAccessException) {
            emptyList()
        }
    }

    fun getTaskDependencyByData(dbPath: String, diagnosis: String, taskName: String, taskDependency: String): Map<String, Any>? {
        val jdbcTemplate = getJdbcTemplate(dbPath)
        val sql = "SELECT * FROM taskDependencies WHERE treatmentName = ? AND taskName = ? AND taskDependency = ?"
        return try {
            jdbcTemplate.queryForMap(sql, diagnosis, taskName, taskDependency)
        } catch (e: EmptyResultDataAccessException) {
            null
        }
    }

    fun updatePatientRoom(dbPath: String, patientId: String, roomNr: Int) {
        val jdbcTemplate = getJdbcTemplate(dbPath)
        val sql = "UPDATE patientStatus SET roomNr = ? WHERE patientId = ?"
        jdbcTemplate.update(sql, roomNr, patientId)
    }
}