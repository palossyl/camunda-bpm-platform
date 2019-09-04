/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. Camunda licenses this file to you under the Apache License,
 * Version 2.0; you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.camunda.bpm.engine.test.api.queries;

import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.IdentityService;
import org.camunda.bpm.engine.ProcessEngineConfiguration;
import org.camunda.bpm.engine.ProcessEngineException;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.filter.Filter;
import org.camunda.bpm.engine.history.HistoricProcessInstance;
import org.camunda.bpm.engine.history.HistoricProcessInstanceQuery;
import org.camunda.bpm.engine.impl.interceptor.Command;
import org.camunda.bpm.engine.impl.interceptor.CommandContext;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.camunda.bpm.engine.runtime.ProcessInstanceQuery;
import org.camunda.bpm.engine.task.Task;
import org.camunda.bpm.engine.test.ProcessEngineRule;
import org.camunda.bpm.engine.test.RequiredHistoryLevel;
import org.camunda.bpm.engine.test.util.ProcessEngineTestRule;
import org.camunda.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.RuleChain;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

public class BoundedNumberOfMaxResultsTest {

  public ProcessEngineRule engineRule = new ProvidedProcessEngineRule();

  protected ProcessEngineTestRule testHelper = new ProcessEngineTestRule(engineRule);

  @Rule
  public RuleChain ruleChain = RuleChain.outerRule(engineRule).around(testHelper);

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  protected HistoryService historyService;
  protected RuntimeService runtimeService;
  protected TaskService taskService;
  protected IdentityService identityService;

  @Before
  public void assignServices() {
    historyService = engineRule.getHistoryService();
    runtimeService = engineRule.getRuntimeService();
    taskService = engineRule.getTaskService();
    identityService = engineRule.getIdentityService();
  }

  @Before
  public void enableMaxResultsLimit() {
    engineRule.getProcessEngineConfiguration()
        .setQueryMaxResultsLimit(10);
  }

  @Before
  public void authenticate() {
    engineRule.getIdentityService()
        .setAuthenticatedUserId("foo");
  }

  @After
  public void clearAuthentication() {
    engineRule.getIdentityService()
        .clearAuthentication();
  }

  @Test
  public void shouldReturnUnboundedResults_UnboundMaxResults() {
    // given
    engineRule.getProcessEngineConfiguration()
        .setQueryMaxResultsLimit(Integer.MAX_VALUE);

    ProcessInstanceQuery processInstanceQuery =
        runtimeService.createProcessInstanceQuery();

    // when
    List<ProcessInstance> processInstances = processInstanceQuery.list();

    // then
    assertThat(processInstances.size()).isEqualTo(0);
  }

  @Test
  public void shouldReturnUnboundedResults_NotAuthenticated() {
    // given
    identityService.clearAuthentication();

    ProcessInstanceQuery processInstanceQuery =
        runtimeService.createProcessInstanceQuery();

    // when
    List<ProcessInstance> processInstances = processInstanceQuery.list();

    // then
    assertThat(processInstances.size()).isEqualTo(0);
  }

  @Test
  public void shouldReturnUnboundedResults_InsideCmd() {
    // given
    engineRule.getProcessEngineConfiguration()
        .setQueryMaxResultsLimit(2);

    Task task = taskService.newTask();
    taskService.saveTask(task);

    engineRule.getProcessEngineConfiguration()
        .getCommandExecutorTxRequired()
        .execute(new Command<Void>() {

          @Override
          public Void execute(CommandContext commandContext) {
            // when
            List<Task> tasks = commandContext.getProcessEngineConfiguration()
                .getTaskService()
                .createTaskQuery()
                .list();

            // then
            assertThat(tasks.size()).isEqualTo(1);

            return null;
          }
        });

    // clear
    taskService.deleteTask(task.getId(), true);
  }

  @RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_ACTIVITY)
  @Test
  public void shouldReturnUnboundedResults_InsideCmd2() {
    // given
    engineRule.getProcessEngineConfiguration()
        .setQueryMaxResultsLimit(2);

    BpmnModelInstance process = Bpmn.createExecutableProcess("process")
        .startEvent("startEvent")
        .endEvent()
        .done();

    String processDefinitionId = testHelper.deploy(process)
        .getDeployedProcessDefinitions()
        .get(0)
        .getId();

    String processInstanceId = runtimeService.startProcessInstanceByKey("process")
        .getProcessInstanceId();

    try {
      // when
      runtimeService.restartProcessInstances(processDefinitionId)
          .processInstanceIds(processInstanceId)
          .startAfterActivity("startEvent")
          .execute();

      // then
      // do not fail
    } catch (ProcessEngineException e) {
      fail("The query inside the command should not throw an exception!");
    }
  }

  @Test
  public void shouldThrowException_UnboundedResultsForList() {
    // given
    ProcessInstanceQuery processInstanceQuery =
        runtimeService.createProcessInstanceQuery();

    // then
    thrown.expect(ProcessEngineException.class);
    thrown.expectMessage("An unbound number of results is forbidden!");

    // when
    processInstanceQuery.list();
  }

  @Test
  public void shouldThrowException_MaxResultsLimitExceeded() {
    // given
    ProcessInstanceQuery processInstanceQuery =
        runtimeService.createProcessInstanceQuery();

    // then
    thrown.expect(ProcessEngineException.class);
    thrown.expectMessage("Max results limit of 10 exceeded!");

    // when
    processInstanceQuery.listPage(0, 11);
  }

  @Test
  public void shouldThrowExceptionWhenFilterQueryList_MaxResultsLimitExceeded() {
    // given
    Filter foo = engineRule.getFilterService().newTaskFilter("foo");
    foo.setQuery(taskService.createTaskQuery());
    engineRule.getFilterService().saveFilter(foo);

    String filterId = engineRule.getFilterService()
        .createFilterQuery()
        .singleResult()
        .getId();

    try {
      // when
      engineRule.getFilterService().list(filterId);
      fail("Exception expected!");
    } catch (ProcessEngineException e) {
      // then
      assertThat(e).hasMessage("An unbound number of results is forbidden!");
    }

    // clear
    engineRule.getFilterService().deleteFilter(filterId);
  }

  @Test
  public void shouldThrowExceptionWhenFilterQueryListPage_MaxResultsLimitExceeded() {
    // given
    Filter foo = engineRule.getFilterService().newTaskFilter("foo");
    foo.setQuery(taskService.createTaskQuery());
    engineRule.getFilterService().saveFilter(foo);

    String filterId = engineRule.getFilterService()
        .createFilterQuery()
        .singleResult()
        .getId();

    try {
      // when
      engineRule.getFilterService().listPage(filterId, 0, 11);
      fail("Exception expected!");
    } catch (ProcessEngineException e) {
      // then
      assertThat(e).hasMessage("Max results limit of 10 exceeded!");
    }

    // clear
    engineRule.getFilterService().deleteFilter(filterId);
  }

  @RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_ACTIVITY)
  @Test
  public void shouldReturnResultWhenMaxResultsLimitNotExceeded() {
    // given
    BpmnModelInstance process = Bpmn.createExecutableProcess("process")
        .startEvent("startEvent")
        .endEvent()
        .done();

    testHelper.deploy(process);

    runtimeService.startProcessInstanceByKey("process");

    HistoricProcessInstanceQuery historicProcessInstanceQuery =
        historyService.createHistoricProcessInstanceQuery();

    // when
    List<HistoricProcessInstance> historicProcessInstances =
        historicProcessInstanceQuery.listPage(0, 10);

    // then
    assertThat(historicProcessInstances.size()).isEqualTo(1);
  }

  @RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_ACTIVITY)
  @Test
  public void shouldReturnResultWhenMaxResultsLimitNotExceeded2() {
    // given
    BpmnModelInstance process = Bpmn.createExecutableProcess("process")
        .startEvent("startEvent")
        .endEvent()
        .done();

    testHelper.deploy(process);

    runtimeService.startProcessInstanceByKey("process");

    HistoricProcessInstanceQuery historicProcessInstanceQuery =
        historyService.createHistoricProcessInstanceQuery();

    // when
    List<HistoricProcessInstance> historicProcessInstances =
        historicProcessInstanceQuery.listPage(0, 9);

    // then
    assertThat(historicProcessInstances.size()).isEqualTo(1);
  }

  @Test
  public void shouldReturnResultInsideJavaDelegate() {
    // given
    BpmnModelInstance process = Bpmn.createExecutableProcess("process")
        .startEvent("startEvent")
        .serviceTask()
          .camundaClass(BoundedNumberOfMaxResultsDelegate.class)
        .endEvent()
        .done();

    testHelper.deploy(process);

    try {
      // when
      runtimeService.startProcessInstanceByKey("process");

      // then: should not fail
    } catch (ProcessEngineException e) {
      fail("Should not throw exception inside command!");
    }
  }

  @RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_ACTIVITY)
  @Test
  public void shouldReturnSingleResult_BoundedMaxResults() {
    // given
    BpmnModelInstance process = Bpmn.createExecutableProcess("process")
        .startEvent()
        .endEvent()
        .done();

    testHelper.deploy(process);

    runtimeService.startProcessInstanceByKey("process");

    HistoricProcessInstanceQuery historicProcessInstanceQuery =
        historyService.createHistoricProcessInstanceQuery();

    // when
    HistoricProcessInstance processInstance = historicProcessInstanceQuery.singleResult();

    // then
    assertThat(processInstance).isNotNull();
  }

}
