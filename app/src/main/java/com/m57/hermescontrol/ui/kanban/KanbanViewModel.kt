package com.m57.hermescontrol.ui.kanban

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.model.KanbanBoard
import com.m57.hermescontrol.data.model.KanbanColumn
import com.m57.hermescontrol.data.model.KanbanTask
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.ui.common.ToastHost
import com.m57.hermescontrol.ui.common.safeLaunchLoad
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class KanbanUiState(
    val isLoading: Boolean = false,
    val boards: List<KanbanBoard> = emptyList(),
    val selectedBoard: KanbanBoard? = null,
    val columns: List<KanbanColumn> = emptyList(),
    val tasks: List<KanbanTask> = emptyList(),
    val errorMessage: String? = null,
    val toastMessage: String? = null,
)

class KanbanViewModel(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) :
    ViewModel(),
        ToastHost {
    private val _uiState = MutableStateFlow(KanbanUiState())
    val uiState: StateFlow<KanbanUiState> = _uiState.asStateFlow()

    fun loadBoards() {
        safeLaunchLoad(
            ioDispatcher = ioDispatcher,
            apiCall = { safeApiCall { ApiClient.hermesApi.getKanbanBoards() } },
            onStart = { _uiState.update { it.copy(isLoading = true, errorMessage = null) } },
            onSuccess = { data ->
                val boards = data.boards.orEmpty()
                _uiState.update { it.copy(isLoading = false, boards = boards) }
                val currentSlug = data.current
                val currentBoard = currentSlug?.let { slug -> boards.find { it.id == slug } }
                if (currentBoard != null) {
                    _uiState.update { it.copy(selectedBoard = currentBoard) }
                    reloadBoard(currentBoard)
                } else {
                    boards.firstOrNull()?.let(::selectBoard)
                }
            },
            onError = { errorMsg ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Failed to load Kanban boards: $errorMsg",
                    )
                }
            },
        )
    }

    fun selectBoard(board: KanbanBoard) {
        if (_uiState.value.selectedBoard?.id == board.id) {
            reloadBoard(board)
            return
        }
        _uiState.update { it.copy(selectedBoard = board, isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            val switchResult =
                withContext(ioDispatcher) {
                    safeApiCall { ApiClient.hermesApi.switchKanbanBoard(board.id) }
                }
            if (switchResult is NetworkResult.Failure) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Failed to switch Kanban board: ${switchResult.error.message}",
                    )
                }
                return@launch
            }

            loadBoardIntoState()
        }
    }

    private fun reloadBoard(board: KanbanBoard) {
        _uiState.update { it.copy(selectedBoard = board, isLoading = true, errorMessage = null) }
        viewModelScope.launch { loadBoardIntoState() }
    }

    private suspend fun loadBoardIntoState() {
        when (val result = withContext(ioDispatcher) { safeApiCall { ApiClient.hermesApi.getKanbanBoard() } }) {
            is NetworkResult.Success -> {
                val columns = result.data.columns
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        columns = columns,
                        tasks =
                            columns.flatMap {
                                    column ->
                                column.tasks
                            },
                    )
                }
            }

            is NetworkResult.Failure -> {
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = "Failed to load Kanban tasks: ${result.error.message}")
                }
            }
        }
    }

    fun createTask(
        title: String,
        description: String?,
        status: String,
    ) {
        val board = _uiState.value.selectedBoard ?: return
        viewModelScope.launch {
            val result =
                withContext(ioDispatcher) {
                    safeApiCall {
                        ApiClient.hermesApi.createKanbanTask(
                            board = board.id,
                            task =
                                com.m57.hermescontrol.data.model.CreateTaskBody(
                                    title = title,
                                    body = description,
                                ),
                        )
                    }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update { it.copy(toastMessage = "Task created successfully") }
                    if (_uiState.value.selectedBoard?.id == board.id) {
                        reloadBoard(board)
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update { it.copy(toastMessage = "Failed to create task: ${result.error.message}") }
                }
            }
        }
    }

    fun moveTask(
        task: KanbanTask,
        newStatus: String,
    ) {
        val originalStatus = task.status
        val board = _uiState.value.selectedBoard ?: return

        // Optimistically update
        _uiState.update { state ->
            state.copy(
                tasks =
                    state.tasks.map {
                        if (it.id == task.id) it.copy(status = newStatus) else it
                    },
            )
        }

        viewModelScope.launch {
            val result =
                withContext(ioDispatcher) {
                    safeApiCall { ApiClient.hermesApi.updateKanbanTask(task.id, mapOf("status" to newStatus)) }
                }
            if (result is NetworkResult.Failure) {
                revertTaskMove(task.id, originalStatus, "Failed to move task: ${result.error.message}")
            }
        }
    }

    private fun revertTaskMove(
        taskId: String,
        originalStatus: String,
        errorMsg: String,
    ) {
        _uiState.update { state ->
            state.copy(
                tasks =
                    state.tasks.map {
                        if (it.id == taskId) it.copy(status = originalStatus) else it
                    },
                toastMessage = errorMsg,
            )
        }
    }

    override fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }
}
