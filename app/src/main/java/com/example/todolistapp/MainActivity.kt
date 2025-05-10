package com.example.todolistapp

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.todolistapp.data.DatabaseProvider
import com.example.todolistapp.data.Task
import com.example.todolistapp.ui.theme.ToDoListTheme
import com.example.todolistapp.viewmodel.TaskViewModel
import com.example.todolistapp.viewmodel.TaskViewModelFactory
import com.example.todolistapp.worker.ReminderWorker
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import android.Manifest
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.zIndex

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val channel = NotificationChannelCompat.Builder(
            "reminder_channel",
            NotificationManagerCompat.IMPORTANCE_DEFAULT
        )
            .setName("Напоминания о задачах")
            .setDescription("Уведомления для напоминаний о задачах")
            .build()
        NotificationManagerCompat.from(this).createNotificationChannel(channel)

        setContent {
            ToDoListTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val taskDao = DatabaseProvider.getDatabase(this).taskDao()
                    val viewModel: TaskViewModel = viewModel(factory = TaskViewModelFactory(taskDao))
                    val navController = rememberNavController()
                    NavHost(navController = navController, startDestination = "taskList") {
                        composable("taskList") {
                            ToDoListScreen(viewModel, onEditTask = { task ->
                                navController.navigate("editTask/${task.id}")
                            })
                        }
                        composable("editTask/{taskId}") { backStackEntry ->
                            val taskId = backStackEntry.arguments?.getString("taskId")?.toInt() ?: 0
                            EditTaskScreen(viewModel, taskId, onBack = { navController.popBackStack() })
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ToDoListScreen(viewModel: TaskViewModel, onEditTask: (Task) -> Unit) {
    var taskTitle by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf("Low") }
    var showPriorityMenu by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var dueDate by remember { mutableStateOf<Long?>(null) }
    val tasks by viewModel.tasks.collectAsState()
    val currentFilter by viewModel.filter.collectAsState()
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            // Можно показать сообщение, что уведомления не будут работать
        }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    var draggedTaskIndex by remember { mutableStateOf<Int?>(null) }
    var tasksList by remember { mutableStateOf(tasks) }
    val scope = rememberCoroutineScope()
    val overscrollJob = remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(tasks) {
        tasksList = tasks
    }

    val calendar = Calendar.getInstance()
    val datePickerDialog = DatePickerDialog(
        context,
        { _, year, month, dayOfMonth ->
            calendar.set(year, month, dayOfMonth)
            TimePickerDialog(
                context,
                { _, hour, minute ->
                    calendar.set(Calendar.HOUR_OF_DAY, hour)
                    calendar.set(Calendar.MINUTE, minute)
                    dueDate = calendar.timeInMillis
                },
                calendar.get(Calendar.HOUR_OF_DAY),
                calendar.get(Calendar.MINUTE),
                true
            ).show()
        },
        calendar.get(Calendar.YEAR),
        calendar.get(Calendar.MONTH),
        calendar.get(Calendar.DAY_OF_MONTH)
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = {
                searchQuery = it
                viewModel.setSearchQuery(it)
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Поиск задач") },
            singleLine = true,
            leadingIcon = {
                Icon(Icons.Default.Search, contentDescription = null)
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = {
                        searchQuery = ""
                        viewModel.setSearchQuery("")
                    }) {
                        Icon(Icons.Default.Clear, contentDescription = "Очистить")
                    }
                }
            },
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = taskTitle,
                onValueChange = { taskTitle = it },
                modifier = Modifier.weight(1f),
                label = { Text("Новая задача") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
            Spacer(modifier = Modifier.width(12.dp))
            Box {
                TextButton(onClick = { showPriorityMenu = true }) {
                    Text(priority, color = MaterialTheme.colorScheme.primary)
                }
                DropdownMenu(
                    expanded = showPriorityMenu,
                    onDismissRequest = { showPriorityMenu = false },
                    modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                ) {
                    listOf("Low", "Medium", "High").forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option, style = MaterialTheme.typography.bodyMedium) },
                            onClick = {
                                priority = option
                                showPriorityMenu = false
                            }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Button(
                onClick = {
                    if (taskTitle.isNotBlank()) {
                        val notificationId = if (dueDate != null) {
                            scheduleReminder(context, taskTitle, dueDate)
                        } else null
                        viewModel.addTask(taskTitle, priority, dueDate, notificationId)
                        taskTitle = ""
                        priority = "Low"
                        dueDate = null
                    }
                },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text("Добавить")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { datePickerDialog.show() }) {
                Text(
                    text = dueDate?.let {
                        SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(it))
                    } ?: "Выбрать дату",
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (dueDate != null) {
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(onClick = { dueDate = null }) {
                    Icon(Icons.Default.Clear, contentDescription = "Очистить дату", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            FilterButton("Все", currentFilter == TaskViewModel.TaskFilter.ALL) {
                viewModel.setFilter(TaskViewModel.TaskFilter.ALL)
            }
            FilterButton("Активные", currentFilter == TaskViewModel.TaskFilter.ACTIVE) {
                viewModel.setFilter(TaskViewModel.TaskFilter.ACTIVE)
            }
            FilterButton("Выполненные", currentFilter == TaskViewModel.TaskFilter.COMPLETED) {
                viewModel.setFilter(TaskViewModel.TaskFilter.COMPLETED)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (tasksList.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (searchQuery.isEmpty()) {
                            when (currentFilter) {
                                TaskViewModel.TaskFilter.ALL -> "Нет задач. Добавьте новую!"
                                TaskViewModel.TaskFilter.ACTIVE -> "Нет активных задач."
                                TaskViewModel.TaskFilter.COMPLETED -> "Нет выполненных задач."
                            }
                        } else {
                            "Нет задач, соответствующих запросу."
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
        } else {
            val lazyListState = rememberLazyListState()
            LazyColumn(
                state = lazyListState,
                modifier = Modifier
                    .pointerInput(Unit) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { offset ->
                                val index = lazyListState.layoutInfo.visibleItemsInfo
                                    .find { it.offset <= offset.y && it.offset + it.size >= offset.y }
                                    ?.index
                                draggedTaskIndex = index
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                draggedTaskIndex?.let { fromIndex ->
                                    val visibleItems = lazyListState.layoutInfo.visibleItemsInfo
                                    val fromItem = visibleItems.find { it.index == fromIndex }
                                    if (fromItem != null) {
                                        val dragY = fromItem.offset + dragAmount.y
                                        val toItem = visibleItems.find {
                                            it.index != fromIndex &&
                                                    dragY >= it.offset &&
                                                    dragY <= it.offset + it.size
                                        }
                                        toItem?.let {
                                            val toIndex = it.index
                                            tasksList = tasksList.toMutableList().apply {
                                                add(toIndex, removeAt(fromIndex))
                                            }
                                            draggedTaskIndex = toIndex
                                        }
                                    }

                                    val overscroll = when {
                                        dragAmount.y > 0 && lazyListState.canScrollForward -> dragAmount.y
                                        dragAmount.y < 0 && lazyListState.canScrollBackward -> dragAmount.y
                                        else -> 0f
                                    }
                                    if (overscroll != 0f) {
                                        overscrollJob.value?.cancel()
                                        overscrollJob.value = scope.launch {
                                            lazyListState.scrollBy(overscroll)
                                        }
                                    }
                                }
                            },
                            onDragEnd = {
                                draggedTaskIndex?.let {
                                    viewModel.reorderTasks(tasksList)
                                }
                                draggedTaskIndex = null
                                overscrollJob.value?.cancel()
                            },
                            onDragCancel = {
                                draggedTaskIndex = null
                                overscrollJob.value?.cancel()
                            }
                        )
                    }
            ) {
                itemsIndexed(tasksList, key = { _, task -> task.id }) { index, task ->
                    TaskItem(
                        task = task,
                        onToggle = { viewModel.toggleTaskCompletion(task, context) },
                        onDelete = { viewModel.deleteTask(task, context) },
                        onClick = { onEditTask(task) },
                        modifier = Modifier
                            .animateItemPlacement()
                            .then(
                                if (draggedTaskIndex == index) {
                                    Modifier
                                        .shadow(8.dp, RoundedCornerShape(16.dp))
                                        .zIndex(1f)
                                        .offset(y = 2.dp)
                                } else {
                                    Modifier
                                }
                            )
                    )
                }
            }
        }
    }
}

@Composable
fun FilterButton(text: String, isSelected: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(
            contentColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
fun TaskItem(task: Task, onToggle: () -> Unit, onDelete: () -> Unit, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple()
            ) { onClick() }
            .animateContentSize(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = task.isCompleted,
                onCheckedChange = { onToggle() },
                colors = CheckboxDefaults.colors(
                    checkedColor = MaterialTheme.colorScheme.primary,
                    uncheckedColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (task.isCompleted) FontWeight.Normal else FontWeight.Medium
                )
                Text(
                    text = "Приоритет: ${task.priority}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = when (task.priority) {
                        "High" -> Color(0xFFE57373)
                        "Medium" -> Color(0xFFFFB300)
                        "Low" -> Color(0xFF4CAF50)
                        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    }
                )
                task.dueDate?.let {
                    Text(
                        text = "Напоминание: ${SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(it))}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Удалить",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
fun EditTaskScreen(viewModel: TaskViewModel, taskId: Int, onBack: () -> Unit) {
    val tasks by viewModel.tasks.collectAsState()
    val task = tasks.find { it.id == taskId }
    var taskTitle by remember { mutableStateOf(task?.title ?: "") }
    var priority by remember { mutableStateOf(task?.priority ?: "Low") }
    var dueDate by remember { mutableStateOf(task?.dueDate) }
    var showPriorityMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val calendar = Calendar.getInstance()
    dueDate?.let { calendar.timeInMillis = it }
    val datePickerDialog = DatePickerDialog(
        context,
        { _, year, month, dayOfMonth ->
            calendar.set(year, month, dayOfMonth)
            TimePickerDialog(
                context,
                { _, hour, minute ->
                    calendar.set(Calendar.HOUR_OF_DAY, hour)
                    calendar.set(Calendar.MINUTE, minute)
                    dueDate = calendar.timeInMillis
                },
                calendar.get(Calendar.HOUR_OF_DAY),
                calendar.get(Calendar.MINUTE),
                true
            ).show()
        },
        calendar.get(Calendar.YEAR),
        calendar.get(Calendar.MONTH),
        calendar.get(Calendar.DAY_OF_MONTH)
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Редактировать задачу",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Medium
        )

        OutlinedTextField(
            value = taskTitle,
            onValueChange = { taskTitle = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Название задачи") },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )

        Box {
            TextButton(onClick = { showPriorityMenu = true }) {
                Text(
                    "Приоритет: $priority",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            DropdownMenu(
                expanded = showPriorityMenu,
                onDismissRequest = { showPriorityMenu = false },
                modifier = Modifier.background(MaterialTheme.colorScheme.surface)
            ) {
                listOf("Low", "Medium", "High").forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option, style = MaterialTheme.typography.bodyMedium) },
                        onClick = {
                            priority = option
                            showPriorityMenu = false
                        }
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { datePickerDialog.show() }) {
                Text(
                    text = dueDate?.let {
                        SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(it))
                    } ?: "Выбрать дату",
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (dueDate != null) {
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(onClick = { dueDate = null }) {
                    Icon(Icons.Default.Clear, contentDescription = "Очистить дату", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Button(
                onClick = {
                    if (task != null && taskTitle.isNotBlank()) {
                        val notificationId = if (dueDate != null) {
                            scheduleReminder(context, taskTitle, dueDate)
                        } else {
                            task.notificationId?.let {
                                WorkManager.getInstance(context).cancelUniqueWork(it)
                            }
                            null
                        }
                        viewModel.updateTask(
                            task.copy(
                                title = taskTitle,
                                priority = priority,
                                dueDate = dueDate,
                                notificationId = notificationId
                            )
                        )
                        onBack()
                    }
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text("Сохранить")
            }
            OutlinedButton(
                onClick = { onBack() },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                border = ButtonDefaults.outlinedButtonBorder.copy(
                    brush = SolidColor(MaterialTheme.colorScheme.primary)
                )
            ) {
                Text("Отмена", color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

private fun scheduleReminder(context: Context, taskTitle: String, dueDate: Long?): String? {
    if (dueDate == null) return null

    val currentTime = System.currentTimeMillis()
    if (dueDate <= currentTime) return null

    val notificationId = UUID.randomUUID().toString()
    val delay = dueDate - currentTime
    val workData = Data.Builder()
        .putString("taskId", notificationId)
        .putString("taskTitle", taskTitle)
        .build()

    val workRequest = OneTimeWorkRequestBuilder<ReminderWorker>()
        .setInitialDelay(delay, TimeUnit.MILLISECONDS)
        .setInputData(workData)
        .build()

    WorkManager.getInstance(context)
        .enqueueUniqueWork(notificationId, androidx.work.ExistingWorkPolicy.REPLACE, workRequest)

    return notificationId
}