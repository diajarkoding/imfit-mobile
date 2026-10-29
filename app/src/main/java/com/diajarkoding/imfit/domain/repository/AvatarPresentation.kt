package com.diajarkoding.imfit.domain.repository

import com.diajarkoding.imfit.domain.model.User
import kotlinx.coroutines.CancellationException

/** Storage paths are canonical data. Signed URLs exist only in presentation copies. */
fun isCanonicalAvatarPath(path: String, ownerId: String): Boolean {
    val segments = path.split('/')
    return segments.size == 2 && segments[0] == ownerId && segments[1].isNotBlank() &&
        segments[1] != "." && segments[1] != ".." &&
        segments[1].all { it.isLetterOrDigit() || it in "._-" }
}

suspend fun AuthRepository.avatarPresentationUrl(path: String?): String? = try {
    getSignedAvatarUrl(path)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null
}

suspend fun AuthRepository.presentAvatar(user: User): User =
    user.copy(profilePhotoUri = avatarPresentationUrl(user.profilePhotoUri))
