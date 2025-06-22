/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package org.opensearch.flowframework.rest;

import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.opensearch.client.Response;
import org.opensearch.client.ResponseException;
import org.opensearch.core.rest.RestStatus;
import org.opensearch.flowframework.FlowFrameworkRestTestCase;
import org.opensearch.flowframework.TestHelpers;
import org.opensearch.flowframework.model.Template;
import org.junit.Before;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.opensearch.flowframework.common.CommonValue.WORKFLOW_ID;
import static org.opensearch.flowframework.common.CommonValue.WORKFLOW_URI;

public class JsonRecommenderIT extends FlowFrameworkRestTestCase {

    @Before
    public void waitToStart() throws Exception {
        // ML Commons cron job runs every 10 seconds and takes 20+ seconds to initialize .plugins-ml-config index
        if (!indexExistsWithAdminClient(".plugins-ml-config")) {
            assertBusy(() -> assertTrue(indexExistsWithAdminClient(".plugins-ml-config")), 40, TimeUnit.SECONDS);
        }
    }

    /**
     * POC to show intended successful call using create workflow API
     *
     * @throws IOException if REST call fails
     * @throws ParseException if return entity fails parsing
     */
    public void testJsonRecommender() throws IOException, ParseException {
        // "Create" a Workflow containing a single json_recommender step with input and output
        // Since it will fail/succeed fast in validation steps nothing will actually be created
        Template template = TestHelpers.createTemplateFromFile("jsonrecommender.json");

        Response response = TestHelpers.makeRequest(
            client(),
            "POST",
            WORKFLOW_URI + "?validation=jsonpath",
            Collections.emptyMap(),
            template.toJson(),
            null
        );

        // We are returning the json recommendation in the workflow_id field
        Map<String, Object> map = responseToMap(response);
        assertTrue(map.containsKey(WORKFLOW_ID));
        String recommendation = map.get(WORKFLOW_ID).toString();
        assertEquals("""
            {
              "allocDetails[*]" : {
                "allocation" : "$.item.allocDetails.useful.items[*].allocation",
                "team" : {
                  "name" : "$.item.allocDetails.useful.items[*].team.name",
                  "id" : "$.item.allocDetails.useful.items[*].team.id"
                }
              },
              "name" : "$.item.name"
            }""", recommendation);
    }

    /**
     * Test invalid workflow key
     */
    public void testWrongWorkflowKey() throws IOException, ParseException {
        // Start with no-op template
        Template template = TestHelpers.createTemplateFromFile("noop.json");

        // This will fail because "json_recommender" key is not present
        ResponseException e = assertThrows(
            ResponseException.class,
            () -> TestHelpers.makeRequest(
                client(),
                "POST",
                WORKFLOW_URI + "?validation=jsonpath",
                Collections.emptyMap(),
                template.toJson(),
                null
            )
        );
        Response response = e.getResponse();

        assertEquals(RestStatus.BAD_REQUEST, TestHelpers.restStatus(response));
        assertEquals(
            "{\"error\":\"JsonPath recommendation requires a workflow with json_recommender key\"}",
            EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8)
        );
    }

    /**
     * Test different template with invalid step type
     */
    public void testWrongStepType() throws IOException, ParseException {
        // Start with no-op template and swap out workflow key
        Template baseTemplate = TestHelpers.createTemplateFromFile("noop.json");
        Template template = Template.builder(baseTemplate)
            .workflows(Map.of("json_recommender", baseTemplate.workflows().values().iterator().next()))
            .build();

        // This will fail because "json_recommender" step type is not present
        ResponseException e = assertThrows(
            ResponseException.class,
            () -> TestHelpers.makeRequest(
                client(),
                "POST",
                WORKFLOW_URI + "?validation=jsonpath",
                Collections.emptyMap(),
                template.toJson(),
                null
            )
        );
        Response response = e.getResponse();

        assertEquals(RestStatus.BAD_REQUEST, TestHelpers.restStatus(response));
        assertEquals(
            "{\"error\":\"JsonPath recommendation requires a step with the json_recommender step type\"}",
            EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8)
        );
    }

    /**
     * Test workflow with multiple nodes
     */
    public void testMultipleNodes() throws IOException, ParseException {
        // Start with a template with multiple nodes
        Template baseTemplate = TestHelpers.createTemplateFromFile("registerremotemodel.json");
        // Change workflow key to get past that check
        String json = baseTemplate.toJson().replace("provision", "json_recommender");
        Template template = Template.parse(json);

        ResponseException e = assertThrows(
            ResponseException.class,
            () -> TestHelpers.makeRequest(
                client(),
                "POST",
                WORKFLOW_URI + "?validation=jsonpath",
                Collections.emptyMap(),
                template.toJson(),
                null
            )
        );
        Response response = e.getResponse();

        assertEquals(RestStatus.BAD_REQUEST, TestHelpers.restStatus(response));
        assertEquals(
            "{\"error\":\"JsonPath recommendation requires a single workflow step\"}",
            EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8)
        );
    }

    /**
     * Test missing json_input field
     */
    public void testMissingJsonInput() throws IOException, ParseException {
        // Start with json recommender template
        Template baseTemplate = TestHelpers.createTemplateFromFile("jsonrecommender.json");
        // Change input key to make it invalid
        String json = baseTemplate.toJson().replace("json_input", "bad_key");
        Template template = Template.parse(json);

        ResponseException e = assertThrows(
            ResponseException.class,
            () -> TestHelpers.makeRequest(
                client(),
                "POST",
                WORKFLOW_URI + "?validation=jsonpath",
                Collections.emptyMap(),
                template.toJson(),
                null
            )
        );
        Response response = e.getResponse();

        assertEquals(RestStatus.BAD_REQUEST, TestHelpers.restStatus(response));
        assertEquals(
            "{\"error\":\"JsonPath recommendation requires a json_input field\"}",
            EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8)
        );
    }

    /**
     * Test missing json_output field
     */
    public void testMissingJsonOutput() throws IOException, ParseException {
        // Start with json recommender template
        Template baseTemplate = TestHelpers.createTemplateFromFile("jsonrecommender.json");
        // Change output key to make it invalid
        String json = baseTemplate.toJson().replace("json_output", "bad_key");
        Template template = Template.parse(json);

        ResponseException e = assertThrows(
            ResponseException.class,
            () -> TestHelpers.makeRequest(
                client(),
                "POST",
                WORKFLOW_URI + "?validation=jsonpath",
                Collections.emptyMap(),
                template.toJson(),
                null
            )
        );
        Response response = e.getResponse();

        assertEquals(RestStatus.BAD_REQUEST, TestHelpers.restStatus(response));
        assertEquals(
            "{\"error\":\"JsonPath recommendation requires a json_output field\"}",
            EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8)
        );
    }
}
