/*
 * Copyright (c) 2008-2016, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.jet.stream.impl.pipeline;

import com.hazelcast.jet.stream.impl.AbstractIntermediatePipeline;
import com.hazelcast.jet.stream.impl.Pipeline;
import com.hazelcast.jet.stream.impl.processor.DistinctProcessor;
import com.hazelcast.jet.DAG;
import com.hazelcast.jet.Edge;
import com.hazelcast.jet.Vertex;

import static com.hazelcast.jet.stream.impl.StreamUtil.randomName;

public class DistinctPipeline<T> extends AbstractIntermediatePipeline<T, T> {

    public DistinctPipeline(StreamContext context, Pipeline<T> upstream) {
        super(context, upstream.isOrdered(), upstream);
    }

    @Override
    public Vertex buildDAG(DAG dag) {
        if (upstream.isOrdered()) {
            Vertex previous = upstream.buildDAG(dag);
            Vertex distinct = new Vertex(randomName(), DistinctProcessor::new).parallelism(1);
            dag.addVertex(distinct)
                    .addEdge(new Edge(previous, distinct));

            return distinct;
        }

        Vertex previous = upstream.buildDAG(dag);
        Vertex distinct = new Vertex("distinct-" + randomName(), DistinctProcessor::new);
        Vertex combiner = new Vertex("distinct-" + randomName(), DistinctProcessor::new);

        dag
                .addVertex(distinct)
                .addVertex(combiner)
                .addEdge(new Edge(previous, distinct).partitioned())
                .addEdge(new Edge(distinct, combiner).partitioned().distributed());

        return combiner;
    }
}
