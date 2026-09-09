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

package org.apache.texera.amber.operator.extractdatetime

import com.fasterxml.jackson.annotation.{JsonProperty, JsonPropertyDescription}
import com.kjetland.jackson.jsonSchema.annotations.{JsonSchemaInject, JsonSchemaTitle}
import org.apache.texera.amber.core.executor.OpExecWithClassName
import org.apache.texera.amber.core.tuple.{AttributeType, Schema}
import org.apache.texera.amber.core.virtualidentity.{ExecutionIdentity, WorkflowIdentity}
import org.apache.texera.amber.core.workflow._
import org.apache.texera.amber.operator.StandaloneCodeGenerator
import org.apache.texera.amber.operator.map.MapOpDesc
import org.apache.texera.amber.operator.metadata.annotations.AutofillAttributeName
import org.apache.texera.amber.operator.metadata.{OperatorGroupConstants, OperatorInfo}
import org.apache.texera.amber.pybuilder.PythonTemplateBuilder.pyStringLiteral
import org.apache.texera.amber.util.JSONUtils.objectMapper

@JsonSchemaInject(json = """
{
  "attributeTypeRules": {
    "attribute": {
      "enum": ["timestamp"]
    }
  }
}
""")
class ExtractDateTimeOpDesc extends MapOpDesc with StandaloneCodeGenerator {

  @JsonProperty(required = true)
  @JsonSchemaTitle("Attribute")
  @JsonPropertyDescription("timestamp column to read")
  @AutofillAttributeName
  var attribute: String = _

  @JsonProperty(required = true)
  @JsonSchemaTitle("Fields")
  @JsonPropertyDescription("parts of the timestamp to add as columns")
  var extractions: List[ExtractDateTimeUnit] = List.empty

  override def operatorInfo: OperatorInfo =
    OperatorInfo(
      userFriendlyName = "Extract Date/Time Fields",
      operatorDescription =
        "Read the year, month, weekday or another whole-number part out of a timestamp column",
      operatorGroupName = OperatorGroupConstants.CLEANING_GROUP,
      inputPorts = List(InputPort()),
      outputPorts = List(OutputPort())
    )

  /** The units, with the empty and the null cases answered once. */
  private def units: List[ExtractDateTimeUnit] =
    Option(extractions).getOrElse(List.empty).filter(_ != null)

  override def getPhysicalOp(
      workflowId: WorkflowIdentity,
      executionId: ExecutionIdentity
  ): PhysicalOp =
    PhysicalOp
      .oneToOnePhysicalOp(
        workflowId,
        executionId,
        operatorIdentifier,
        OpExecWithClassName(
          "org.apache.texera.amber.operator.extractdatetime.ExtractDateTimeOpExec",
          objectMapper.writeValueAsString(this)
        )
      )
      .withInputPorts(operatorInfo.inputPorts)
      .withOutputPorts(operatorInfo.outputPorts)
      .withPropagateSchema(
        SchemaPropagationFunc { inputSchemas: Map[PortIdentity, Schema] =>
          // Every field reads as a whole number, so the added columns are INTEGER
          // whichever fields were asked for. `add` refuses a name the input already
          // carries, which is how a collision is reported before the operator runs.
          val outputSchema = units.foldLeft(inputSchemas.values.head) { (schema, unit) =>
            schema.add(resultNameOf(unit), AttributeType.INTEGER)
          }
          Map(operatorInfo.outputPorts.head.id -> outputSchema)
        }
      )

  private def resultNameOf(unit: ExtractDateTimeUnit): String =
    Option(unit.getResultAttribute)
      .map(_.trim)
      .filter(_.nonEmpty)
      .getOrElse(throw new RuntimeException("Result attribute cannot be empty"))

  override def generateStandaloneCode(): String = {
    if (units.isEmpty) return "out1df = in1df.copy()"
    val source = pyStringLiteral(attribute)
    val lines = scala.collection.mutable.ArrayBuffer[String](
      "out1df = in1df.copy()",
      // The schema says TIMESTAMP, so a source that parsed its input has already
      // made this a datetime and the call is a no-op. It is still made, for a source
      // that handed the column over as text. NOT coerced: the engine reads a real
      // moment here, so a cell Python cannot read is a disagreement to raise rather
      // than to answer with an empty one.
      s"""_texera_ts = pd.to_datetime(out1df[$source])"""
    )
    units.foreach { unit =>
      val target = pyStringLiteral(resultNameOf(unit))
      // Int64 rather than int64: a NaT has no year, and only the nullable dtype
      // can hold the hole the engine leaves there.
      lines += s"""out1df[$target] = ${expressionFor(unit.getField)}.astype("Int64")"""
    }
    lines.mkString("\n")
  }

  /** The pandas reading of one field, stated in ISO terms where pandas does not.
    *
    * Weekday is the one that has to be said: pandas counts Monday as 0, ISO and
    * `java.time.DayOfWeek` count it as 1.
    */
  private def expressionFor(field: DateTimeField): String =
    field match {
      case DateTimeField.YEAR         => "_texera_ts.dt.year"
      case DateTimeField.QUARTER      => "_texera_ts.dt.quarter"
      case DateTimeField.MONTH        => "_texera_ts.dt.month"
      case DateTimeField.DAY          => "_texera_ts.dt.day"
      case DateTimeField.DAY_OF_WEEK  => "(_texera_ts.dt.dayofweek + 1)"
      case DateTimeField.DAY_OF_YEAR  => "_texera_ts.dt.dayofyear"
      case DateTimeField.WEEK_OF_YEAR => "_texera_ts.dt.isocalendar().week"
      case DateTimeField.HOUR         => "_texera_ts.dt.hour"
      case DateTimeField.MINUTE       => "_texera_ts.dt.minute"
      case DateTimeField.SECOND       => "_texera_ts.dt.second"
    }
}
