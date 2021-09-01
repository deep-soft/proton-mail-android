/*
 * Copyright (c) 2020 Proton Technologies AG
 *
 * This file is part of ProtonMail.
 *
 * ProtonMail is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * ProtonMail is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with ProtonMail. If not, see https://www.gnu.org/licenses/.
 */
package ch.protonmail.android.contacts.details.data

import ch.protonmail.android.api.ProtonMailApiManager
import ch.protonmail.android.contacts.details.presentation.model.ContactLabelUiModel
import ch.protonmail.android.data.ContactsRepository
import ch.protonmail.android.data.local.ContactDao
import ch.protonmail.android.data.local.model.ContactData
import ch.protonmail.android.data.local.model.ContactEmail
import ch.protonmail.android.data.local.model.FullContactDetails
import ch.protonmail.android.labels.data.LabelRepository
import ch.protonmail.android.labels.data.db.LabelEntity
import ch.protonmail.android.labels.data.mapper.LabelsMapper
import ch.protonmail.android.labels.data.model.LabelId
import com.birbit.android.jobqueue.JobManager
import io.reactivex.Observable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.proton.core.domain.entity.UserId
import me.proton.core.util.kotlin.DispatcherProvider
import timber.log.Timber
import javax.inject.Inject

open class ContactDetailsRepository @Inject constructor(
    protected val jobManager: JobManager,
    protected val api: ProtonMailApiManager,
    protected val contactDao: ContactDao,
    private val dispatcherProvider: DispatcherProvider,
    private val labelsMapper: LabelsMapper,
    private val labelRepository: LabelRepository,
    private val contactRepository: ContactsRepository
) {

    suspend fun getContactGroupsLabelForId(emailId: String): List<LabelEntity> =
        contactRepository.getAllContactGroupsByContactEmail(emailId)

    fun getContactEmails(id: String): Observable<List<ContactEmail>> {
        return contactDao.findContactEmailsByContactIdObservable(id)
            .toObservable()
    }

    fun observeContactEmails(contactId: String): Flow<List<ContactEmail>> =
        contactDao.observeContactEmailsByContactId(contactId)

    fun getContactGroups(userId: UserId): Observable<List<ContactLabelUiModel>> {
        return Observable.fromCallable {
            runBlocking {
                val dbContacts = getContactGroupsFromDb(userId)
                val apiContacts = getContactGroupsFromApi(userId)
                dbContacts.plus(apiContacts)
            }
        }
    }

    suspend fun getContactEmailsCount(contactGroupId: LabelId) =
        contactRepository.countContactEmailsByLabelId(contactGroupId)

    private suspend fun getContactGroupsFromApi(userId: UserId): List<ContactLabelUiModel> {
        val contactGroupsResponse = api.fetchContactGroups(userId).valueOrNull?.labels

        return contactGroupsResponse?.let { labels ->
            val entities = labels.map {
                labelsMapper.mapLabelToLabelEntity(it, userId)
            }
            labelRepository.deleteContactGroups(userId)
            labelRepository.saveLabels(entities)
            labels.map {
                labelsMapper.mapLabelToContactLabelUiModel(it, getContactEmailsCount(LabelId(it.id)))
            }

        } ?: emptyList()
    }

    private suspend fun getContactGroupsFromDb(userId: UserId): List<ContactLabelUiModel> {
        return labelRepository.findContactGroups(userId)
            .map { entity ->
                labelsMapper.mapLabelEntityToContactLabelUiModel(entity, getContactEmailsCount(entity.id))
            }
    }

    suspend fun saveContactEmails(emails: List<ContactEmail>) = withContext(dispatcherProvider.Io) {
        contactDao.saveAllContactsEmails(emails)
    }

    suspend fun updateContactDataWithServerId(contactDataInDb: ContactData, contactServerId: String) {
        withContext(dispatcherProvider.Io) {
            contactDao.findContactDataByDbId(contactDataInDb.dbId ?: -1)?.let {
                it.contactId = contactServerId
                contactDao.saveContactData(it)
            }
        }
    }

    suspend fun updateAllContactEmails(contactId: String?, contactServerEmails: List<ContactEmail>) {
        withContext(dispatcherProvider.Io) {
            contactId?.let {
                val localContactEmails = contactDao.findContactEmailsByContactId(it)
                contactDao.deleteAllContactsEmails(localContactEmails)
                contactDao.saveAllContactsEmails(contactServerEmails)
            }
        }
    }

    suspend fun deleteContactData(contactData: ContactData) =
        withContext(dispatcherProvider.Io) {
            contactDao.deleteContactData(contactData)
        }

    suspend fun saveContactData(contactData: ContactData): Long =
        withContext(dispatcherProvider.Io) {
            return@withContext contactDao.saveContactData(contactData)
        }

    fun observeFullContactDetails(contactId: String): Flow<FullContactDetails> =
        contactDao.observeFullContactDetailsById(contactId)
            .distinctUntilChanged()
            .onEach { savedContacts ->
                Timber.v("Fetched saved Contact Details $savedContacts")
                if (savedContacts == null) {
                    val response = api.fetchContactDetails(contactId)
                    val fetchedContact = response.contact
                    Timber.d("Fetched new Contact Details $fetchedContact")
                    insertFullContactDetails(fetchedContact)
                }
            }
            .filterNotNull()

    private suspend fun insertFullContactDetails(fullContactDetails: FullContactDetails) =
        contactDao.insertFullContactDetails(fullContactDetails)

}
