// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.windows.state

import com.caochaoxing.core.model.ChaoxingAccountSession
import com.caochaoxing.core.model.ChaoxingCourse
import com.caochaoxing.core.model.ChaoxingEasemobIMConfig
import com.caochaoxing.core.model.ChaoxingGroupSignActivity
import com.caochaoxing.core.model.ChaoxingIMGroup
import com.caochaoxing.core.model.ChaoxingSignActivity
import com.caochaoxing.core.model.ChaoxingSignActivityStatus
import com.caochaoxing.core.model.ChaoxingSignDetailMetadata
import com.caochaoxing.core.network.ChaoxingHttpClient
import com.caochaoxing.core.service.ChaoxingActivityService
import com.caochaoxing.core.service.ChaoxingAuthService
import com.caochaoxing.core.service.ChaoxingCourseService
import com.caochaoxing.core.service.ChaoxingGroupService
import com.caochaoxing.core.signer.ChaoxingSignExecutor
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient

object WindowsHttpClientFactory {
    val sharedBaseClient: OkHttpClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ChaoxingHttpClient.defaultOkHttpClient()
    }

    fun createClient(
        session: ChaoxingAccountSession,
        userAgent: String,
        sharedBaseClient: OkHttpClient = this.sharedBaseClient,
    ): ChaoxingHttpClient = ChaoxingHttpClient(
        session = session,
        userAgent = userAgent,
        sharedBaseClient = sharedBaseClient,
    )
}

interface WindowsAppStateDependencies {
    val sharedHttpClient: OkHttpClient
        get() = WindowsHttpClientFactory.sharedBaseClient

    fun createHttpClient(
        session: ChaoxingAccountSession,
        userAgent: String,
    ): ChaoxingHttpClient = WindowsHttpClientFactory.createClient(
        session = session,
        userAgent = userAgent,
        sharedBaseClient = sharedHttpClient,
    )

    suspend fun login(
        phoneNumber: String,
        password: String,
        userAgent: String,
        isPrimary: Boolean,
    ): ChaoxingAccountSession

    suspend fun reLogin(client: ChaoxingHttpClient): ChaoxingAccountSession

    suspend fun fetchCourses(client: ChaoxingHttpClient): List<ChaoxingCourse>

    suspend fun fetchIMConfig(client: ChaoxingHttpClient): ChaoxingEasemobIMConfig

    suspend fun fetchGroups(
        client: ChaoxingHttpClient,
        imConfig: ChaoxingEasemobIMConfig,
    ): List<ChaoxingIMGroup>

    suspend fun fetchCourseActivities(
        client: ChaoxingHttpClient,
        course: ChaoxingCourse,
    ): List<ChaoxingSignActivity>

    suspend fun fetchGroupActivities(
        client: ChaoxingHttpClient,
        group: ChaoxingIMGroup,
        imConfig: ChaoxingEasemobIMConfig,
    ): List<ChaoxingGroupSignActivity>

    suspend fun fetchSignDetail(
        client: ChaoxingHttpClient,
        activeId: Long,
        fallbackClassId: Int,
        fallbackCourseId: Long,
    ): ChaoxingSignDetailMetadata

    suspend fun prepareSign(
        client: ChaoxingHttpClient,
        activity: ChaoxingSignActivity,
    ): ChaoxingSignActivityStatus

    suspend fun sleep(delayMs: Long)
}

class LiveWindowsAppStateDependencies(
    override val sharedHttpClient: OkHttpClient = WindowsHttpClientFactory.sharedBaseClient,
    private val onLogin: suspend (String, String, String, Boolean) -> ChaoxingAccountSession = { phone, pwd, ua, primary ->
        ChaoxingAuthService.login(
            phoneNumber = phone,
            password = pwd,
            userAgent = ua,
            isPrimary = primary,
        )
    },
    private val onReLogin: suspend (ChaoxingHttpClient) -> ChaoxingAccountSession = { client ->
        ChaoxingAuthService.reLogin(client)
        client.session
    },
    private val onFetchCourses: suspend (ChaoxingHttpClient) -> List<ChaoxingCourse> = { client ->
        ChaoxingCourseService.fetchCourses(client)
    },
    private val onFetchIMConfig: suspend (ChaoxingHttpClient) -> ChaoxingEasemobIMConfig = { client ->
        ChaoxingGroupService.getEasemobConfig(client)
    },
    private val onFetchGroups: suspend (ChaoxingHttpClient, ChaoxingEasemobIMConfig) -> List<ChaoxingIMGroup> = { client, im ->
        ChaoxingGroupService.fetchJoinedGroups(client, im)
    },
    private val onFetchCourseActivities: suspend (ChaoxingHttpClient, ChaoxingCourse) -> List<ChaoxingSignActivity> = { client, course ->
        ChaoxingActivityService.fetchCourseActivities(client, course)
    },
    private val onFetchGroupActivities: suspend (ChaoxingHttpClient, ChaoxingIMGroup, ChaoxingEasemobIMConfig) -> List<ChaoxingGroupSignActivity> = { client, group, im ->
        ChaoxingGroupService.fetchGroupSignActivities(client, group, im)
    },
    private val onFetchSignDetail: suspend (ChaoxingHttpClient, Long, Int, Long) -> ChaoxingSignDetailMetadata = { client, aid, cid, courseId ->
        ChaoxingActivityService.fetchSignDetail(
            client = client,
            activeId = aid,
            fallbackClassId = cid,
            fallbackCourseId = courseId,
        )
    },
    private val onPrepareSign: suspend (ChaoxingHttpClient, ChaoxingSignActivity) -> ChaoxingSignActivityStatus = { client, activity ->
        ChaoxingSignExecutor.preSign(client, activity)
    },
    private val onSleep: suspend (Long) -> Unit = { delayMs -> delay(delayMs) },
) : WindowsAppStateDependencies {
    override suspend fun login(
        phoneNumber: String,
        password: String,
        userAgent: String,
        isPrimary: Boolean,
    ): ChaoxingAccountSession = onLogin(phoneNumber, password, userAgent, isPrimary)

    override suspend fun reLogin(client: ChaoxingHttpClient): ChaoxingAccountSession = onReLogin(client)

    override suspend fun fetchCourses(client: ChaoxingHttpClient): List<ChaoxingCourse> = onFetchCourses(client)

    override suspend fun fetchIMConfig(client: ChaoxingHttpClient): ChaoxingEasemobIMConfig = onFetchIMConfig(client)

    override suspend fun fetchGroups(
        client: ChaoxingHttpClient,
        imConfig: ChaoxingEasemobIMConfig,
    ): List<ChaoxingIMGroup> = onFetchGroups(client, imConfig)

    override suspend fun fetchCourseActivities(
        client: ChaoxingHttpClient,
        course: ChaoxingCourse,
    ): List<ChaoxingSignActivity> = onFetchCourseActivities(client, course)

    override suspend fun fetchGroupActivities(
        client: ChaoxingHttpClient,
        group: ChaoxingIMGroup,
        imConfig: ChaoxingEasemobIMConfig,
    ): List<ChaoxingGroupSignActivity> = onFetchGroupActivities(client, group, imConfig)

    override suspend fun fetchSignDetail(
        client: ChaoxingHttpClient,
        activeId: Long,
        fallbackClassId: Int,
        fallbackCourseId: Long,
    ): ChaoxingSignDetailMetadata = onFetchSignDetail(client, activeId, fallbackClassId, fallbackCourseId)

    override suspend fun prepareSign(
        client: ChaoxingHttpClient,
        activity: ChaoxingSignActivity,
    ): ChaoxingSignActivityStatus = onPrepareSign(client, activity)

    override suspend fun sleep(delayMs: Long) = onSleep(delayMs)
}
