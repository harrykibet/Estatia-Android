package com.estatia.realestate.apps.feature.property.utils

import android.net.Uri
import androidx.core.net.toUri
import com.estatia.realestate.apps.core.architecture.annotations.Data.UiState

@UiState
data class AddPropertyUiState(

    val countyNames: List<String> = emptyList(),

    val propertyTypes: List<String> = emptyList(),

    val isUploading: Boolean = false,

    val uploadError: String? = null,

    /** The editable property draft is owned by this single UI-state snapshot. */
    val draft: AddPropertyDraft = AddPropertyDraft()
) {
    /** Media URIs are derived from the draft and are not a separate state authority. */
    val allMedia: List<Uri>
        get() = (draft.images + draft.videos).map { it.value.toUri() }
}
