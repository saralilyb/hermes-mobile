package com.m57.hermescontrol.ui.chat.fakes

import com.m57.hermescontrol.data.local.SlashUsageStore
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

class FakeSlashUsageStore : SlashUsageStore(mockk(relaxed = true)) {
    private val countsByProfile = MutableStateFlow<Map<String, Map<String, Int>>>(emptyMap())

    override fun counts(profileId: String): Flow<Map<String, Int>> = countsByProfile.map { it[profileId].orEmpty() }

    override suspend fun recordUse(
        profileId: String,
        command: String,
    ) {
        countsByProfile.update { all ->
            val profileCounts = all[profileId].orEmpty()
            all + (profileId to (profileCounts + (command to ((profileCounts[command] ?: 0) + 1))))
        }
    }

    fun countsNow(profileId: String): Map<String, Int> = countsByProfile.value[profileId].orEmpty()
}
