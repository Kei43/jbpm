/**
 * Copyright 2010 JBoss Inc
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

package org.jbpm.task.service.local.sync;

import org.jbpm.task.service.base.sync.TaskServiceLifeCycleBaseSyncTest;
import org.jbpm.task.service.local.LocalTaskService;

public class TaskServiceLifeCycleLocalSyncTest extends TaskServiceLifeCycleBaseSyncTest {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        client = new LocalTaskService(taskService);
    }

    protected void tearDown() throws Exception {
        super.tearDown();
        
    }
    
}
