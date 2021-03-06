/*
 * Copyright 2016 Schibsted ASA.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.igor.travis

import com.netflix.appinfo.InstanceInfo
import com.netflix.discovery.DiscoveryClient
import com.netflix.spinnaker.igor.build.BuildCache
import com.netflix.spinnaker.igor.build.model.GenericBuild
import com.netflix.spinnaker.igor.build.model.GenericProject
import com.netflix.spinnaker.igor.history.EchoService
import com.netflix.spinnaker.igor.history.model.GenericBuildContent
import com.netflix.spinnaker.igor.history.model.GenericBuildEvent
import com.netflix.spinnaker.igor.model.BuildServiceProvider
import com.netflix.spinnaker.igor.polling.PollingMonitor
import com.netflix.spinnaker.igor.service.BuildMasters
import com.netflix.spinnaker.igor.travis.client.model.Build
import com.netflix.spinnaker.igor.travis.client.model.Commit
import com.netflix.spinnaker.igor.travis.client.model.Repo
import com.netflix.spinnaker.igor.travis.service.TravisBuildConverter
import com.netflix.spinnaker.igor.travis.service.TravisResultConverter
import com.netflix.spinnaker.igor.travis.service.TravisService
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.event.ContextRefreshedEvent
import org.springframework.stereotype.Service
import rx.Observable
import rx.Scheduler
import rx.functions.Action0
import rx.schedulers.Schedulers

import java.util.concurrent.TimeUnit


/**
 * Monitors new travis builds
 */
@Slf4j
@Service
@SuppressWarnings('CatchException')
@ConditionalOnProperty('travis.enabled')
class TravisBuildMonitor implements PollingMonitor{

    Scheduler.Worker worker = Schedulers.io().createWorker()

    @Autowired
    BuildCache buildCache

    @Autowired(required = false)
    EchoService echoService

    @Autowired(required = false)
    DiscoveryClient discoveryClient

    @Autowired
    BuildMasters buildMasters

    Long lastPoll

    static final int NEW_BUILD_EVENT_THRESHOLD = 1

    static final long BUILD_STARTED_AT_THRESHOLD = TimeUnit.SECONDS.toMillis(30)

    @SuppressWarnings('GStringExpressionWithinString')
    @Value('${spinnaker.build.pollInterval:60}')
    int pollInterval

    @SuppressWarnings('GStringExpressionWithinString')
    @Value('${travis.repositorySyncEnabled:false}')
    Boolean repositorySyncEnabled

    @SuppressWarnings('GStringExpressionWithinString')
    @Value('${travis.cachedJobTTLDays:60}')
    int cachedJobTTLDays

    @Override
    void onApplicationEvent(ContextRefreshedEvent event) {
        log.info('Started')
        setBuildCacheTTL()
        worker.schedulePeriodically(
            {
                if (isInService()) {
                    buildMasters.filteredMap(BuildServiceProvider.TRAVIS).keySet().each { master ->
                        changedBuilds(master)
                    }
                } else {
                    log.info("not in service (lastPoll: ${lastPoll ?: 'n/a'})")
                    lastPoll = null
                }
            } as Action0, 0, pollInterval, TimeUnit.SECONDS
        )
    }

    @Override
    String getName() {
        return "travisBuildMonitor"
    }

    @Override
    boolean isInService() {
        if (discoveryClient == null) {
            log.info("no DiscoveryClient, assuming InService")
            true
        } else {
            def remoteStatus = discoveryClient.instanceRemoteStatus
            log.info("current remote status ${remoteStatus}")
            remoteStatus == InstanceInfo.InstanceStatus.UP
        }
    }

    @Override
    Long getLastPoll() {
        return lastPoll
    }

    @Override
    int getPollInterval() {
        return pollInterval
    }

    List<Map> changedBuilds(String master) {
        log.info('Checking for new builds for ' + master)
        List<String> cachedRepoSlugs = buildCache.getJobNames(master)
        List<Map> results = []

        TravisService travisService = buildMasters.map[master]

        lastPoll = System.currentTimeMillis()
        def startTime = System.currentTimeMillis()
        List<Repo> repos = filterOutOldBuilds(travisService.getReposForAccounts())
        log.info("Took ${System.currentTimeMillis() - startTime}ms to retrieve ${repos.size()} repositories (master: ${master})")

        Observable.from(repos).subscribe(
            { Repo repo ->
                boolean addToCache = false
                Map cachedBuild = null

                if (cachedRepoSlugs.contains(repo.slug)) {
                    cachedBuild = buildCache.getLastBuild(master, repo.slug)
                    if ((TravisResultConverter.running(repo.lastBuildState) != cachedBuild.lastBuildBuilding) ||
                        (repo.lastBuildNumber != Integer.valueOf(cachedBuild.lastBuildLabel))) {
                        addToCache = true
                        log.info "Build changed: ${master}: ${repo.slug} : ${repo.lastBuildNumber} : ${repo.lastBuildState}"
                        if (echoService) {
                            pushEventsForMissingBuilds(repo, cachedBuild, master, travisService)
                        }
                    }
                } else {
                    addToCache = true
                }
                if (addToCache) {
                    log.info("Build update [${repo.slug}:${repo.lastBuildNumber}] [status:${repo.lastBuildState}] [running:${TravisResultConverter.running(repo.lastBuildState)}]")
                    buildCache.setLastBuild(master, repo.slug, repo.lastBuildNumber, TravisResultConverter.running(repo.lastBuildState), buildCacheJobTTLSeconds())
                    sendEventForBuild(repo, master, travisService)


                    results << [previous: cachedBuild, current: repo]
                }
            }, {
            log.error("Error: ${it.message} (${master})")
        }
        )
        log.info("Last poll took ${System.currentTimeMillis() - lastPoll}ms (master: ${master})")
        if (repositorySyncEnabled) {
            startTime = System.currentTimeMillis()
            travisService.syncRepos()
            log.info("repositorySync: Took ${System.currentTimeMillis() - startTime}ms to sync repositories for ${master}")
        }
        results

    }

    private void sendEventForBuild(Repo repo, String master, TravisService travisService) {
        if (echoService) {
            log.info "pushing event for ${master}:${repo.slug}:${repo.lastBuildNumber}"

            GenericProject project = new GenericProject(repo.slug, TravisBuildConverter.genericBuild(repo, travisService.baseUrl))
            echoService.postEvent(
                new GenericBuildEvent(content: new GenericBuildContent(project: project, master: master, type: 'travis'))
            )

        }
        Commit commit = travisService.getCommit(repo.slug, repo.lastBuildNumber)
        if (commit) {
            String branchedSlug = travisService.branchedRepoSlug(repo.slug, repo.lastBuildNumber, commit)

            if (branchedSlug != repo.slug) {
                buildCache.setLastBuild(master, branchedSlug, repo.lastBuildNumber, TravisResultConverter.running(repo.lastBuildState), buildCacheJobTTLSeconds())
                if (echoService) {
                    log.info "pushing event for ${master}:${branchedSlug}:${repo.lastBuildNumber}"

                    GenericProject project = new GenericProject(branchedSlug, TravisBuildConverter.genericBuild(repo, travisService.baseUrl))
                    echoService.postEvent(
                        new GenericBuildEvent(content: new GenericBuildContent(project: project, master: master, type: 'travis'))
                    )

                }
            }
        }
    }

    private void pushEventsForMissingBuilds(Repo repo, Map cachedBuild, String master, TravisService travisService) {
        int currentBuild = repo.lastBuildNumber
        int lastBuild = Integer.valueOf(cachedBuild.lastBuildLabel)
        int nextBuild = lastBuild + NEW_BUILD_EVENT_THRESHOLD

        if (nextBuild < currentBuild) {
            log.info "sending build events for builds between ${lastBuild} and ${currentBuild}"

            for (int buildNumber = nextBuild; buildNumber < currentBuild; buildNumber++) {
                Build build = travisService.getBuild(repo, buildNumber) //rewrite to afterNumber list thing
                if (build?.state) {
                    log.info "pushing event for ${master}:${repo.slug}:${build.number}"
                    String url = "${travisService.baseUrl}/${repo.slug}/builds/${build.id}"
                    GenericProject project = new GenericProject(repo.slug, new GenericBuild((TravisResultConverter.running(build.state)), build.number, build.duration, TravisResultConverter.getResultFromTravisState(build.state), repo.slug, url))
                    echoService.postEvent(
                        new GenericBuildEvent(content: new GenericBuildContent(project: project, master: master, type: 'travis')))
                }
            }
        }
    }

    private void setBuildCacheTTL() {
        /*
        This method is here to help migrate to ttl usage. It can be removed in igor after some time.
         */
        buildMasters.filteredMap(BuildServiceProvider.TRAVIS).keySet().each { master ->
            log.info "Searching for cached builds without TTL."

            buildCache.getJobNames(master).each { job ->
                Long ttl = buildCache.getTTL(master, job)
                if (ttl == -1L) {
                    log.info "Found build without TTL: ${master}:${job}:${ttl} - Setting TTL to ${buildCacheJobTTLSeconds()}"
                    buildCache.setTTL(master, job, buildCacheJobTTLSeconds())
                }
            }
        }
    }

    private int buildCacheJobTTLSeconds() {
        return TimeUnit.DAYS.toSeconds(cachedJobTTLDays)
    }

    private List<Repo> filterOutOldBuilds(List<Repo> repos){
        /*
        BUILD_STARTED_AT_THRESHOLD is here because the builds can be picked up by igor before lastBuildStartedAt is
        set. This means the TTL can be set in the BuildCache before lastBuildStartedAt, if that happens we need a
        grace threshold so that we don't resend the event to echo. The value of the threshold assumes that travis
        will set the lastBuildStartedAt within 30 seconds.
         */
        Long threshold = new Date().getTime() - TimeUnit.DAYS.toMillis(cachedJobTTLDays) + BUILD_STARTED_AT_THRESHOLD
        return repos.findAll({ repo ->
            repo.lastBuildStartedAt?.getTime() > threshold
        })
    }
}
