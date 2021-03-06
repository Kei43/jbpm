/*
 * Copyright 2012 JBoss by Red Hat.
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
package org.jbpm.task.service.persistence.variable;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;

import org.drools.impl.EnvironmentFactory;
import org.drools.marshalling.ObjectMarshallingStrategy;
import org.drools.marshalling.impl.ClassObjectMarshallingStrategyAcceptor;
import org.drools.marshalling.impl.SerializablePlaceholderResolverStrategy;
import org.drools.persistence.jpa.marshaller.JPAPlaceholderResolverStrategy;
import org.drools.process.instance.impl.WorkItemImpl;
import org.drools.runtime.Environment;
import org.drools.runtime.EnvironmentName;
import org.drools.runtime.process.WorkItemHandler;
import org.drools.runtime.process.WorkItemManager;
import org.jbpm.process.workitem.wsht.AsyncMinaHTWorkItemHandler;
import org.jbpm.process.workitem.wsht.MyObject;
import org.jbpm.task.AccessType;
import org.jbpm.task.AsyncTaskService;
import org.jbpm.task.BaseTest;
import org.jbpm.task.Status;
import org.jbpm.task.Task;
import org.jbpm.task.TestStatefulKnowledgeSession;
import org.jbpm.task.query.TaskSummary;
import org.jbpm.task.service.ContentData;
import org.jbpm.task.service.DefaultEscalatedDeadlineHandler;
import org.jbpm.task.service.DefaultUserInfo;
import org.jbpm.task.service.EscalatedDeadlineHandler;
import org.jbpm.task.service.mina.MinaTaskServer;
import org.jbpm.task.service.responsehandlers.BlockingGetContentResponseHandler;
import org.jbpm.task.service.responsehandlers.BlockingGetTaskResponseHandler;
import org.jbpm.task.service.responsehandlers.BlockingTaskOperationResponseHandler;
import org.jbpm.task.service.responsehandlers.BlockingTaskSummaryResponseHandler;
import org.jbpm.task.utils.ContentMarshallerHelper;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.subethamail.wiser.Wiser;


public class VariablePersistenceStrategiesASyncHTTest extends BaseTest {

    private static final int DEFAULT_WAIT_TIME = 5000;
    private static final int MANAGER_COMPLETION_WAIT_TIME = DEFAULT_WAIT_TIME;
    private static final int MANAGER_ABORT_WAIT_TIME = DEFAULT_WAIT_TIME;
    protected AsyncTaskService client;
    private WorkItemHandler handler;
    private EntityManagerFactory domainEmf;
    protected TestStatefulKnowledgeSession ksession = new TestStatefulKnowledgeSession();
    private MinaTaskServer server;
    private static Wiser wiser;
    protected Environment env;
    
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        env = EnvironmentFactory.newEnvironment();
        Environment domainEnv = EnvironmentFactory.newEnvironment();
        domainEmf = Persistence.createEntityManagerFactory("org.jbpm.persistence.example");
        
        domainEnv.set(EnvironmentName.ENTITY_MANAGER_FACTORY, domainEmf);
        env.set(EnvironmentName.OBJECT_MARSHALLING_STRATEGIES, new ObjectMarshallingStrategy[]{
                                                                    new JPAPlaceholderResolverStrategy(domainEnv),
                                                                    new SerializablePlaceholderResolverStrategy(ClassObjectMarshallingStrategyAcceptor.DEFAULT)});
        ksession.setEnvironment(env);
        

        
         super.setUp();
        server = new MinaTaskServer(taskService);
        Thread thread = new Thread(server);
        thread.start();
        System.out.println("Waiting for the MinaTask Server to come up");
        while (!server.isRunning()) {
        	System.out.print(".");
        	Thread.sleep( 50 );
        }

        
        
        
        AsyncMinaHTWorkItemHandler asyncWSHumanTaskHandler = new AsyncMinaHTWorkItemHandler(ksession);
        setClient(asyncWSHumanTaskHandler.getClient());
        setHandler(asyncWSHumanTaskHandler);
    }

    protected void tearDown() throws Exception {
        if (wiser != null) {
            wiser.stop();
        }
        ((AsyncMinaHTWorkItemHandler) getHandler()).dispose();
        getClient().disconnect();
        super.tearDown();
        server.stop();
    }

    public VariablePersistenceStrategiesASyncHTTest() {
    }

    @BeforeClass
    public static void setUpClass() throws Exception {
    }

    @AfterClass
    public static void tearDownClass() throws Exception {
    }

 

    @Test
    public void testTaskDataWithVPSJPAEntity() throws Exception {
        //JPA Entity
        EntityManager em = domainEmf.createEntityManager();
        em.getTransaction().begin();
        MyEntity myEntity = new MyEntity("This is a JPA Entity");
        em.persist(myEntity);
        em.getTransaction().commit();
        
        
        TestWorkItemManager manager = new TestWorkItemManager();
        ksession.setWorkItemManager(manager);
        WorkItemImpl workItem = new WorkItemImpl();
        workItem.setName("Human Task");
        workItem.setParameter("TaskName", "TaskName");
        workItem.setParameter("Comment", "Comment");
        workItem.setParameter("Priority", "10");
        workItem.setParameter("ActorId", "Darth Vader");
        workItem.setParameter("Content", myEntity);
        getHandler().executeWorkItem(workItem, manager);

        BlockingTaskSummaryResponseHandler taskSummaryResponseHandler = new BlockingTaskSummaryResponseHandler();
        getClient().getTasksAssignedAsPotentialOwner("Darth Vader", "en-UK", taskSummaryResponseHandler);
        List<TaskSummary> tasks = taskSummaryResponseHandler.getResults();
        assertEquals(1, tasks.size());
        TaskSummary taskSummary = tasks.get(0);
        assertEquals("TaskName", taskSummary.getName());
        assertEquals(10, taskSummary.getPriority());
        assertEquals("Comment", taskSummary.getDescription());
        assertEquals(Status.Reserved, taskSummary.getStatus());
        assertEquals("Darth Vader", taskSummary.getActualOwner().getId());

        BlockingGetTaskResponseHandler getTaskResponseHandler = new BlockingGetTaskResponseHandler();  
        getClient().getTask(taskSummary.getId(),getTaskResponseHandler);
        Task task = getTaskResponseHandler.getTask();
        
        assertEquals(AccessType.Inline, task.getTaskData().getDocumentAccessType());
        assertEquals(task.getTaskData().getProcessSessionId(), TestStatefulKnowledgeSession.testSessionId);
        long contentId = task.getTaskData().getDocumentContentId();
        assertTrue(contentId != -1);
        
        BlockingGetContentResponseHandler getContentResponseHandler = new BlockingGetContentResponseHandler();
        getClient().getContent(contentId,getContentResponseHandler);
        Object data = ContentMarshallerHelper.unmarshall(getContentResponseHandler.getContent().getContent() ,  ksession.getEnvironment());
        assertEquals(myEntity.getTest(), ((MyEntity)data).getTest());

        BlockingTaskOperationResponseHandler startResponseHandler = new BlockingTaskOperationResponseHandler();
        getClient().start(task.getId(), "Darth Vader", startResponseHandler);
        
        em.getTransaction().begin();
        MyEntity myEntity2 = new MyEntity("This is a JPA Entity 2");
        em.persist(myEntity2);
        em.getTransaction().commit();
        
        ContentData result = ContentMarshallerHelper.marshal(myEntity2, ksession.getEnvironment());
        BlockingTaskOperationResponseHandler completeResponseHandler = new BlockingTaskOperationResponseHandler();
        getClient().complete(task.getId(), "Darth Vader", result, completeResponseHandler);

        assertTrue(manager.waitTillCompleted(MANAGER_COMPLETION_WAIT_TIME));
        Map<String, Object> results = manager.getResults();
        assertNotNull(results);
        assertEquals("Darth Vader", results.get("ActorId"));
        assertEquals(myEntity2.getTest(), ((MyEntity)results.get("Result")).getTest());
    }
    
    @Test
    public void testTaskDataWithVPSSerializableObject() throws Exception {
        //Serializable Object
        MyObject myObject = new MyObject("This is a Serializable Object");
        
        TestWorkItemManager manager = new TestWorkItemManager();
        ksession.setWorkItemManager(manager);
        WorkItemImpl workItem = new WorkItemImpl();
        workItem.setName("Human Task");
        workItem.setParameter("TaskName", "TaskName");
        workItem.setParameter("Comment", "Comment");
        workItem.setParameter("Priority", "10");
        workItem.setParameter("ActorId", "Darth Vader");
        workItem.setParameter("Content", myObject);
        getHandler().executeWorkItem(workItem, manager);

        BlockingTaskSummaryResponseHandler taskSummaryResponseHandler = new BlockingTaskSummaryResponseHandler();
        getClient().getTasksAssignedAsPotentialOwner("Darth Vader", "en-UK", taskSummaryResponseHandler);
        List<TaskSummary> tasks = taskSummaryResponseHandler.getResults();
        assertEquals(1, tasks.size());
        TaskSummary taskSummary = tasks.get(0);
        assertEquals("TaskName", taskSummary.getName());
        assertEquals(10, taskSummary.getPriority());
        assertEquals("Comment", taskSummary.getDescription());
        assertEquals(Status.Reserved, taskSummary.getStatus());
        assertEquals("Darth Vader", taskSummary.getActualOwner().getId());

        BlockingGetTaskResponseHandler getTaskResponseHandler = new BlockingGetTaskResponseHandler();  
        getClient().getTask(taskSummary.getId(),getTaskResponseHandler);
        Task task = getTaskResponseHandler.getTask();
        assertEquals(AccessType.Inline, task.getTaskData().getDocumentAccessType());
        
        assertEquals(task.getTaskData().getProcessSessionId(), TestStatefulKnowledgeSession.testSessionId);
        long contentId = task.getTaskData().getDocumentContentId();
        assertTrue(contentId != -1);

        BlockingGetContentResponseHandler getContentResponseHandler = new BlockingGetContentResponseHandler();
        getClient().getContent(contentId,getContentResponseHandler);
        Object data = ContentMarshallerHelper.unmarshall(getContentResponseHandler.getContent().getContent(),  ksession.getEnvironment());
        assertEquals(myObject.getValue(), ((MyObject)data).getValue());

        BlockingTaskOperationResponseHandler startResponseHandler = new BlockingTaskOperationResponseHandler();
        getClient().start(task.getId(), "Darth Vader", startResponseHandler);
        
        
        MyObject myObject2 = new MyObject("This is a Serializable Object 2");
        
        
        ContentData result = ContentMarshallerHelper.marshal(myObject2, ksession.getEnvironment());
        BlockingTaskOperationResponseHandler completeResponseHandler = new BlockingTaskOperationResponseHandler();
        getClient().complete(task.getId(), "Darth Vader", result, completeResponseHandler);

        assertTrue(manager.waitTillCompleted(MANAGER_COMPLETION_WAIT_TIME));
        Map<String, Object> results = manager.getResults();
        assertNotNull(results);
        assertEquals("Darth Vader", results.get("ActorId"));
        assertEquals(myObject2.getValue(), ((MyObject)results.get("Result")).getValue());
    }

    @Test
    public void testTaskDataWithVPSandMAP() throws Exception {
        //JPA Entity
        EntityManager em = domainEmf.createEntityManager();
        em.getTransaction().begin();
        MyEntity myEntity = new MyEntity("This is a JPA Entity");
        em.persist(myEntity);
        em.getTransaction().commit();
        
        //Serializable Object
        MyObject myObject = new MyObject("This is a Serializable Object");
        
        Map<String, Object> content = new HashMap<String, Object>();
        content.put("myJPAEntity", myEntity);
        content.put("mySerializableObject", myObject);
        
        
        TestWorkItemManager manager = new TestWorkItemManager();
        ksession.setWorkItemManager(manager);
        WorkItemImpl workItem = new WorkItemImpl();
        workItem.setName("Human Task");
        workItem.setParameter("TaskName", "TaskName");
        workItem.setParameter("Comment", "Comment");
        workItem.setParameter("Priority", "10");
        workItem.setParameter("ActorId", "Darth Vader");
        workItem.setParameter("Content", content);
        getHandler().executeWorkItem(workItem, manager);

        BlockingTaskSummaryResponseHandler taskSummaryResponseHandler = new BlockingTaskSummaryResponseHandler();
        getClient().getTasksAssignedAsPotentialOwner("Darth Vader", "en-UK", taskSummaryResponseHandler);
        List<TaskSummary> tasks = taskSummaryResponseHandler.getResults();
        assertEquals(1, tasks.size());
        TaskSummary taskSummary = tasks.get(0);
        assertEquals("TaskName", taskSummary.getName());
        assertEquals(10, taskSummary.getPriority());
        assertEquals("Comment", taskSummary.getDescription());
        assertEquals(Status.Reserved, taskSummary.getStatus());
        assertEquals("Darth Vader", taskSummary.getActualOwner().getId());

        BlockingGetTaskResponseHandler getTaskResponseHandler = new BlockingGetTaskResponseHandler();  
        getClient().getTask(taskSummary.getId(),getTaskResponseHandler);
        Task task = getTaskResponseHandler.getTask();
        assertEquals(AccessType.Inline, task.getTaskData().getDocumentAccessType());
        assertEquals(task.getTaskData().getProcessSessionId(), TestStatefulKnowledgeSession.testSessionId);
        long contentId = task.getTaskData().getDocumentContentId();
        assertTrue(contentId != -1);

        BlockingGetContentResponseHandler getContentResponseHandler = new BlockingGetContentResponseHandler();
        getClient().getContent(contentId,getContentResponseHandler);
        Object data = ContentMarshallerHelper.unmarshall( getContentResponseHandler.getContent().getContent(),  ksession.getEnvironment());
        Map<String, Object> dataMap = (Map<String, Object>)data;
        
        assertEquals(myEntity.getTest(), ((MyEntity)dataMap.get("myJPAEntity")).getTest());
        assertEquals(myObject.getValue(), ((MyObject)dataMap.get("mySerializableObject")).getValue());
        
        BlockingTaskOperationResponseHandler startResponseHandler = new BlockingTaskOperationResponseHandler();
        getClient().start(task.getId(), "Darth Vader", startResponseHandler);
        
        Map<String, Object> results = new HashMap<String, Object>();
        em.getTransaction().begin();
        MyEntity myEntity2 = new MyEntity("This is a JPA Entity 2");
        em.persist(myEntity2);
        em.getTransaction().commit();
        results.put("myEntity2", myEntity2);
        MyObject myObject2 = new MyObject("This is a Serializable Object 2");
        results.put("myObject2", myObject2);
        
        ContentData result = ContentMarshallerHelper.marshal(results, ksession.getEnvironment());
        
        BlockingTaskOperationResponseHandler completeResponseHandler = new BlockingTaskOperationResponseHandler();
        getClient().complete(task.getId(), "Darth Vader", result, completeResponseHandler);

        assertTrue(manager.waitTillCompleted(MANAGER_COMPLETION_WAIT_TIME));
        Map<String, Object> managerResults = manager.getResults();
        assertNotNull(managerResults);
        assertEquals("Darth Vader", managerResults.get("ActorId"));
        assertEquals(myEntity2.getTest(), ((MyEntity)((Map)managerResults.get("Result")).get("myEntity2")).getTest());
        assertEquals(myEntity2.getTest(), ((MyEntity)managerResults.get("myEntity2")).getTest());
        assertEquals(myObject2.getValue(), ((MyObject)((Map)managerResults.get("Result")).get("myObject2")).getValue());
        assertEquals(myObject2.getValue(), ((MyObject)managerResults.get("myObject2")).getValue());
    }

    @Test
    public void testTaskDataWithVPSJPAEntityWithMarshal() throws Exception {
        //JPA Entity
        EntityManager em = domainEmf.createEntityManager();
        em.getTransaction().begin();
        MyEntity myEntity = new MyEntity("This is a JPA Entity");
        em.persist(myEntity);
        em.getTransaction().commit();

        TestWorkItemManager manager = new TestWorkItemManager();
        ksession.setWorkItemManager(manager);
        WorkItemImpl workItem = new WorkItemImpl();
        workItem.setName("Human Task");
        workItem.setParameter("TaskName", "TaskName");
        workItem.setParameter("Comment", "Comment");
        workItem.setParameter("Priority", "10");
        workItem.setParameter("ActorId", "Darth Vader");
        workItem.setParameter("Content", myEntity);
        getHandler().executeWorkItem(workItem, manager);

        BlockingTaskSummaryResponseHandler taskSummaryResponseHandler = new BlockingTaskSummaryResponseHandler();
        getClient().getTasksAssignedAsPotentialOwner("Darth Vader", "en-UK", taskSummaryResponseHandler);
        List<TaskSummary> tasks = taskSummaryResponseHandler.getResults();
        assertEquals(1, tasks.size());
        TaskSummary taskSummary = tasks.get(0);
        assertEquals("TaskName", taskSummary.getName());
        assertEquals(10, taskSummary.getPriority());
        assertEquals("Comment", taskSummary.getDescription());
        assertEquals(Status.Reserved, taskSummary.getStatus());
        assertEquals("Darth Vader", taskSummary.getActualOwner().getId());

        BlockingGetTaskResponseHandler getTaskResponseHandler = new BlockingGetTaskResponseHandler();  
        getClient().getTask(taskSummary.getId(),getTaskResponseHandler);
        Task task = getTaskResponseHandler.getTask();

        assertEquals(AccessType.Inline, task.getTaskData().getDocumentAccessType());
        assertEquals(task.getTaskData().getProcessSessionId(), TestStatefulKnowledgeSession.testSessionId);
        long contentId = task.getTaskData().getDocumentContentId();
        assertTrue(contentId != -1);

        BlockingGetContentResponseHandler getContentResponseHandler = new BlockingGetContentResponseHandler();
        getClient().getContent(contentId,getContentResponseHandler);
        Object data = ContentMarshallerHelper.unmarshall( getContentResponseHandler.getContent().getContent() ,  ksession.getEnvironment());
        assertEquals(myEntity.getTest(), ((MyEntity)data).getTest());

        BlockingTaskOperationResponseHandler startResponseHandler = new BlockingTaskOperationResponseHandler();
        getClient().start(task.getId(), "Darth Vader", startResponseHandler);

        em.getTransaction().begin();
        MyEntity myEntity2 = new MyEntity("This is a JPA Entity 2");
        em.persist(myEntity2);
        em.getTransaction().commit();

        ContentData result = ContentMarshallerHelper.marshal(myEntity2, ksession.getEnvironment());
        BlockingTaskOperationResponseHandler completeResponseHandler = new BlockingTaskOperationResponseHandler();
        getClient().complete(task.getId(), "Darth Vader", result, completeResponseHandler);

        assertTrue(manager.waitTillCompleted(MANAGER_COMPLETION_WAIT_TIME));
        Map<String, Object> results = manager.getResults();
        assertNotNull(results);
        assertEquals("Darth Vader", results.get("ActorId"));
        assertEquals(myEntity2.getTest(), ((MyEntity)results.get("Result")).getTest());
    }

    

    @Test
    public void testTaskDataWithVPSSerializableObjectWithMarshal() throws Exception {
        //Serializable Object
        MyObject myObject = new MyObject("This is a Serializable Object");

        TestWorkItemManager manager = new TestWorkItemManager();
        ksession.setWorkItemManager(manager);
        WorkItemImpl workItem = new WorkItemImpl();
        workItem.setName("Human Task");
        workItem.setParameter("TaskName", "TaskName");
        workItem.setParameter("Comment", "Comment");
        workItem.setParameter("Priority", "10");
        workItem.setParameter("ActorId", "Darth Vader");
        workItem.setParameter("Content", myObject);
        getHandler().executeWorkItem(workItem, manager);

        BlockingTaskSummaryResponseHandler taskSummaryResponseHandler = new BlockingTaskSummaryResponseHandler();
        getClient().getTasksAssignedAsPotentialOwner("Darth Vader", "en-UK", taskSummaryResponseHandler);
        List<TaskSummary> tasks = taskSummaryResponseHandler.getResults();
        assertEquals(1, tasks.size());
        TaskSummary taskSummary = tasks.get(0);
        assertEquals("TaskName", taskSummary.getName());
        assertEquals(10, taskSummary.getPriority());
        assertEquals("Comment", taskSummary.getDescription());
        assertEquals(Status.Reserved, taskSummary.getStatus());
        assertEquals("Darth Vader", taskSummary.getActualOwner().getId());

        BlockingGetTaskResponseHandler getTaskResponseHandler = new BlockingGetTaskResponseHandler();  
        getClient().getTask(taskSummary.getId(),getTaskResponseHandler);
        Task task = getTaskResponseHandler.getTask();
        assertEquals(AccessType.Inline, task.getTaskData().getDocumentAccessType());

        assertEquals(task.getTaskData().getProcessSessionId(), TestStatefulKnowledgeSession.testSessionId);
        long contentId = task.getTaskData().getDocumentContentId();
        assertTrue(contentId != -1);

        BlockingGetContentResponseHandler getContentResponseHandler = new BlockingGetContentResponseHandler();
        getClient().getContent(contentId,getContentResponseHandler);
        Object data = ContentMarshallerHelper.unmarshall( getContentResponseHandler.getContent().getContent() ,  ksession.getEnvironment());
        assertEquals(myObject.getValue(), ((MyObject)data).getValue());

        BlockingTaskOperationResponseHandler startResponseHandler = new BlockingTaskOperationResponseHandler();
        getClient().start(task.getId(), "Darth Vader", startResponseHandler);

        MyObject myObject2 = new MyObject("This is a Serializable Object 2");

        ContentData result = ContentMarshallerHelper.marshal(myObject2,  ksession.getEnvironment());
        BlockingTaskOperationResponseHandler completeResponseHandler = new BlockingTaskOperationResponseHandler();
        getClient().complete(task.getId(), "Darth Vader", result, completeResponseHandler);

        assertTrue(manager.waitTillCompleted(MANAGER_COMPLETION_WAIT_TIME));
        Map<String, Object> results = manager.getResults();
        assertNotNull(results);
        assertEquals("Darth Vader", results.get("ActorId"));
        assertEquals(myObject2.getValue(), ((MyObject)results.get("Result")).getValue());
    }



    @Test
    public void testTaskDataWithVPSandMAPWithMarshal() throws Exception {
        //JPA Entity
        EntityManager em = domainEmf.createEntityManager();
        em.getTransaction().begin();
        MyEntity myEntity = new MyEntity("This is a JPA Entity");
        em.persist(myEntity);
        em.getTransaction().commit();

        //Serializable Object
        MyObject myObject = new MyObject("This is a Serializable Object");
        
        Map<String, Object> content = new HashMap<String, Object>();
        content.put("myJPAEntity", myEntity);
        content.put("mySerializableObject", myObject);
        

        TestWorkItemManager manager = new TestWorkItemManager();
        ksession.setWorkItemManager(manager);
        WorkItemImpl workItem = new WorkItemImpl();
        workItem.setName("Human Task");
        workItem.setParameter("TaskName", "TaskName");
        workItem.setParameter("Comment", "Comment");
        workItem.setParameter("Priority", "10");
        workItem.setParameter("ActorId", "Darth Vader");
        workItem.setParameter("Content", content);
        getHandler().executeWorkItem(workItem, manager);

        BlockingTaskSummaryResponseHandler taskSummaryResponseHandler = new BlockingTaskSummaryResponseHandler();
        getClient().getTasksAssignedAsPotentialOwner("Darth Vader", "en-UK", taskSummaryResponseHandler);
        List<TaskSummary> tasks = taskSummaryResponseHandler.getResults();
        assertEquals(1, tasks.size());
        TaskSummary taskSummary = tasks.get(0);
        assertEquals("TaskName", taskSummary.getName());
        assertEquals(10, taskSummary.getPriority());
        assertEquals("Comment", taskSummary.getDescription());
        assertEquals(Status.Reserved, taskSummary.getStatus());
        assertEquals("Darth Vader", taskSummary.getActualOwner().getId());

        BlockingGetTaskResponseHandler getTaskResponseHandler = new BlockingGetTaskResponseHandler();  
        getClient().getTask(taskSummary.getId(),getTaskResponseHandler);
        Task task = getTaskResponseHandler.getTask();
        assertEquals(AccessType.Inline, task.getTaskData().getDocumentAccessType());
        assertEquals(task.getTaskData().getProcessSessionId(), TestStatefulKnowledgeSession.testSessionId);
        long contentId = task.getTaskData().getDocumentContentId();
        assertTrue(contentId != -1);

        BlockingGetContentResponseHandler getContentResponseHandler = new BlockingGetContentResponseHandler();
        getClient().getContent(contentId,getContentResponseHandler);
        Object data = ContentMarshallerHelper.unmarshall( getContentResponseHandler.getContent().getContent() , ksession.getEnvironment());
        Map<String, Object> dataMap = (Map<String, Object>)data;

        assertEquals(myEntity.getTest(), ((MyEntity)dataMap.get("myJPAEntity")).getTest());
        assertEquals(myObject.getValue(), ((MyObject)dataMap.get("mySerializableObject")).getValue());
       
        BlockingTaskOperationResponseHandler startResponseHandler = new BlockingTaskOperationResponseHandler();
        getClient().start(task.getId(), "Darth Vader", startResponseHandler);
        
        Map<String, Object> results = new HashMap<String, Object>();
        em.getTransaction().begin();
        MyEntity myEntity2 = new MyEntity("This is a JPA Entity 2");
        em.persist(myEntity2);
        em.getTransaction().commit();
        results.put("myEntity2", myEntity2);
        MyObject myObject2 = new MyObject("This is a Serializable Object 2");
        results.put("myObject2", myObject2);
        
        ContentData result = ContentMarshallerHelper.marshal(results, ksession.getEnvironment());

        BlockingTaskOperationResponseHandler completeResponseHandler = new BlockingTaskOperationResponseHandler();
        getClient().complete(task.getId(), "Darth Vader", result, completeResponseHandler);

        assertTrue(manager.waitTillCompleted(MANAGER_COMPLETION_WAIT_TIME));
        Map<String, Object> managerResults = manager.getResults();
        assertNotNull(managerResults);
        assertEquals("Darth Vader", managerResults.get("ActorId"));
        assertEquals(myEntity2.getTest(), ((MyEntity)((Map)managerResults.get("Result")).get("myEntity2")).getTest());
        assertEquals(myEntity2.getTest(), ((MyEntity)managerResults.get("myEntity2")).getTest());
        assertEquals(myObject2.getValue(), ((MyObject)((Map)managerResults.get("Result")).get("myObject2")).getValue());
        assertEquals(myObject2.getValue(), ((MyObject)managerResults.get("myObject2")).getValue());
    }

    

    

    @Test
    public void testTaskDataWithVPSSerializableObjectWithDeadline() throws Exception {
        //Serializable Object
        taskService.setEscalatedDeadlineHandler(buildDeadlineHandler(env));
        MyObject myObject = new MyObject("This is a Serializable Object");
        
        TestWorkItemManager manager = new TestWorkItemManager();
        ksession.setWorkItemManager(manager);
        WorkItemImpl workItem = new WorkItemImpl();
        workItem.setName("Human Task");
        workItem.setParameter("TaskName", "TaskName");
        workItem.setParameter("Comment", "Comment");
        workItem.setParameter("Priority", "10");
        workItem.setParameter("ActorId", "Darth Vader");
        workItem.setParameter("Content", myObject);
        workItem.setParameter("NotStartedNotify", "[tousers:john|subject:Test|body:${doc['content'].value}]@[2s]");
        getHandler().executeWorkItem(workItem, manager);

        BlockingTaskSummaryResponseHandler taskSummaryResponseHandler = new BlockingTaskSummaryResponseHandler();
        getClient().getTasksAssignedAsPotentialOwner("Darth Vader", "en-UK", taskSummaryResponseHandler);
        List<TaskSummary> tasks = taskSummaryResponseHandler.getResults();
        assertEquals(1, tasks.size());
        TaskSummary taskSummary = tasks.get(0);
        assertEquals("TaskName", taskSummary.getName());
        assertEquals(10, taskSummary.getPriority());
        assertEquals("Comment", taskSummary.getDescription());
        assertEquals(Status.Reserved, taskSummary.getStatus());
        assertEquals("Darth Vader", taskSummary.getActualOwner().getId());

        BlockingGetTaskResponseHandler getTaskResponseHandler = new BlockingGetTaskResponseHandler();  
        getClient().getTask(taskSummary.getId(),getTaskResponseHandler);
        Task task = getTaskResponseHandler.getTask();
        assertEquals(AccessType.Inline, task.getTaskData().getDocumentAccessType());
        
        assertEquals(task.getTaskData().getProcessSessionId(), TestStatefulKnowledgeSession.testSessionId);
        long contentId = task.getTaskData().getDocumentContentId();
        assertTrue(contentId != -1);

        BlockingGetContentResponseHandler getContentResponseHandler = new BlockingGetContentResponseHandler();
        getClient().getContent(contentId,getContentResponseHandler);
        Object data = ContentMarshallerHelper.unmarshall(getContentResponseHandler.getContent().getContent() , ksession.getEnvironment());
        assertEquals(myObject.getValue(), ((MyObject)data).getValue());
        
        Thread.sleep(5000);
        assertEquals(2, wiser.getMessages().size());
        assertEquals("admin@domain.com", wiser.getMessages().get(0).getEnvelopeReceiver());
        assertEquals("john@domain.com", wiser.getMessages().get(1).getEnvelopeReceiver());
        assertEquals("Test", wiser.getMessages().get(0).getMimeMessage().getSubject());
        assertEquals(myObject.getValue(), wiser.getMessages().get(0).getMimeMessage().getContent());
        BlockingTaskOperationResponseHandler startResponseHandler = new BlockingTaskOperationResponseHandler();
        getClient().start(task.getId(), "Darth Vader", startResponseHandler);

        MyObject myObject2 = new MyObject("This is a Serializable Object 2");

        ContentData result = ContentMarshallerHelper.marshal(myObject2,  ksession.getEnvironment());
        BlockingTaskOperationResponseHandler completeResponseHandler = new BlockingTaskOperationResponseHandler();
        getClient().complete(task.getId(), "Darth Vader", result, completeResponseHandler);

        assertTrue(manager.waitTillCompleted(MANAGER_COMPLETION_WAIT_TIME));
        Map<String, Object> results = manager.getResults();
        assertNotNull(results);
        assertEquals("Darth Vader", results.get("ActorId"));
        assertEquals(myObject2.getValue(), ((MyObject)results.get("Result")).getValue());
    }
    
    @Test
    public void testTaskDataWithVPSSerializableObjectWithDeadlineWithMarshal() throws Exception {
        
        taskService.setEscalatedDeadlineHandler(buildDeadlineHandler(env));
        //Serializable Object
        MyObject myObject = new MyObject("This is a Serializable Object");
        
        TestWorkItemManager manager = new TestWorkItemManager();
        ksession.setWorkItemManager(manager);
        WorkItemImpl workItem = new WorkItemImpl();
        workItem.setName("Human Task");
        workItem.setParameter("TaskName", "TaskName");
        workItem.setParameter("Comment", "Comment");
        workItem.setParameter("Priority", "10");
        workItem.setParameter("ActorId", "Darth Vader");
        workItem.setParameter("Content", myObject);
        workItem.setParameter("NotStartedNotify", "[tousers:john|subject:Test|body:${doc['content'].value}]@[2s]");
        getHandler().executeWorkItem(workItem, manager);

        BlockingTaskSummaryResponseHandler taskSummaryResponseHandler = new BlockingTaskSummaryResponseHandler();
        getClient().getTasksAssignedAsPotentialOwner("Darth Vader", "en-UK", taskSummaryResponseHandler);
        List<TaskSummary> tasks = taskSummaryResponseHandler.getResults();
        assertEquals(1, tasks.size());
        TaskSummary taskSummary = tasks.get(0);
        assertEquals("TaskName", taskSummary.getName());
        assertEquals(10, taskSummary.getPriority());
        assertEquals("Comment", taskSummary.getDescription());
        assertEquals(Status.Reserved, taskSummary.getStatus());
        assertEquals("Darth Vader", taskSummary.getActualOwner().getId());

        BlockingGetTaskResponseHandler getTaskResponseHandler = new BlockingGetTaskResponseHandler();  
        getClient().getTask(taskSummary.getId(),getTaskResponseHandler);
        Task task = getTaskResponseHandler.getTask();
        assertEquals(AccessType.Inline, task.getTaskData().getDocumentAccessType());
        assertEquals(task.getTaskData().getProcessSessionId(), TestStatefulKnowledgeSession.testSessionId);
        long contentId = task.getTaskData().getDocumentContentId();
        assertTrue(contentId != -1);

        BlockingGetContentResponseHandler getContentResponseHandler = new BlockingGetContentResponseHandler();
        getClient().getContent(contentId,getContentResponseHandler);
        Object data = ContentMarshallerHelper.unmarshall( getContentResponseHandler.getContent().getContent() ,   ksession.getEnvironment());
        assertEquals(myObject.getValue(), ((MyObject)data).getValue());

        Thread.sleep(5000);
        assertEquals(2, wiser.getMessages().size());
        assertEquals("admin@domain.com", wiser.getMessages().get(0).getEnvelopeReceiver());
        assertEquals("john@domain.com", wiser.getMessages().get(1).getEnvelopeReceiver());
        assertEquals("Test", wiser.getMessages().get(0).getMimeMessage().getSubject());
        assertEquals(myObject.getValue(), wiser.getMessages().get(0).getMimeMessage().getContent());

        BlockingTaskOperationResponseHandler startResponseHandler = new BlockingTaskOperationResponseHandler();
        getClient().start(task.getId(), "Darth Vader", startResponseHandler);

        MyObject myObject2 = new MyObject("This is a Serializable Object 2");

        ContentData result = ContentMarshallerHelper.marshal(myObject2,  ksession.getEnvironment());
        BlockingTaskOperationResponseHandler completeResponseHandler = new BlockingTaskOperationResponseHandler();
        getClient().complete(task.getId(), "Darth Vader", result, completeResponseHandler);

        assertTrue(manager.waitTillCompleted(MANAGER_COMPLETION_WAIT_TIME));
        Map<String, Object> results = manager.getResults();
        assertNotNull(results);
        assertEquals("Darth Vader", results.get("ActorId"));
        assertEquals(myObject2.getValue(), ((MyObject)results.get("Result")).getValue());
    }    

    @Test
    public void testTaskDataWithVPSandMAPWithDeadline() throws Exception {
        
        taskService.setEscalatedDeadlineHandler(buildDeadlineHandler(env));
        //JPA Entity
        EntityManager em = domainEmf.createEntityManager();
        em.getTransaction().begin();
        MyEntity myEntity = new MyEntity("This is a JPA Entity");
        em.persist(myEntity);
        em.getTransaction().commit();
        
        //Serializable Object
        MyObject myObject = new MyObject("This is a Serializable Object");
        
        Map<String, Object> content = new HashMap<String, Object>();
        content.put("myJPAEntity", myEntity);
        content.put("mySerializableObject", myObject);

        TestWorkItemManager manager = new TestWorkItemManager();
        ksession.setWorkItemManager(manager);
        WorkItemImpl workItem = new WorkItemImpl();
        workItem.setName("Human Task");
        workItem.setParameter("TaskName", "TaskName");
        workItem.setParameter("Comment", "Comment");
        workItem.setParameter("Priority", "10");
        workItem.setParameter("ActorId", "Darth Vader");
        workItem.setParameter("Content", content);
        workItem.setParameter("NotStartedNotify", "[tousers:john|subject:${doc['myJPAEntity'].test}|body:${doc['mySerializableObject'].value}]@[2s]");
        getHandler().executeWorkItem(workItem, manager);

        BlockingTaskSummaryResponseHandler taskSummaryResponseHandler = new BlockingTaskSummaryResponseHandler();
        getClient().getTasksAssignedAsPotentialOwner("Darth Vader", "en-UK", taskSummaryResponseHandler);
        List<TaskSummary> tasks = taskSummaryResponseHandler.getResults();
        assertEquals(1, tasks.size());
        TaskSummary taskSummary = tasks.get(0);
        assertEquals("TaskName", taskSummary.getName());
        assertEquals(10, taskSummary.getPriority());
        assertEquals("Comment", taskSummary.getDescription());
        assertEquals(Status.Reserved, taskSummary.getStatus());
        assertEquals("Darth Vader", taskSummary.getActualOwner().getId());

        BlockingGetTaskResponseHandler getTaskResponseHandler = new BlockingGetTaskResponseHandler();  
        getClient().getTask(taskSummary.getId(),getTaskResponseHandler);
        Task task = getTaskResponseHandler.getTask();
        assertEquals(AccessType.Inline, task.getTaskData().getDocumentAccessType());
        assertEquals(task.getTaskData().getProcessSessionId(), TestStatefulKnowledgeSession.testSessionId);
        long contentId = task.getTaskData().getDocumentContentId();
        assertTrue(contentId != -1);

        BlockingGetContentResponseHandler getContentResponseHandler = new BlockingGetContentResponseHandler();
        getClient().getContent(contentId,getContentResponseHandler);
        Object data = ContentMarshallerHelper.unmarshall( getContentResponseHandler.getContent().getContent() ,  ksession.getEnvironment());
        Map<String, Object> dataMap = (Map<String, Object>)data;

        assertEquals(myEntity.getTest(), ((MyEntity)dataMap.get("myJPAEntity")).getTest());
        assertEquals(myObject.getValue(), ((MyObject)dataMap.get("mySerializableObject")).getValue());
       
        Thread.sleep(5000);
        assertEquals(2, wiser.getMessages().size());
        assertEquals("admin@domain.com", wiser.getMessages().get(0).getEnvelopeReceiver());
        assertEquals("john@domain.com", wiser.getMessages().get(1).getEnvelopeReceiver());
        assertEquals(myEntity.getTest(), wiser.getMessages().get(0).getMimeMessage().getSubject());
        assertEquals(myObject.getValue(), wiser.getMessages().get(0).getMimeMessage().getContent());

        BlockingTaskOperationResponseHandler startResponseHandler = new BlockingTaskOperationResponseHandler();
        getClient().start(task.getId(), "Darth Vader", startResponseHandler);

        Map<String, Object> results = new HashMap<String, Object>();
        em.getTransaction().begin();
        MyEntity myEntity2 = new MyEntity("This is a JPA Entity 2");
        em.persist(myEntity2);
        em.getTransaction().commit();
        results.put("myEntity2", myEntity2);
        MyObject myObject2 = new MyObject("This is a Serializable Object 2");
        results.put("myObject2", myObject2);

        ContentData result = ContentMarshallerHelper.marshal(results, ksession.getEnvironment());

        BlockingTaskOperationResponseHandler completeResponseHandler = new BlockingTaskOperationResponseHandler();
        getClient().complete(task.getId(), "Darth Vader", result, completeResponseHandler);

        assertTrue(manager.waitTillCompleted(MANAGER_COMPLETION_WAIT_TIME));
        Map<String, Object> managerResults = manager.getResults();
        assertNotNull(managerResults);
        assertEquals("Darth Vader", managerResults.get("ActorId"));
        assertEquals(myEntity2.getTest(), ((MyEntity)((Map)managerResults.get("Result")).get("myEntity2")).getTest());
        assertEquals(myEntity2.getTest(), ((MyEntity)managerResults.get("myEntity2")).getTest());
        assertEquals(myObject2.getValue(), ((MyObject)((Map)managerResults.get("Result")).get("myObject2")).getValue());
        assertEquals(myObject2.getValue(), ((MyObject)managerResults.get("myObject2")).getValue());
    }

    protected static EscalatedDeadlineHandler buildDeadlineHandler(Environment environment) {
       
         wiser = new Wiser();
         wiser.setHostname("localhost");
         wiser.setPort(2345);        
         wiser.start();
         
         Properties emailProperties = new Properties();
         emailProperties.setProperty("from", "jbpm@domain.com");
         emailProperties.setProperty("replyTo", "jbpm@domain.com");
         emailProperties.setProperty("mail.smtp.host", "localhost");
         emailProperties.setProperty("mail.smtp.port", "2345");
         
         Properties userInfoProperties = new Properties();
         userInfoProperties.setProperty("john", "john@domain.com:en-UK:John");
         userInfoProperties.setProperty("mike", "mike@domain.com:en-UK:Mike");
     
         userInfoProperties.setProperty("Administrator", "admin@domain.com:en-UK:Admin");
         
             
         DefaultEscalatedDeadlineHandler handler = new DefaultEscalatedDeadlineHandler(emailProperties);
         handler.setUserInfo(new DefaultUserInfo(userInfoProperties));
         handler.setEnvironment(environment);
         
         return handler;
     }
    
    public void setHandler(WorkItemHandler handler) {
        this.handler = handler;
    }

    public WorkItemHandler getHandler() {
        return handler;
    }

    public void setClient(AsyncTaskService client) {
        this.client = client;
    }

    public AsyncTaskService getClient() {
        return client;
    }
    
     private class TestWorkItemManager implements WorkItemManager {

        private volatile boolean completed;
        private volatile boolean aborted;
        private volatile Map<String, Object> results;

        public synchronized boolean waitTillCompleted(long time) {
            if (!isCompleted()) {
                try {
                    wait(time);
                } catch (InterruptedException e) {
                    // swallow and return state of completed
                }
            }

            return isCompleted();
        }

        public synchronized boolean waitTillAborted(long time) {
            if (!isAborted()) {
                try {
                    wait(time);
                } catch (InterruptedException e) {
                    // swallow and return state of aborted
                }
            }

            return isAborted();
        }

        public void abortWorkItem(long id) {
            setAborted(true);
        }

        public synchronized boolean isAborted() {
            return aborted;
        }

        private synchronized void setAborted(boolean aborted) {
            this.aborted = aborted;
            notifyAll();
        }

        public void completeWorkItem(long id, Map<String, Object> results) {
            this.results = results;
            setCompleted(true);
        }

        private synchronized void setCompleted(boolean completed) {
            this.completed = completed;
            notifyAll();
        }

        public synchronized boolean isCompleted() {
            return completed;
        }

        public Map<String, Object> getResults() {
            return results;
        }

        public void registerWorkItemHandler(String workItemName, WorkItemHandler handler) {
        }
    }
}
