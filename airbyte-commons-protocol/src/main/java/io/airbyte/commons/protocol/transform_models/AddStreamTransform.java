/*
 * Copyright (c) 2020-2024 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.commons.protocol.transform_models;

import io.airbyte.protocol.models.StreamDescriptor;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * Represents the addition of an {@link io.airbyte.protocol.models.AirbyteStream} to a
 * {@link io.airbyte.protocol.models.AirbyteCatalog}.
 */
@AllArgsConstructor
@EqualsAndHashCode
@ToString
public class AddStreamTransform {

  private final StreamDescriptor streamDescriptor;

  public StreamDescriptor getStreamDescriptor() {
    return streamDescriptor;
  }

}
