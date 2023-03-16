/*
 * Copyright (c) 2023 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.container_orchestrator.orchestrator;

import static io.airbyte.metrics.lib.ApmTraceConstants.JOB_ORCHESTRATOR_OPERATION_NAME;
import static io.airbyte.metrics.lib.ApmTraceConstants.Tags.DESTINATION_DOCKER_IMAGE_KEY;
import static io.airbyte.metrics.lib.ApmTraceConstants.Tags.JOB_ID_KEY;
import static io.airbyte.metrics.lib.ApmTraceConstants.Tags.SOURCE_DOCKER_IMAGE_KEY;

import datadog.trace.api.Trace;
import io.airbyte.api.client.AirbyteApiClient;
import io.airbyte.api.client.generated.DestinationApi;
import io.airbyte.api.client.generated.SourceApi;
import io.airbyte.api.client.generated.SourceDefinitionApi;
import io.airbyte.api.client.model.generated.SourceDefinitionIdRequestBody;
import io.airbyte.api.client.model.generated.SourceIdRequestBody;
import io.airbyte.commons.converters.ConnectorConfigUpdater;
import io.airbyte.commons.features.FeatureFlagHelper;
import io.airbyte.commons.features.FeatureFlags;
import io.airbyte.commons.json.Jsons;
import io.airbyte.commons.logging.MdcScope;
import io.airbyte.commons.protocol.AirbyteMessageSerDeProvider;
import io.airbyte.commons.protocol.AirbyteProtocolVersionedMigratorFactory;
import io.airbyte.commons.temporal.TemporalUtils;
import io.airbyte.commons.version.Version;
import io.airbyte.config.Configs;
import io.airbyte.config.ReplicationOutput;
import io.airbyte.config.StandardSyncInput;
import io.airbyte.featureflag.FeatureFlagClient;
import io.airbyte.featureflag.FieldSelectionEnabled;
import io.airbyte.featureflag.PerfBackgroundJsonValidation;
import io.airbyte.featureflag.ShouldStartHeartbeatMonitoring;
import io.airbyte.featureflag.Workspace;
import io.airbyte.metrics.lib.ApmTraceUtils;
import io.airbyte.metrics.lib.MetricClientFactory;
import io.airbyte.metrics.lib.MetricEmittingApps;
import io.airbyte.persistence.job.models.IntegrationLauncherConfig;
import io.airbyte.persistence.job.models.JobRunConfig;
import io.airbyte.protocol.models.ConfiguredAirbyteCatalog;
import io.airbyte.workers.RecordSchemaValidator;
import io.airbyte.workers.WorkerConstants;
import io.airbyte.workers.WorkerMetricReporter;
import io.airbyte.workers.WorkerUtils;
import io.airbyte.workers.general.DefaultReplicationWorker;
import io.airbyte.workers.internal.AirbyteStreamFactory;
import io.airbyte.workers.internal.DefaultAirbyteDestination;
import io.airbyte.workers.internal.DefaultAirbyteSource;
import io.airbyte.workers.internal.DefaultAirbyteStreamFactory;
import io.airbyte.workers.internal.EmptyAirbyteSource;
import io.airbyte.workers.internal.HeartbeatMonitor;
import io.airbyte.workers.internal.HeartbeatTimeoutChaperone;
import io.airbyte.workers.internal.NamespacingMapper;
import io.airbyte.workers.internal.VersionedAirbyteMessageBufferedWriterFactory;
import io.airbyte.workers.internal.VersionedAirbyteStreamFactory;
import io.airbyte.workers.internal.book_keeping.AirbyteMessageTracker;
import io.airbyte.workers.internal.sync_persistence.SyncPersistenceFactory;
import io.airbyte.workers.process.AirbyteIntegrationLauncher;
import io.airbyte.workers.process.KubePodProcess;
import io.airbyte.workers.process.ProcessFactory;
import io.airbyte.workers.sync.ReplicationLauncherWorker;
import java.lang.invoke.MethodHandles;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs replication worker.
 */
public class ReplicationJobOrchestrator implements JobOrchestrator<StandardSyncInput> {

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  private final ProcessFactory processFactory;
  private final Configs configs;
  private final FeatureFlags featureFlags;
  private final FeatureFlagClient featureFlagClient;
  private final AirbyteMessageSerDeProvider serDeProvider;
  private final AirbyteProtocolVersionedMigratorFactory migratorFactory;
  private final JobRunConfig jobRunConfig;
  private final SourceApi sourceApi;
  private final DestinationApi destinationApi;
  private final SourceDefinitionApi sourceDefinitionApi;
  private final SyncPersistenceFactory syncPersistenceFactory;

  public ReplicationJobOrchestrator(final Configs configs,
                                    final ProcessFactory processFactory,
                                    final FeatureFlags featureFlags,
                                    final FeatureFlagClient featureFlagClient,
                                    final AirbyteMessageSerDeProvider serDeProvider,
                                    final AirbyteProtocolVersionedMigratorFactory migratorFactory,
                                    final JobRunConfig jobRunConfig,
                                    final SourceApi sourceApi,
                                    final DestinationApi destinationApi,
                                    final SourceDefinitionApi sourceDefinitionApi,
                                    final SyncPersistenceFactory syncPersistenceFactory) {
    this.configs = configs;
    this.processFactory = processFactory;
    this.featureFlags = featureFlags;
    this.featureFlagClient = featureFlagClient;
    this.serDeProvider = serDeProvider;
    this.migratorFactory = migratorFactory;
    this.jobRunConfig = jobRunConfig;
    this.sourceApi = sourceApi;
    this.destinationApi = destinationApi;
    this.sourceDefinitionApi = sourceDefinitionApi;
    this.syncPersistenceFactory = syncPersistenceFactory;
  }

  @Override
  public String getOrchestratorName() {
    return "Replication";
  }

  @Override
  public Class<StandardSyncInput> getInputClass() {
    return StandardSyncInput.class;
  }

  @Trace(operationName = JOB_ORCHESTRATOR_OPERATION_NAME)
  @Override
  public Optional<String> runJob() throws Exception {
    final var syncInput = readInput();

    final var sourceLauncherConfig = JobOrchestrator.readAndDeserializeFile(
        Path.of(KubePodProcess.CONFIG_DIR, ReplicationLauncherWorker.INIT_FILE_SOURCE_LAUNCHER_CONFIG),
        IntegrationLauncherConfig.class);

    final var destinationLauncherConfig = JobOrchestrator.readAndDeserializeFile(
        Path.of(KubePodProcess.CONFIG_DIR, ReplicationLauncherWorker.INIT_FILE_DESTINATION_LAUNCHER_CONFIG),
        IntegrationLauncherConfig.class);
    log.info("sourceLauncherConfig is: " + sourceLauncherConfig.toString());

    ApmTraceUtils.addTagsToTrace(
        Map.of(JOB_ID_KEY, jobRunConfig.getJobId(),
            DESTINATION_DOCKER_IMAGE_KEY, destinationLauncherConfig.getDockerImage(),
            SOURCE_DOCKER_IMAGE_KEY, sourceLauncherConfig.getDockerImage()));

    // At this moment, if either source or destination is from custom connector image, we will put all
    // jobs into isolated pool to run.
    final boolean useIsolatedPool = sourceLauncherConfig.getIsCustomConnector() || destinationLauncherConfig.getIsCustomConnector();
    log.info("Setting up source launcher...");
    final var sourceLauncher = new AirbyteIntegrationLauncher(
        sourceLauncherConfig.getJobId(),
        Math.toIntExact(sourceLauncherConfig.getAttemptId()),
        sourceLauncherConfig.getDockerImage(),
        processFactory,
        syncInput.getSourceResourceRequirements(),
        sourceLauncherConfig.getAllowedHosts(),
        useIsolatedPool,
        featureFlags);

    log.info("Setting up destination launcher...");
    final var destinationLauncher = new AirbyteIntegrationLauncher(
        destinationLauncherConfig.getJobId(),
        Math.toIntExact(destinationLauncherConfig.getAttemptId()),
        destinationLauncherConfig.getDockerImage(),
        processFactory,
        syncInput.getDestinationResourceRequirements(),
        destinationLauncherConfig.getAllowedHosts(),
        useIsolatedPool,
        featureFlags);

    log.info("Setting up source...");

    final UUID sourceDefinitionId = sourceApi.getSource(new SourceIdRequestBody().sourceId(syncInput.getSourceId())).getSourceDefinitionId();

    final long maxSecondsBetweenMessages = AirbyteApiClient.retryWithJitter(() -> sourceDefinitionApi
        .getSourceDefinition(new SourceDefinitionIdRequestBody().sourceDefinitionId(sourceDefinitionId)), "get the source definition")
        .getMaxSecondsBetweenMessages();
    // reset jobs use an empty source to induce resetting all data in destination.
    final HeartbeatMonitor heartbeatMonitor = new HeartbeatMonitor(Duration.ofSeconds(maxSecondsBetweenMessages));

    final var airbyteSource =
        WorkerConstants.RESET_JOB_SOURCE_DOCKER_IMAGE_STUB.equals(sourceLauncherConfig.getDockerImage()) ? new EmptyAirbyteSource(
            featureFlags.useStreamCapableState())
            : new DefaultAirbyteSource(sourceLauncher,
                getStreamFactory(sourceLauncherConfig.getProtocolVersion(), syncInput.getCatalog(), DefaultAirbyteSource.CONTAINER_LOG_MDC_BUILDER),
                heartbeatMonitor,
                migratorFactory.getProtocolSerializer(sourceLauncherConfig.getProtocolVersion()),
                featureFlags);

    MetricClientFactory.initialize(MetricEmittingApps.WORKER);
    final var metricClient = MetricClientFactory.getMetricClient();
    final var metricReporter = new WorkerMetricReporter(metricClient,
        sourceLauncherConfig.getDockerImage());

    try (final HeartbeatTimeoutChaperone heartbeatTimeoutChaperone = new HeartbeatTimeoutChaperone(heartbeatMonitor,
        HeartbeatTimeoutChaperone.DEFAULT_TIMEOUT_CHECK_DURATION, featureFlagClient, syncInput.getWorkspaceId(), syncInput.getConnectionId(),
        MetricClientFactory.getMetricClient())) {

      log.info("Setting up replication worker...");
      final UUID workspaceId = syncInput.getWorkspaceId();
      // NOTE: we apply field selection if the feature flag client says so (recommended) or the old
      // environment-variable flags say so (deprecated).
      // The latter FeatureFlagHelper will be removed once the flag client is fully deployed.
      final boolean fieldSelectionEnabled = workspaceId != null
          && (featureFlagClient.enabled(FieldSelectionEnabled.INSTANCE, new Workspace(workspaceId))
              || FeatureFlagHelper.isFieldSelectionEnabledForWorkspace(featureFlags, workspaceId));
      final boolean heartbeatTimeoutEnabled = workspaceId != null
          && featureFlagClient.enabled(ShouldStartHeartbeatMonitoring.INSTANCE, new Workspace(workspaceId));
      final var replicationWorker = new DefaultReplicationWorker(
          jobRunConfig.getJobId(),
          Math.toIntExact(jobRunConfig.getAttemptId()),
          airbyteSource,
          new NamespacingMapper(syncInput.getNamespaceDefinition(), syncInput.getNamespaceFormat(), syncInput.getPrefix()),
          new DefaultAirbyteDestination(destinationLauncher,
              getStreamFactory(destinationLauncherConfig.getProtocolVersion(), syncInput.getCatalog(),
                  DefaultAirbyteDestination.CONTAINER_LOG_MDC_BUILDER),
              new VersionedAirbyteMessageBufferedWriterFactory(serDeProvider, migratorFactory, destinationLauncherConfig.getProtocolVersion(),
                  Optional.of(syncInput.getCatalog())),
              migratorFactory.getProtocolSerializer(destinationLauncherConfig.getProtocolVersion())),
          new AirbyteMessageTracker(featureFlags),
          syncPersistenceFactory,
          new RecordSchemaValidator(WorkerUtils.mapStreamNamesToSchemas(syncInput),
              featureFlagClient.enabled(PerfBackgroundJsonValidation.INSTANCE, new Workspace(syncInput.getWorkspaceId()))),
          metricReporter,
          new ConnectorConfigUpdater(sourceApi, destinationApi),
          fieldSelectionEnabled,
          heartbeatTimeoutEnabled,
          heartbeatTimeoutChaperone);

      log.info("Running replication worker...");
      final var jobRoot = TemporalUtils.getJobRoot(configs.getWorkspaceRoot(),
          jobRunConfig.getJobId(), jobRunConfig.getAttemptId());
      final ReplicationOutput replicationOutput = replicationWorker.run(syncInput, jobRoot);

      log.info("Returning output...");
      return Optional.of(Jsons.serialize(replicationOutput));
    }
  }

  private AirbyteStreamFactory getStreamFactory(final Version protocolVersion,
                                                final ConfiguredAirbyteCatalog configuredAirbyteCatalog,
                                                final MdcScope.Builder mdcScope) {
    return protocolVersion != null
        ? new VersionedAirbyteStreamFactory<>(serDeProvider, migratorFactory, protocolVersion, Optional.of(configuredAirbyteCatalog), mdcScope,
            Optional.of(RuntimeException.class))
        : new DefaultAirbyteStreamFactory(mdcScope);
  }

}
