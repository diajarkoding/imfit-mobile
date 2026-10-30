package com.diajarkoding.imfit.domain.model

enum class AuthSessionState {
    Initializing,
    Authenticated,
    /**
     * Refresh token gagal sementara (SessionStatus.RefreshFailure pada supabase-kt 3.x).
     * Konteks UI & cache lokal dipertahankan, tetapi session belum tervalidasi untuk request remote baru.
     */
    RefreshFailure,
    Unauthenticated
}

sealed interface ProfileLoadState {
    data object Unauthenticated : ProfileLoadState
    data object Loading : ProfileLoadState
    data class Ready(val user: User) : ProfileLoadState
    /** Session valid, tetapi baris di tabel `profiles` belum ada dan menggunakan fallback metadata auth. */
    data class Empty(val userId: String, val fallbackName: String, val fallbackEmail: String) : ProfileLoadState
    /** Fetch ke remote gagal; tetap menyimpan cachedUser (jika ada) agar UI tidak kehilangan identitas yang sudah diketahui. */
    data class Error(val userId: String, val message: String?, val cachedUser: User? = null) : ProfileLoadState
}

sealed interface AvatarLoadState {
    /** Pengguna tidak memiliki foto profil (`avatar_url == null/blank`). Jangan tampilkan shimmer. */
    data object None : AvatarLoadState
    /** Sedang mengambil signed URL dari Supabase Storage. Tampilkan shimmer lingkaran. */
    data object Loading : AvatarLoadState
    /** Signed URL (atau URI langsung yang valid) siap dimuat oleh Coil. */
    data class Ready(val url: String) : AvatarLoadState
    /** Gagal membuat signed URL. Tampilkan ikon fallback Person, bukan shimmer tanpa batas. */
    data object Error : AvatarLoadState
}

data class UserProfileState(
    val sessionState: AuthSessionState = AuthSessionState.Initializing,
    val userId: String? = null,
    val profileState: ProfileLoadState = ProfileLoadState.Loading,
    val avatarState: AvatarLoadState = AvatarLoadState.None
) {
    /**
     * Digunakan oleh UI untuk mempertahankan konteks tampilan (tidak memaksa logout saat RefreshFailure sementara).
     */
    val hasRetainedSessionContext: Boolean
        get() = sessionState == AuthSessionState.Authenticated ||
            (sessionState == AuthSessionState.RefreshFailure && userId != null)

    /**
     * Hanya bernilai true jika session benar-benar terautentikasi dan siap melakukan operasi remote.
     */
    val hasValidatedRemoteSession: Boolean
        get() = sessionState == AuthSessionState.Authenticated && userId != null

    val currentUser: User?
        get() = when (val s = profileState) {
            is ProfileLoadState.Ready -> s.user
            is ProfileLoadState.Error -> s.cachedUser
            is ProfileLoadState.Empty -> User(id = s.userId, name = s.fallbackName, email = s.fallbackEmail)
            else -> null
        }

    val displayName: String
        get() = currentUser?.name.orEmpty()

    val email: String
        get() = currentUser?.email.orEmpty()

    val birthDate: String?
        get() = currentUser?.birthDate

    val isProfileLoading: Boolean
        get() = sessionState == AuthSessionState.Initializing ||
            (profileState is ProfileLoadState.Loading && currentUser == null)

    val isAvatarLoading: Boolean
        get() = isProfileLoading || avatarState is AvatarLoadState.Loading

    /**
     * Hanya mengembalikan URL yang benar-benar siap dimuat oleh AsyncImage (Signed URL atau URI langsung yang valid).
     * TIDAK PERNAH mengembalikan storagePath privat mentah (seperti "userId/uuid.jpg").
     */
    val resolvedAvatarUrl: String?
        get() = (avatarState as? AvatarLoadState.Ready)?.url

    companion object {
        fun unauthenticated(): UserProfileState = UserProfileState(
            sessionState = AuthSessionState.Unauthenticated,
            userId = null,
            profileState = ProfileLoadState.Unauthenticated,
            avatarState = AvatarLoadState.None
        )

        fun fromDirectUser(user: User?): UserProfileState {
            if (user == null) return unauthenticated()
            val photoUri = user.profilePhotoUri?.takeIf { it.isNotBlank() }
            val avatarState = if (photoUri != null && isDirectLoadableUri(photoUri)) {
                AvatarLoadState.Ready(photoUri)
            } else {
                AvatarLoadState.None
            }
            return UserProfileState(
                sessionState = AuthSessionState.Authenticated,
                userId = user.id,
                profileState = ProfileLoadState.Ready(user),
                avatarState = avatarState
            )
        }

        fun isDirectLoadableUri(uri: String?): Boolean {
            if (uri.isNullOrBlank()) return false
            return uri.startsWith("https://") ||
                uri.startsWith("http://") ||
                uri.startsWith("content://") ||
                uri.startsWith("file://") ||
                uri.startsWith("data:")
        }
    }
}
