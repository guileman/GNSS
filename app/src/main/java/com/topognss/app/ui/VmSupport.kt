package com.topognss.app.ui

import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.topognss.app.TopoApp
import com.topognss.app.data.PointEntity
import com.topognss.app.data.ProjectEntity
import com.topognss.app.data.ReportBlockEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

val AndroidViewModel.topo: TopoApp get() = getApplication()

fun <T> AndroidViewModel.hold(flow: Flow<T>, initial: T): StateFlow<T> =
    flow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), initial)

@OptIn(ExperimentalCoroutinesApi::class)
fun AndroidViewModel.activeProjectFlow(): Flow<ProjectEntity?> =
    topo.prefs.activeProjectId.flatMapLatest { id -> if (id > 0) topo.repository.observeProject(id) else flowOf(null) }

@OptIn(ExperimentalCoroutinesApi::class)
fun AndroidViewModel.activePointsFlow(): Flow<List<PointEntity>> =
    topo.prefs.activeProjectId.flatMapLatest { id -> if (id > 0) topo.repository.observePoints(id) else flowOf(emptyList()) }

@OptIn(ExperimentalCoroutinesApi::class)
fun AndroidViewModel.activeBlocksFlow(): Flow<List<ReportBlockEntity>> =
    topo.prefs.activeProjectId.flatMapLatest { id -> if (id > 0) topo.repository.observeBlocks(id) else flowOf(emptyList()) }
