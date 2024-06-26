/*
 * Copyright (c) 2020-2024 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.commons.converters;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.common.collect.Lists;
import io.airbyte.commons.text.Names;
import io.airbyte.protocol.models.AirbyteCatalog;
import io.airbyte.protocol.models.AirbyteStream;
import io.airbyte.protocol.models.CatalogHelpers;
import io.airbyte.protocol.models.Field;
import io.airbyte.protocol.models.JsonSchemaType;
import io.airbyte.protocol.models.SyncMode;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class CatalogClientConvertersTest {

  public static final String ID_FIELD_NAME = "id";
  private static final String STREAM_NAME = "users-data";
  private static final AirbyteStream STREAM = new AirbyteStream()
      .withName(STREAM_NAME)
      .withJsonSchema(
          CatalogHelpers.fieldsToJsonSchema(Field.of(ID_FIELD_NAME, JsonSchemaType.STRING)))
      .withDefaultCursorField(Lists.newArrayList(ID_FIELD_NAME))
      .withSourceDefinedCursor(false)
      .withSourceDefinedPrimaryKey(Collections.emptyList())
      .withSupportedSyncModes(List.of(SyncMode.FULL_REFRESH, SyncMode.INCREMENTAL));

  private static final io.airbyte.api.client.model.generated.AirbyteStream CLIENT_STREAM =
      new io.airbyte.api.client.model.generated.AirbyteStream(
          STREAM_NAME,
          CatalogHelpers.fieldsToJsonSchema(Field.of(ID_FIELD_NAME, JsonSchemaType.STRING)),
          List.of(io.airbyte.api.client.model.generated.SyncMode.FULL_REFRESH,
              io.airbyte.api.client.model.generated.SyncMode.INCREMENTAL),
          false,
          List.of(ID_FIELD_NAME),
          List.of(),
          null);
  private static final io.airbyte.api.client.model.generated.AirbyteStreamConfiguration CLIENT_DEFAULT_STREAM_CONFIGURATION =
      new io.airbyte.api.client.model.generated.AirbyteStreamConfiguration(
          io.airbyte.api.client.model.generated.SyncMode.FULL_REFRESH,
          io.airbyte.api.client.model.generated.DestinationSyncMode.APPEND,
          List.of(ID_FIELD_NAME),
          List.of(),
          Names.toAlphanumericAndUnderscore(STREAM_NAME),
          true,
          null,
          null,
          null,
          null,
          null,
          null);

  private static final AirbyteCatalog BASIC_MODEL_CATALOG = new AirbyteCatalog().withStreams(
      Lists.newArrayList(STREAM));

  private static final io.airbyte.api.client.model.generated.AirbyteCatalog EXPECTED_CLIENT_CATALOG =
      new io.airbyte.api.client.model.generated.AirbyteCatalog(
          List.of(
              new io.airbyte.api.client.model.generated.AirbyteStreamAndConfiguration(
                  CLIENT_STREAM,
                  CLIENT_DEFAULT_STREAM_CONFIGURATION)));

  @Test
  void testConvertToClientAPI() {
    assertEquals(EXPECTED_CLIENT_CATALOG,
        CatalogClientConverters.toAirbyteCatalogClientApi(BASIC_MODEL_CATALOG));
  }

  @Test
  void testConvertToProtocol() {
    assertEquals(BASIC_MODEL_CATALOG,
        CatalogClientConverters.toAirbyteProtocol(EXPECTED_CLIENT_CATALOG));
  }

}
