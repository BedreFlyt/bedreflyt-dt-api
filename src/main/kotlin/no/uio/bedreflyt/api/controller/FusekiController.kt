package no.uio.bedreflyt.api.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import jakarta.validation.Valid
import no.uio.bedreflyt.api.config.CacheConfig
import no.uio.bedreflyt.api.config.EnvironmentConfig
import no.uio.bedreflyt.api.config.REPLConfig
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import io.swagger.v3.oas.annotations.parameters.RequestBody as SwaggerRequestBody
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

@RestController
@RequestMapping("/api/v1/fuseki")
class FusekiController (
    private val environmentConfig: EnvironmentConfig,
    private val replConfig: REPLConfig,
    private val cacheConfig: CacheConfig,
    private val redisTemplate: RedisTemplate<String, Any>
) {

    private val log : Logger = LoggerFactory.getLogger(FusekiController::class.java.name)

    @Operation(summary = "Update the model")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Model updated"),
        ApiResponse(responseCode = "400", description = "Invalid model"),
        ApiResponse(responseCode = "401", description = "Unauthorized"),
        ApiResponse(responseCode = "403", description = "Accessing the resource you were trying to reach is forbidden"),
        ApiResponse(responseCode = "500", description = "Internal server error")
    ])
    @PostMapping("/update")
    fun updateModel(@SwaggerRequestBody(description = "Query to update the model") @Valid @RequestBody query: String) : ResponseEntity<String> {
        log.info("Updating model")

        return ResponseEntity.ok("Model updated")
    }

    @Operation(summary = "Retrieve the model")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Model retrieved"),
        ApiResponse(responseCode = "400", description = "Invalid model"),
        ApiResponse(responseCode = "401", description = "Unauthorized"),
        ApiResponse(responseCode = "403", description = "Accessing the resource you were trying to reach is forbidden"),
        ApiResponse(responseCode = "500", description = "Internal server error")
    ])
    @GetMapping("/retrieve")
    fun retrieveModel() : ResponseEntity<String> {
        log.info("Retrieving model")

        // Download the bedreflyt.ttl file from here
        val path = "bedreflyt.ttl"
        val fileContent = File(path).readText(Charsets.UTF_8)

        // Return the file content with appropriate headers
        return ResponseEntity.ok()
            .header("Content-Disposition", "attachment; filename=\"$path\"")
            .body(fileContent)
    }

    private fun makePostRequest(url: String, headers: Map<String, String>, body: String): String {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            // Configure the connection
            connection.requestMethod = "POST"
            connection.doOutput = true
            headers.forEach { (key, value) ->
                connection.setRequestProperty(key, value)
            }

            // Write the body
            connection.outputStream.use { outputStream ->
                outputStream.write(body.toByteArray(Charsets.UTF_8))
            }

            // Read the response
            return connection.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            // Handle error response
            return connection.errorStream?.bufferedReader()?.use { it.readText() } ?: e.message.orEmpty()
        } finally {
            connection.disconnect()
        }
    }

    @Operation(summary = "Upload a new model")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Model uploaded"),
        ApiResponse(responseCode = "400", description = "Invalid model"),
        ApiResponse(responseCode = "401", description = "Unauthorized"),
        ApiResponse(responseCode = "403", description = "Accessing the resource you were trying to reach is forbidden"),
        ApiResponse(responseCode = "500", description = "Internal server error")
    ])
    @PostMapping("/upload")
    fun uploadModel(@SwaggerRequestBody(description = "Model to upload") @Valid @RequestBody modelFile: MultipartFile) : ResponseEntity<String> {
        log.info("Uploading model")

        val host = environmentConfig.getOrDefault("TRIPLESTORE_URL", "localhost")
        val dataStore = environmentConfig.getOrDefault("TRIPLESTORE_DATASET", "Bedreflyt")
        val tripleStore = "http://$host:3030/$dataStore"
        val prefix = environmentConfig.getOrDefault("DOMAIN_PREFIX", "http://www.smolang.org/bedreflyt#")

        val deleteUrl = "$tripleStore/update"
        val deleteHeaders = mapOf("Content-Type" to "application/sparql-update")
        val deleteBody = "WITH $prefix DELETE { ?s ?p ?o } WHERE { ?s ?p ?o }"

        val deleteResponse = makePostRequest(deleteUrl, deleteHeaders, deleteBody)
        println(deleteResponse)

        // Then upload the new ontology
        val uploadUrl = "$tripleStore/data"
        val ontologyData = modelFile.bytes.toString(Charsets.UTF_8)
        val uploadHeaders = mapOf("Content-Type" to "text/turtle;charset=utf-8")

        val uploadResponse = makePostRequest(uploadUrl, uploadHeaders, ontologyData)
        println(uploadResponse)

        val repl = replConfig.repl()
        repl.interpreter!!.tripleManager.regenerateTripleStoreModel(tripleStore)
        repl.interpreter!!.evalCall(
            repl.interpreter!!.getObjectNames("AssetModel")[0],
            "AssetModel",
            "reconfigure"
        )

        val path = "bedreflyt.ttl"
        modelFile.transferTo(File(path))

        // Clear all Redis cache
        try {
            val keys = redisTemplate.keys("*")
            if (keys.isNotEmpty()) {
                redisTemplate.delete(keys)
                log.info("Cleared ${keys.size} cache entries from Redis")
            } else {
                log.info("No cache entries found in Redis")
            }
        } catch (e: Exception) {
            log.error("Failed to clear Redis cache: ${e.message}")
        }

        return ResponseEntity.ok("Model uploaded")
    }
}