package com.m57.hermescontrol.data.model

import kotlinx.serialization.Serializable

/** Read-only gateway projects.list payload; serving profile is selected by the gateway. */
@Serializable
data class ProjectsListResponse(
    val projects: List<ProjectInfo>,
    val activeId: String? = null,
)

@Serializable
data class ProjectInfo(
    val id: String,
    val name: String = "",
    val slug: String? = null,
    val description: String? = null,
    val icon: String? = null,
    val color: String? = null,
    val boardSlug: String? = null,
    val primaryPath: String? = null,
    @Serializable(with = LenientNullableBooleanSerializer::class)
    val archived: Boolean? = null,
    val createdAt: Long? = null,
    val folders: List<ProjectFolder> = emptyList(),
) {
    val isArchived: Boolean get() = archived == true
}

@Serializable
data class ProjectFolder(
    val path: String,
    val label: String? = null,
    @Serializable(with = LenientNullableBooleanSerializer::class)
    val isPrimary: Boolean? = null,
    val addedAt: Long? = null,
)
