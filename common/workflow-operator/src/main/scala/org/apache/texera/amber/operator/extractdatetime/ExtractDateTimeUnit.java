/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.texera.amber.operator.extractdatetime;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.kjetland.jackson.jsonSchema.annotations.JsonSchemaInject;
import com.kjetland.jackson.jsonSchema.annotations.JsonSchemaTitle;

/** One field to read out of the operator's timestamp column, and the column to put it in. */
public class ExtractDateTimeUnit {

    @JsonProperty(required = true)
    @JsonSchemaTitle("Field")
    @JsonPropertyDescription("part of the timestamp to read")
    public DateTimeField field;

    @JsonProperty(required = true)
    @JsonSchemaTitle("Result attribute")
    @JsonPropertyDescription("name of the column to hold it")
    // No `examples`: two rows are two new columns, and a shared sample would name
    // them alike, which the output schema refuses.
    @JsonSchemaInject(json = "{\"pattern\": \"^\\\\S(.*\\\\S)?$\"}")
    public String resultAttribute;

    public DateTimeField getField() {
        return this.field;
    }

    public String getResultAttribute() {
        return this.resultAttribute;
    }
}
