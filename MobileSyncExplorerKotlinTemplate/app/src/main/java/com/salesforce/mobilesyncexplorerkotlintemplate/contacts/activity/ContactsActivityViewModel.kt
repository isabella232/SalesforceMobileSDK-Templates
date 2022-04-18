/*
 * Copyright (c) 2022-present, salesforce.com, inc.
 * All rights reserved.
 * Redistribution and use of this software in source and binary forms, with or
 * without modification, are permitted provided that the following conditions
 * are met:
 * - Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 * - Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 * - Neither the name of salesforce.com, inc. nor the names of its contributors
 * may be used to endorse or promote products derived from this software without
 * specific prior written permission of salesforce.com, inc.
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package com.salesforce.mobilesyncexplorerkotlintemplate.contacts.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salesforce.mobilesyncexplorerkotlintemplate.contacts.detailscomponent.*
import com.salesforce.mobilesyncexplorerkotlintemplate.contacts.listcomponent.ContactsListDataActionClickHandler
import com.salesforce.mobilesyncexplorerkotlintemplate.contacts.listcomponent.ContactsListUiClickHandler
import com.salesforce.mobilesyncexplorerkotlintemplate.contacts.listcomponent.ContactsListUiState
import com.salesforce.mobilesyncexplorerkotlintemplate.core.extensions.requireIsLocked
import com.salesforce.mobilesyncexplorerkotlintemplate.core.extensions.withLockDebug
import com.salesforce.mobilesyncexplorerkotlintemplate.core.repos.RepoOperationException
import com.salesforce.mobilesyncexplorerkotlintemplate.core.repos.usecases.*
import com.salesforce.mobilesyncexplorerkotlintemplate.core.salesforceobject.SObjectRecord
import com.salesforce.mobilesyncexplorerkotlintemplate.core.salesforceobject.isLocallyDeleted
import com.salesforce.mobilesyncexplorerkotlintemplate.core.ui.state.*
import com.salesforce.mobilesyncexplorerkotlintemplate.model.contacts.ContactObject
import com.salesforce.mobilesyncexplorerkotlintemplate.model.contacts.ContactRecord
import com.salesforce.mobilesyncexplorerkotlintemplate.model.contacts.ContactValidationException
import com.salesforce.mobilesyncexplorerkotlintemplate.model.contacts.ContactsRepo
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex

interface ContactsActivityViewModel {
    val activityUiState: StateFlow<ContactsActivityUiState>
    val detailsUiState: StateFlow<ContactDetailsUiState>
    val listUiState: StateFlow<ContactsListUiState>

    fun sync(syncDownOnly: Boolean = false)
}

class DefaultContactsActivityViewModel(
    private val contactsRepo: ContactsRepo
) : ViewModel(), ContactsActivityViewModel {

    private val detailsVm by lazy { DefaultContactDetailsViewModel() }
    private val listVm by lazy { DefaultContactsListViewModel() }
    override val detailsUiState: StateFlow<ContactDetailsUiState> get() = detailsVm.uiState
    override val listUiState: StateFlow<ContactsListUiState> get() = listVm.uiState

    private lateinit var detailsVmInt: InnerDetailsViewModel
    private lateinit var listVmInt: InnerListViewModel

    private val stateMutex = Mutex()
    private val mutUiState = MutableStateFlow(
        ContactsActivityUiState(isSyncing = false, dataOpIsActive = false, dialogUiState = null)
    )

    override val activityUiState: StateFlow<ContactsActivityUiState> get() = mutUiState

    @Volatile
    private var curRecordsByIds: Map<String, ContactRecord> = emptyMap()

    init {
        viewModelScope.launch {
            contactsRepo.recordsById.collect { records ->
                stateMutex.withLockDebug {
                    curRecordsByIds = records
                    detailsVmInt.onRecordsEmitted(records)
                    listVmInt.onRecordsEmitted(records)
                }
            }
        }
    }

    override fun sync(syncDownOnly: Boolean) {
        viewModelScope.launch {
            stateMutex.withLockDebug {
                mutUiState.value = activityUiState.value.copy(isSyncing = true)
            }
            if (syncDownOnly) {
                contactsRepo.syncDownOnly()
            } else {
                contactsRepo.syncUpAndDown()
            }
            stateMutex.withLockDebug {
                mutUiState.value = activityUiState.value.copy(isSyncing = false)
            }
        }
    }

    /* If you look closely, you will see that this class' click handlers are running suspending setter
     * methods _within_ the state locks. This should be alarming because this can lead to unresponsive
     * UI, but in this case it is necessary.
     *
     * The user expects atomic operations when they perform a UI action that would change the state
     * of multiple coupled components. If we allow more than one action to contend for the components'
     * setters, it opens up the possibility of inconsistent state, which is deemed here to be worse
     * than "dropping clicks."
     *
     * Note that the UI _thread_ is not locked up during this. All suspending APIs are main-safe, so
     * the UI thread can continue to render and there will be no ANRs. The fact of the matter is:
     * if a component is locked up and cannot finish the set operation, the user must wait until
     * that component cooperates with the setter until they are allowed to take another action.
     *
     * In almost all cases, this will not even be an issue. */
    private val listClickDelegate = ListClickDelegate()

    private inner class ListClickDelegate : ContactsListUiClickHandler {
        override fun contactClick(contactId: String) {
            doSetContact(contactId = contactId, editing = false)
        }

        override fun editClick(contactId: String) {
            doSetContact(contactId = contactId, editing = true)
        }

        override fun createClick() = launchWithStateLock {
            // TODO check for data operations
            if (!detailsVmInt.hasUnsavedChanges) {
                detailsVmInt.clobberRecord(record = null, editing = true)
                listVmInt.setSelectedContact(null)
            } else {
                val discardChangesDialog = DiscardChangesDialogUiState(
                    onDiscardChanges = {
                        launchWithStateLock {
                            detailsVmInt.clobberRecord(record = null, editing = true)
                            dismissCurDialog()
                        }
                    },
                    onKeepChanges = { launchWithStateLock { dismissCurDialog() } }
                )

                mutUiState.value = activityUiState.value.copy(dialogUiState = discardChangesDialog)
            }
        }

        private fun doSetContact(contactId: String, editing: Boolean) = launchWithStateLock {
            // TODO check for data operations
            val record = curRecordsByIds[contactId] ?: return@launchWithStateLock
            if (!detailsVmInt.hasUnsavedChanges) {
                detailsVmInt.clobberRecord(record = record, editing = editing)
                listVmInt.setSelectedContact(id = contactId)
            } else {
                val discardChangesDialog = DiscardChangesDialogUiState(
                    onDiscardChanges = {
                        launchWithStateLock inner@{
                            val futureRecord = curRecordsByIds[contactId] ?: return@inner
                            detailsVmInt.clobberRecord(record = futureRecord, editing = editing)
                            dismissCurDialog()
                        }
                    },
                    onKeepChanges = { launchWithStateLock { dismissCurDialog() } }
                )

                mutUiState.value = activityUiState.value.copy(dialogUiState = discardChangesDialog)
            }
        }
    }

    private fun launchUpdateOp(idToUpdate: String, so: ContactObject) =
        launchWithinDataOperationActiveState {
            try {
                val updatedRecord = contactsRepo.locallyUpdate(id = idToUpdate, so = so)
                stateMutex.withLockDebug {
                    if (detailsVmInt.curRecordId == idToUpdate) {
                        detailsVmInt.clobberRecord(record = updatedRecord, editing = false)
                    }
                }
            } catch (ex: RepoOperationException) {
                throw ex
            }
        }

    private fun launchCreateOp(so: ContactObject) =
        launchWithinDataOperationActiveState {
            try {
                val newRecord = contactsRepo.locallyCreate(so = so)
                stateMutex.withLockDebug {
                    if (detailsVmInt.curRecordId == null && detailsVmInt.uiState.value !is ContactDetailsUiState.NoContactSelected) {
                        detailsVmInt.clobberRecord(record = newRecord, editing = false)
                    }
                }
            } catch (ex: RepoOperationException) {
                throw ex
            }
        }

    private fun launchDeleteOpWithConfirmation(idToDelete: String) = launchWithStateLock {
        suspend fun doDelete() {
            try {
                val updatedRecord = contactsRepo.locallyDelete(id = idToDelete)
                stateMutex.withLockDebug {
                    if (detailsVmInt.curRecordId == idToDelete) {
                        detailsVmInt.clobberRecord(record = updatedRecord, editing = false)
                    }
                }
            } catch (ex: RepoOperationException) {
                throw ex
            }
        }

        val deleteDialog = DeleteConfirmationDialogUiState(
            objIdToDelete = idToDelete,
            objName = when (val detailsState = detailsVmInt.uiState.value) {
                is ContactDetailsUiState.NoContactSelected -> null
                is ContactDetailsUiState.ViewingContactDetails -> detailsState.fullName
            },
            onCancelDelete = { launchWithStateLock { dismissCurDialog() } },
            onDeleteConfirm = { launchWithinDataOperationActiveState { doDelete() } }
        )

        mutUiState.value = activityUiState.value.copy(dialogUiState = deleteDialog)
    }

    private fun launchUndeleteOpWithConfirmation(idToUndelete: String) = viewModelScope.launch {
        suspend fun doUndelete() {
            try {
                val updatedRecord = contactsRepo.locallyUndelete(id = idToUndelete)
                stateMutex.withLockDebug {
                    if (detailsVmInt.curRecordId == idToUndelete) {
                        detailsVmInt.clobberRecord(record = updatedRecord, editing = false)
                    }
                }
            } catch (ex: RepoOperationException) {
                throw ex
            }
        }

        val undeleteDialog = UndeleteConfirmationDialogUiState(
            objIdToUndelete = idToUndelete,
            objName = when (val detailsState = detailsVmInt.uiState.value) {
                is ContactDetailsUiState.NoContactSelected -> null
                is ContactDetailsUiState.ViewingContactDetails -> detailsState.fullName
            },
            onCancelUndelete = { launchWithStateLock { dismissCurDialog() } },
            onUndeleteConfirm = { launchWithinDataOperationActiveState { doUndelete() } }
        )

        mutUiState.value = activityUiState.value.copy(dialogUiState = undeleteDialog)
    }

    private fun launchWithinDataOperationActiveState(block: suspend () -> Unit) =
        viewModelScope.launch {
            stateMutex.withLockDebug {
                if (activityUiState.value.dataOpIsActive) {
                    // TODO toast to user that the data operation couldn't be done b/c there is another already active
                    return@launch
                } else {
                    mutUiState.value = activityUiState.value.copy(dataOpIsActive = true)
                }
            }

            try {
                block()
            } finally {
                withContext(NonCancellable) {
                    stateMutex.withLockDebug {
                        mutUiState.value = activityUiState.value.copy(dataOpIsActive = false)
                    }
                }
            }
        }


    // region Utilities


    /**
     * Convenience method to wrap a method body in a coroutine which acquires the event mutex lock
     * before executing the [block]. Because this launches a new coroutine, it is okay to nest
     * invocations of this method within other [launchWithStateLock] blocks without worrying about
     * deadlocks.
     *
     * Note! If you nest [launchWithStateLock] calls, the outer [launchWithStateLock] [block]
     * will run to completion _before_ the nested [block] is invoked since the outer [block] already
     * has the event lock.
     */
    private fun launchWithStateLock(block: suspend CoroutineScope.() -> Unit) {
        viewModelScope.launch { stateMutex.withLockDebug { block() } }
    }

    private fun dismissCurDialog() {
        stateMutex.requireIsLocked()
        mutUiState.value = mutUiState.value.copy(dialogUiState = null)
    }

    private companion object {
        private const val TAG = "DefaultContactsActivityViewModel"
    }


    // endregion


    /* WIP steps to migrate VMs to inner classes:
     *
     * Dialogs need to go through activity vm
     * List data action delegate back to activity vm
     * Delegate list click events to activity vm
     * Everything having the same state mutex is okay?
     * Activity VM data action lock or data action locks per component? (see note in ContactActivityUiState)
     * Does each VM need its own contacts collector?
     *     Probably not, but delegating updating UI state on new emission is necessary I think
     */

    private interface InnerDetailsViewModel {
        val uiState: StateFlow<ContactDetailsUiState>
        val curRecordId: String?
        val hasUnsavedChanges: Boolean

        suspend fun clobberRecord(record: ContactRecord?, editing: Boolean)
        suspend fun onRecordsEmitted(records: Map<String, ContactRecord>)
    }

    private inner class DefaultContactDetailsViewModel
        : ContactDetailsFieldChangeHandler,
        ContactDetailsUiEventHandler {

        private val mutDetailsUiState: MutableStateFlow<ContactDetailsUiState> = MutableStateFlow(
            ContactDetailsUiState.NoContactSelected(
                dataOperationIsActive = false,
                doingInitialLoad = true,
//                curDialogUiState = null
            )
        )

        val uiState: StateFlow<ContactDetailsUiState> get() = mutDetailsUiState

        @Volatile
        private var curRecordId: String? = null

        @Volatile
        private lateinit var upstreamRecords: Map<String, SObjectRecord<ContactObject>>

        init {
            viewModelScope.launch(Dispatchers.Default) {
                contactsRepo.recordsById.collect { onNewRecords(it) }
            }
        }

        private suspend fun onNewRecords(
            newRecords: Map<String, SObjectRecord<ContactObject>>
        ) = stateMutex.withLockDebug {
            if (uiState.value.doingInitialLoad) {
                mutDetailsUiState.value = uiState.value.copy(doingInitialLoad = false)
            }

            upstreamRecords = newRecords

            val curId = curRecordId ?: return@withLockDebug
            val matchingRecord = newRecords[curId]

            // TODO This whole onNewRecords is buggy. I think a refactor is necessary, maybe having an internal state which includes the corresponding UI state so that the [curRecordId] doesn't get out of sync with the ui state?
            if (matchingRecord == null) {
                mutDetailsUiState.value = ContactDetailsUiState.NoContactSelected(
                    dataOperationIsActive = dataOpDelegate.dataOperationIsActive,
//                    curDialogUiState = uiState.value.curDialogUiState
                )
                return@withLockDebug
            }

            mutDetailsUiState.value = when (val curState = uiState.value) {
                is ContactDetailsUiState.NoContactSelected -> {
                    matchingRecord.sObject.buildViewingContactUiState(
                        uiSyncState = matchingRecord.localStatus.toUiSyncState(),
                        isEditingEnabled = false,
                        shouldScrollToErrorField = false,
                    )
                }

                is ContactDetailsUiState.ViewingContactDetails -> {
                    when {
                        // not editing, so simply update all fields to match upstream emission:
                        !curState.isEditingEnabled -> curState.copy(
                            firstNameField = matchingRecord.sObject.buildFirstNameField(),
                            lastNameField = matchingRecord.sObject.buildLastNameField(),
                            titleField = matchingRecord.sObject.buildTitleField(),
                            departmentField = matchingRecord.sObject.buildDepartmentField(),
                        )

                        /* TODO figure out how to reconcile when upstream was locally deleted but the user has
                            unsaved changes. Also applies to if upstream is permanently deleted. Reminder,
                            showing dialogs from data events is not allowed.
                            Idea: create a "snapshot" of the SO as soon as they begin editing, and only
                            prompt for choice upon clicking save. */
                        matchingRecord.localStatus.isLocallyDeleted && hasUnsavedChanges -> TODO()

                        // user is editing and there is no incompatible state, so no changes to state:
                        else -> curState
                    }
                }
            }
        }

        @Throws(ContactDetailsException::class)
        override suspend fun setContactOrThrow(recordId: String?, isEditing: Boolean) {
            setContactOrThrow(
                recordId = recordId,
                isEditing = isEditing,
                forceDiscardChanges = false
            )
        }

        @Throws(DataOperationActiveException::class)
        override suspend fun discardChangesAndSetContactOrThrow(
            recordId: String?,
            isEditing: Boolean
        ) {
            setContactOrThrow(
                recordId = recordId,
                isEditing = isEditing,
                forceDiscardChanges = true
            )
        }

        /**
         * This must not use launch if the thrown exceptions are to be correctly caught by the caller.
         */
        @Throws(ContactDetailsException::class)
        private suspend fun setContactOrThrow(
            recordId: String?,
            isEditing: Boolean,
            forceDiscardChanges: Boolean
        ) = stateMutex.withLockDebug {

            if (!this@DefaultContactDetailsViewModel::upstreamRecords.isInitialized || dataOpDelegate.dataOperationIsActive) {
                throw DataOperationActiveException(
                    message = "Cannot change details content while there are data operations active."
                )
            }

            if (!forceDiscardChanges && hasUnsavedChanges) {
                throw HasUnsavedChangesException()
            }

            curRecordId = recordId

            if (recordId == null) {
                if (isEditing) {
                    setStateForCreateNew()
                } else {
                    mutDetailsUiState.value = ContactDetailsUiState.NoContactSelected(
                        dataOperationIsActive = dataOpDelegate.dataOperationIsActive,
//                        curDialogUiState = uiState.value.curDialogUiState
                    )
                }
            } else {
                val newRecord = upstreamRecords[recordId]
                    ?: TODO("Did not find record with ID $recordId")

                mutDetailsUiState.value = when (val curState = uiState.value) {
                    is ContactDetailsUiState.NoContactSelected -> newRecord.sObject.buildViewingContactUiState(
                        uiSyncState = newRecord.localStatus.toUiSyncState(),
                        isEditingEnabled = isEditing,
                        shouldScrollToErrorField = false,
                    )
                    is ContactDetailsUiState.ViewingContactDetails -> curState.copy(
                        firstNameField = newRecord.sObject.buildFirstNameField(),
                        lastNameField = newRecord.sObject.buildLastNameField(),
                        titleField = newRecord.sObject.buildTitleField(),
                        departmentField = newRecord.sObject.buildDepartmentField(),
                        isEditingEnabled = isEditing,
                        shouldScrollToErrorField = false
                    )
                }
            }
        }

        val hasUnsavedChanges: Boolean
            get() {
                stateMutex.requireIsLocked()
                return when (val curState = uiState.value) {
                    is ContactDetailsUiState.ViewingContactDetails -> curRecordId?.let {
                        try {
                            curState.toSObjectOrThrow() != upstreamRecords[it]?.sObject
                        } catch (ex: ContactValidationException) {
                            // invalid field values means there are unsaved changes
                            true
                        }
                    } ?: true // creating new contact, so assume changes are present
                    is ContactDetailsUiState.NoContactSelected -> false
                }
            }

        override fun createClick() = launchWithStateLock {
            if (hasUnsavedChanges) {
                mutDetailsUiState.value = uiState.value.copy(
                    TODO()
//                    curDialogUiState = DiscardChangesDialogUiState(
//
//                        onDiscardChanges = {
//                            launchWithStateLock {
//                                setStateForCreateNew()
//                                dismissCurDialog()
//                            }
//                        },
//
//                        onKeepChanges = {
//                            launchWithStateLock {
//                                dismissCurDialog()
//                            }
//                        }
//                    )
                )
                return@launchWithStateLock
            }

            setStateForCreateNew()
        }

        private fun setStateForCreateNew() {
            stateMutex.requireIsLocked()

            curRecordId = null

            mutDetailsUiState.value = ContactDetailsUiState.ViewingContactDetails(
                firstNameField = ContactDetailsField.FirstName(
                    fieldValue = null,
                    onValueChange = ::onFirstNameChange
                ),
                lastNameField = ContactDetailsField.LastName(
                    fieldValue = null,
                    onValueChange = ::onLastNameChange
                ),
                titleField = ContactDetailsField.Title(
                    fieldValue = null,
                    onValueChange = ::onTitleChange
                ),
                departmentField = ContactDetailsField.Department(
                    fieldValue = null,
                    onValueChange = ::onDepartmentChange
                ),

                uiSyncState = SObjectUiSyncState.NotSaved,

                isEditingEnabled = true,
                dataOperationIsActive = dataOpDelegate.dataOperationIsActive,
                shouldScrollToErrorField = false,
//                curDialogUiState = uiState.value.curDialogUiState
            )
        }

        override fun deleteClick() = launchWithStateLock {
            val targetRecordId = curRecordId ?: return@launchWithStateLock // no id => nothing to do
            launchDeleteOpWithConfirmation(idToDelete = targetRecordId)
        }

        override fun undeleteClick() = launchWithStateLock {
            val targetRecordId = curRecordId ?: return@launchWithStateLock // no id => nothing to do
            launchUndeleteOpWithConfirmation(idToUndelete = targetRecordId)
        }

        override fun editClick() = launchWithStateLock {
            mutDetailsUiState.value = when (val curState = uiState.value) {
                is ContactDetailsUiState.NoContactSelected -> curState
                is ContactDetailsUiState.ViewingContactDetails -> curState.copy(isEditingEnabled = true)
            }
        }

        override fun deselectContact() = launchWithStateLock {
            val viewingDetailsState = uiState.value as? ContactDetailsUiState.ViewingContactDetails
                ?: return@launchWithStateLock // already have no contact

            if (!hasUnsavedChanges) {
                mutDetailsUiState.value = ContactDetailsUiState.NoContactSelected(
                    dataOperationIsActive = dataOpDelegate.dataOperationIsActive,
//                    curDialogUiState = viewingDetailsState.curDialogUiState
                )
                return@launchWithStateLock
            }

            mutDetailsUiState.value = viewingDetailsState.copy(
                TODO()
//                curDialogUiState = DiscardChangesDialogUiState(
//                    onDiscardChanges = {
//                        launchWithStateLock {
//                            mutUiState.value = ContactDetailsUiState.NoContactSelected(
//                                dataOperationIsActive = dataOpDelegate.dataOperationIsActive,
//                                curDialogUiState = null
//                            )
//                        }
//                    },
//                    onKeepChanges = {
//                        launchWithStateLock {
//                            dismissCurDialog()
//                        }
//                    }
//                )
            )
        }

        override fun exitEditClick() = launchWithStateLock {
            val viewingContactDetails =
                uiState.value as? ContactDetailsUiState.ViewingContactDetails
                    ?: return@launchWithStateLock // not editing, so nothing to do

            if (!hasUnsavedChanges) {
                mutDetailsUiState.value = viewingContactDetails.copy(isEditingEnabled = false)
                return@launchWithStateLock
            }

            TODO()
//            val discardChangesDialog = DiscardChangesDialogUiState(
//                onDiscardChanges = {
//                    launchWithStateLock {
//                        val record = curRecordId?.let { upstreamRecords[it] }
//                        mutUiState.value = record
//                            ?.sObject
//                            ?.buildViewingContactUiState(
//                                uiSyncState = record.localStatus.toUiSyncState(),
//                                isEditingEnabled = false,
//                                shouldScrollToErrorField = false
//                            )
//                            ?: ContactDetailsUiState.NoContactSelected(
//                                dataOperationIsActive = uiState.value.dataOperationIsActive,
//                                curDialogUiState = null
//                            )
//                        dismissCurDialog()
//                    }
//                },
//
//                onKeepChanges = {
//                    launchWithStateLock {
//                        dismissCurDialog()
//                    }
//                }
//            )

            mutDetailsUiState.value = viewingContactDetails.copy(
                TODO()
//                curDialogUiState = discardChangesDialog
            )
        }

        override fun saveClick() = launchWithStateLock {
            // If not viewing details, we cannot build the SObject, so there is nothing to do
            val curState = uiState.value as? ContactDetailsUiState.ViewingContactDetails
                ?: return@launchWithStateLock

            val so = try {
                curState.toSObjectOrThrow()
            } catch (ex: Exception) {
                mutDetailsUiState.value = curState.copy(shouldScrollToErrorField = true)
                return@launchWithStateLock
            }

            curRecordId.also {
                if (it == null) {
                    launchCreateOp(so = so)
                } else {
                    launchUpdateOp(idToUpdate = it, so = so)
                }
            }
        }

        override fun onFirstNameChange(newFirstName: String) = launchWithStateLock {
            val curState = uiState.value as? ContactDetailsUiState.ViewingContactDetails
                ?: return@launchWithStateLock

            mutDetailsUiState.value = curState.copy(
                firstNameField = curState.firstNameField.copy(fieldValue = newFirstName)
            )
        }

        override fun onLastNameChange(newLastName: String) = launchWithStateLock {
            val curState = uiState.value as? ContactDetailsUiState.ViewingContactDetails
                ?: return@launchWithStateLock

            mutDetailsUiState.value = curState.copy(
                lastNameField = curState.lastNameField.copy(fieldValue = newLastName)
            )
        }

        override fun onTitleChange(newTitle: String) = launchWithStateLock {
            val curState = uiState.value as? ContactDetailsUiState.ViewingContactDetails
                ?: return@launchWithStateLock

            mutDetailsUiState.value = curState.copy(
                titleField = curState.titleField.copy(fieldValue = newTitle)
            )
        }

        override fun onDepartmentChange(newDepartment: String) = launchWithStateLock {
            val curState = uiState.value as? ContactDetailsUiState.ViewingContactDetails
                ?: return@launchWithStateLock

            mutDetailsUiState.value = curState.copy(
                departmentField = curState.departmentField.copy(fieldValue = newDepartment)
            )
        }

        private fun ContactDetailsUiState.ViewingContactDetails.toSObjectOrThrow() = ContactObject(
            firstName = firstNameField.fieldValue,
            lastName = lastNameField.fieldValue ?: "",
            title = titleField.fieldValue,
            department = departmentField.fieldValue
        )

        private fun ContactObject.buildViewingContactUiState(
            uiSyncState: SObjectUiSyncState,
            isEditingEnabled: Boolean,
            shouldScrollToErrorField: Boolean,
        ): ContactDetailsUiState.ViewingContactDetails {
            stateMutex.requireIsLocked()

            return ContactDetailsUiState.ViewingContactDetails(
                firstNameField = buildFirstNameField(),
                lastNameField = buildLastNameField(),
                titleField = buildTitleField(),
                departmentField = buildDepartmentField(),
                uiSyncState = uiSyncState,
                isEditingEnabled = isEditingEnabled,
                dataOperationIsActive = dataOpDelegate.dataOperationIsActive,
                shouldScrollToErrorField = shouldScrollToErrorField,
//                curDialogUiState = uiState.value.curDialogUiState,
            )
        }

        private fun ContactObject.buildFirstNameField() = ContactDetailsField.FirstName(
            fieldValue = firstName,
            onValueChange = this@DefaultContactDetailsViewModel::onFirstNameChange
        )

        private fun ContactObject.buildLastNameField() = ContactDetailsField.LastName(
            fieldValue = lastName,
            onValueChange = this@DefaultContactDetailsViewModel::onLastNameChange
        )

        private fun ContactObject.buildTitleField() = ContactDetailsField.Title(
            fieldValue = title,
            onValueChange = this@DefaultContactDetailsViewModel::onTitleChange
        )

        private fun ContactObject.buildDepartmentField() = ContactDetailsField.Department(
            fieldValue = department,
            onValueChange = this@DefaultContactDetailsViewModel::onDepartmentChange
        )
    }

    private interface InnerListViewModel {
        fun onRecordsEmitted(records: Map<String, ContactRecord>)
        fun setSelectedContact(id: String?)
    }

    private inner class DefaultContactsListViewModel :
        ContactsListUiClickHandler,
        ContactsListDataActionClickHandler {

        private val mutListUiState = MutableStateFlow(
            ContactsListUiState(
                contacts = emptyList(),
                curSelectedContactId = null,
                isDoingInitialLoad = true,
                isDoingDataAction = false,
                isSearchJobRunning = false
            )
        )
        val uiState: StateFlow<ContactsListUiState> get() = mutListUiState

        @Volatile
        private lateinit var curRecords: Map<String, ContactRecord>

        init {
            viewModelScope.launch(Dispatchers.Default) {
                contactsRepo.recordsById.collect {
                    onContactListUpdate(it)
                }
            }
        }

        override fun setSelectedContact(id: String?) = launchWithStateLock {
            mutListUiState.value = uiState.value.copy(curSelectedContactId = id)
        }

        private fun onContactListUpdate(newRecords: Map<String, SObjectRecord<ContactObject>>) =
            launchWithStateLock {
                curRecords = newRecords

                // always launch the search with new records and only update the ui list with the search results
                restartSearch(searchTerm = uiState.value.curSearchTerm) { filteredList ->
                    stateMutex.withLockDebug {
                        mutListUiState.value = uiState.value.copy(
                            contacts = filteredList,
                            isDoingInitialLoad = false
                        )
                    }
                }
                // TODO handle when selected contact is no longer in the records list
            }

        override fun contactClick(contactId: String) {
            TODO()
//            itemClickDelegate?.contactClick(contactId = contactId)
        }

        override fun createClick() {
            TODO()
//            itemClickDelegate?.createClick()
        }

        override fun deleteClick(contactId: String) {
//            if (dataActionClickDelegate != null) {
//                dataActionClickDelegate.deleteClick(contactId = contactId)
//                return
//            }
            TODO("Not yet implemented")
        }

        override fun editClick(contactId: String) {
            TODO()
//            itemClickDelegate?.editClick(contactId = contactId)
        }

        override fun undeleteClick(contactId: String) {
//            if (dataActionClickDelegate != null) {
//                dataActionClickDelegate.undeleteClick(contactId = contactId)
//                return
//            }
            TODO("Not yet implemented")
        }

        override fun setSearchTerm(newSearchTerm: String) = launchWithStateLock {
            mutListUiState.value = uiState.value.copy(curSearchTerm = newSearchTerm)

            restartSearch(searchTerm = newSearchTerm) { filteredList ->
                stateMutex.withLockDebug {
                    mutListUiState.value = uiState.value.copy(contacts = filteredList)
                }
            }
        }

        override fun onSearchTermUpdated(newSearchTerm: String) {
//            if (searchTermUpdatedDelegate != null) {
//                searchTermUpdatedDelegate.invoke(newSearchTerm)
//                return
//            }

            setSearchTerm(newSearchTerm = newSearchTerm)
        }

        @Volatile
        private var curSearchJob: Job? = null

        private suspend fun restartSearch(
            searchTerm: String,
            block: suspend (filteredList: List<ContactRecord>) -> Unit
        ) {
            stateMutex.requireIsLocked()

            // TODO this is not optimized. it would be cool to successively refine the search, but for now just search the entire list every time
            val contacts = curRecords.values.toList()

            curSearchJob?.cancel()
            curSearchJob = viewModelScope.launch(Dispatchers.Default) {
                try {
                    stateMutex.withLockDebug {
                        mutListUiState.value = uiState.value.copy(isSearchJobRunning = true)
                    }

                    val filteredResults =
                        if (searchTerm.isEmpty()) {
                            contacts
                        } else {
                            contacts.filter {
                                ensureActive()
                                it.sObject.fullName.contains(searchTerm, ignoreCase = true)
                            }
                        }

                    ensureActive()

                    block(filteredResults)
                } finally {
                    withContext(NonCancellable) {
                        stateMutex.withLockDebug {
                            mutListUiState.value = uiState.value.copy(isSearchJobRunning = false)
                        }
                    }
                }
            }
        }
    }
}
