/*
 * Copyright 2014 MovingBlocks
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.terasology.workstation.system;

import org.terasology.engine.Time;
import org.terasology.entitySystem.entity.EntityManager;
import org.terasology.entitySystem.entity.EntityRef;
import org.terasology.entitySystem.entity.lifecycleEvents.OnAddedComponent;
import org.terasology.entitySystem.event.ReceiveEvent;
import org.terasology.entitySystem.systems.BaseComponentSystem;
import org.terasology.entitySystem.systems.RegisterMode;
import org.terasology.entitySystem.systems.RegisterSystem;
import org.terasology.logic.delay.DelayedActionTriggeredEvent;
import org.terasology.monitoring.PerformanceMonitor;
import org.terasology.registry.In;
import org.terasology.workstation.component.WorkstationComponent;
import org.terasology.workstation.component.WorkstationProcessingComponent;
import org.terasology.workstation.event.WorkstationProcessRequest;
import org.terasology.workstation.event.WorkstationStateChanged;
import org.terasology.workstation.process.WorkstationProcess;
import org.terasology.world.block.BlockComponent;

import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.Map;

/**
 * @author Marcin Sciesinski <marcins78@gmail.com>
 */
@RegisterSystem(RegisterMode.AUTHORITY)
public class WorkstationAuthoritySystem extends BaseComponentSystem {
    @In
    private WorkstationRegistry workstationRegistry;
    @In
    private Time time;
    @In
    private EntityManager entityManager;

    private boolean executingProcess;

    // I would rather use LinkedHashSet, however cannot due to PojoEntityRef's hashcode changing when it is being destroyed.
    private Deque<EntityRef> pendingWorkstationChecks = new LinkedList<>();

    @ReceiveEvent
    public void machineAdded(OnAddedComponent event, EntityRef workstation, WorkstationComponent workstationComponent, BlockComponent block) {
        pendingWorkstationChecks.add(workstation);
        startProcessingIfNotExecuting();
    }

    @ReceiveEvent
    public void automaticProcessingStateChanged(WorkstationStateChanged event, EntityRef workstation, WorkstationComponent workstationComponent) {
        pendingWorkstationChecks.add(workstation);
        startProcessingIfNotExecuting();
    }

    @ReceiveEvent
    public void finishProcessing(DelayedActionTriggeredEvent event, EntityRef workstation, WorkstationComponent workstationComp,
                                 WorkstationProcessingComponent workstationProcessing) {
        PerformanceMonitor.startActivity("Workstation - finishing process");
        executingProcess = true;
        try {
            String actionId = event.getActionId();
            if (actionId.equals(WorkstationUtils.WORKSTATION_PROCESSING)) {
                long gameTime = time.getGameTimeInMs();
                Map<String, WorkstationProcessingComponent.ProcessDef> processesCopy = new HashMap<>(workstationProcessing.processes);
                for (Map.Entry<String, WorkstationProcessingComponent.ProcessDef> processes : processesCopy.entrySet()) {
                    WorkstationProcessingComponent.ProcessDef processDef = processes.getValue();
                    if (processDef.processingFinishTime <= gameTime) {
                        final WorkstationProcess workstationProcess = workstationRegistry.
                                getWorkstationProcessById(workstationComp.supportedProcessTypes.keySet(), processDef.processingProcessId);
                        WorkstationUtils.finishProcessing(workstation, workstation, workstationProcess);
                    }
                }

                pendingWorkstationChecks.add(workstation);
                processPendingChecks();
            }
        } finally {
            executingProcess = false;
            PerformanceMonitor.endActivity();
        }
    }

    @ReceiveEvent
    public void manualWorkstationProcess(WorkstationProcessRequest event, EntityRef instigator) {
        EntityRef workstation = event.getWorkstation();
        String processId = event.getProcessId();
        WorkstationComponent workstationComp = workstation.getComponent(WorkstationComponent.class);

        if (workstationComp != null) {
            WorkstationProcess process = workstationRegistry.getWorkstationProcessById(workstationComp.supportedProcessTypes.keySet(), processId);
            if (process != null) {
                String processType = process.getProcessType();

                WorkstationProcessingComponent workstationProcessing = workstation.getComponent(WorkstationProcessingComponent.class);
                // It's not processing anything, or not processing this type of process
                if (workstationProcessing == null || !workstationProcessing.processes.containsKey(processType)) {
                    executingProcess = true;
                    try {
                        WorkstationUtils.startProcessingManual(instigator, workstation, process, event, time.getGameTimeInMs());
                    } finally {
                        executingProcess = false;
                    }
                }
            }
        }
    }

    private void startProcessingIfNotExecuting() {
        // This is to avoid a new process starting in a middle of some other process executing
        if (!executingProcess) {
            executingProcess = true;
            try {
                processPendingChecks();
            } finally {
                executingProcess = false;
            }
        }
    }

    private void processPendingChecks() {
        PerformanceMonitor.startActivity("Workstation - processing pending checks");
        try {
            while (!pendingWorkstationChecks.isEmpty()) {
                EntityRef workstation = extractFirstPendingWorkstation();
                if (workstation.exists()) {
                    WorkstationComponent workstationComp = workstation.getComponent(WorkstationComponent.class);
                    if (workstationComp != null) {
                        processIfHasPendingAutomaticProcesses(workstation, workstationComp);
                    }
                }
            }
        } finally {
            PerformanceMonitor.endActivity();
        }
    }

    private EntityRef extractFirstPendingWorkstation() {
        return pendingWorkstationChecks.removeFirst();
    }

    private void processIfHasPendingAutomaticProcesses(EntityRef entity, WorkstationComponent workstation) {
        Map<String, Boolean> possibleProcesses = new LinkedHashMap<>(workstation.supportedProcessTypes);

        // Filter out those currently processing
        WorkstationProcessingComponent processing = entity.getComponent(WorkstationProcessingComponent.class);
        if (processing != null) {
            for (String processed : processing.processes.keySet()) {
                possibleProcesses.remove(processed);
            }
        }

        // Filter out non-automatic
        for (Map.Entry<String, Boolean> processDef : workstation.supportedProcessTypes.entrySet()) {
            if (!processDef.getValue()) {
                possibleProcesses.remove(processDef.getKey());
            }
        }

        for (WorkstationProcess workstationProcess : workstationRegistry.getWorkstationProcesses(possibleProcesses.keySet())) {
            if (possibleProcesses.get(workstationProcess.getProcessType())) {
                WorkstationUtils.startProcessingAutomatic(entity, workstationProcess, time.getGameTimeInMs());
            }
        }
    }
}
