/*
 * Copyright (c) 2023 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.server.apis.publicapi.helpers

import com.cronutils.model.Cron
import com.cronutils.model.CronType.QUARTZ
import com.cronutils.model.definition.CronDefinition
import com.cronutils.model.definition.CronDefinitionBuilder
import com.cronutils.parser.CronParser
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.google.common.annotations.VisibleForTesting
import io.airbyte.api.model.generated.AirbyteCatalog
import io.airbyte.api.model.generated.AirbyteStream
import io.airbyte.api.model.generated.AirbyteStreamAndConfiguration
import io.airbyte.api.model.generated.AirbyteStreamConfiguration
import io.airbyte.api.model.generated.DestinationSyncMode
import io.airbyte.api.model.generated.SyncMode
import io.airbyte.api.problems.throwable.generated.UnexpectedProblem
import io.airbyte.commons.server.errors.problems.ConnectionConfigurationProblem
import io.airbyte.commons.server.errors.problems.ConnectionConfigurationProblem.Companion.duplicateStream
import io.airbyte.commons.server.errors.problems.ConnectionConfigurationProblem.Companion.invalidStreamName
import io.airbyte.public_api.model.generated.AirbyteApiConnectionSchedule
import io.airbyte.public_api.model.generated.ConnectionSyncModeEnum
import io.airbyte.public_api.model.generated.ScheduleTypeEnum
import io.airbyte.public_api.model.generated.SelectedFieldInfo
import io.airbyte.public_api.model.generated.StreamConfiguration
import io.airbyte.public_api.model.generated.StreamConfigurations
import io.airbyte.server.apis.publicapi.mappers.ConnectionReadMapper
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import java.io.IOException

/**
 * Does everything necessary to both build and validate the AirbyteCatalog.
 */
object AirbyteCatalogHelper {
  private val cronDefinition: CronDefinition = CronDefinitionBuilder.instanceDefinitionFor(QUARTZ)
  private val parser: CronParser = CronParser(cronDefinition)
  private val log = LoggerFactory.getLogger(AirbyteCatalogHelper.javaClass)
  private const val MAX_LENGTH_OF_CRON = 7

  /**
   * Check whether stream configurations exist.
   *
   * @param streamConfigurations StreamConfigurations from conneciton create/update request
   * @return true if they exist, false if they don't
   */
  fun hasStreamConfigurations(streamConfigurations: StreamConfigurations?): Boolean {
    return !streamConfigurations?.streams.isNullOrEmpty()
  }

  /**
   * Just set a config to be full refresh overwrite.
   *
   * @param config config to be set
   */
  fun updateConfigDefaultFullRefreshOverwrite(config: AirbyteStreamConfiguration?): AirbyteStreamConfiguration {
    val updatedStreamConfiguration = AirbyteStreamConfiguration()
    config?.let {
      updatedStreamConfiguration.aliasName = config.aliasName
      updatedStreamConfiguration.cursorField = config.cursorField
      updatedStreamConfiguration.fieldSelectionEnabled = config.fieldSelectionEnabled
      updatedStreamConfiguration.selected = config.selected
      updatedStreamConfiguration.selectedFields = config.selectedFields
      updatedStreamConfiguration.suggested = config.suggested
    }
    updatedStreamConfiguration.destinationSyncMode = DestinationSyncMode.OVERWRITE
    updatedStreamConfiguration.syncMode = SyncMode.FULL_REFRESH
    return updatedStreamConfiguration
  }

  /**
   * Given an airbyte catalog, set all streams to be full refresh overwrite.
   *
   * @param airbyteCatalog The catalog to be modified
   */
  fun updateAllStreamsFullRefreshOverwrite(airbyteCatalog: AirbyteCatalog): AirbyteCatalog {
    val updatedAirbyteCatalog = AirbyteCatalog()
    updatedAirbyteCatalog.streams =
      airbyteCatalog.streams.stream().map { stream: AirbyteStreamAndConfiguration ->
        val updatedAirbyteStreamAndConfiguration = AirbyteStreamAndConfiguration()
        updatedAirbyteStreamAndConfiguration.config = updateConfigDefaultFullRefreshOverwrite(stream.config)
        updatedAirbyteStreamAndConfiguration.stream = stream.stream
        updatedAirbyteStreamAndConfiguration
      }.toList()

    return updatedAirbyteCatalog
  }

  /**
   * Given a reference catalog and a user's passed in streamConfigurations, ensure valid streams or
   * throw a problem to be returned to the user.
   *
   * @param referenceCatalog - catalog, usually from discoverSourceSchema
   * @param streamConfigurations - configurations passed in by the user.
   * @return boolean so we can callWithTracker
   */
  fun validateStreams(
    referenceCatalog: AirbyteCatalog,
    streamConfigurations: StreamConfigurations,
  ): Boolean {
    val validStreams = getValidStreams(referenceCatalog)
    val alreadyConfiguredStreams: MutableSet<String> = HashSet()
    for (streamConfiguration in streamConfigurations.streams) {
      if (!validStreams.containsKey(streamConfiguration.name)) {
        throw invalidStreamName(validStreams.keys)
      } else if (alreadyConfiguredStreams.contains(streamConfiguration.name)) {
        throw duplicateStream(streamConfiguration.name)
      }
      alreadyConfiguredStreams.add(streamConfiguration.name)
    }
    return true
  }

  /**
   * Validate field selection for a single stream.
   *
   * @param streamConfiguration The configuration input of a specific stream provided by the caller.
   * @param sourceStream The immutable schema defined by the source
   */
  @VisibleForTesting
  fun validateFieldSelection(
    streamConfiguration: StreamConfiguration,
    sourceStream: AirbyteStream,
  ) {
    if (streamConfiguration.selectedFields.isNullOrEmpty()) {
      log.debug("No fields selected specifically. Bypass validation.")
      return
    }

    val allSelectedFields = streamConfiguration.selectedFields.mapNotNull { it.fieldPath?.firstOrNull() }
    // 1. Avoid duplicate fields selection.
    val allSelectedFieldsSet = allSelectedFields.toSet()
    if (allSelectedFields.size != allSelectedFieldsSet.size) {
      throw ConnectionConfigurationProblem.duplicateFieldsSelected(sourceStream.name)
    }
    // 2. Avoid non-existing fields selection.
    val allTopLevelStreamFields = getStreamTopLevelFields(sourceStream.jsonSchema).toSet()
    require(allSelectedFields.all { it in allTopLevelStreamFields }) {
      throw ConnectionConfigurationProblem.invalidFieldName(sourceStream.name, allTopLevelStreamFields)
    }
    // 3. Selected fields must contain primary key(s).
    val primaryKeys = selectPrimaryKey(sourceStream, streamConfiguration)
    val primaryKeyFields = primaryKeys?.mapNotNull { it.firstOrNull() } ?: emptyList()
    require(primaryKeyFields.all { it in allSelectedFieldsSet }) {
      throw ConnectionConfigurationProblem.missingPrimaryKeySelected(sourceStream.name)
    }
    // 4. Selected fields must contain the cursor field.
    val cursorField = selectCursorField(sourceStream, streamConfiguration)
    if (!cursorField.isNullOrEmpty() && !allSelectedFieldsSet.contains(cursorField.first())) {
      // first element is the top level field, and it has to be present in selected fields
      throw ConnectionConfigurationProblem.missingCursorFieldSelected(sourceStream.name)
    }
  }

  /**
   * Given an AirbyteCatalog, return a map of valid streams where key == name and value == the stream
   * config.
   *
   * @param airbyteCatalog Airbyte catalog to pull streams out of
   * @return map of stream name: stream config
   */
  fun getValidStreams(airbyteCatalog: AirbyteCatalog): Map<String, AirbyteStreamAndConfiguration> {
    val validStreams: MutableMap<String, AirbyteStreamAndConfiguration> = HashMap()
    for (schemaStream in airbyteCatalog.streams) {
      validStreams[schemaStream.stream!!.name] = schemaStream
    }
    return validStreams
  }

  /**
   * Validate cron configuration for a given connectionschedule.
   *
   * @param connectionSchedule the schedule to validate
   * @return boolean, but mostly so we can callwithTracker.
   */
  fun validateCronConfiguration(connectionSchedule: @Valid AirbyteApiConnectionSchedule): Boolean {
    if (connectionSchedule != null) {
      if (connectionSchedule.scheduleType != null && connectionSchedule.scheduleType === ScheduleTypeEnum.CRON) {
        if (connectionSchedule.cronExpression == null) {
          throw ConnectionConfigurationProblem.missingCronExpression()
        }
        try {
          if (connectionSchedule.cronExpression.endsWith("UTC")) {
            connectionSchedule.cronExpression = connectionSchedule.cronExpression.replace("UTC", "").trim()
          }
          val cron: Cron = parser.parse(connectionSchedule.cronExpression)
          cron.validate()
          val cronStrings: List<String> = cron.asString().split(" ")
          // Ensure first value is not `*`, could be seconds or minutes value
          Integer.valueOf(cronStrings[0])
          if (cronStrings.size == MAX_LENGTH_OF_CRON) {
            // Ensure minutes value is not `*`
            Integer.valueOf(cronStrings[1])
          }
        } catch (e: NumberFormatException) {
          log.debug("Invalid cron expression: " + connectionSchedule.cronExpression)
          log.debug("NumberFormatException: $e")
          throw ConnectionConfigurationProblem.invalidCronExpressionUnderOneHour(connectionSchedule.cronExpression)
        } catch (e: IllegalArgumentException) {
          log.debug("Invalid cron expression: " + connectionSchedule.cronExpression)
          log.debug("IllegalArgumentException: $e")
          throw ConnectionConfigurationProblem.invalidCronExpression(connectionSchedule.cronExpression, e.message)
        }
      }
    }
    return true
    // validate that the cron expression is not more often than every hour due to product specs
    // check that the first seconds and hour values are not *
  }

  /**
   * Convert proto/object from public_api model to airbyte_api model.
   * */
  private fun selectedFieldInfoConverter(publicApiSelectedFieldInfo: SelectedFieldInfo): io.airbyte.api.model.generated.SelectedFieldInfo {
    return io.airbyte.api.model.generated.SelectedFieldInfo().apply {
      fieldPath = publicApiSelectedFieldInfo.fieldPath
    }
  }

  fun updateAirbyteStreamConfiguration(
    config: AirbyteStreamConfiguration,
    airbyteStream: AirbyteStream,
    streamConfiguration: StreamConfiguration,
  ): AirbyteStreamConfiguration {
    val updatedStreamConfiguration = AirbyteStreamConfiguration()
    // Set stream config as selected
    updatedStreamConfiguration.selected = true
    updatedStreamConfiguration.aliasName = config.aliasName
    updatedStreamConfiguration.fieldSelectionEnabled = config.fieldSelectionEnabled
    if (!streamConfiguration.selectedFields.isNullOrEmpty()) {
      // We will ignore the null or empty input and sync all fields by default,
      // which is consistent with Cloud UI where all fields are selected by default.
      updatedStreamConfiguration.selectedFields = streamConfiguration.selectedFields.map { selectedFieldInfoConverter(it) }
    }
    updatedStreamConfiguration.suggested = config.suggested

    if (streamConfiguration.syncMode == null) {
      updatedStreamConfiguration.syncMode = SyncMode.FULL_REFRESH
      updatedStreamConfiguration.destinationSyncMode = DestinationSyncMode.OVERWRITE
      updatedStreamConfiguration.cursorField = config.cursorField
      updatedStreamConfiguration.primaryKey = config.primaryKey
    } else {
      when (streamConfiguration.syncMode) {
        ConnectionSyncModeEnum.FULL_REFRESH_APPEND -> {
          updatedStreamConfiguration.syncMode = SyncMode.FULL_REFRESH
          updatedStreamConfiguration.destinationSyncMode = DestinationSyncMode.APPEND
          updatedStreamConfiguration.cursorField = config.cursorField
          updatedStreamConfiguration.primaryKey = config.primaryKey
        }

        ConnectionSyncModeEnum.INCREMENTAL_APPEND -> {
          updatedStreamConfiguration.syncMode(SyncMode.INCREMENTAL)
          updatedStreamConfiguration.destinationSyncMode(DestinationSyncMode.APPEND)
          updatedStreamConfiguration.cursorField(selectCursorField(airbyteStream, streamConfiguration))
          updatedStreamConfiguration.primaryKey(selectPrimaryKey(airbyteStream, streamConfiguration))
        }

        ConnectionSyncModeEnum.INCREMENTAL_DEDUPED_HISTORY -> {
          updatedStreamConfiguration.syncMode = SyncMode.INCREMENTAL
          updatedStreamConfiguration.destinationSyncMode = DestinationSyncMode.APPEND_DEDUP
          updatedStreamConfiguration.cursorField = selectCursorField(airbyteStream, streamConfiguration)
          updatedStreamConfiguration.primaryKey = selectPrimaryKey(airbyteStream, streamConfiguration)
        }

        else -> {
          updatedStreamConfiguration.syncMode = SyncMode.FULL_REFRESH
          updatedStreamConfiguration.destinationSyncMode = DestinationSyncMode.OVERWRITE
          updatedStreamConfiguration.cursorField = config.cursorField
          updatedStreamConfiguration.primaryKey = config.primaryKey
        }
      }
    }

    return updatedStreamConfiguration
  }

  private fun selectCursorField(
    airbyteStream: AirbyteStream,
    streamConfiguration: StreamConfiguration,
  ): List<String>? {
    return if (airbyteStream.sourceDefinedCursor != null && airbyteStream.sourceDefinedCursor!!) {
      airbyteStream.defaultCursorField
    } else if (streamConfiguration.cursorField != null && streamConfiguration.cursorField.isNotEmpty()) {
      streamConfiguration.cursorField
    } else {
      airbyteStream.defaultCursorField
    }
  }

  private fun selectPrimaryKey(
    airbyteStream: AirbyteStream,
    streamConfiguration: StreamConfiguration,
  ): List<List<String>>? {
    return (airbyteStream.sourceDefinedPrimaryKey ?: emptyList()).ifEmpty {
      streamConfiguration.primaryKey
    }
  }

  /**
   * Validates a stream's configurations and sets those configurations in the
   * `AirbyteStreamConfiguration` object. Logic comes from
   * https://docs.airbyte.com/understanding-airbyte/airbyte-protocol/#configuredairbytestream.
   *
   * @param streamConfiguration The configuration input of a specific stream provided by the caller.
   * @param validDestinationSyncModes All the valid destination sync modes for a destination
   * @param airbyteStream The immutable schema defined by the source
   * @return True if no exceptions. Needed so it can be used inside TrackingHelper.callWithTracker
   */
  fun validateStreamConfig(
    streamConfiguration: StreamConfiguration,
    validDestinationSyncModes: List<DestinationSyncMode?>,
    airbyteStream: AirbyteStream,
  ): Boolean {
    // 1. Validate field selection
    validateFieldSelection(streamConfiguration, airbyteStream)

    // 2. validate that sync and destination modes are valid
    if (streamConfiguration.syncMode == null) {
      return true
    }
    val validCombinedSyncModes: Set<ConnectionSyncModeEnum> = validCombinedSyncModes(airbyteStream.supportedSyncModes, validDestinationSyncModes)
    if (!validCombinedSyncModes.contains(streamConfiguration.syncMode)) {
      throw ConnectionConfigurationProblem.handleSyncModeProblem(
        streamConfiguration.syncMode,
        streamConfiguration.name,
        validCombinedSyncModes,
      )
    }

    when (streamConfiguration.syncMode) {
      ConnectionSyncModeEnum.INCREMENTAL_APPEND -> {
        validateCursorField(streamConfiguration.cursorField, airbyteStream)
      }

      ConnectionSyncModeEnum.INCREMENTAL_DEDUPED_HISTORY -> {
        validateCursorField(streamConfiguration.cursorField, airbyteStream)
        validatePrimaryKey(streamConfiguration.primaryKey, airbyteStream)
      }

      else -> {}
    }
    return true
  }

  private fun validateCursorField(
    cursorField: List<String>?,
    airbyteStream: AirbyteStream,
  ) {
    if (airbyteStream.sourceDefinedCursor != null && airbyteStream.sourceDefinedCursor!!) {
      if (!cursorField.isNullOrEmpty()) {
        // if cursor given is not empty and is NOT the same as the default, throw error
        if (java.util.Set.copyOf(cursorField) != java.util.Set.copyOf(airbyteStream.defaultCursorField)) {
          throw ConnectionConfigurationProblem.sourceDefinedCursorFieldProblem(airbyteStream.name, airbyteStream.defaultCursorField!!)
        }
      }
    } else {
      if (!cursorField.isNullOrEmpty()) {
        // validate cursor field
        val validCursorFields: List<List<String>> = getStreamFields(airbyteStream.jsonSchema!!)
        if (!validCursorFields.contains(cursorField)) {
          throw ConnectionConfigurationProblem.invalidCursorField(airbyteStream.name, validCursorFields)
        }
      } else {
        // no default or given cursor field
        if (airbyteStream.defaultCursorField == null || airbyteStream.defaultCursorField!!.isEmpty()) {
          throw ConnectionConfigurationProblem.missingCursorField(airbyteStream.name)
        }
      }
    }
  }

  private fun validatePrimaryKey(
    primaryKey: List<List<String>>?,
    airbyteStream: AirbyteStream,
  ) {
    // Validate that if a source defined primary key exists, that's the one we use.
    // Currently, UI only supports this and there's likely assumptions baked into the platform that mean this needs to be true.
    val sourceDefinedPrimaryKeyExists = !airbyteStream.sourceDefinedPrimaryKey.isNullOrEmpty()
    val configuredPrimaryKeyExists = !primaryKey.isNullOrEmpty()

    if (sourceDefinedPrimaryKeyExists && configuredPrimaryKeyExists) {
      if (airbyteStream.sourceDefinedPrimaryKey != primaryKey) {
        throw ConnectionConfigurationProblem.primaryKeyAlreadyDefined(airbyteStream.name, airbyteStream.sourceDefinedPrimaryKey)
      }
    }

    // Ensure that we've passed at least some kind of primary key
    val noPrimaryKey = !configuredPrimaryKeyExists && !sourceDefinedPrimaryKeyExists
    if (noPrimaryKey) {
      throw ConnectionConfigurationProblem.missingPrimaryKey(airbyteStream.name)
    }

    // Validate the actual key passed in
    val validPrimaryKey: List<List<String>> = getStreamFields(airbyteStream.jsonSchema!!)

    for (singlePrimaryKey in primaryKey!!) {
      if (!validPrimaryKey.contains(singlePrimaryKey)) { // todo double check if the .contains() for list of strings works as intended
        throw ConnectionConfigurationProblem.invalidPrimaryKey(airbyteStream.name, validPrimaryKey)
      }

      if (singlePrimaryKey.distinct() != singlePrimaryKey) {
        throw ConnectionConfigurationProblem.duplicatePrimaryKey(airbyteStream.name, primaryKey)
      }
    }
  }

  /**
   * Fetch a set off the valid combined sync modes given the valid source/destination sync modes.
   *
   * @param validSourceSyncModes - List of valid source sync modes
   * @param validDestinationSyncModes - list of valid destination sync modes
   * @return Set of valid ConnectionSyncModeEnum values
   */
  fun validCombinedSyncModes(
    validSourceSyncModes: List<SyncMode?>?,
    validDestinationSyncModes: List<DestinationSyncMode?>,
  ): Set<ConnectionSyncModeEnum> {
    val validCombinedSyncModes: MutableSet<ConnectionSyncModeEnum> = HashSet<ConnectionSyncModeEnum>()
    for (sourceSyncMode in validSourceSyncModes!!) {
      for (destinationSyncMode in validDestinationSyncModes) {
        val combinedSyncMode: ConnectionSyncModeEnum? =
          ConnectionReadMapper.syncModesToConnectionSyncModeEnum(sourceSyncMode, destinationSyncMode)
        // This is true when the supported sync modes include full_refresh and the destination supports
        // append_deduped
        // or when the sync modes include incremental and the destination supports overwrite
        if (combinedSyncMode != null) {
          validCombinedSyncModes.add(combinedSyncMode)
        }
      }
    }
    return validCombinedSyncModes
  }

  /**
   * Parses a connectorSchema to retrieve top level fields only, ignoring the nested fields.
   *
   * @param connectorSchema source or destination schema
   * @return A list of top level fields, ignoring the nested fields.
   */
  @VisibleForTesting
  private fun getStreamTopLevelFields(connectorSchema: JsonNode): List<String> {
    val yamlMapper = ObjectMapper(YAMLFactory())
    val streamFields: MutableList<String> = ArrayList()
    val spec: JsonNode =
      try {
        yamlMapper.readTree(connectorSchema.traverse())
      } catch (e: IOException) {
        log.error("Error getting stream fields from schema", e)
        throw UnexpectedProblem()
      }
    val fields = spec.fields()
    while (fields.hasNext()) {
      val (key, paths) = fields.next()
      if ("properties" == key) {
        val propertyFields = paths.fields()
        while (propertyFields.hasNext()) {
          val (propertyName, _) = propertyFields.next()
          streamFields.add(propertyName)
        }
      }
    }
    return streamFields.toList()
  }

  /**
   * Parses a connectorSchema to retrieve all the possible stream fields.
   *
   * @param connectorSchema source or destination schema
   * @return A list of stream fields, which are represented as list of strings since they can be
   * nested fields.
   */
  fun getStreamFields(connectorSchema: JsonNode): List<List<String>> {
    val yamlMapper = ObjectMapper(YAMLFactory())
    val streamFields: MutableList<List<String>> = ArrayList()
    val spec: JsonNode
    spec =
      try {
        yamlMapper.readTree<JsonNode>(connectorSchema.traverse())
      } catch (e: IOException) {
        log.error("Error getting stream fields from schema", e)
        throw UnexpectedProblem()
      }
    val fields = spec.fields()
    while (fields.hasNext()) {
      val (key, paths) = fields.next()
      if ("properties" == key) {
        val propertyFields = paths.fields()
        while (propertyFields.hasNext()) {
          val (propertyName, nestedProperties) = propertyFields.next()
          streamFields.add(java.util.List.of(propertyName))

          // retrieve nested paths
          for (entry in getStreamFields(nestedProperties)) {
            if (entry.isEmpty()) {
              continue
            }
            val streamFieldPath: MutableList<String> = ArrayList(java.util.List.of(propertyName))
            streamFieldPath.addAll(entry)
            streamFields.add(streamFieldPath)
          }
        }
      }
    }
    return streamFields.toList()
  }
}
