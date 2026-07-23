package no.uio.bedreflyt.api.model.triplestore

import com.fasterxml.jackson.annotation.JsonIgnore
import java.io.Serializable

data class Ward (
    val wardName: String,
    val wardCode: String?,
    val capacityThreshold: Double,
    val wardHospital: Hospital,
    val wardFloor: Floor
) : Serializable
{
    companion object {
        private const val serialVersionUID = 1L
    }
    
    @get:JsonIgnore
    val hospital : Hospital
        get() = wardHospital // Custom getter for SpEL compatibility
    @get:JsonIgnore
    val floor : Floor
        get() = wardFloor // Custom getter for SpEL compatibility

    override fun toString(): String {
        return "Ward(wardName='$wardName', wardCode=$wardCode, wardHospital=$wardHospital, wardFloor=$wardFloor)"
    }
}