package no.uio.bedreflyt.api.config

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PostConstruct
import no.uio.microobject.ast.expr.LiteralExpr
import no.uio.microobject.main.Settings
import no.uio.microobject.main.ReasonerMode
import no.uio.microobject.runtime.REPL
import no.uio.microobject.type.STRINGTYPE
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

@Configuration
open class REPLConfig (
    private val environmentConfig: EnvironmentConfig
) {

    private lateinit var repl: REPL
    private val md = MessageDigest.getInstance("MD5")
    private val objectMapper = ObjectMapper()
    private val logger = LoggerFactory.getLogger(REPLConfig::class.java)

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

    private fun updateTriplestore(fusekiUrl: String): String {
        // First delete if Fuseki already contains our ontology
        val prefix = environmentConfig.getOrDefault("DOMAIN_PREFIX", "http://www.smolang.org/bedreflyt#")
        val deleteUrl = "$fusekiUrl/update"
        val deleteHeaders = mapOf("Content-Type" to "application/sparql-update")
        val deleteBody = "WITH $prefix DELETE { ?s ?p ?o } WHERE { ?s ?p ?o }"

        val deleteResponse = makePostRequest(deleteUrl, deleteHeaders, deleteBody)
        println(deleteResponse)

        // Then upload the new ontology
        val uploadUrl = "$fusekiUrl/data"
        val ontologyData = File("bedreflyt.ttl").readText()
        val uploadHeaders = mapOf("Content-Type" to "text/turtle;charset=utf-8")

        val uploadResponse = makePostRequest(uploadUrl, uploadHeaders, ontologyData)
        println(uploadResponse)

        return uploadResponse
    }

    @PostConstruct
    fun initRepl() {
        val verbose = false
        val materialize = true
        val liftedStateOutputPath = environmentConfig.getOrDefault("LIFTED_STATE_OUTPUT_PATH", "./")
        val progPrefix = "https://github.com/Edkamb/SemanticObjects/Program#"
        // Use the md5 of "BedreFlytDT" as run ID to ensure it remains the same across runs
        val runId = md.digest("BedreFlytDT".toByteArray()).joinToString("") { String.format("%02x", it) }
        val runPrefix = "https://github.com/Edkamb/SemanticObjects/Run$runId#"
        val langPrefix = "https://github.com/Edkamb/SemanticObjects#"
        val extraPrefixes = HashMap<String, String>()
        val useQueryType = false
        val tripleStoreHost = System.getenv("TRIPLESTORE_URL") ?: "localhost"
        val tripleStoreDataset = System.getenv("TRIPLESTORE_DATASET") ?: "ds"
        val odrlTripleStoreDataset = System.getenv("ODRL_TRIPLESTORE_DATASET") ?: "odrl"
        val tripleStores = (odrlTripleStoreDataset.split(";") + tripleStoreDataset).distinct().map { "http://$tripleStoreHost:3030/$it/query" }
        val domainPrefixUri = environmentConfig.getOrDefault("DOMAIN_PREFIX_URI", "")
        val reasoner = ReasonerMode.off
        val features = mutableMapOf(
            "odrl" to true
        )

        if (environmentConfig.get("EXTRA_PREFIXES") != null) {
            val prefixes = environmentConfig.get("EXTRA_PREFIXES")!!.split(";")
            for (prefix in prefixes) {
                val parts = prefix.split(",")
                extraPrefixes.putAll(mapOf(parts[0] to parts[1]))
            }
        }

        val settings = Settings(
            verbose,
            materialize,
            liftedStateOutputPath,
            tripleStores,
            "",
            domainPrefixUri,
            progPrefix,
            runPrefix,
            langPrefix,
            extraPrefixes,
            useQueryType,
            reasoner,
            features = features
        )

        val smolPath = environmentConfig.getOrDefault("SMOL_PATH", "Bedreflyt.smol")
        repl = REPL(settings)
        repl.command("multiread", smolPath)
        repl.command("auto", "")

        if (!validatePolicies()) {
            throw RuntimeException("Policy validation failed")
        }
    }

    private fun validatePolicies(): Boolean {
        val tripleStoreHost = System.getenv("TRIPLESTORE_URL") ?: "localhost"
        val odrlTripleStoreDataset = System.getenv("ODRL_TRIPLESTORE_DATASET") ?: "odrl"
        val odrlEndpoint = System.getenv("ODRL_URL") ?: "localhost"
        val odrlPort = System.getenv("ODRL_PORT") ?: "3000"
        val odrlToken = System.getenv("ODRL_TOKEN") ?: ""

        val policyUrl = "http://$tripleStoreHost:3030/policies/data"
        val requestUrl = "http://$tripleStoreHost:3030/requests/data"
        val sotwUrl = "http://$tripleStoreHost:3030/sotw/data"

        // Make basic get requests to the above urls and get the content as string. Don't use khttp
        val policyString = java.net.URI(policyUrl).toURL().readText()
        val requestString = java.net.URI(requestUrl).toURL().readText()
        val sotwString = java.net.URI(sotwUrl).toURL().readText()

        val evaluateUrl = "http://$odrlEndpoint:$odrlPort/evaluate"
        logger.info("Evaluating ODRL policies at $evaluateUrl")
        logger.info("Authorization token: $odrlToken")

        val evaluateBody = objectMapper.writeValueAsString(
            mapOf(
                "policy" to policyString,
                "request" to requestString,
                "sotw" to sotwString
            )
        )

        logger.info("Request body length: ${evaluateBody.length}")

        val connection = java.net.URI(evaluateUrl).toURL().openConnection() as java.net.HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Authorization", "Bearer $odrlToken")
        connection.doOutput = true
        connection.outputStream.use { os ->
            val input = evaluateBody.toByteArray(Charsets.UTF_8)
            os.write(input, 0, input.size)
        }
        val responseCode = connection.responseCode
        if (responseCode != 200) {
            val errorStream = connection.errorStream
            val errorMessage = errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
            println("Error evaluating ODRL: $errorMessage")
            return false
        }

        return true
    }

    @Bean
    open fun validatePoliciesBean(): () -> Unit = {
        validatePolicies()
    }

    @Bean
    open fun repl(): REPL {
        return repl
    }

    @Bean
    open fun regenerateSingleModel() : (String) -> Unit = { modelName: String ->
        val escapedModelName = "\"$modelName\""
        val tripleStoreHost = System.getenv("TRIPLESTORE_URL") ?: "localhost"
        val tripleStoreDataset = System.getenv("TRIPLESTORE_DATASET") ?: "ds"
        val endpoint = "http://$tripleStoreHost:3030/$tripleStoreDataset"
        repl.interpreter!!.tripleManager.regenerateTripleStoreModel(endpoint)
        repl.interpreter!!.evalCall(
            repl.interpreter!!.getObjectNames("AssetModel")[0],
            "AssetModel",
            "reconfigureSingleModel",
            mapOf("mod" to LiteralExpr(escapedModelName, STRINGTYPE))
        )
    }
}