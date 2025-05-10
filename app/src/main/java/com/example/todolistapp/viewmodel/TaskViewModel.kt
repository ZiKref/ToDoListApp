package com.example.todolistapp.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.example.todolistapp.data.Task
import com.example.todolistapp.data.TaskDao
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TaskViewModel(private val taskDao: TaskDao) : ViewModel() {
    enum class TaskFilter { ALL, ACTIVE, COMPLETED }

    private val _filter = MutableStateFlow(TaskFilter.ALL)
    val filter: StateFlow<TaskFilter> = _filter.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val tasks: StateFlow<List<Task>> = combine(
        taskDao.getAllTasks(),
        filter,
        searchQuery
    ) { tasks, filter, query ->
        val filteredTasks = tasks.filter {
            it.title.contains(query, ignoreCase = true)
        }
        when (filter) {
            TaskFilter.ALL -> filteredTasks
            TaskFilter.ACTIVE -> filteredTasks.filter { !it.isCompleted }
            TaskFilter.COMPLETED -> filteredTasks.filter { it.isCompleted }
        }
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), emptyList())

    fun addTask(title: String, priority: String, dueDate: Long? = null, notificationId: String? = null) {
        viewModelScope.launch {
            val maxOrder = taskDao.getAllTasks().stateIn(viewModelScope).value.maxOfOrNull { it.order } ?: 0
            val task = Task(
                title = title,
                priority = priority,
                order = maxOrder + 1,
                dueDate = dueDate,
                notificationId = notificationId
            )
            taskDao.insertTask(task)
        }
    }

    fun toggleTaskCompletion(task: Task, context: Context) {
        viewModelScope.launch {
            val updatedTask = task.copy(isCompleted = !task.isCompleted)
            if (updatedTask.isCompleted && updatedTask.notificationId != null) {
                WorkManager.getInstance(context).cancelUniqueWork(updatedTask.notificationId)
            }
            taskDao.updateTask(updatedTask)
        }
    }

    fun deleteTask(task: Task, context: Context) {
        viewModelScope.launch {
            task.notificationId?.let { notificationId ->
                WorkManager.getInstance(context).cancelUniqueWork(notificationId)
            }
            taskDao.deleteTask(task)
        }
    }

    fun setFilter(newFilter: TaskFilter) {
        _filter.value = newFilter
    }

    fun updateTask(task: Task) {
        viewModelScope.launch {
            taskDao.updateTask(task)
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun reorderTasks(tasks: List<Task>) {
        viewModelScope.launch {
            tasks.forEachIndexed { index, task ->
                taskDao.updateTask(task.copy(order = index))
            }
        }
    }
}