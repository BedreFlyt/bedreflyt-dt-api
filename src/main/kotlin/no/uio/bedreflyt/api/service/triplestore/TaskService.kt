package no.uio.bedreflyt.api.service.triplestore

import no.uio.bedreflyt.api.config.REPLConfig
import no.uio.bedreflyt.api.config.TriplestoreProperties
import no.uio.bedreflyt.api.model.triplestore.Supply
import no.uio.bedreflyt.api.model.triplestore.Task
import org.apache.jena.query.QuerySolution
import org.apache.jena.query.ResultSet
import org.apache.jena.update.UpdateExecutionFactory
import org.apache.jena.update.UpdateFactory
import org.apache.jena.update.UpdateProcessor
import org.apache.jena.update.UpdateRequest
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.CachePut
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import java.util.concurrent.locks.ReentrantReadWriteLock

@Service
open class TaskService (
    private val replConfig: REPLConfig,
    triplestoreProperties: TriplestoreProperties
) {

    private val tripleStore = triplestoreProperties.tripleStore
    private val prefix = triplestoreProperties.prefix
    private val ttlPrefix = triplestoreProperties.ttlPrefix
    private val repl = replConfig.repl()
    private val lock = ReentrantReadWriteLock()
    private val log : Logger = LoggerFactory.getLogger(TaskService::class.java.name)

    @CacheEvict(value = ["tasks"], allEntries = true)
    @CachePut("tasks", key = "#taskName")
    open fun createTask(taskName: String) : Task? {
        lock.writeLock().lock()
        try {
            val name = taskName.replace(" ", "")
            val query = """
            PREFIX bedreflyt: <$prefix>
            PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
            PREFIX owl: <http://www.w3.org/2002/07/owl#>
            
            INSERT DATA {
                bedreflyt:$name rdf:type owl:NamedIndividual , 
                        <http://purl.org/net/p-plan#Step> ;
                    bedreflyt:taskName "$taskName" .
            }
        """

            val updateRequest: UpdateRequest = UpdateFactory.create(query)
            val fusekiEndpoint = "$tripleStore/update"
            val updateProcessor: UpdateProcessor = UpdateExecutionFactory.createRemote(updateRequest, fusekiEndpoint)

            try {
                updateProcessor.execute()
                replConfig.regenerateSingleModel().invoke("tasks")
                return Task(taskName)
            } catch (_: Exception) {
                return null
            }
        } finally {
            lock.writeLock().unlock()
        }
    }

    private fun getSuppliesForTasks (taskName: String) : List<Supply>? {
        val supplies: MutableList<Supply> = mutableListOf()

        val query = """
            SELECT DISTINCT ?supplyName WHERE {
                ?obj a prog:Task ;
                    prog:Task_taskName "$taskName" ;
                    prog:Task_supplies ?supplies .
                ?supplies a prog:Supply;
                    prog:Supply_supplyName ?supplyName .
            }
        """

        val resultSupplies: ResultSet = repl.interpreter!!.query(query)!!

        if (!resultSupplies.hasNext()) {
            return null
        }

        while (resultSupplies.hasNext()) {
            val solution: QuerySolution = resultSupplies.next()
            val supplyName = solution.get("?supplyName").asLiteral().getString()
            // Sometimes the label is not parsed properly and contains the language tag, so we remove it if present
            val cleanedSupplyName = supplyName.replace("@en", "").trim()

            val supplyNames = cleanedSupplyName.split("and").map { it.trim() }
            for (name in supplyNames) {
                supplies.add(Supply(name))
            }
        }

        return supplies
    }

    @Cacheable(value = ["tasks"], key = "'allTasks'")
    open fun getAllTasks() : List<Task>? {
        lock.readLock().lock()
        try {
            val tasks: MutableList<Task> = mutableListOf()

            val query =
                """
           SELECT DISTINCT ?taskName WHERE {
                ?obj a prog:Task ;
                    prog:Task_taskName ?taskName .
            }"""

            val resultTasks: ResultSet = repl.interpreter!!.query(query)!!

            if (!resultTasks.hasNext()) {
                return null
            }

            val taskNames: MutableList<String> = mutableListOf()
            while (resultTasks.hasNext()) {
                val solution: QuerySolution = resultTasks.next()
                taskNames.add(solution.get("?taskName").asLiteral().getString())
            }

            for (taskName in taskNames) {
                val supplies = getSuppliesForTasks(taskName)
                tasks.add(Task(taskName, supplies ?: emptyList()))
            }

            return tasks
        } finally {
            lock.readLock().unlock()
        }
    }

    @Cacheable("tasks", key = "#taskName")
    open fun getTaskByTaskName(taskName: String) : Task? {
        lock.readLock().lock()
        try {
            val query = """
            SELECT DISTINCT ?taskName WHERE {
                ?obj a prog:Task ;
                    prog:Task_taskName ?taskName .
                FILTER (?taskName = "$taskName")
            }
        """

            val resultTask: ResultSet = repl.interpreter!!.query(query)!!

            if (!resultTask.hasNext()) {
                return null
            }

            val solution: QuerySolution = resultTask.next()
            val taskNameResult = solution.get("?taskName").asLiteral().getString()
            val supplies = getSuppliesForTasks(taskNameResult)

            return Task(taskNameResult, supplies ?: emptyList())
        } finally {
            lock.readLock().unlock()
        }
    }

    @CacheEvict(value = ["tasks"], allEntries = true)
    @CachePut("tasks", key = "#newTaskName")
    open fun updateTask(task: Task, newTaskName: String) : Task? {
        lock.writeLock().lock()
        try {
            val oldName = task.taskName.replace(" ", "")
            val newName = newTaskName.replace(" ", "")
            val query = """
            PREFIX bedreflyt: <$prefix>
            PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
            PREFIX owl: <http://www.w3.org/2002/07/owl#>
            
            DELETE {
                bedreflyt:$oldName rdf:type owl:NamedIndividual , 
                        <http://purl.org/net/p-plan#Step> ;
                    bedreflyt:taskName "${task.taskName}" .
            }
            INSERT {
                bedreflyt:$newName rdf:type owl:NamedIndividual , 
                        <http://purl.org/net/p-plan#Step> ;
                    bedreflyt:taskName "$newTaskName" .
            }
            WHERE {
               bedreflyt:$oldName rdf:type owl:NamedIndividual , 
                        <http://purl.org/net/p-plan#Step> ;
                    bedreflyt:taskName "${task.taskName}" .
            }
        """

            val updateRequest: UpdateRequest = UpdateFactory.create(query)
            val fusekiEndpoint = "$tripleStore/update"
            val updateProcessor: UpdateProcessor = UpdateExecutionFactory.createRemote(updateRequest, fusekiEndpoint)

            try {
                updateProcessor.execute()
                replConfig.regenerateSingleModel().invoke("tasks")
                return Task(newTaskName)
            } catch (_: Exception) {
                return null
            }
        } finally {
            lock.writeLock().unlock()
        }
    }

    @CacheEvict(value = ["tasks"], allEntries = true)
    open fun deleteTask(task: Task) : Boolean {
        lock.writeLock().lock()
        try {
            val name = task.taskName.replace(" ", "")
            val query = """
            PREFIX bedreflyt: <$prefix>
            PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
            PREFIX owl: <http://www.w3.org/2002/07/owl#>
            
            DELETE {
                bedreflyt:$name rdf:type owl:NamedIndividual , 
                        <http://purl.org/net/p-plan#Step> ;
                    bedreflyt:taskName "${task.taskName}" .
            }
            WHERE {
                bedreflyt:$name rdf:type owl:NamedIndividual , 
                        <http://purl.org/net/p-plan#Step> ;
                    bedreflyt:taskName "${task.taskName}" .
            }
        """

            val updateRequest: UpdateRequest = UpdateFactory.create(query)
            val fusekiEndpoint = "$tripleStore/update"
            val updateProcessor: UpdateProcessor = UpdateExecutionFactory.createRemote(updateRequest, fusekiEndpoint)

            try {
                updateProcessor.execute()
                replConfig.regenerateSingleModel().invoke("tasks")
                return true
            } catch (_: Exception) {
                return false
            }
        } finally {
            lock.writeLock().unlock()
        }
    }
}