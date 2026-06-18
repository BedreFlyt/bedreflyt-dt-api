package no.uio.bedreflyt.api.types

data class Supply(
    val itemName: String,
    val quantity: Int
)

data class SupplyChecker(
    val supplies: Map<String, List<List<Supply>>>,
    val sbl: Boolean
)

data class SuppliesChecker(
    val numberOfPatients: Int,
    val supplies: List<Supply>,
    val sbl: Boolean
)
