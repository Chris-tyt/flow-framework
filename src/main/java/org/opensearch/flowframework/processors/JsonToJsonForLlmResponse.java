/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
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
package org.opensearch.flowframework.processors;

import java.io.IOException;

import org.opensearch.action.search.SearchResponse;
import org.opensearch.action.search.SearchResponseSections;
import org.opensearch.action.search.ShardSearchFailure;
import org.opensearch.core.xcontent.XContentBuilder;
import org.opensearch.core.xcontent.XContentParser;
import org.opensearch.flowframework.util.ParseUtils;

/**
 * This is an extension of SearchResponse that adds JSON-to-JSON transformation results to search responses in a dedicated "ext" section.
 * Used by JsonToJsonForLlmResponseProcessor to include transformation metadata and results.
 */
public class JsonToJsonForLlmResponse extends SearchResponse {

    private static final String EXT_SECTION_NAME = "ext";
    
    private final String additionalJson;

    public JsonToJsonForLlmResponse(
        String additionalJson,
        SearchResponseSections internalResponse,
        String scrollId,
        int totalShards,
        int successfulShards,
        int skippedShards,
        long tookInMillis,
        ShardSearchFailure[] shardFailures,
        Clusters clusters
    ) {
        super(internalResponse, scrollId, totalShards, successfulShards, skippedShards, tookInMillis, shardFailures, clusters);
        this.additionalJson = additionalJson;
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        XContentParser parser = ParseUtils.jsonToParser(additionalJson);
        parser.nextToken();

        builder.startObject();
        innerToXContent(builder, params);
        // builder.field(EXT_SECTION_NAME);

        builder.copyCurrentStructure(parser);

        // builder.endObject();
        builder.endObject();
        return builder;
    }

    /**
     * Get the additional JSON
     * @return the additional JSON
     */
    public String getAdditionalJson() {
        return additionalJson;
    }
}
