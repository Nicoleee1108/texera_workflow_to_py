/**
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

import { Component, EventEmitter, Input, Output } from "@angular/core";
import { NgIf } from "@angular/common";
import { NzIconDirective } from "ng-zorro-antd/icon";
import { WorkflowActionService } from "../../../service/workflow-graph/model/workflow-action.service";
import { WorkflowResultService } from "../../../service/workflow-result/workflow-result.service";
import { ResultTableFrameComponent } from "../../result-panel/result-table-frame/result-table-frame.component";
import { VisualizationFrameContentComponent } from "../../visualization-panel-content/visualization-frame-content.component";
import { OperatorResultPreviewComponent } from "../operator-result-preview/operator-result-preview.component";

/**
 * An operator's result pinned next to it on the canvas. It shows the whole result, the way the
 * result panel would, and stays until it is closed. Dragging its header moves it away from the
 * operator; the editor keeps it at that offset when the operator or the canvas moves.
 */
@Component({
  selector: "texera-pinned-result-window",
  templateUrl: "./pinned-result-window.component.html",
  styleUrls: ["./pinned-result-window.component.scss"],
  imports: [
    NgIf,
    NzIconDirective,
    ResultTableFrameComponent,
    VisualizationFrameContentComponent,
    OperatorResultPreviewComponent,
  ],
})
export class PinnedResultWindowComponent {
  @Input() operatorId = "";
  @Output() closed = new EventEmitter<void>();
  @Output() moved = new EventEmitter<{ dx: number; dy: number }>();

  constructor(
    private workflowActionService: WorkflowActionService,
    private workflowResultService: WorkflowResultService
  ) {}

  get title(): string {
    const graph = this.workflowActionService.getTexeraGraph();
    if (!graph.hasOperator(this.operatorId)) return this.operatorId;
    const operator = graph.getOperator(this.operatorId);
    return operator.customDisplayName ?? operator.operatorType;
  }

  get kind(): "table" | "chart" | "other" {
    if (this.workflowResultService.getPaginatedResultService(this.operatorId)) return "table";
    if (this.workflowResultService.getResultService(this.operatorId)) return "chart";
    return "other";
  }

  /** Drag by the header. Reports how far the pointer moved, so the editor can keep the offset. */
  startDrag(event: MouseEvent): void {
    if ((event.target as HTMLElement).closest("button")) return;
    event.preventDefault();
    let lastX = event.clientX;
    let lastY = event.clientY;
    const move = (e: MouseEvent) => {
      this.moved.emit({ dx: e.clientX - lastX, dy: e.clientY - lastY });
      lastX = e.clientX;
      lastY = e.clientY;
    };
    const stop = () => {
      window.removeEventListener("mousemove", move);
      window.removeEventListener("mouseup", stop);
    };
    window.addEventListener("mousemove", move);
    window.addEventListener("mouseup", stop);
  }
}
