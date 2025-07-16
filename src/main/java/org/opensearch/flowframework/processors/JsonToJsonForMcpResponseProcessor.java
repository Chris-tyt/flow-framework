/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

 package org.opensearch.flowframework.processors;

 import static org.opensearch.ml.common.settings.MLCommonsSettings.ML_COMMONS_MCP_SERVER_DISABLED_MESSAGE;
 
 import java.util.Map;
 
 import org.opensearch.action.search.SearchRequest;
 import org.opensearch.action.search.SearchResponse;
 import org.opensearch.core.action.ActionListener;
 import org.opensearch.ingest.ConfigurationUtils;
 import org.opensearch.ml.common.settings.MLFeatureEnabledSetting;
 import org.opensearch.ml.common.transport.mcpserver.action.MLMcpMessageAction;
 import org.opensearch.ml.common.transport.mcpserver.requests.message.MLMcpMessageRequest;
 import org.opensearch.search.SearchHit;
 import org.opensearch.search.pipeline.AbstractProcessor;
 import org.opensearch.search.pipeline.PipelineProcessingContext;
 import org.opensearch.search.pipeline.Processor;
 import org.opensearch.search.pipeline.SearchResponseProcessor;
 import org.opensearch.transport.client.Client;
 
 import com.fasterxml.jackson.databind.ObjectMapper;
 
 
 public class JsonToJsonForMcpResponseProcessor extends AbstractProcessor implements SearchResponseProcessor {
     
     public static final String TYPE = "mcp_agent";
     public static final String AGENT_ID_FIELD = "agent_id";
     public static final String SESSION_ID_FIELD = "session_id";
     public static final String NODE_ID_FIELD = "node_id";
     public static final String QUESTION_FIELD = "question";
     public static final String CONTEXT_FIELD = "context_field";
     public static final String OUTPUT_FIELD = "output_field";
     public static final String DEFAULT_OUTPUT_FIELD = "mcp_agent_response";
     public static final String DEFAULT_CONTEXT_FIELD = "text";
     
     private final String agentId;
     private final String sessionId;
     private final String nodeId;
     private final String question;
     private final String contextField;
     private final String outputField;
     private final Client client;
     private final MLFeatureEnabledSetting mlFeatureEnabledSetting;
     private final ObjectMapper objectMapper;
     
     protected JsonToJsonForMcpResponseProcessor(
         String agentId,
         String sessionId,
         String nodeId,
         String question,
         String contextField,
         String outputField,
         String tag,
         String description,
         boolean ignoreFailure,
         Client client,
         MLFeatureEnabledSetting mlFeatureEnabledSetting
     ) {
         super(tag, description, ignoreFailure);
         this.agentId = agentId;
         this.sessionId = sessionId;
         this.nodeId = nodeId;
         this.question = question;
         this.contextField = contextField != null ? contextField : DEFAULT_CONTEXT_FIELD;
         this.outputField = outputField != null ? outputField : DEFAULT_OUTPUT_FIELD;
         this.client = client;
         this.mlFeatureEnabledSetting = mlFeatureEnabledSetting;
         this.objectMapper = new ObjectMapper();
     }
 
     @Override
     public SearchResponse processResponse(SearchRequest request, SearchResponse response) throws Exception {
         throw new UnsupportedOperationException("Synchronous processing not supported for MCP agent processor");
     }
 
     @Override
     public void processResponseAsync(
         SearchRequest request,
         SearchResponse response,
         PipelineProcessingContext context,
         ActionListener<SearchResponse> responseListener
     ) {
         // 检查MCP服务器是否启用
         if (!mlFeatureEnabledSetting.isMcpServerEnabled()) {
             if (isIgnoreFailure()) {
                 System.out.println("MCP server is disabled, skipping processing");
                 responseListener.onResponse(response);
                 return;
             } else {
                 responseListener.onFailure(new IllegalStateException(ML_COMMONS_MCP_SERVER_DISABLED_MESSAGE));
                 return;
             }
         }
 
         try {
             // 1. 提取搜索结果作为上下文
             String contextText = extractContextFromSearchResponse(response);
             
             // 2. 构建MCP消息请求体
            //  Map<String, Object> messageData = new HashMap<>();
            //  messageData.put("method", "tools/call");
            //  messageData.put("id", System.currentTimeMillis());
             
            //  Map<String, Object> params = new HashMap<>();
            //  params.put("name", agentId);
             
            //  Map<String, Object> arguments = new HashMap<>();
            //  arguments.put("question", question);
            //  arguments.put("context", contextText);
            //  params.put("arguments", arguments);
             
            //  messageData.put("params", params);

             
            //  String requestBody = objectMapper.writeValueAsString(messageData);
            String requestBody = "{\"parameters\": {\"question\": \"what's the current price of bitcoin?\"}}";
             // 3. 创建MCP消息请求
             MLMcpMessageRequest mcpRequest = new MLMcpMessageRequest(sessionId, requestBody, nodeId);
             
             // 4. 发送MCP消息
             client.execute(MLMcpMessageAction.INSTANCE, mcpRequest, ActionListener.wrap(
                 mcpResponse -> {
                     try {
                         // 5. 处理MCP响应并增强搜索结果
                         SearchResponse enhancedResponse = enhanceSearchResponseWithMcpResult(response, mcpResponse.toString());
                         responseListener.onResponse(enhancedResponse);
                     } catch (Exception e) {
                         handleError(e, response, responseListener);
                     }
                 },
                 e -> {
                     System.out.println("Failed to execute MCP agent: " + agentId + " " + e);
                     handleError(e, response, responseListener);
                 }
             ));
             
         } catch (Exception e) {
             handleError(e, response, responseListener);
         }
     }
 
     private String extractContextFromSearchResponse(SearchResponse response) {
         StringBuilder contextBuilder = new StringBuilder();
         
         for (SearchHit hit : response.getHits().getHits()) {
             Map<String, Object> sourceAsMap = hit.getSourceAsMap();
             if (sourceAsMap != null && sourceAsMap.containsKey(contextField)) {
                 Object contextValue = sourceAsMap.get(contextField);
                 if (contextValue != null) {
                     contextBuilder.append(contextValue.toString()).append(" ");
                 }
             }
         }
         
         return contextBuilder.toString().trim();
     }
 
     private SearchResponse enhanceSearchResponseWithMcpResult(SearchResponse originalResponse, String mcpResult) {
         // 这里您可以选择如何将MCP结果集成到搜索响应中
         // 例如：添加到ext字段、修改hits、或者创建新的响应结构
         
         System.out.println("MCP Agent response: " + mcpResult);
         
         // 简单示例：将结果记录到日志
         // 实际实现中需要根据需要修改SearchResponse
         return originalResponse;
     }
 
     private void handleError(Exception e, SearchResponse originalResponse, ActionListener<SearchResponse> responseListener) {
         if (isIgnoreFailure()) {
             System.out.println("MCP agent processing failed, but ignoring failure: " + e.getMessage());
             responseListener.onResponse(originalResponse);
         } else {
             responseListener.onFailure(e);
         }
     }
 
     @Override
     public String getType() {
         return TYPE;
     }
 
     public static class Factory implements Processor.Factory<SearchResponseProcessor> {
         
         private final Client client;
         private final MLFeatureEnabledSetting mlFeatureEnabledSetting;
         
         public Factory(Client client, MLFeatureEnabledSetting mlFeatureEnabledSetting) {
             this.client = client;
             this.mlFeatureEnabledSetting = mlFeatureEnabledSetting;
         }
 
         @Override
         public SearchResponseProcessor create(
             Map<String, Processor.Factory<SearchResponseProcessor>> processorFactories,
             String processorTag,
             String description,
             boolean ignoreFailure,
             Map<String, Object> config,
             PipelineContext pipelineContext
         ) throws Exception {
             
             String agentId = ConfigurationUtils.readStringProperty(TYPE, processorTag, config, AGENT_ID_FIELD);
             String sessionId = ConfigurationUtils.readStringProperty(TYPE, processorTag, config, SESSION_ID_FIELD);
             String nodeId = ConfigurationUtils.readStringProperty(TYPE, processorTag, config, NODE_ID_FIELD);
             String question = ConfigurationUtils.readStringProperty(TYPE, processorTag, config, QUESTION_FIELD);
             String contextField = ConfigurationUtils.readOptionalStringProperty(TYPE, processorTag, config, CONTEXT_FIELD);
             String outputField = ConfigurationUtils.readOptionalStringProperty(TYPE, processorTag, config, OUTPUT_FIELD);
             
             return new JsonToJsonForMcpResponseProcessor(
                 agentId,
                 sessionId,
                 nodeId,
                 question,
                 contextField,
                 outputField,
                 processorTag,
                 description,
                 ignoreFailure,
                 client,
                 mlFeatureEnabledSetting
             );
         }
     }
 } 