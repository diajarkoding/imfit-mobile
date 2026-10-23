package com.diajarkoding.imfit.data.repository

import com.diajarkoding.imfit.data.local.entity.UserEntity
import com.diajarkoding.imfit.domain.model.User

internal fun User.toLocal() = UserEntity(id, name, email, birthDate, profilePhotoUri)
internal fun UserEntity.toUser() = User(id, name, email, birthDate, profilePhotoUri)
