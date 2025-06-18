package org.opensearch.flowframework.processors;

import org.opensearch.action.search.SearchRequest;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.common.xcontent.XContentFactory;
import org.opensearch.search.builder.SearchSourceBuilder;
import org.opensearch.core.action.ActionListener;
import org.opensearch.core.xcontent.ToXContent;
import org.opensearch.search.SearchExtBuilder;

import org.opensearch.core.xcontent.XContentBuilder;
import org.opensearch.core.xcontent.XContentParser;
import org.opensearch.core.xcontent.XContentHelper;

import org.opensearch.common.xcontent.json.JsonXContent;
import org.opensearch.flowframework.util.ParseUtils;
import org.opensearch.ingest.ConfigurationUtils;
import org.opensearch.ml.client.MachineLearningNodeClient;
import org.opensearch.ml.common.connector.ConnectorAction;
import org.opensearch.ml.common.input.MLInput;
import org.opensearch.ml.common.output.MLOutput;
import org.opensearch.search.pipeline.AbstractProcessor;
import org.opensearch.search.pipeline.PipelineProcessingContext;
import org.opensearch.search.pipeline.Processor;
import org.opensearch.search.pipeline.SearchResponseProcessor;
import org.opensearch.flowframework.util.JsonToJsonTransformer;
import org.opensearch.flowframework.util.JsonToJsonRecommender;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Collections;

/**
 * JSON to JSON Request Processor for transforming search responses using LLM
 */
public class JsonToJsonForLlmResponseProcessor extends AbstractProcessor implements SearchResponseProcessor {

    public static final String TYPE = "json_to_json_for_llm";

    private final String requestJsonPath;
    private final String responseJsonPath;
    private final MachineLearningNodeClient client;
    private final String openSearchModelId;

    private JsonToJsonForLlmResponseProcessor(
            String tag,
            String description,
            boolean ignoreFailure,
            String requestJsonPath,
            String responseJsonPath,
            MachineLearningNodeClient client,
            String openSearchModelId) {
        super(tag, description, ignoreFailure);
        this.requestJsonPath = requestJsonPath;
        this.responseJsonPath = responseJsonPath;
        this.client = client;
        this.openSearchModelId = openSearchModelId;
    }

    @Override
    public SearchResponse processResponse(SearchRequest request, SearchResponse response) throws Exception {
        throw new RuntimeException("JsonToJsonForLlmResponseProcessor does not call processResponse");
    }

    @Override
    public void processResponseAsync(
            SearchRequest request,
            SearchResponse response,
            PipelineProcessingContext responseContext,
            ActionListener<SearchResponse> responseListener) {
        System.out.println("=====================IN PROCESS RESPONSE ASYNC===========================");
        String requestToModel = null;
        try {
            requestToModel = generateRequestToModel(request);
        } catch (Exception e) {
            System.out.println("Failed to generate request to model: " + e.getMessage());
            responseListener.onFailure(e);
        }

        ActionListener<MLOutput> actionListener = new ActionListener<>() {
            @Override
            public void onResponse(MLOutput mlOutput) {
                try {
                    XContentBuilder builder = XContentFactory.jsonBuilder();
                    String responseFromeModel = mlOutput.toXContent(builder, ToXContent.EMPTY_PARAMS).toString();
                    System.out.println("responseFromeModel: " + responseFromeModel);
                    System.out.println("Processing with response_json_path: " + responseJsonPath);

                    String additionalFieldInResponse = JsonToJsonTransformer.transform(responseFromeModel, responseJsonPath);
                    System.out.println("additionalFieldInResponse: " + additionalFieldInResponse);

                    responseListener.onResponse(generateNewResponse(response, additionalFieldInResponse));
                } catch (IOException e) {
                    responseListener.onFailure(e);
                }
            }
            @Override
            public void onFailure(Exception e) {
                System.out.println("Failed to predict");
                responseListener.onFailure(e);
            }
        };

        if (requestToModel != null) {
            callPredictModel(requestToModel, actionListener);
        } else {
            responseListener.onFailure(new IllegalArgumentException("No request to model provided."));
        }
    }

    @Override
    public String getType() {
        return TYPE;
    }

    private String generateRequestToModel(SearchRequest request) throws IllegalArgumentException, JsonProcessingException ,IOException{
        if (request.source() == null) {
            throw new IllegalArgumentException("No source provided.");
        }

        List<SearchExtBuilder> extBuilders = request.source().ext();
        request.source().ext(Collections.emptyList());
        // SearchSourceBuilder sourceBuilder = request.source();
        String requestJson = request.source().toString();

        XContentBuilder builder = XContentFactory.jsonBuilder();
        builder.startObject();

        builder.startObject("ext");
        for (SearchExtBuilder extBuilder : extBuilders) {
            builder.field(extBuilder.getWriteableName());
            extBuilder.toXContent(builder, ToXContent.EMPTY_PARAMS);
        }
        builder.endObject();
        builder.endObject();
        String extJson = builder.toString();

        String requestJsonwithExtField = requestJson.substring(0, requestJson.length() - 1) + ","
                + extJson.substring(1, extJson.length());
        System.out.println("requestJsonwithExtField: " + requestJsonwithExtField);

        System.out.println("Processing with request_json_path: " + requestJsonPath);
        return JsonToJsonTransformer.transform(requestJsonwithExtField, requestJsonPath);
    }   

    private SearchResponse generateNewResponse(SearchResponse response, String additionalFieldInResponse) {
        return new JsonToJsonForLlmResponse(
            additionalFieldInResponse,
            response.getInternalResponse(),
            response.getScrollId(),
            response.getTotalShards(),
            response.getSuccessfulShards(),
            response.getSkippedShards(),
            response.getSuccessfulShards(),
            response.getShardFailures(),
            response.getClusters());
    }

    private void callPredictModel(String requestToModel, ActionListener<MLOutput> actionListener) {
        MLInput mlInput = null;
        try {
            XContentParser parser = ParseUtils.jsonToParser(requestToModel);
            mlInput = MLInput.parse(parser, "REMOTE", ConnectorAction.ActionType.PREDICT);
        } catch (Exception e) {
            System.out.println("Failed to parse JSON or MLInput: " + e.getMessage());
            actionListener.onFailure(e);
        }
        
        client.predict(openSearchModelId, mlInput, actionListener);
    }

    /**
     * Factory for creating JsonToJsonForLlmResponseProcessor instances
     */
    public static final class Factory implements Processor.Factory<SearchResponseProcessor> {
        private final MachineLearningNodeClient client;

        public Factory(MachineLearningNodeClient client) {
            this.client = client;
        }

        @Override
        public JsonToJsonForLlmResponseProcessor create(
                Map<String, Processor.Factory<SearchResponseProcessor>> processorFactories,
                String tag,
                String description,
                boolean ignoreFailure,
                Map<String, Object> config,
                Processor.PipelineContext pipelineContext) throws Exception {

            System.out.println("Creating JsonToJsonForLlmResponseProcessor with config: " + config);

            // Read if_generate_jsonpath boolean field
            boolean ifGenerateJsonpath = ConfigurationUtils.readBooleanProperty(TYPE, tag, config,
                    "if_generate_jsonpath", false);
            System.out.println("if_generate_jsonpath: " + ifGenerateJsonpath);

            String requestJsonPath = ConfigurationUtils.readOptionalStringProperty(TYPE, tag, config,
                    "request_json_path");
            String responseJsonPath = ConfigurationUtils.readOptionalStringProperty(TYPE, tag, config,
                    "response_json_path");
            String inputFormatForRequest = ConfigurationUtils.readOptionalStringProperty(TYPE, tag, config,
                    "input_format_for_request");
            String outputFormatForRequest = ConfigurationUtils.readOptionalStringProperty(TYPE, tag, config,
                    "output_format_for_request");
            String inputFormatForResponse = ConfigurationUtils.readOptionalStringProperty(TYPE, tag, config,
                    "input_format_for_response");
            String outputFormatForResponse = ConfigurationUtils.readOptionalStringProperty(TYPE, tag, config,
                    "output_format_for_response");

            if (ifGenerateJsonpath) {
                System.out.println("Generating JSON paths via recommender:");
                System.out.println("  input_format_for_request: " + inputFormatForRequest);
                System.out.println("  output_format_for_request: " + outputFormatForRequest);
                System.out.println("  input_format_for_response: " + inputFormatForResponse);
                System.out.println("  output_format_for_response: " + outputFormatForResponse);

                requestJsonPath = JsonToJsonRecommender.getRecommendationGeneralizedInStringFormat(inputFormatForRequest, outputFormatForRequest);
                responseJsonPath = JsonToJsonRecommender.getRecommendationGeneralizedInStringFormat(inputFormatForResponse, outputFormatForResponse);

                System.out.println("Generated request_json_path: " + requestJsonPath);
                System.out.println("Generated response_json_path: " + responseJsonPath);

            } else {
                // Read JSON paths directly from configuration
                System.out.println("Using provided JSON paths:");
                System.out.println("  request_json_path: " + requestJsonPath);
                System.out.println("  response_json_path: " + responseJsonPath);
            }

            // Validation
            if (requestJsonPath != null && requestJsonPath.trim().isEmpty()) {
                throw new IllegalArgumentException("[" + TYPE + "] request_json_path cannot be empty");
            }

            if (responseJsonPath != null && responseJsonPath.trim().isEmpty()) {
                throw new IllegalArgumentException("[" + TYPE + "] response_json_path cannot be empty");
            }

            String openSearchModelId = ConfigurationUtils.readOptionalStringProperty(TYPE, tag, config,
                    "open_search_model_id");

            return new JsonToJsonForLlmResponseProcessor(
                    tag,
                    description,
                    ignoreFailure,
                    requestJsonPath,
                    responseJsonPath,
                    client,
                    openSearchModelId);
        }
    }
}