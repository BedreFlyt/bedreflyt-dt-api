package no.uio.bedreflyt.api.model.live

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "patient_allocation", uniqueConstraints = [UniqueConstraint(columnNames = ["patient_id", "simulated"])])
class PatientAllocation (
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    var id: Long? = null,

    @ManyToOne
    @JoinColumn(name = "patient_id", referencedColumnName = "patient_id")
    var patientId : Patient,

    @Column(name = "acute")
    var acute : Boolean = false,

    @Column(name = "diagnosis_code")
    var diagnosisCode : String = "",

    @Column(name = "diagnosis_name")
    var diagnosisName : String = "",

    @Column(name = "acute_category")
    var acuteCategory : Int = 0,

    @Column(name = "care_category")
    var careCategory : Int = 0,

    @Column(name = "monitoring_category")
    var monitoringCategory : Int = 0,

    @Column(name = "care_id")
    var careId : Int = 0,

    @Column(name = "contagious")
    var contagious : Boolean = false,

    @Column(name = "ward_name")
    var wardName : String = "",

    @Column(name = "hospital_code")
    var hospitalCode : String = "",

    @Column(name = "room_number")
    var roomNumber : Int = -1,

    @Column(name = "due_date")
    var dueDate: LocalDateTime = LocalDateTime.now(),

    @Column(name = "simulated")
    var simulated: Boolean = false,
) {
    constructor() : this(
        null,
        Patient(),
        false,
        "",
        "",
        0,
        0,
        0,
        0,
        false,
        "",
        "",
        -1,
        LocalDateTime.now(),
        false
    )

    constructor (
        patientId: Patient,
        acute: Boolean,
        diagnosisCode: String,
        diagnosisName: String,
        acuteCategory: Int,
        careCategory: Int,
        monitoringCategory: Int,
        careId: Int,
        contagious: Boolean,
        wardName: String,
        hospitalCode: String,
        roomNumber: Int,
        dueDate: LocalDateTime,
        simulated: Boolean = false
    ) : this(
        null,
        patientId,
        acute,
        diagnosisCode,
        diagnosisName,
        acuteCategory,
        careCategory,
        monitoringCategory,
        careId,
        contagious,
        wardName,
        hospitalCode,
        roomNumber,
        dueDate,
        simulated
    )
}