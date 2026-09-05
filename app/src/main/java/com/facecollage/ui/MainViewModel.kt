package com.facecollage.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.facecollage.domain.model.ProcessingState
import com.facecollage.domain.usecase.ProcessVideoUseCase
import com.facecollage.utils.ImageSaver
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val processVideoUseCase: ProcessVideoUseCase,
    private val imageSaver: ImageSaver,
) : ViewModel() {

    private val _state           = MutableStateFlow<ProcessingState>(ProcessingState.Idle)
    val state: StateFlow<ProcessingState> = _state.asStateFlow()

    private val _selectedVideoUri = MutableStateFlow<Uri?>(null)
    val selectedVideoUri: StateFlow<Uri?> = _selectedVideoUri.asStateFlow()

    private val _videoName        = MutableStateFlow<String?>(null)
    val videoName: StateFlow<String?> = _videoName.asStateFlow()

    private var processingJob: Job? = null

    fun onVideoSelected(uri: Uri, name: String?) {
        _selectedVideoUri.value = uri
        _videoName.value        = name ?: uri.lastPathSegment ?: "video"
        _state.value            = ProcessingState.Idle
    }

    fun startProcessing() {
        val uri = _selectedVideoUri.value ?: return
        processingJob?.cancel()
        processingJob = viewModelScope.launch {
            processVideoUseCase.execute(uri)
                .catch { e -> _state.value = ProcessingState.Error(e.message ?: "Unknown error") }
                .collect { state -> _state.value = state }
        }
    }

    fun cancelProcessing() {
        processingJob?.cancel()
        _state.value = ProcessingState.Idle
    }

    fun shareCollage(path: String) {
        val uri    = imageSaver.getShareUri(path)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type  = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, "Share Collage")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    fun resetState() {
        processingJob?.cancel()
        _state.value            = ProcessingState.Idle
        _selectedVideoUri.value = null
        _videoName.value        = null
    }
}