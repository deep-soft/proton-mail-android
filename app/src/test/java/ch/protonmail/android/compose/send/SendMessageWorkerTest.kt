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

package ch.protonmail.android.compose.send

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import ch.protonmail.android.activities.messageDetails.repository.MessageDetailsRepository
import ch.protonmail.android.api.ProtonMailApiManager
import ch.protonmail.android.api.interceptors.RetrofitTag
import ch.protonmail.android.api.models.MessageRecipient
import ch.protonmail.android.api.models.SendPreference
import ch.protonmail.android.api.models.enumerations.MIMEType
import ch.protonmail.android.api.models.enumerations.PackageType
import ch.protonmail.android.api.models.factories.MessageSecurityOptions
import ch.protonmail.android.api.models.factories.PackageFactory
import ch.protonmail.android.api.models.factories.SendPreferencesFactory
import ch.protonmail.android.api.models.messages.send.MessageSendBody
import ch.protonmail.android.api.models.messages.send.MessageSendKey
import ch.protonmail.android.api.models.messages.send.MessageSendPackage
import ch.protonmail.android.api.models.room.messages.Message
import ch.protonmail.android.core.Constants
import ch.protonmail.android.core.UserManager
import ch.protonmail.android.usecase.compose.SaveDraft
import ch.protonmail.android.usecase.compose.SaveDraftResult
import ch.protonmail.android.utils.extensions.serialize
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.RelaxedMockK
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runBlockingTest
import me.proton.core.test.kotlin.CoroutinesTest
import me.proton.core.util.kotlin.deserialize
import me.proton.core.util.kotlin.serialize
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.test.BeforeTest
import kotlin.test.Test

class SendMessageWorkerTest : CoroutinesTest {

    @RelaxedMockK
    private lateinit var context: Context

    @RelaxedMockK
    private lateinit var parameters: WorkerParameters

    @RelaxedMockK
    private lateinit var messageDetailsRepository: MessageDetailsRepository

    @RelaxedMockK
    private lateinit var workManager: WorkManager

    @RelaxedMockK
    private lateinit var saveDraft: SaveDraft

    @RelaxedMockK
    private lateinit var sendPreferencesFactory: SendPreferencesFactory

    @RelaxedMockK
    private lateinit var apiManager: ProtonMailApiManager

    @RelaxedMockK
    private lateinit var userManager: UserManager

    @RelaxedMockK
    private lateinit var packageFactory: PackageFactory

    @InjectMockKs
    private lateinit var worker: SendMessageWorker

    private val currentUsername = "username"

    @BeforeTest
    fun setUp() {
        MockKAnnotations.init(this)
    }

    @Test
    fun workerEnqueuerCreatesOneTimeRequestWorkerWhichIsUniqueForMessageId() {
        runBlockingTest {
            // Given
            val messageParentId = "98234"
            val attachmentIds = listOf("attachmentId234", "238")
            val messageId = "2834"
            val messageDbId = 534L
            val messageActionType = Constants.MessageActionType.REPLY_ALL
            val message = Message(messageId = messageId)
            message.dbId = messageDbId
            val previousSenderAddressId = "previousSenderId82348"
            val securityOptions = MessageSecurityOptions("password", "hint", 3273727L)
            val requestSlot = slot<OneTimeWorkRequest>()
            every {
                workManager.enqueueUniqueWork(messageId, ExistingWorkPolicy.REPLACE, capture(requestSlot))
            } answers { mockk() }

            // When
            SendMessageWorker.Enqueuer(workManager).enqueue(
                message,
                attachmentIds,
                messageParentId,
                messageActionType,
                previousSenderAddressId,
                securityOptions
            )

            // Then
            val workSpec = requestSlot.captured.workSpec
            val constraints = workSpec.constraints
            val inputData = workSpec.input
            val actualMessageDbId = inputData.getLong(KEY_INPUT_SEND_MESSAGE_MSG_DB_ID, -1)
            val actualAttachmentIds = inputData.getStringArray(KEY_INPUT_SEND_MESSAGE_ATTACHMENT_IDS)
            val actualMessageLocalId = inputData.getString(KEY_INPUT_SEND_MESSAGE_MESSAGE_ID)
            val actualMessageParentId = inputData.getString(KEY_INPUT_SEND_MESSAGE_MSG_PARENT_ID)
            val actualMessageActionType = inputData.getString(KEY_INPUT_SEND_MESSAGE_ACTION_TYPE_SERIALIZED)
            val actualPreviousSenderAddress = inputData.getString(KEY_INPUT_SEND_MESSAGE_PREV_SENDER_ADDR_ID)
            val actualMessageSecurityOptions = inputData.getString(KEY_INPUT_SEND_MESSAGE_SECURITY_OPTIONS_SERIALIZED)
            assertEquals(message.dbId, actualMessageDbId)
            assertEquals(message.messageId, actualMessageLocalId)
            assertArrayEquals(attachmentIds.toTypedArray(), actualAttachmentIds)
            assertEquals(messageParentId, actualMessageParentId)
            assertEquals(messageActionType.serialize(), actualMessageActionType)
            assertEquals(previousSenderAddressId, actualPreviousSenderAddress)
            assertEquals(securityOptions, actualMessageSecurityOptions?.deserialize(MessageSecurityOptions.serializer()))
            assertEquals(NetworkType.CONNECTED, constraints.requiredNetworkType)
            assertEquals(BackoffPolicy.EXPONENTIAL, workSpec.backoffPolicy)
            assertEquals(20000, workSpec.backoffDelayDuration)
            verify { workManager.getWorkInfoByIdLiveData(any()) }
        }
    }

    @Test
    fun workerSavesDraftPassingGivenParameters() = runBlockingTest {
        val messageDbId = 2373L
        val messageId = "8322223"
        val message = Message().apply {
            dbId = messageDbId
            this.messageId = messageId
        }
        givenFullValidInput(
            messageDbId,
            messageId,
            arrayOf("attId8327"),
            "parentId82384",
            Constants.MessageActionType.NONE,
            "prevSenderAddress"
        )
        every { messageDetailsRepository.findMessageByMessageDbId(messageDbId) } returns message
        coEvery { messageDetailsRepository.findMessageById(any()) } returns Message()
        coEvery { saveDraft(any()) } returns flowOf(SaveDraftResult.Success(messageId))

        worker.doWork()

        val expectedParameters = SaveDraft.SaveDraftParameters(
            message,
            listOf("attId8327"),
            "parentId82384",
            Constants.MessageActionType.NONE,
            "prevSenderAddress"
        )
        coVerify { saveDraft(expectedParameters) }
    }

    @Test
    fun workerFailsReturningErrorWhenMessageIsNotFoundInTheDatabase() = runBlockingTest {
        val messageDbId = 2373L
        val messageId = "8322223"
        givenFullValidInput(messageDbId, messageId)
        every { messageDetailsRepository.findMessageByMessageDbId(messageDbId) } returns null

        val result = worker.doWork()

        assertEquals(
            ListenableWorker.Result.failure(
                workDataOf(KEY_OUTPUT_RESULT_SEND_MESSAGE_ERROR_ENUM to "MessageNotFound")
            ),
            result
        )
        coVerify(exactly = 0) { saveDraft(any()) }
    }

    @Test
    fun workerFailsReturningErrorWhenSaveDraftOperationFails() = runBlockingTest {
        val messageDbId = 2834L
        val messageId = "823472"
        val message = Message().apply {
            dbId = messageDbId
            this.messageId = messageId
        }
        givenFullValidInput(messageDbId, messageId)
        every { messageDetailsRepository.findMessageByMessageDbId(messageDbId) } returns message
        coEvery { saveDraft(any()) } returns flowOf(SaveDraftResult.OnlineDraftCreationFailed)

        val result = worker.doWork()

        assertEquals(
            ListenableWorker.Result.failure(
                workDataOf(KEY_OUTPUT_RESULT_SEND_MESSAGE_ERROR_ENUM to "DraftCreationFailed")
            ),
            result
        )
    }

    @Test
    fun workerGetsTheUpdatedMessageThatWasSavedAsDraftWhenSavingDraftSucceeds() = runBlockingTest {
        val messageDbId = 328423L
        val messageId = "82384203"
        val message = Message().apply {
            dbId = messageDbId
            this.messageId = messageId
        }
        val savedDraftId = "234232"
        val savedDraftMessage = Message().apply {
            dbId = messageDbId
            this.messageId = savedDraftId
            toList = listOf(MessageRecipient("recipient", "recipientOnSavedDraft@pm.me"))
        }
        givenFullValidInput(messageDbId, messageId)
        every { messageDetailsRepository.findMessageByMessageDbId(messageDbId) } returns message
        coEvery { messageDetailsRepository.findMessageById(savedDraftId) } returns savedDraftMessage
        coEvery { saveDraft(any()) } returns flowOf(SaveDraftResult.Success(savedDraftId))

        worker.doWork()

        coVerify { messageDetailsRepository.findMessageById(savedDraftId) }
        verify { sendPreferencesFactory.fetch(listOf("recipientOnSavedDraft@pm.me")) }
    }

    @Test
    fun workerRetriesOrFailsWhenTheUpdatedMessageThatWasSavedAsDraftIsNotFoundInTheDb() = runBlockingTest {
        val messageDbId = 38472L
        val messageId = "29837462"
        val message = Message().apply {
            dbId = messageDbId
            this.messageId = messageId
        }
        val savedDraftId = "2836462"
        givenFullValidInput(messageDbId, messageId)
        every { messageDetailsRepository.findMessageByMessageDbId(messageDbId) } returns message
        coEvery { messageDetailsRepository.findMessageById(savedDraftId) } returns null
        coEvery { saveDraft(any()) } returns flowOf(SaveDraftResult.Success(savedDraftId))
        every { parameters.runAttemptCount } returns 1

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.Retry(), result)
    }

    @Test
    fun workerFetchesSendPreferencesForEachUniqueRecipientWhenSavingDraftSucceeds() = runBlockingTest {
        val messageDbId = 2834L
        val messageId = "823472"
        val toRecipientEmail = "to-recipient@pm.me"
        val toRecipient1Email = "to-recipient-1@pm.me"
        val ccRecipientEmail = "cc-recipient@protonmail.com"
        val bccRecipientEmail = "bcc-recipient@protonmail.ch"
        val message = Message().apply {
            dbId = messageDbId
            this.messageId = messageId
            this.toList = listOf(
                MessageRecipient("recipient", toRecipientEmail),
                MessageRecipient("duplicatedMailRecipient", toRecipientEmail),
                MessageRecipient("recipient1", toRecipient1Email)
            )
            this.ccList = listOf(MessageRecipient("recipient2", ccRecipientEmail))
            this.bccList = listOf(MessageRecipient("recipient3", bccRecipientEmail))
        }
        val savedDraftMessageId = "6234723"
        givenFullValidInput(messageDbId, messageId)
        every { messageDetailsRepository.findMessageByMessageDbId(messageDbId) } returns message
        // The following mock reuses `message` defined above for convenience. It's actually the saved draft message
        coEvery { messageDetailsRepository.findMessageById(savedDraftMessageId) } returns message
        coEvery { saveDraft(any()) } returns flowOf(SaveDraftResult.Success(savedDraftMessageId))

        worker.doWork()

        val recipientsCaptor = slot<List<String>>()
        verify { sendPreferencesFactory.fetch(capture(recipientsCaptor)) }
        assertTrue(
            recipientsCaptor.captured.containsAll(
                listOf(
                    toRecipient1Email,
                    ccRecipientEmail,
                    toRecipientEmail,
                    bccRecipientEmail
                )
            )
        )
        assertEquals(4, recipientsCaptor.captured.size)
    }

    @Test
    fun workerRetriesSendingMessageWhenFetchingSendPreferencesFailsAndMaxRetriesWasNotReached() = runBlockingTest {
        val messageDbId = 34238L
        val messageId = "823720"
        val message = Message().apply {
            dbId = messageDbId
            this.messageId = messageId
        }
        givenFullValidInput(messageDbId, messageId)
        every { messageDetailsRepository.findMessageByMessageDbId(messageDbId) } returns message
        coEvery { messageDetailsRepository.findMessageById(any()) } returns Message()
        coEvery { saveDraft(any()) } returns flowOf(SaveDraftResult.Success(messageId))
        every { sendPreferencesFactory.fetch(any()) } throws Exception("test - failed fetching send preferences")
        every { parameters.runAttemptCount } returns 1

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.Retry(), result)
    }

    @Test
    fun workerFailsWhenFetchingSendPreferencesFailsAndMaxRetriesWasReached() = runBlockingTest {
        val messageDbId = 8435L
        val messageId = "923482"
        val message = Message().apply {
            dbId = messageDbId
            this.messageId = messageId
        }
        givenFullValidInput(messageDbId, messageId)
        every { messageDetailsRepository.findMessageByMessageDbId(messageDbId) } returns message
        coEvery { messageDetailsRepository.findMessageById(any()) } returns Message()
        coEvery { saveDraft(any()) } returns flowOf(SaveDraftResult.Success(messageId))
        every { sendPreferencesFactory.fetch(any()) } throws Exception("test - failed fetching send preferences")
        every { parameters.runAttemptCount } returns 6

        val result = worker.doWork()

        assertEquals(
            ListenableWorker.Result.failure(
                workDataOf(KEY_OUTPUT_RESULT_SEND_MESSAGE_ERROR_ENUM to "FetchSendPreferencesFailed")
            ),
            result
        )
    }

    @Test
    fun workerFetchesSendPreferencesOnlyForNonEmptyRecipientListsWhenSavingDraftSucceeds() = runBlockingTest {
        val messageDbId = 2834L
        val messageId = "823472"
        val toRecipientEmail = "to-recipient@pm.me"
        val message = Message().apply {
            dbId = messageDbId
            this.messageId = messageId
            this.toList = listOf(MessageRecipient("recipient", toRecipientEmail))
            this.ccList = emptyList()
            this.bccList = listOf(MessageRecipient("emptyBccRecipient", ""))
        }
        val savedDraftMessageId = "6234723"
        givenFullValidInput(messageDbId, messageId)
        every { messageDetailsRepository.findMessageByMessageDbId(messageDbId) } returns message
        // The following mock reuses `message` defined above for convenience. It's actually the saved draft message
        coEvery { messageDetailsRepository.findMessageById(savedDraftMessageId) } returns message
        coEvery { saveDraft(any()) } returns flowOf(SaveDraftResult.Success(savedDraftMessageId))

        worker.doWork()

        verify { sendPreferencesFactory.fetch(listOf(toRecipientEmail)) }
    }

    @Test
    fun workerPerformsSendMessageApiCallWhenDraftWasSavedAndSendPreferencesFetchedSuccessfully() = runBlockingTest {
        val messageDbId = 82321L
        val messageId = "233472"
        val message = Message().apply {
            dbId = messageDbId
            this.messageId = messageId
        }
        val savedDraftMessageId = "6234723"
        val messageSendKey = MessageSendKey("algorithm", "key")
        val packages = listOf(
            MessageSendPackage(
                "body",
                messageSendKey,
                MIMEType.PLAINTEXT,
                mapOf("key" to messageSendKey)
            )
        )
        val savedDraftMessage = Message().apply {
            dbId = messageDbId
            this.messageId = savedDraftMessageId
        }
        val sendPreference = SendPreference(
            "email",
            true,
            true,
            MIMEType.HTML,
            "publicKey",
            PackageType.PGP_MIME,
            false,
            false,
            true,
            false
        )
        val sendPreferences = mapOf(
            "key" to sendPreference
        )
        val securityOptions = MessageSecurityOptions("password", "hint", 172800L)
        givenFullValidInput(messageDbId, messageId, securityOptions = securityOptions)
        every { messageDetailsRepository.findMessageByMessageDbId(messageDbId) } returns message
        // The following mock reuses `message` defined above for convenience. It's actually the saved draft message
        coEvery { messageDetailsRepository.findMessageById(savedDraftMessageId) } returns savedDraftMessage
        coEvery { saveDraft(any()) } returns flowOf(SaveDraftResult.Success(savedDraftMessageId))
        every { userManager.getMailSettings(currentUsername)!!.autoSaveContacts } returns 1
        every { sendPreferencesFactory.fetch(any()) } returns sendPreferences
        every { packageFactory.generatePackages(savedDraftMessage, listOf(sendPreference), securityOptions) } returns packages

        worker.doWork()

        val expiresAfterSeconds = 172800L
        val autoSaveContacts = userManager.getMailSettings(currentUsername)!!.autoSaveContacts
        val requestBody = MessageSendBody(packages, expiresAfterSeconds, autoSaveContacts)
        val retrofitTag = RetrofitTag(currentUsername)
        coVerify { apiManager.sendMessage(savedDraftMessageId, requestBody, retrofitTag) }
    }

    private fun givenFullValidInput(
        messageDbId: Long,
        messageId: String,
        attachments: Array<String> = arrayOf("attId62364"),
        parentId: String = "parentId72364",
        messageActionType: Constants.MessageActionType = Constants.MessageActionType.REPLY,
        previousSenderAddress: String = "prevSenderAddress923",
        securityOptions: MessageSecurityOptions? = MessageSecurityOptions(null, null, -1)
    ) {
        every { parameters.inputData.getLong(KEY_INPUT_SEND_MESSAGE_MSG_DB_ID, -1) } answers { messageDbId }
        every { parameters.inputData.getStringArray(KEY_INPUT_SEND_MESSAGE_ATTACHMENT_IDS) } answers { attachments }
        every { parameters.inputData.getString(KEY_INPUT_SEND_MESSAGE_MESSAGE_ID) } answers { messageId }
        every { parameters.inputData.getString(KEY_INPUT_SEND_MESSAGE_MSG_PARENT_ID) } answers { parentId }
        every { parameters.inputData.getString(KEY_INPUT_SEND_MESSAGE_ACTION_TYPE_SERIALIZED) } answers {
            messageActionType.serialize()
        }
        every { parameters.inputData.getString(KEY_INPUT_SEND_MESSAGE_PREV_SENDER_ADDR_ID) } answers {
            previousSenderAddress
        }
        every { parameters.inputData.getString(KEY_INPUT_SEND_MESSAGE_SECURITY_OPTIONS_SERIALIZED) } answers {
            securityOptions!!.serialize()
        }
    }
}
