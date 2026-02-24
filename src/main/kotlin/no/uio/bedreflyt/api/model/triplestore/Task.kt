package no.uio.bedreflyt.api.model.triplestore

import java.io.Serializable

data class Task (
    val taskName: String,
    val supplies: List<Supply> = emptyList()
) : Serializable
{
    override fun toString(): String {
        return "Task(taskName='$taskName')"
    }
}