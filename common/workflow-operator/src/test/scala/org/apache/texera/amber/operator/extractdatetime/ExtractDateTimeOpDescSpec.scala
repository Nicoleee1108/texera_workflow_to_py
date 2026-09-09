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

import org.apache.texera.amber.core.executor.OpExecWithClassName
import org.apache.texera.amber.core.tuple.{Attribute, AttributeType, Schema, Tuple}
import org.apache.texera.amber.core.virtualidentity.{ExecutionIdentity, WorkflowIdentity}
import org.apache.texera.amber.core.workflow.PortIdentity
import org.apache.texera.amber.operator.metadata.OperatorGroupConstants
import org.apache.texera.amber.util.JSONUtils.objectMapper
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.Timestamp

class ExtractDateTimeOpDescSpec extends AnyFlatSpec with Matchers {

  private val workflowId = WorkflowIdentity(1L)
  private val executionId = ExecutionIdentity(1L)

  private val inputSchema = new Schema(
    new Attribute("id", AttributeType.INTEGER),
    new Attribute("ts", AttributeType.TIMESTAMP)
  )

  private def unit(field: DateTimeField, result: String): ExtractDateTimeUnit = {
    val u = new ExtractDateTimeUnit()
    u.field = field
    u.resultAttribute = result
    u
  }

  private def desc(units: ExtractDateTimeUnit*): ExtractDateTimeOpDesc = {
    val d = new ExtractDateTimeOpDesc
    d.attribute = "ts"
    d.extractions = units.toList
    d
  }

  private def outputSchema(d: ExtractDateTimeOpDesc): Schema =
    d.getPhysicalOp(workflowId, executionId)
      .propagateSchema
      .func(Map(PortIdentity() -> inputSchema))(PortIdentity())

  private def rowsOf(d: ExtractDateTimeOpDesc, moments: Option[String]*): Seq[Seq[Any]] = {
    val exec = new ExtractDateTimeOpExec(objectMapper.writeValueAsString(d))
    exec.open()
    val out = moments.zipWithIndex.map {
      case (moment, i) =>
        val b = Tuple.builder(inputSchema)
        b.add(inputSchema.getAttribute("id"), Int.box(i))
        b.add(inputSchema.getAttribute("ts"), moment.map(Timestamp.valueOf).orNull)
        exec.processTuple(b.build(), 0).next().getFields.toSeq
    }
    exec.close()
    out
  }

  "ExtractDateTimeOpDesc.operatorInfo" should "advertise the name and the Cleaning group" in {
    val info = (new ExtractDateTimeOpDesc).operatorInfo
    info.userFriendlyName shouldBe "Extract Date/Time Fields"
    info.operatorGroupName shouldBe OperatorGroupConstants.CLEANING_GROUP
    info.inputPorts should have length 1
    info.outputPorts should have length 1
  }

  "ExtractDateTimeOpDesc.getPhysicalOp" should "wire ExtractDateTimeOpExec" in {
    (new ExtractDateTimeOpDesc)
      .getPhysicalOp(workflowId, executionId)
      .opExecInitInfo match {
      case OpExecWithClassName(className, _) =>
        className shouldBe "org.apache.texera.amber.operator.extractdatetime.ExtractDateTimeOpExec"
      case other => fail(s"unexpected executor: $other")
    }
  }

  "The output schema" should "append one INTEGER column per field, in the order asked" in {
    val schema = outputSchema(desc(unit(DateTimeField.YEAR, "y"), unit(DateTimeField.MONTH, "m")))
    schema.getAttributeNames shouldBe List("id", "ts", "y", "m")
    schema.getAttribute("y").getType shouldBe AttributeType.INTEGER
    schema.getAttribute("m").getType shouldBe AttributeType.INTEGER
  }

  it should "keep the input untouched when no field is asked for" in {
    outputSchema(desc()).getAttributeNames shouldBe List("id", "ts")
  }

  it should "refuse a result column the input already carries" in {
    a[RuntimeException] should be thrownBy outputSchema(desc(unit(DateTimeField.YEAR, "id")))
  }

  it should "refuse an empty result column name" in {
    a[RuntimeException] should be thrownBy outputSchema(desc(unit(DateTimeField.YEAR, "  ")))
  }

  // 2024-03-05 14:09:07 is a Tuesday in ISO week 10 of Q1, day 65 of the year.
  "The executor" should "read every field the way ISO-8601 states it" in {
    val d = desc(
      unit(DateTimeField.YEAR, "year"),
      unit(DateTimeField.QUARTER, "quarter"),
      unit(DateTimeField.MONTH, "month"),
      unit(DateTimeField.DAY, "day"),
      unit(DateTimeField.DAY_OF_WEEK, "dow"),
      unit(DateTimeField.DAY_OF_YEAR, "doy"),
      unit(DateTimeField.WEEK_OF_YEAR, "week"),
      unit(DateTimeField.HOUR, "hour"),
      unit(DateTimeField.MINUTE, "minute"),
      unit(DateTimeField.SECOND, "second")
    )
    val row = rowsOf(d, Some("2024-03-05 14:09:07")).head
    row.drop(2) shouldBe Seq(2024, 1, 3, 5, 2, 65, 10, 14, 9, 7)
  }

  it should "count Monday as 1 and Sunday as 7" in {
    val d = desc(unit(DateTimeField.DAY_OF_WEEK, "dow"))
    val week = Seq(
      "2024-03-04",
      "2024-03-05",
      "2024-03-06",
      "2024-03-07",
      "2024-03-08",
      "2024-03-09",
      "2024-03-10"
    ).map(day => Some(s"$day 00:00:00"))
    rowsOf(d, week: _*).map(_.last) shouldBe Seq(1, 2, 3, 4, 5, 6, 7)
  }

  // The operator adds columns; it says nothing about which rows belong, so a row
  // whose timestamp is empty keeps its place with the added columns empty.
  it should "leave the added columns empty for an empty timestamp, and keep the row" in {
    val d = desc(unit(DateTimeField.YEAR, "year"), unit(DateTimeField.MONTH, "month"))
    val rows = rowsOf(d, Some("2024-03-05 14:09:07"), None)
    rows should have length 2
    rows(1).drop(2) shouldBe Seq(null, null)
  }

  "The generated Python" should "state the ISO weekday, which pandas does not" in {
    val code = desc(unit(DateTimeField.DAY_OF_WEEK, "dow")).generateStandaloneCode()
    code should include("_texera_ts.dt.dayofweek + 1")
  }

  it should "hold every column name as an escaped literal" in {
    val d = new ExtractDateTimeOpDesc
    d.attribute = "a\"b"
    d.extractions = List(unit(DateTimeField.YEAR, "c\\d"))
    val code = d.generateStandaloneCode()
    code should include("""out1df["a\"b"]""")
    code should include("""out1df["c\\d"]""")
  }

  it should "copy the frame through when no field is asked for" in {
    desc().generateStandaloneCode() shouldBe "out1df = in1df.copy()"
  }
}
