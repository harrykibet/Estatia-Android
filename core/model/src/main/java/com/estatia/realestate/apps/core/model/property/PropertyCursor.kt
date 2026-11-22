package com.estatia.realestate.apps.core.model.property
import com.estatia.realestate.apps.core.architecture.annotations.Data.DomainModel


@DomainModel
data class PropertyCursor(
    /**
     * Epoch milliseconds exposed to the domain and presentation layers.
     */
    val createdAt: Long,
    val documentId: String,
    /**
     * Optional exact timestamp components retained by Firestore pagination.
     *
     * A Firestore Timestamp can have sub-millisecond precision. Keeping these
     * components prevents startAfter() from rounding the page boundary when
     * the public createdAt value is represented in milliseconds.
     *
     * Both values remain null for backends that use numeric epoch milliseconds.
     */
    val createdAtSeconds: Long? = null,
    val createdAtNanoseconds: Int? = null
)


@DomainModel
data class PropertyPage(
    val properties: List<PropertyDomainModel>,
    val cursor: PropertyCursor?
)
