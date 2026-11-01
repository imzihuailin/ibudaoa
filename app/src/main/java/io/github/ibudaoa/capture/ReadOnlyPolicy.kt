package io.github.ibudaoa.capture

/** An exact allowlist. "get" in a name is not proof of a read: signup/timestamp are excluded. */
object ReadOnlyPolicy {
    const val OFFICIAL_PACKAGE = "com.lptiyu.tanke"
    const val HOST = "api2.lptiyu.com"
    // Official 4.3.1 splash.k.J3 reads RequestIPByDomain before account startup.
    const val BOOTSTRAP_IP = "/v3/api.php/System/getIp"
    const val BEFORE_RUN = "/v3/api.php/Run2/beforeRunV260"
    const val USER_PROFILE = "/v3/api.php/User/User"
    private val paths = setOf(
        BOOTSTRAP_IP,
        BEFORE_RUN,
        USER_PROFILE,
        "/v3/api.php/Run/GetAllZone",
        "/v3/api.php/Run/GetZonePoints",
    )

    fun allows(method: String, target: String): Boolean = method == "POST" && target in paths
    fun allowsConnect(target: String): Boolean = target == "$HOST:443"

    fun blockedKind(target: String): BlockedRequestKind = when (target.substringBefore('?')) {
        "/v3/api.php/My/getSignupPoints" -> BlockedRequestKind.SIGNUP
        "/v3/api.php/Run/getTimestampV278" -> BlockedRequestKind.TASK_TIME_OR_START
        "/v3/api.php/Run/setRunZone" -> BlockedRequestKind.SELECT_ZONE
        "/v3/api.php/Run/setRunLocationRecord" -> BlockedRequestKind.RECORD_UPLOAD
        "/v3/api.php/Run/stopRunV278" -> BlockedRequestKind.FINISH
        "/v3/api.php/Run2/faceVerify" -> BlockedRequestKind.FACE
        else -> BlockedRequestKind.OTHER
    }
}

enum class BlockedRequestKind { SIGNUP, TASK_TIME_OR_START, SELECT_ZONE, RECORD_UPLOAD, FINISH, FACE, OTHER }
