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
package ch.protonmail.android.api

import ch.protonmail.android.api.segments.BaseApi
import ch.protonmail.android.api.segments.address.AddressApi
import ch.protonmail.android.api.segments.address.AddressApiSpec
import ch.protonmail.android.api.segments.attachment.AttachmentApi
import ch.protonmail.android.api.segments.attachment.AttachmentApiSpec
import ch.protonmail.android.api.segments.attachment.AttachmentDownloadService
import ch.protonmail.android.api.segments.attachment.AttachmentUploadService
import ch.protonmail.android.api.segments.connectivity.ConnectivityApi
import ch.protonmail.android.api.segments.connectivity.ConnectivityApiSpec
import ch.protonmail.android.api.segments.connectivity.PingService
import ch.protonmail.android.api.segments.contact.ContactApi
import ch.protonmail.android.api.segments.contact.ContactApiSpec
import ch.protonmail.android.api.segments.device.DeviceApi
import ch.protonmail.android.api.segments.device.DeviceApiSpec
import ch.protonmail.android.api.segments.domain.DomainApi
import ch.protonmail.android.api.segments.domain.DomainApiSpec
import ch.protonmail.android.api.segments.domain.DomainPubService
import ch.protonmail.android.api.segments.key.KeyApi
import ch.protonmail.android.api.segments.key.KeyApiSpec
import ch.protonmail.android.api.segments.label.LabelApi
import ch.protonmail.android.api.segments.label.LabelApiSpec
import ch.protonmail.android.api.segments.message.MessageApi
import ch.protonmail.android.api.segments.message.MessageApiSpec
import ch.protonmail.android.api.segments.organization.OrganizationApi
import ch.protonmail.android.api.segments.organization.OrganizationApiSpec
import ch.protonmail.android.api.segments.payment.PaymentApi
import ch.protonmail.android.api.segments.payment.PaymentApiSpec
import ch.protonmail.android.api.segments.payment.PaymentPubService
import ch.protonmail.android.api.segments.report.ReportApi
import ch.protonmail.android.api.segments.report.ReportApiSpec
import ch.protonmail.android.api.segments.settings.mail.MailSettingsApi
import ch.protonmail.android.api.segments.settings.mail.MailSettingsApiSpec
import ch.protonmail.android.api.segments.settings.mail.UserSettingsApi
import ch.protonmail.android.api.segments.settings.mail.UserSettingsApiSpec
import ch.protonmail.android.api.segments.user.UserApi
import ch.protonmail.android.api.segments.user.UserApiSpec
import ch.protonmail.android.api.segments.user.UserPubService
import javax.inject.Inject

/**
 * Base API class that all API calls should go through.
 */
class ProtonMailApi private constructor(
    private val addressApi: AddressApiSpec,
    private val attachmentApi: AttachmentApiSpec,
    val connectivityApi: ConnectivityApiSpec,
    private val contactApi: ContactApiSpec,
    private val deviceApi: DeviceApiSpec,
    private val keyApi: KeyApiSpec,
    private val messageApi: MessageApiSpec,
    private val labelApi: LabelApiSpec,
    private val organizationApi: OrganizationApiSpec,
    private val paymentApi: PaymentApiSpec,
    private val reportApi: ReportApiSpec,
    private val mailSettingsApi: MailSettingsApiSpec,
    private val userSettingsApi: UserSettingsApiSpec,
    private val userApi: UserApiSpec,
    private val domainApi: DomainApiSpec,
    var securedServices: SecuredServices
) :
    BaseApi(),
    AddressApiSpec by addressApi,
    AttachmentApiSpec by attachmentApi,
    ConnectivityApiSpec by connectivityApi,
    ContactApiSpec by contactApi,
    DeviceApiSpec by deviceApi,
    KeyApiSpec by keyApi,
    LabelApiSpec by labelApi,
    MessageApiSpec by messageApi,
    OrganizationApiSpec by organizationApi,
    PaymentApiSpec by paymentApi,
    ReportApiSpec by reportApi,
    UserSettingsApiSpec by userSettingsApi,
    MailSettingsApiSpec by mailSettingsApi,
    UserApiSpec by userApi,
    DomainApiSpec by domainApi
{

    // region hack to insert parameters in the constructor instead of init, otherwise delegation doesn't work
    @Inject
    constructor(protonRetrofitBuilder: ProtonRetrofitBuilder) :
        this(createConstructionParams(protonRetrofitBuilder))

    constructor(params: Array<Any>) : this(
        // region params
        params[0] as AddressApiSpec,
        params[1] as AttachmentApiSpec,
        params[2] as ConnectivityApiSpec,
        params[3] as ContactApiSpec,
        params[4] as DeviceApiSpec,
        params[5] as KeyApiSpec,
        params[6] as MessageApi,
        params[7] as LabelApiSpec,
        params[8] as OrganizationApiSpec,
        params[9] as PaymentApiSpec,
        params[10] as ReportApiSpec,
        params[11] as MailSettingsApiSpec,
        params[12] as UserSettingsApiSpec,
        params[13] as UserApiSpec,
        params[14] as DomainApiSpec,
        params[15] as SecuredServices
        // endregion
    )

    companion object {

        /**
         * We inject the base url, which is now becoming dynamic instead of previously kept in Constants.ENDPOINT_URI.
         * Retrofit builders should now depend on a dynamic base url and also we should not recreate
         * them on every API call.
         */
        private fun createConstructionParams(protonRetrofitBuilder: ProtonRetrofitBuilder): Array<Any> {

            // region config
            val services = SecuredServices(protonRetrofitBuilder.provideRetrofit(RetrofitType.SECURE))
            val paymentPubService = protonRetrofitBuilder.provideRetrofit(RetrofitType.PUBLIC).create(PaymentPubService::class.java)
            val userPubService = protonRetrofitBuilder.provideRetrofit(RetrofitType.PUBLIC).create(UserPubService::class.java)
            val domainPubService = protonRetrofitBuilder.provideRetrofit(RetrofitType.PUBLIC).create(DomainPubService::class.java)
            val servicePing = protonRetrofitBuilder.provideRetrofit(RetrofitType.PING).create(PingService::class.java)
            val mUploadService = protonRetrofitBuilder.provideRetrofit(RetrofitType.EXTENDED_TIMEOUT).create(AttachmentUploadService::class.java)
            val mAttachmentsService = protonRetrofitBuilder.provideRetrofit(RetrofitType.ATTACHMENTS).create(AttachmentDownloadService::class.java)

            val addressApi = AddressApi(services.address)
            val attachmentApi = AttachmentApi(services.attachment, mAttachmentsService, mUploadService)
            val connectivityApi = ConnectivityApi(servicePing)
            val contactApi = ContactApi(services.contact)
            val deviceApi = DeviceApi(services.device)
            val keyApi = KeyApi(services.key)
            val messageApi = MessageApi(services.message)
            val labelApi = LabelApi(services.label)
            val organizationApi = OrganizationApi(services.organization)
            val paymentApi = PaymentApi(services.payment, paymentPubService)
            val reportApi = ReportApi(services.report)
            val mailSettingsApi = MailSettingsApi(services.mailSettings)
            val userSettingsApi = UserSettingsApi(services.userSettings)
            val domainApi = DomainApi(domainPubService)
            val userApi = UserApi(services.user, userPubService)
            // endregion
            return arrayOf(
                addressApi,
                attachmentApi,
                connectivityApi,
                contactApi,
                deviceApi,
                keyApi,
                messageApi,
                labelApi,
                organizationApi,
                paymentApi,
                reportApi,
                mailSettingsApi,
                userSettingsApi,
                userApi,
                domainApi,
                services
            )
        }
    }
}
