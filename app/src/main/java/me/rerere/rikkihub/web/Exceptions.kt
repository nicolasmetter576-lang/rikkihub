package me.rerere.rikkihub.web

import io.ktor.http.HttpStatusCode

sealed class ApiException(
    override val message: String,
    val status: HttpStatusCode,
    val errorCode: String? = null,
) : RuntimeException(message)

class BadRequestException(message: String) : ApiException(message, HttpStatusCode.BadRequest)
class NotFoundException(message: String) : ApiException(message, HttpStatusCode.NotFound)
class UnauthorizedException(message: String) : ApiException(message, HttpStatusCode.Unauthorized)
class ForbiddenException(message: String) : ApiException(message, HttpStatusCode.Forbidden)
class ConflictException(message: String) : ApiException(message, HttpStatusCode.Conflict)
class TooManyRequestsException(message: String) : ApiException(message, HttpStatusCode.TooManyRequests)
class PayloadTooLargeException(message: String) : ApiException(message, HttpStatusCode.PayloadTooLarge)

/** Stable, sanitized file errors; the numeric HTTP code remains backward compatible. */
class FileOperationApiException(
    status: HttpStatusCode,
    errorCode: String,
    message: String,
) : ApiException(message, status, errorCode)

/** Typed client condition with a stable machine-readable [errorCode] (for routes outside this package). */
class CodedApiException(
    status: HttpStatusCode,
    errorCode: String,
    message: String,
) : ApiException(message, status, errorCode)
