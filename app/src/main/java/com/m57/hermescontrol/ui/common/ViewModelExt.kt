package com.m57.hermescontrol.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.remote.NetworkResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

interface ToastHost {
    fun clearToast()
}

/**
 * Runs [apiCall] off the main thread and reports the outcome back on it.
 *
 * [ioDispatcher] exists so tests can supply their own dispatcher. The default
 * hops to the real [Dispatchers.IO] pool, which a test scheduler cannot drain:
 * `advanceUntilIdle()` returns while the work is still in flight, the test
 * finishes, `Dispatchers.resetMain()` runs, and the resumption then lands on an
 * unset main dispatcher and throws on a background thread. Pass the test
 * dispatcher from the owning ViewModel to keep the whole chain deterministic.
 */
inline fun <T> ViewModel.safeLaunchLoad(
    currentJob: Job? = null,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    crossinline apiCall: suspend () -> NetworkResult<T>,
    crossinline onStart: () -> Unit,
    crossinline onSuccess: (T) -> Unit,
    crossinline onError: (String) -> Unit,
): Job {
    if (currentJob?.isActive == true) return currentJob
    onStart()
    return viewModelScope.launch {
        val result = withContext(ioDispatcher) { apiCall() }
        when (result) {
            is NetworkResult.Success -> onSuccess(result.data)
            is NetworkResult.Failure -> onError(result.error.message)
        }
    }
}
